// Outgoing actions: sending, reactions, edits, read state and chat settings.

package wabridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"strings"
	"time"

	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func parseChat(chat string) (types.JID, error) {
	jid, err := types.ParseJID(chat)
	if err != nil || jid.User == "" {
		return types.EmptyJID, fmt.Errorf("invalid WhatsApp chat %q", chat)
	}
	return jid, nil
}

func timeout() (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.Background(), 60*time.Second)
}

// ResolvePhone checks a number is on WhatsApp and returns its chat JID.
func (c *Client) ResolvePhone(phone string) (string, error) {
	digits := strings.Map(func(r rune) rune {
		if r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, phone)
	if len(digits) < 7 {
		return "", errors.New("enter a full phone number with country code")
	}
	ctx, cancel := timeout()
	defer cancel()
	results, err := c.wa.IsOnWhatsApp(ctx, []string{"+" + digits})
	if err != nil {
		return "", err
	}
	for _, result := range results {
		if result.IsIn {
			return c.canonical(result.JID).String(), nil
		}
	}
	return "", errors.New("that number isn't on WhatsApp")
}

// SendText sends a message, optionally quoting quoteID, and returns it as
// JSON. spansJSON lists mentions ({start, length, style: "mention", id:
// JID}) over "@Name" in text, in UTF-16 units; "" for none.
func (c *Client) SendText(chat, text, quoteID, spansJSON string) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	var spans []span
	if spansJSON != "" {
		if err := json.Unmarshal([]byte(spansJSON), &spans); err != nil {
			return "", err
		}
	}
	body, mentions := encodeMentions(text, spans)
	context := &waE2E.ContextInfo{MentionedJID: mentions}
	quoteSender, quoteText := "", ""
	if quoteID != "" {
		if quoted, _ := c.store.message(jid.String(), quoteID); quoted != nil {
			quoteSender, quoteText = quoted.Sender, quoted.Text
			context.StanzaID = proto.String(quoteID)
			context.Participant = proto.String(quoted.Sender)
			context.QuotedMessage = &waE2E.Message{Conversation: proto.String(quoted.Text)}
		}
	}
	msg := &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{Text: proto.String(body), ContextInfo: context}}
	expires := c.stampExpiry(jid, msg)
	if context.StanzaID == nil && len(mentions) == 0 && expires == 0 {
		msg = &waE2E.Message{Conversation: proto.String(body)}
	}
	r := row{Kind: "text", Text: body, QuoteID: quoteID, QuoteSender: quoteSender, QuoteText: quoteText, Mentions: mentions}
	return c.queue(jid, r, func() (*waE2E.Message, error) { return msg, nil })
}

// stampExpiry marks an outgoing message with the chat's disappearing
// timer, as WhatsApp clients expect; returns the timer (0 for none).
func (c *Client) stampExpiry(chat types.JID, msg *waE2E.Message) uint32 {
	seconds := c.store.chatTimer(chat.String())
	if seconds == 0 {
		return 0
	}
	ctx := contextInfo(msg)
	if ctx == nil {
		ctx = &waE2E.ContextInfo{}
		switch {
		case msg.ImageMessage != nil:
			msg.ImageMessage.ContextInfo = ctx
		case msg.VideoMessage != nil:
			msg.VideoMessage.ContextInfo = ctx
		case msg.AudioMessage != nil:
			msg.AudioMessage.ContextInfo = ctx
		case msg.DocumentMessage != nil:
			msg.DocumentMessage.ContextInfo = ctx
		case msg.ExtendedTextMessage != nil:
			msg.ExtendedTextMessage.ContextInfo = ctx
		case msg.StickerMessage != nil:
			msg.StickerMessage.ContextInfo = ctx
		default:
			if poll := pollCreation(msg); poll != nil {
				poll.ContextInfo = ctx
			} else {
				return 0
			}
		}
	}
	ctx.Expiration = proto.Uint32(seconds)
	return seconds
}

func expiresAt(sent int64, seconds uint32) int64 {
	if seconds == 0 {
		return 0
	}
	return sent + int64(seconds)*1000
}

// SetExpireTimer sets a chat's disappearing-message timer in seconds (0 for off).
func (c *Client) SetExpireTimer(chat string, seconds int) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	ctx, cancel := timeout()
	defer cancel()
	if err := c.wa.SetDisappearingTimer(ctx, jid, time.Duration(seconds)*time.Second, time.Now()); err != nil {
		return err
	}
	if changed, err := c.store.setChatTimer(jid.String(), uint32(seconds)); err != nil {
		return err
	} else if changed {
		c.note(jid.String(), time.Now(), timerNote("You", uint32(seconds)))
	}
	emit(c.listener, "messages", map[string]string{"chat": jid.String()})
	emit(c.listener, "chats", nil)
	return nil
}

// SetBlocked blocks or unblocks the person in a direct chat.
func (c *Client) SetBlocked(chat string, blocked bool) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	if jid.Server == types.GroupServer {
		return errors.New("groups can't be blocked; leave the group instead")
	}
	action := events.BlocklistChangeActionUnblock
	if blocked {
		action = events.BlocklistChangeActionBlock
	}
	ctx, cancel := timeout()
	defer cancel()
	if _, err := c.wa.UpdateBlocklist(ctx, jid, action); err != nil {
		return err
	}
	if err := c.store.setBlocked([]string{jid.String()}, blocked, false); err != nil {
		return err
	}
	emit(c.listener, "chats", nil)
	return nil
}

func (c *Client) senderFor(chat types.JID, id string) (types.JID, bool, error) {
	stored, err := c.store.message(chat.String(), id)
	if err != nil || stored == nil {
		return types.EmptyJID, false, errors.New("message not found")
	}
	if stored.FromMe {
		return c.ownJID(), true, nil
	}
	sender, err := types.ParseJID(stored.Sender)
	return sender, false, err
}

// React sets (or with an empty emoji, removes) our reaction to a message.
func (c *Client) React(chat, id, emoji string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	sender, _, err := c.senderFor(jid, id)
	if err != nil {
		return err
	}
	if err := c.send(jid, c.wa.BuildReaction(jid, sender, id, emoji)); err != nil {
		return err
	}
	return c.store.setReaction(jid.String(), id, c.ownJID().String(), emoji)
}

const pinSeconds = 30 * 24 * 60 * 60

// PinMessage pins a message for everyone, or removes its pin.
func (c *Client) PinMessage(chat, id string, pinned bool) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	stored, err := c.store.message(jid.String(), id)
	if err != nil || stored == nil {
		return errors.New("message not found")
	}
	key := &waCommon.MessageKey{RemoteJID: proto.String(jid.String()), FromMe: proto.Bool(stored.FromMe), ID: proto.String(id)}
	if jid.Server == types.GroupServer && !stored.FromMe {
		key.Participant = proto.String(stored.Sender)
	}
	typ := waE2E.PinInChatMessage_PIN_FOR_ALL
	if !pinned {
		typ = waE2E.PinInChatMessage_UNPIN_FOR_ALL
	}
	now := time.Now().UnixMilli()
	msg := &waE2E.Message{PinInChatMessage: &waE2E.PinInChatMessage{Key: key, Type: &typ, SenderTimestampMS: proto.Int64(now)}}
	var until int64
	if pinned {
		// WhatsApp pins expire; 30 days is the longest it offers.
		msg.MessageContextInfo = &waE2E.MessageContextInfo{MessageAddOnDurationInSecs: proto.Uint32(pinSeconds)}
		until = now + pinSeconds*1000
	}
	if err := c.send(jid, msg); err != nil {
		return err
	}
	return c.store.setMessagePinned(jid.String(), id, pinned, now, until)
}

// Edit replaces the text of one of our messages.
func (c *Client) Edit(chat, id, text string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	if err := c.send(jid, c.wa.BuildEdit(jid, id, &waE2E.Message{Conversation: proto.String(text)})); err != nil {
		return err
	}
	return c.store.editMessage(jid.String(), id, text, nil)
}

// Revoke deletes one of our messages for everyone.
func (c *Client) Revoke(chat, id string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	if err := c.send(jid, c.wa.BuildRevoke(jid, types.EmptyJID, id)); err != nil {
		return err
	}
	return c.store.deleteMessage(jid.String(), id)
}

// MarkRead reads the chat up to and including message upTo, or all of
// it when upTo is "", sending read receipts for what that newly reads.
func (c *Client) MarkRead(chat, upTo string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	through := int64(math.MaxInt64)
	if upTo != "" {
		target, err := c.store.message(jid.String(), upTo)
		if err != nil || target == nil {
			return errors.New("message not found")
		}
		through = target.Ts
	}
	rows, err := c.store.unreadThrough(jid.String(), through, 100)
	if err != nil {
		return err
	}
	bySender := map[string][]types.MessageID{}
	for _, r := range rows {
		bySender[r.Sender] = append(bySender[r.Sender], r.ID)
	}
	ctx, cancel := timeout()
	defer cancel()
	for sender, ids := range bySender {
		senderJID, _ := types.ParseJID(sender)
		if jid.Server != types.GroupServer {
			senderJID = types.EmptyJID
		}
		if err := c.wa.MarkRead(ctx, ids, time.Now(), jid, senderJID); err != nil {
			c.log.Warnf("mark read: %v", err)
		}
	}
	if err := c.store.readThrough(jid.String(), through); err != nil {
		return err
	}
	emit(c.listener, "chats", nil)
	return nil
}

// SetTyping shows or hides our typing indicator.
func (c *Client) SetTyping(chat string, active bool) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	state := types.ChatPresencePaused
	if active {
		state = types.ChatPresenceComposing
	}
	ctx, cancel := timeout()
	defer cancel()
	return c.wa.SendChatPresence(ctx, jid, state, types.ChatPresenceMediaText)
}

// OpenChat goes "online" while a chat is on screen so typing notices
// arrive. Staying offline otherwise keeps the main phone's notifications.
func (c *Client) OpenChat(chat string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	ctx, cancel := timeout()
	defer cancel()
	if err := c.wa.SendPresence(ctx, types.PresenceAvailable); err != nil {
		return err
	}
	if jid.Server == types.DefaultUserServer {
		return c.wa.SubscribePresence(ctx, jid)
	}
	return nil
}

// CloseChat goes back to "offline".
func (c *Client) CloseChat() error {
	ctx, cancel := timeout()
	defer cancel()
	return c.wa.SendPresence(ctx, types.PresenceUnavailable)
}

// send sends a message that needs nothing back but success.
func (c *Client) send(jid types.JID, msg *waE2E.Message) error {
	ctx, cancel := timeout()
	defer cancel()
	_, err := c.wa.SendMessage(ctx, jid, msg)
	return err
}

// sendAppState syncs a chat setting (archive, pin, mute) to our other devices.
func (c *Client) sendAppState(patch appstate.PatchInfo) error {
	ctx, cancel := timeout()
	defer cancel()
	return c.wa.SendAppState(ctx, patch)
}

func (c *Client) lastKey(jid types.JID) (time.Time, *waCommon.MessageKey) {
	last, err := c.store.lastMessage(jid.String())
	if err != nil || last == nil {
		return time.Now(), nil
	}
	return time.UnixMilli(last.Ts), &waCommon.MessageKey{
		RemoteJID: proto.String(jid.String()), FromMe: proto.Bool(last.FromMe), ID: proto.String(last.ID),
	}
}

// SetArchived archives or unarchives a chat on all devices.
func (c *Client) SetArchived(chat string, archived bool) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	ts, key := c.lastKey(jid)
	if err := c.sendAppState(appstate.BuildArchive(jid, archived, ts, key)); err != nil {
		return err
	}
	return c.store.updateChat(jid.String(), "archived", b2i(archived))
}

// SetPinned pins or unpins a chat on all devices.
func (c *Client) SetPinned(chat string, pinned bool) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	if err := c.sendAppState(appstate.BuildPin(jid, pinned)); err != nil {
		return err
	}
	return c.store.updateChat(jid.String(), "pinned", b2i(pinned))
}

// SetMuted mutes a chat forever, or unmutes it, on all devices.
func (c *Client) SetMuted(chat string, muted bool) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	if err := c.sendAppState(appstate.BuildMute(jid, muted, 0)); err != nil {
		return err
	}
	until := int64(0)
	if muted {
		until = -1
	}
	return c.store.updateChat(jid.String(), "muted_until", until)
}

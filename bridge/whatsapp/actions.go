// Outgoing actions: sending, reactions, edits, read state and chat settings.

package wabridge

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
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

// SendText sends a message, optionally quoting quoteID, and returns it as JSON.
func (c *Client) SendText(chat, text, quoteID string) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	msg := &waE2E.Message{Conversation: proto.String(text)}
	quoteSender, quoteText := "", ""
	if quoteID != "" {
		if quoted, _ := c.store.message(jid.String(), quoteID); quoted != nil {
			participant := quoted.Sender
			quoteSender, quoteText = participant, quoted.Text
			msg = &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
				Text: proto.String(text),
				ContextInfo: &waE2E.ContextInfo{
					StanzaID:      proto.String(quoteID),
					Participant:   proto.String(participant),
					QuotedMessage: &waE2E.Message{Conversation: proto.String(quoted.Text)},
				},
			}}
		}
	}
	ctx, cancel := timeout()
	defer cancel()
	resp, err := c.wa.SendMessage(ctx, jid, msg)
	if err != nil {
		return "", err
	}
	sent := row{
		Chat: jid.String(), ID: resp.ID, Sender: c.ownJID().String(), FromMe: true, Ts: resp.Timestamp.UnixMilli(),
		Kind: "text", Text: text, QuoteID: quoteID, QuoteSender: quoteSender, QuoteText: quoteText, Status: "sent",
	}
	if err := c.store.putMessage(sent); err != nil {
		return "", err
	}
	emit(c.listener, "chats", nil)
	return marshal(c.toJSONMessage(sent, nil))
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
	ctx, cancel := timeout()
	defer cancel()
	if _, err := c.wa.SendMessage(ctx, jid, c.wa.BuildReaction(jid, sender, id, emoji)); err != nil {
		return err
	}
	return c.store.setReaction(jid.String(), id, c.ownJID().String(), emoji)
}

// Edit replaces the text of one of our messages.
func (c *Client) Edit(chat, id, text string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	ctx, cancel := timeout()
	defer cancel()
	if _, err := c.wa.SendMessage(ctx, jid, c.wa.BuildEdit(jid, id, &waE2E.Message{Conversation: proto.String(text)})); err != nil {
		return err
	}
	return c.store.editMessage(jid.String(), id, text)
}

// Revoke deletes one of our messages for everyone.
func (c *Client) Revoke(chat, id string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	ctx, cancel := timeout()
	defer cancel()
	if _, err := c.wa.SendMessage(ctx, jid, c.wa.BuildRevoke(jid, types.EmptyJID, id)); err != nil {
		return err
	}
	return c.store.deleteMessage(jid.String(), id)
}

// MarkRead sends read receipts for the latest incoming messages in a chat.
func (c *Client) MarkRead(chat string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	rows, err := c.store.unreadIncoming(jid.String(), 20)
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
	return c.store.updateChat(jid.String(), "unread", 0)
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
	ctx, cancel := timeout()
	defer cancel()
	if err := c.wa.SendAppState(ctx, appstate.BuildArchive(jid, archived, ts, key)); err != nil {
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
	ctx, cancel := timeout()
	defer cancel()
	if err := c.wa.SendAppState(ctx, appstate.BuildPin(jid, pinned)); err != nil {
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
	ctx, cancel := timeout()
	defer cancel()
	if err := c.wa.SendAppState(ctx, appstate.BuildMute(jid, muted, 0)); err != nil {
		return err
	}
	until := int64(0)
	if muted {
		until = -1
	}
	return c.store.updateChat(jid.String(), "muted_until", until)
}

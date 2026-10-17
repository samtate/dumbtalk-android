// Handling whatsmeow events: storing messages, receipts, history and
// app-state changes, and notifying the listener.

package wabridge

import (
	"context"
	"strconv"
	"time"

	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"
)

func (c *Client) onEvent(raw any) {
	switch evt := raw.(type) {
	case *events.Connected, *events.Disconnected, *events.LoggedOut, *events.StreamReplaced, *events.PairSuccess:
		emit(c.listener, "status", nil)
		if _, ok := evt.(*events.Connected); ok {
			go c.refreshGroups()
			go c.refreshBlocklist()
		}
	case *events.Message:
		chat := c.canonical(evt.Info.Chat)
		if evt.Info.IsFromMe && evt.Info.DeviceSentMeta != nil {
			if dest, err := types.ParseJID(evt.Info.DeviceSentMeta.DestinationJID); err == nil {
				chat = c.canonical(dest)
			}
		}
		if chat.Server == types.BroadcastServer || chat.Server == types.NewsletterServer {
			return
		}
		if evt.Message.GetPollUpdateMessage() != nil {
			if c.onPollVote(chat, evt) {
				emit(c.listener, "messages", map[string]string{"chat": chat.String()})
			}
			return
		}
		raw := evt.RawMessage
		if raw == nil {
			raw = evt.Message
		}
		if c.ingest(chat, evt.Info, evt.Message, raw, "", true) {
			emit(c.listener, "messages", map[string]string{"chat": chat.String()})
			emit(c.listener, "chats", nil)
			if !evt.Info.IsFromMe {
				emit(c.listener, "incoming", map[string]string{"chat": chat.String(), "id": evt.Info.ID})
			}
		}
	case *events.Receipt:
		c.onReceipt(evt)
	case *events.ChatPresence:
		emit(c.listener, "typing", map[string]any{
			"chat":   c.canonical(evt.Chat).String(),
			"sender": c.contactName(c.canonical(evt.Sender)),
			"active": evt.State == types.ChatPresenceComposing,
		})
	case *events.HistorySync:
		c.onHistorySync(evt)
	case *events.Archive:
		c.store.updateChat(c.canonical(evt.JID).String(), "archived", b2i(evt.Action.GetArchived()))
		emit(c.listener, "chats", nil)
	case *events.Pin:
		c.store.updateChat(c.canonical(evt.JID).String(), "pinned", b2i(evt.Action.GetPinned()))
		emit(c.listener, "chats", nil)
	case *events.Mute:
		until := int64(0)
		if evt.Action.GetMuted() {
			until = evt.Action.GetMuteEndTimestamp()
			if until == 0 {
				until = -1
			}
		}
		c.store.updateChat(c.canonical(evt.JID).String(), "muted_until", until)
		emit(c.listener, "chats", nil)
	case *events.MarkChatAsRead:
		if evt.Action.GetRead() {
			c.store.updateChat(c.canonical(evt.JID).String(), "unread", 0)
			emit(c.listener, "chats", nil)
		}
	case *events.GroupInfo:
		chat := evt.JID.String()
		who := "Someone"
		if evt.Sender != nil {
			who = c.contactName(c.canonical(*evt.Sender))
		}
		if evt.Name != nil {
			c.store.updateChat(chat, "name", evt.Name.Name)
			c.note(chat, evt.Timestamp, who+" renamed the group to “"+evt.Name.Name+"”")
		}
		if evt.Ephemeral != nil {
			seconds := uint32(0)
			if evt.Ephemeral.IsEphemeral {
				seconds = evt.Ephemeral.DisappearingTimer
			}
			if changed, _ := c.store.setChatTimer(chat, seconds); changed {
				c.note(chat, evt.Timestamp, timerNote(who, seconds))
			}
		}
		for _, joined := range evt.Join {
			c.note(chat, evt.Timestamp, c.contactName(c.canonical(joined))+" joined")
		}
		for _, left := range evt.Leave {
			if c.isMe(left) {
				c.note(chat, evt.Timestamp, "You left the group")
				continue
			}
			c.note(chat, evt.Timestamp, c.contactName(c.canonical(left))+" left")
		}
		if len(evt.Join)+len(evt.Leave)+len(evt.Promote)+len(evt.Demote) > 0 {
			go c.refreshGroup(evt.JID)
		}
		emit(c.listener, "messages", map[string]string{"chat": chat})
		emit(c.listener, "chats", nil)
	case *events.Blocklist:
		if evt.Action == events.BlocklistActionModify || len(evt.Changes) == 0 {
			go c.refreshBlocklist()
			return
		}
		for _, change := range evt.Changes {
			c.store.setBlocked([]string{c.canonical(change.JID).String()}, change.Action == events.BlocklistChangeActionBlock, false)
		}
		emit(c.listener, "chats", nil)
	case *events.JoinedGroup:
		c.store.updateChat(evt.JID.String(), "name", evt.Name)
		c.store.ensureChat(evt.JID.String(), time.Now().UnixMilli())
		emit(c.listener, "chats", nil)
	case *events.PushName, *events.Contact:
		emit(c.listener, "chats", nil)
	}
}

func (c *Client) refreshGroups() {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	groups, err := c.wa.GetJoinedGroups(ctx)
	if err != nil {
		c.log.Warnf("group list: %v", err)
		return
	}
	for _, group := range groups {
		c.keepGroup(group)
	}
	emit(c.listener, "chats", nil)
}

// keepGroup stores what the chat list shows of a group: name, timer, our role.
func (c *Client) keepGroup(group *types.GroupInfo) {
	chat := group.JID.String()
	c.store.updateChat(chat, "name", group.Name)
	seconds := uint32(0)
	if group.IsEphemeral {
		seconds = group.DisappearingTimer
	}
	c.store.setChatTimer(chat, seconds)
	admin := false
	for _, participant := range group.Participants {
		if c.isMe(participant.JID) || (!participant.PhoneNumber.IsEmpty() && c.isMe(participant.PhoneNumber)) {
			admin = participant.IsAdmin || participant.IsSuperAdmin
		}
	}
	c.store.setAdmin(chat, admin)
}

func (c *Client) refreshGroup(jid types.JID) {
	ctx, cancel := timeout()
	defer cancel()
	if group, err := c.wa.GetGroupInfo(ctx, jid); err == nil {
		c.keepGroup(group)
		emit(c.listener, "chats", nil)
	}
}

func (c *Client) refreshBlocklist() {
	ctx, cancel := timeout()
	defer cancel()
	list, err := c.wa.GetBlocklist(ctx)
	if err != nil {
		c.log.Warnf("blocklist: %v", err)
		return
	}
	jids := make([]string, 0, len(list.JIDs))
	for _, jid := range list.JIDs {
		jids = append(jids, c.canonical(jid).String())
	}
	c.store.setBlocked(jids, true, true)
	emit(c.listener, "chats", nil)
}

// note adds a line to a chat about a change (timer, members, name).
func (c *Client) note(chat string, at time.Time, text string) {
	if at.IsZero() {
		at = time.Now()
	}
	id := "note-" + strconv.FormatInt(at.UnixMilli(), 10) + "-" + strconv.Itoa(len(text))
	if err := c.store.putMessage(row{Chat: chat, ID: id, Ts: at.UnixMilli(), Kind: "system", Text: text}); err != nil {
		c.log.Warnf("store note: %v", err)
	}
}

// timerNote says who set the disappearing timer to what.
func timerNote(who string, seconds uint32) string {
	if seconds == 0 {
		return who + " turned off disappearing messages"
	}
	return who + " set disappearing messages to " + timerLabel(seconds)
}

func timerLabel(seconds uint32) string {
	count, unit := seconds, "second"
	switch {
	case seconds%86400 == 0:
		count, unit = seconds/86400, "day"
	case seconds%3600 == 0:
		count, unit = seconds/3600, "hour"
	case seconds%60 == 0:
		count, unit = seconds/60, "minute"
	}
	if count == 1 {
		return "1 " + unit
	}
	return strconv.Itoa(int(count)) + " " + unit + "s"
}

// expireLoop deletes disappearing messages as their time runs out.
func (c *Client) expireLoop() {
	for {
		chats, err := c.store.purgeExpired(time.Now().UnixMilli())
		if err != nil {
			c.log.Warnf("expire messages: %v", err)
		}
		for _, chat := range chats {
			emit(c.listener, "messages", map[string]string{"chat": chat})
		}
		if len(chats) > 0 {
			emit(c.listener, "chats", nil)
		}
		wait := time.Minute
		if next := c.store.nextExpiry(); next > 0 {
			wait = min(max(time.Until(time.UnixMilli(next)), time.Second), time.Minute)
		}
		time.Sleep(wait)
	}
}

// ingest stores one message or applies it to an earlier one (reaction,
// edit, revoke). It reports whether anything visible changed.
func (c *Client) ingest(chat types.JID, info types.MessageInfo, msg, raw *waE2E.Message, status string, live bool) bool {
	if msg == nil {
		return false
	}
	if pin := msg.GetPinInChatMessage(); pin != nil && pin.GetKey() != nil {
		// The key's RemoteJID is the chat as the pinner sees it (in a 1:1
		// that is us), so the target lives in the chat this event arrived in.
		at := info.Timestamp.UnixMilli()
		var until int64
		if secs := msg.GetMessageContextInfo().GetMessageAddOnDurationInSecs(); secs > 0 {
			until = at + int64(secs)*1000
		}
		pinned := pin.GetType() == waE2E.PinInChatMessage_PIN_FOR_ALL
		if err := c.store.setMessagePinned(chat.String(), pin.GetKey().GetID(), pinned, at, until); err != nil {
			c.log.Warnf("update message pin: %v", err)
		}
		return true
	}
	p := parseMessage(msg)
	chatID := chat.String()
	sender := c.canonical(info.Sender)
	if info.IsFromMe {
		sender = c.ownJID()
	}
	switch {
	case p.Ignore:
		return false
	case p.IsReaction:
		c.store.setReaction(chatID, p.ReactionTo, sender.String(), p.ReactionEmoji)
		return true
	case p.RevokeID != "":
		c.store.deleteMessage(chatID, p.RevokeID)
		return true
	case p.EditID != "":
		c.store.editMessage(chatID, p.EditID, p.Text, p.Mentions)
		return true
	case p.TimerSet:
		if changed, _ := c.store.setChatTimer(chatID, p.TimerSeconds); changed {
			who := "You"
			if !info.IsFromMe {
				who = c.contactName(sender)
			}
			c.note(chatID, info.Timestamp, timerNote(who, p.TimerSeconds))
		}
		return true
	}
	// Messages carry the timer they were sent under; one from before we
	// linked tells us the chat's.
	var expiresAt int64
	if p.Expiration > 0 {
		expiresAt = info.Timestamp.UnixMilli() + int64(p.Expiration)*1000
		if live && c.store.chatTimer(chatID) == 0 {
			c.store.setChatTimer(chatID, p.Expiration)
		}
	}
	if status == "" && info.IsFromMe {
		status = "sent"
	}
	var rawBytes []byte
	if p.Kind == "image" || p.Kind == "video" || p.Kind == "audio" || p.Kind == "document" || p.Kind == "sticker" {
		rawBytes, _ = proto.Marshal(raw) // kept for downloading media later
	}
	quoteSender := ""
	if p.QuoteSender != "" {
		if jid, err := types.ParseJID(p.QuoteSender); err == nil {
			quoteSender = c.canonical(jid).String()
		}
	}
	err := c.store.putMessage(row{
		Chat: chatID, ID: info.ID, Sender: sender.String(), FromMe: info.IsFromMe,
		Ts: info.Timestamp.UnixMilli(), Kind: p.Kind, Text: p.Text, Filename: p.Filename, Mime: p.Mime, Size: p.Size,
		QuoteID: p.QuoteID, QuoteSender: quoteSender, QuoteText: p.QuoteText,
		Edited: p.Edited, Forwarded: p.Forwarded, Status: status, Poll: withOrigin(p.Poll, info.Chat, info.Sender), Raw: rawBytes,
		Mentions: p.Mentions, ExpiresAt: expiresAt,
	})
	if err != nil {
		c.log.Warnf("store message: %v", err)
		return false
	}
	if live && !info.IsFromMe && p.Kind != "system" {
		c.store.bumpUnread(chatID)
	}
	return true
}

func (c *Client) onReceipt(evt *events.Receipt) {
	chat := c.canonical(evt.Chat).String()
	if evt.Type == types.ReceiptTypeReadSelf || (evt.IsFromMe && evt.Type == types.ReceiptTypeRead) {
		// We read this chat on another device.
		c.store.updateChat(chat, "unread", 0)
		emit(c.listener, "chats", nil)
		return
	}
	var status string
	switch evt.Type {
	case types.ReceiptTypeDelivered:
		status = "delivered"
	case types.ReceiptTypeRead, types.ReceiptTypePlayed:
		status = "read"
	default:
		return
	}
	c.store.setStatus(chat, evt.MessageIDs, status)
	emit(c.listener, "messages", map[string]string{"chat": chat})
	emit(c.listener, "chats", nil)
}

var webStatus = map[waWeb.WebMessageInfo_Status]string{
	waWeb.WebMessageInfo_PENDING:      "pending",
	waWeb.WebMessageInfo_SERVER_ACK:   "sent",
	waWeb.WebMessageInfo_DELIVERY_ACK: "delivered",
	waWeb.WebMessageInfo_READ:         "read",
	waWeb.WebMessageInfo_PLAYED:       "read",
}

func (c *Client) onHistorySync(evt *events.HistorySync) {
	for _, conversation := range evt.Data.GetConversations() {
		jid, err := types.ParseJID(conversation.GetID())
		if err != nil || jid.Server == types.BroadcastServer || jid.Server == types.NewsletterServer {
			continue
		}
		chat := c.canonical(jid)
		chatID := chat.String()
		for _, item := range conversation.GetMessages() {
			web := item.GetMessage()
			parsed, err := c.wa.ParseWebMessage(jid, web)
			if err != nil {
				continue
			}
			status := ""
			if parsed.Info.IsFromMe {
				status = webStatus[web.GetStatus()]
			}
			c.ingest(chat, parsed.Info, parsed.Message, parsed.Message, status, false)
			if updates := web.GetPollUpdates(); len(updates) > 0 {
				c.historyVotes(chat, parsed.Info.ID, updates, func(key *waCommon.MessageKey) types.JID {
					return c.voterFromKey(jid, key)
				})
			}
		}
		c.store.ensureChat(chatID, int64(conversation.GetConversationTimestamp())*1000)
		c.store.setChatTimer(chatID, conversation.GetEphemeralExpiration())
		if name := conversation.GetName(); name != "" && chat.Server == types.GroupServer {
			c.store.updateChat(chatID, "name", name)
		}
		c.store.updateChat(chatID, "archived", b2i(conversation.GetArchived()))
		c.store.updateChat(chatID, "pinned", b2i(conversation.GetPinned() > 0))
		c.store.updateChat(chatID, "unread", int(conversation.GetUnreadCount()))
		if end := conversation.GetMuteEndTime(); end != 0 && (end < 0 || int64(end)*1000 > time.Now().UnixMilli()) {
			c.store.updateChat(chatID, "muted_until", int64(end)*1000)
		}
	}
	emit(c.listener, "chats", nil)
	emit(c.listener, "messages", map[string]string{"chat": "*"})
}

// voterFromKey is who sent the vote message with this key in chat.
func (c *Client) voterFromKey(chat types.JID, key *waCommon.MessageKey) types.JID {
	if key.GetFromMe() {
		return c.ownJID()
	}
	who := key.GetParticipant()
	if who == "" && chat.Server != types.GroupServer {
		who = key.GetRemoteJID()
	}
	jid, err := types.ParseJID(who)
	if err != nil {
		return types.EmptyJID
	}
	return jid
}

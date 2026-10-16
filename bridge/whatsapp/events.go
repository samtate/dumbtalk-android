// Handling whatsmeow events: storing messages, receipts, history and
// app-state changes, and notifying the listener.

package wabridge

import (
	"context"
	"time"

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
		if evt.Name != nil {
			c.store.updateChat(evt.JID.String(), "name", evt.Name.Name)
			emit(c.listener, "chats", nil)
		}
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
		c.store.updateChat(group.JID.String(), "name", group.Name)
	}
	emit(c.listener, "chats", nil)
}

// ingest stores one message or applies it to an earlier one (reaction,
// edit, revoke). It reports whether anything visible changed.
func (c *Client) ingest(chat types.JID, info types.MessageInfo, msg, raw *waE2E.Message, status string, live bool) bool {
	if msg == nil {
		return false
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
		c.store.editMessage(chatID, p.EditID, p.Text)
		return true
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
		Edited: p.Edited, Forwarded: p.Forwarded, Status: status, Poll: p.Poll, Raw: rawBytes,
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
		}
		c.store.ensureChat(chatID, int64(conversation.GetConversationTimestamp())*1000)
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

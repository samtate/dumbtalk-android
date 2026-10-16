// Package wabridge runs WhatsApp (via whatsmeow) inside the Android app.
// It is compiled with gomobile, so its exported API sticks to strings,
// numbers and one listener interface; structured data travels as JSON.
package wabridge

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"
)

// Listener receives events: "status", "chats", "messages" {chat},
// "incoming" {chat,id}, "typing" {chat,sender,active}, "qr" {code},
// "pair-code" {code}, "paired", "link-error" {error} and "log" {message}.
type Listener interface {
	OnEvent(kind string, payload string)
}

type Client struct {
	dir      string
	listener Listener
	wa       *whatsmeow.Client
	store    *msgStore
	log      waLog.Logger

	mu      sync.Mutex
	linking context.CancelFunc
}

func emit(l Listener, kind string, payload any) {
	data := "{}"
	if payload != nil {
		encoded, _ := json.Marshal(payload)
		data = string(encoded)
	}
	l.OnEvent(kind, data)
}

// listenerLog forwards whatsmeow warnings and errors to Android's log.
type listenerLog struct {
	l      Listener
	module string
}

func (g listenerLog) Errorf(msg string, args ...any) {
	emit(g.l, "log", map[string]string{"message": "E " + g.module + ": " + fmt.Sprintf(msg, args...)})
}
func (g listenerLog) Warnf(msg string, args ...any) {
	emit(g.l, "log", map[string]string{"message": "W " + g.module + ": " + fmt.Sprintf(msg, args...)})
}
func (g listenerLog) Infof(string, ...any)  {}
func (g listenerLog) Debugf(string, ...any) {}
func (g listenerLog) Sub(module string) waLog.Logger {
	return listenerLog{g.l, g.module + "/" + module}
}

// NewClient opens (or creates) the WhatsApp databases in dir.
func NewClient(dir string, deviceName string, listener Listener) (*Client, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	store.DeviceProps.Os = proto.String(deviceName)
	log := listenerLog{listener, "wa"}
	ctx := context.Background()
	container, err := sqlstore.New(ctx, "sqlite3",
		"file:"+filepath.Join(dir, "session.db")+"?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=5000", log.Sub("db"))
	if err != nil {
		return nil, err
	}
	device, err := container.GetFirstDevice(ctx)
	if err != nil {
		return nil, err
	}
	messages, err := openStore(filepath.Join(dir, "messages.db"))
	if err != nil {
		return nil, err
	}
	c := &Client{dir: dir, listener: listener, store: messages, log: log}
	c.wa = whatsmeow.NewClient(device, log.Sub("client"))
	c.wa.AddEventHandler(c.onEvent)
	return c, nil
}

// IsLinked reports whether this device has been paired with a phone.
func (c *Client) IsLinked() bool { return c.wa.Store.ID != nil }

// IsConnected reports whether the WhatsApp connection is up and logged in.
func (c *Client) IsConnected() bool { return c.wa.IsConnected() && c.wa.IsLoggedIn() }

// AccountJSON describes the linked account: {"phone","name"}.
func (c *Client) AccountJSON() string {
	if c.wa.Store.ID == nil {
		return "{}"
	}
	encoded, _ := json.Marshal(map[string]string{"phone": c.wa.Store.ID.User, "name": c.wa.Store.PushName})
	return string(encoded)
}

// Connect starts the connection for an already linked device.
func (c *Client) Connect() error {
	if !c.IsLinked() {
		return errors.New("WhatsApp is not linked")
	}
	if c.wa.IsConnected() {
		return nil
	}
	return c.wa.Connect()
}

// StartLink begins linking. With an empty phone it emits "qr" codes;
// otherwise it emits one "pair-code" for "Link with phone number".
func (c *Client) StartLink(phone string) error {
	if c.IsLinked() {
		return errors.New("WhatsApp is already linked")
	}
	c.mu.Lock()
	if c.linking != nil {
		c.linking()
	}
	ctx, cancel := context.WithCancel(context.Background())
	c.linking = cancel
	c.mu.Unlock()

	c.wa.Disconnect()
	qr, err := c.wa.GetQRChannel(ctx)
	if err != nil {
		cancel()
		return err
	}
	if err := c.wa.Connect(); err != nil {
		cancel()
		return err
	}
	go func() {
		requested := false
		for item := range qr {
			switch {
			case item.Event == "code" && phone == "":
				emit(c.listener, "qr", map[string]string{"code": item.Code})
			case item.Event == "code" && !requested:
				requested = true
				code, err := c.wa.PairPhone(ctx, phone, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
				if err != nil {
					emit(c.listener, "link-error", map[string]string{"error": err.Error()})
					return
				}
				emit(c.listener, "pair-code", map[string]string{"code": code})
			case item == whatsmeow.QRChannelSuccess:
				emit(c.listener, "paired", nil)
			case item == whatsmeow.QRChannelTimeout:
				emit(c.listener, "link-error", map[string]string{"error": "The code expired. Start again."})
			case item.Event == "error" && item.Error != nil:
				emit(c.listener, "link-error", map[string]string{"error": item.Error.Error()})
			}
		}
	}()
	return nil
}

// CancelLink stops a link attempt that is still waiting.
func (c *Client) CancelLink() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.linking != nil {
		c.linking()
		c.linking = nil
	}
	if !c.IsLinked() {
		c.wa.Disconnect()
	}
}

// Logout unlinks the device and deletes all WhatsApp data.
func (c *Client) Logout() error {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	if c.IsLinked() {
		if err := c.wa.Logout(ctx); err != nil {
			c.log.Warnf("logout: %v", err)
		}
	}
	c.wa.Disconnect()
	for _, table := range []string{"reactions", "messages", "chats"} {
		c.store.db.Exec("DELETE FROM " + table)
	}
	emit(c.listener, "status", nil)
	emit(c.listener, "chats", nil)
	return nil
}

// region Identity

func (c *Client) ownJID() types.JID {
	if c.wa.Store.ID == nil {
		return types.EmptyJID
	}
	return c.wa.Store.ID.ToNonAD()
}

// canonical maps a chat or user to one stable JID: device suffixes are
// dropped and hidden-number (LID) users use their phone JID when known, so
// one person never shows up as two chats.
func (c *Client) canonical(jid types.JID) types.JID {
	jid = jid.ToNonAD()
	if jid.Server == types.HiddenUserServer {
		if pn, err := c.wa.Store.LIDs.GetPNForLID(context.Background(), jid); err == nil && !pn.IsEmpty() {
			return pn.ToNonAD()
		}
	}
	if c.wa.Store.LID.User != "" && jid.User == c.wa.Store.LID.User && jid.Server == types.HiddenUserServer {
		return c.ownJID()
	}
	return jid
}

func (c *Client) isMe(jid types.JID) bool {
	own := c.ownJID()
	return !own.IsEmpty() && c.canonical(jid).User == own.User
}

func (c *Client) contactName(jid types.JID) string {
	if c.isMe(jid) {
		return "You"
	}
	ctx := context.Background()
	info, err := c.wa.Store.Contacts.GetContact(ctx, jid)
	if (err != nil || !info.Found) && jid.Server == types.DefaultUserServer {
		if lid, lerr := c.wa.Store.LIDs.GetLIDForPN(ctx, jid); lerr == nil && !lid.IsEmpty() {
			info, err = c.wa.Store.Contacts.GetContact(ctx, lid)
		}
	}
	if err == nil && info.Found {
		if name := firstNonEmpty(info.FullName, info.FirstName, info.PushName, info.BusinessName); name != "" {
			return name
		}
	}
	if jid.Server == types.DefaultUserServer {
		return "+" + jid.User
	}
	return jid.User
}

func (c *Client) chatName(jid types.JID, stored string) string {
	if jid.Server == types.GroupServer {
		return firstNonEmpty(stored, "Group")
	}
	return c.contactName(jid)
}

// endregion

// region Events

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

// endregion

// region Queries (JSON for Kotlin)

type jsonReaction struct {
	Sender string `json:"sender"`
	Name   string `json:"name"`
	Emoji  string `json:"emoji"`
	FromMe bool   `json:"fromMe"`
}

type jsonMessage struct {
	ID          string         `json:"id"`
	Chat        string         `json:"chat"`
	Sender      string         `json:"sender"`
	SenderName  string         `json:"senderName"`
	FromMe      bool           `json:"fromMe"`
	Ts          int64          `json:"ts"`
	Kind        string         `json:"kind"`
	Text        string         `json:"text"`
	Filename    string         `json:"filename,omitempty"`
	Mime        string         `json:"mime,omitempty"`
	Size        int64          `json:"size,omitempty"`
	QuoteID     string         `json:"quoteId,omitempty"`
	QuoteSender string         `json:"quoteSender,omitempty"`
	QuoteText   string         `json:"quoteText,omitempty"`
	Edited      bool           `json:"edited"`
	Deleted     bool           `json:"deleted"`
	Forwarded   bool           `json:"forwarded"`
	Status      string         `json:"status,omitempty"`
	Poll        string         `json:"poll,omitempty"`
	Reactions   []jsonReaction `json:"reactions"`
}

type jsonChat struct {
	JID        string       `json:"jid"`
	Name       string       `json:"name"`
	Group      bool         `json:"group"`
	Archived   bool         `json:"archived"`
	Pinned     bool         `json:"pinned"`
	Muted      bool         `json:"muted"`
	Unread     int          `json:"unread"`
	LastTs     int64        `json:"lastTs"`
	Last       *jsonMessage `json:"last,omitempty"`
	NoteToSelf bool         `json:"noteToSelf"`
}

func (c *Client) toJSONMessage(r row, reactions []reactionRow) jsonMessage {
	m := jsonMessage{
		ID: r.ID, Chat: r.Chat, Sender: r.Sender, FromMe: r.FromMe, Ts: r.Ts, Kind: r.Kind, Text: r.Text,
		Filename: r.Filename, Mime: r.Mime, Size: r.Size, QuoteID: r.QuoteID, QuoteText: r.QuoteText,
		Edited: r.Edited, Deleted: r.Deleted, Forwarded: r.Forwarded, Status: r.Status, Poll: r.Poll,
		Reactions: []jsonReaction{},
	}
	if jid, err := types.ParseJID(r.Sender); err == nil {
		m.SenderName = c.contactName(jid)
	}
	if r.QuoteSender != "" {
		if jid, err := types.ParseJID(r.QuoteSender); err == nil {
			m.QuoteSender = c.contactName(jid)
		}
	}
	for _, reaction := range reactions {
		jid, _ := types.ParseJID(reaction.Sender)
		m.Reactions = append(m.Reactions, jsonReaction{
			Sender: reaction.Sender, Name: c.contactName(jid), Emoji: reaction.Emoji, FromMe: c.isMe(jid),
		})
	}
	return m
}

func (c *Client) toJSONChat(row chatRow) jsonChat {
	jid, _ := types.ParseJID(row.JID)
	chat := jsonChat{
		JID: row.JID, Name: c.chatName(jid, row.Name), Group: jid.Server == types.GroupServer,
		Archived: row.Archived, Pinned: row.Pinned, Unread: row.Unread, LastTs: row.LastTs,
		Muted:      row.MutedUntil < 0 || row.MutedUntil > time.Now().UnixMilli(),
		NoteToSelf: c.isMe(jid),
	}
	if chat.NoteToSelf {
		chat.Name = "Message yourself"
	}
	if last, err := c.store.lastMessage(row.JID); err == nil && last != nil {
		m := c.toJSONMessage(*last, nil)
		chat.Last = &m
	}
	return chat
}

func marshal(value any) (string, error) {
	encoded, err := json.Marshal(value)
	return string(encoded), err
}

// ChatsJSON lists chats: {"chats":[…],"archivedCount":n}.
func (c *Client) ChatsJSON(archived bool) (string, error) {
	rows, archivedCount, err := c.store.chats(archived)
	if err != nil {
		return "", err
	}
	chats := make([]jsonChat, 0, len(rows))
	for _, row := range rows {
		chats = append(chats, c.toJSONChat(row))
	}
	return marshal(map[string]any{"chats": chats, "archivedCount": archivedCount})
}

// ChatJSON describes one chat, creating it if it's new.
func (c *Client) ChatJSON(jid string) (string, error) {
	row, err := c.store.chat(jid)
	if err != nil {
		parsed, perr := types.ParseJID(jid)
		if perr != nil {
			return "", perr
		}
		row = chatRow{JID: parsed.String()}
	}
	return marshal(c.toJSONChat(row))
}

// MessagesJSON pages through a chat, oldest first, older than (beforeTs, beforeID).
func (c *Client) MessagesJSON(chat string, beforeTs int64, beforeID string, limit int) (string, error) {
	rows, err := c.store.messages(chat, beforeTs, beforeID, limit)
	if err != nil {
		return "", err
	}
	ids := make([]string, len(rows))
	for i, r := range rows {
		ids[i] = r.ID
	}
	reactions, err := c.store.reactions(chat, ids)
	if err != nil {
		return "", err
	}
	out := make([]jsonMessage, 0, len(rows))
	for _, r := range rows {
		out = append(out, c.toJSONMessage(r, reactions[r.ID]))
	}
	return marshal(out)
}

// SearchJSON finds messages containing query across all chats.
func (c *Client) SearchJSON(query string) (string, error) {
	rows, err := c.store.search(query, 50)
	if err != nil {
		return "", err
	}
	out := make([]jsonMessage, 0, len(rows))
	for _, r := range rows {
		out = append(out, c.toJSONMessage(r, nil))
	}
	return marshal(out)
}

// endregion

// region Actions

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

// endregion

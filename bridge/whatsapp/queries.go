// Read-only queries returning JSON in the shape BridgeJson.kt expects.

package wabridge

import (
	"context"
	"encoding/json"
	"sort"
	"strings"
	"time"

	"go.mau.fi/whatsmeow/types"
)

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

// ContactsJSON lists saved address-book contacts (not everyone seen in
// groups), sorted by name: [{"id","name","detail"}].
func (c *Client) ContactsJSON() (string, error) {
	all, err := c.wa.Store.Contacts.GetAllContacts(context.Background())
	if err != nil {
		return "", err
	}
	type item struct {
		ID     string `json:"id"`
		Name   string `json:"name"`
		Detail string `json:"detail,omitempty"`
	}
	seen := map[string]bool{}
	out := []item{}
	for jid, info := range all {
		name := firstNonEmpty(info.FullName, info.FirstName)
		canon := c.canonical(jid)
		if name == "" || canon.Server != types.DefaultUserServer || c.isMe(canon) || seen[canon.String()] {
			continue
		}
		seen[canon.String()] = true
		out = append(out, item{ID: canon.String(), Name: name, Detail: "+" + canon.User})
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return marshal(out)
}

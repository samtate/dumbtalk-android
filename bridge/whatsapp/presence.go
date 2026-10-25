package wabridge

import (
	"encoding/json"

	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// WhatsApp reports someone's presence only once asked to (per connection),
// and only while we show as online ourselves, which an open chat does.

type presenceState struct {
	Online   bool  `json:"online"`
	LastSeen int64 `json:"lastSeen,omitempty"` // ms; 0 when hidden
}

type jsonReceipt struct {
	Name      string `json:"name"`
	Delivered int64  `json:"delivered,omitempty"`
	Read      int64  `json:"read,omitempty"`
	Played    int64  `json:"played,omitempty"`
}

func (c *Client) onPresence(evt *events.Presence) {
	p := presenceState{Online: !evt.Unavailable}
	if !evt.LastSeen.IsZero() {
		p.LastSeen = evt.LastSeen.UnixMilli()
	}
	// Under both forms of the JID, so either finds it.
	c.presence.Store(evt.From.ToNonAD().String(), p)
	c.presence.Store(c.canonical(evt.From.ToNonAD()).String(), p)
	emit(c.listener, "presence", nil)
}

// PresenceJSON returns what's known of the people with these JIDs (a JSON
// array, e.g. a chat or a group's member ids) as {id: {online, lastSeen}},
// and asks WhatsApp to report on any it isn't already (the first 64).
func (c *Client) PresenceJSON(idsJSON string) (string, error) {
	var ids []string
	if err := json.Unmarshal([]byte(idsJSON), &ids); err != nil {
		return "", err
	}
	ctx, cancel := timeout()
	defer cancel()
	out := map[string]presenceState{}
	for i, id := range ids {
		jid, err := types.ParseJID(id)
		if err != nil || jid.Server == types.GroupServer || c.isMe(jid) {
			continue
		}
		if _, asked := c.subscribed.LoadOrStore(id, true); !asked && i < 64 {
			if err := c.wa.SubscribePresence(ctx, jid); err != nil {
				c.subscribed.Delete(id)
			}
		}
		for _, key := range []string{id, c.canonical(jid).String()} {
			if p, ok := c.presence.Load(key); ok {
				out[id] = p.(presenceState)
				break
			}
		}
	}
	return marshal(out)
}

// ReceiptsJSON lists who a message of ours reached and when, by recipient.
func (c *Client) ReceiptsJSON(chat, id string) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	rows, err := c.store.receipts(jid.String(), id)
	if err != nil {
		return "", err
	}
	out := []jsonReceipt{}
	for _, r := range rows {
		reader, err := types.ParseJID(r.Reader)
		if err != nil {
			continue
		}
		out = append(out, jsonReceipt{Name: c.contactName(reader), Delivered: r.Delivered, Read: r.Read, Played: r.Played})
	}
	return marshal(out)
}

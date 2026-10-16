// Mapping users and chats to stable JIDs and display names.

package wabridge

import (
	"context"

	"go.mau.fi/whatsmeow/types"
)

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

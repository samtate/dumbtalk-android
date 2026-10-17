// Groups: their members (also used for mentions), creating them and
// changing who is in them.

package wabridge

import (
	"encoding/json"
	"errors"
	"sort"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/types"
)

type jsonMember struct {
	// The JID the chat addresses them by: a phone number or, in groups
	// that hide numbers, a LID. Mentions use it as is.
	ID    string `json:"id"`
	Name  string `json:"name"`
	Admin bool   `json:"admin"`
}

// MembersJSON lists the people in a chat besides us, by name.
func (c *Client) MembersJSON(chat string) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	out := []jsonMember{}
	if jid.Server != types.GroupServer {
		if !c.isMe(jid) {
			out = append(out, jsonMember{ID: jid.String(), Name: c.contactName(jid)})
		}
		return marshal(out)
	}
	ctx, cancel := timeout()
	defer cancel()
	group, err := c.wa.GetGroupInfo(ctx, jid)
	if err != nil {
		return "", err
	}
	c.keepGroup(group)
	for _, participant := range group.Participants {
		if c.isMe(participant.JID) || (!participant.PhoneNumber.IsEmpty() && c.isMe(participant.PhoneNumber)) {
			continue
		}
		person := participant.JID
		if !participant.PhoneNumber.IsEmpty() {
			person = participant.PhoneNumber
		}
		out = append(out, jsonMember{
			ID: participant.JID.String(), Name: c.contactName(c.canonical(person)),
			Admin: participant.IsAdmin || participant.IsSuperAdmin,
		})
	}
	sort.Slice(out, func(i, j int) bool { return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name) })
	return marshal(out)
}

func parseMembers(membersJSON string) ([]types.JID, error) {
	var ids []string
	if err := json.Unmarshal([]byte(membersJSON), &ids); err != nil {
		return nil, err
	}
	var out []types.JID
	for _, id := range ids {
		if jid, err := types.ParseJID(id); err == nil && jid.User != "" {
			out = append(out, jid)
		}
	}
	if len(out) == 0 {
		return nil, errors.New("choose at least one person")
	}
	return out, nil
}

// CreateGroup makes a group with us as admin; returns its chat id.
func (c *Client) CreateGroup(name, membersJSON string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		return "", errors.New("a group needs a name")
	}
	if len([]rune(name)) > 100 {
		return "", errors.New("that name is too long")
	}
	members, err := parseMembers(membersJSON)
	if err != nil {
		return "", err
	}
	ctx, cancel := timeout()
	defer cancel()
	group, err := c.wa.CreateGroup(ctx, whatsmeow.ReqCreateGroup{Name: name, Participants: members})
	if err != nil {
		return "", err
	}
	chat := group.JID.String()
	c.store.ensureChat(chat, time.Now().UnixMilli())
	c.keepGroup(group)
	c.note(chat, time.Now(), "You created the group")
	c.noteRefusals(chat, group.Participants)
	emit(c.listener, "chats", nil)
	return chat, nil
}

// noteRefusals says who couldn't be added (their privacy settings need an invite).
func (c *Client) noteRefusals(chat string, participants []types.GroupParticipant) {
	var refused []string
	for _, participant := range participants {
		if participant.Error != 0 {
			refused = append(refused, c.contactName(c.canonical(participant.JID)))
		}
	}
	if len(refused) > 0 {
		c.note(chat, time.Now(), strings.Join(refused, ", ")+" couldn't be added: invite them from your main phone")
	}
}

func (c *Client) RenameGroup(chat, name string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	name = strings.TrimSpace(name)
	if name == "" {
		return errors.New("a group needs a name")
	}
	ctx, cancel := timeout()
	defer cancel()
	if err := c.wa.SetGroupName(ctx, jid, name); err != nil {
		return err
	}
	c.store.updateChat(jid.String(), "name", name)
	c.note(jid.String(), time.Now(), "You renamed the group to “"+name+"”")
	emit(c.listener, "messages", map[string]string{"chat": jid.String()})
	emit(c.listener, "chats", nil)
	return nil
}

func (c *Client) changeMembers(chat string, members []types.JID, change whatsmeow.ParticipantChange, done string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	ctx, cancel := timeout()
	defer cancel()
	result, err := c.wa.UpdateGroupParticipants(ctx, jid, members, change)
	if err != nil {
		return err
	}
	var names []string
	for _, participant := range result {
		if participant.Error == 0 {
			names = append(names, c.contactName(c.canonical(participant.JID)))
		}
	}
	if len(names) > 0 {
		c.note(jid.String(), time.Now(), done+" "+strings.Join(names, ", "))
	}
	c.noteRefusals(jid.String(), result)
	go c.refreshGroup(jid)
	emit(c.listener, "messages", map[string]string{"chat": jid.String()})
	return nil
}

// AddMembers adds people (JSON array of chat ids) to a group.
func (c *Client) AddMembers(chat, membersJSON string) error {
	members, err := parseMembers(membersJSON)
	if err != nil {
		return err
	}
	return c.changeMembers(chat, members, whatsmeow.ParticipantChangeAdd, "You added")
}

func (c *Client) RemoveMember(chat, member string) error {
	jid, err := types.ParseJID(member)
	if err != nil {
		return err
	}
	return c.changeMembers(chat, []types.JID{jid}, whatsmeow.ParticipantChangeRemove, "You removed")
}

func (c *Client) LeaveGroup(chat string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	ctx, cancel := timeout()
	defer cancel()
	if err := c.wa.LeaveGroup(ctx, jid); err != nil {
		return err
	}
	c.note(jid.String(), time.Now(), "You left the group")
	c.store.updateChat(jid.String(), "archived", 1)
	emit(c.listener, "chats", nil)
	return nil
}

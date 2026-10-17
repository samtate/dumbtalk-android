// Polls: creating them, voting, and tallying the (encrypted) votes others cast.

package wabridge

import (
	"context"
	"encoding/json"
	"errors"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// pollSpec is the poll JSON kept on a message row. Chat and From are the
// poll's chat and sender as whatsmeow saw them, which key its message secret.
type pollSpec struct {
	Question string   `json:"question"`
	Options  []string `json:"options"`
	Multiple bool     `json:"multiple"`
	Chat     string   `json:"chat,omitempty"`
	From     string   `json:"from,omitempty"`
}

func decodePoll(encoded string) (pollSpec, bool) {
	var spec pollSpec
	return spec, encoded != "" && json.Unmarshal([]byte(encoded), &spec) == nil && len(spec.Options) > 0
}

// withOrigin records where whatsmeow keeps the secret for this poll.
func withOrigin(encoded string, chat, from types.JID) string {
	spec, ok := decodePoll(encoded)
	if !ok {
		return encoded
	}
	spec.Chat, spec.From = chat.ToNonAD().String(), from.ToNonAD().String()
	out, _ := json.Marshal(spec)
	return string(out)
}

// optionIndexes maps hashed option names in a vote to option positions.
func optionIndexes(spec pollSpec, hashes [][]byte) []int {
	byHash := map[string]int{}
	for i, hash := range whatsmeow.HashPollOptions(spec.Options) {
		byHash[string(hash)] = i
	}
	out := []int{}
	for _, hash := range hashes {
		if i, ok := byHash[string(hash)]; ok {
			out = append(out, i)
		}
	}
	return out
}

// recordVote stores a vote (newest wins; an empty vote retracts) on a poll we have.
func (c *Client) recordVote(chat, pollID string, voter types.JID, hashes [][]byte, ts int64) bool {
	poll, err := c.store.message(chat, pollID)
	if err != nil || poll == nil {
		return false
	}
	spec, ok := decodePoll(poll.Poll)
	if !ok {
		return false
	}
	if err := c.store.setVote(chat, pollID, c.canonical(voter).String(), optionIndexes(spec, hashes), ts); err != nil {
		c.log.Warnf("store poll vote: %v", err)
		return false
	}
	return true
}

// onPollVote decrypts a live vote and stores it.
func (c *Client) onPollVote(chat types.JID, evt *events.Message) bool {
	ctx, cancel := timeout()
	defer cancel()
	vote, err := c.wa.DecryptPollVote(ctx, evt)
	if err != nil {
		c.log.Warnf("poll vote: %v", err)
		return false
	}
	pollID := evt.Message.GetPollUpdateMessage().GetPollCreationMessageKey().GetID()
	return c.recordVote(chat.String(), pollID, evt.Info.Sender, vote.GetSelectedOptions(), evt.Info.Timestamp.UnixMilli())
}

// historyVotes stores the votes history sync delivers, already decrypted, with a poll.
func (c *Client) historyVotes(chat types.JID, pollID string, updates []*waWeb.PollUpdate, sender func(*waCommon.MessageKey) types.JID) {
	for _, update := range updates {
		voter := sender(update.GetPollUpdateMessageKey())
		if voter.IsEmpty() {
			continue
		}
		c.recordVote(chat.String(), pollID, voter, update.GetVote().GetSelectedOptions(), update.GetSenderTimestampMS())
	}
}

// pollVotes lists who chose each option, and which options we chose.
func (c *Client) pollVotes(chat, id string, options int) ([][]string, []int) {
	voters := make([][]string, options)
	for i := range voters {
		voters[i] = []string{}
	}
	mine := []int{}
	votes, err := c.store.votes(chat, id)
	if err != nil {
		return voters, mine
	}
	for _, vote := range votes {
		jid, err := types.ParseJID(vote.Voter)
		if err != nil {
			continue
		}
		name := c.contactName(jid)
		for _, i := range vote.Options {
			if i >= 0 && i < options {
				voters[i] = append(voters[i], name)
				if c.isMe(jid) {
					mine = append(mine, i)
				}
			}
		}
	}
	return voters, mine
}

// pollJSON is the poll as the UI sees it: the question and options plus votes.
func (c *Client) pollJSON(r row) string {
	spec, ok := decodePoll(r.Poll)
	if !ok {
		return r.Poll
	}
	voters, mine := c.pollVotes(r.Chat, r.ID, len(spec.Options))
	out, _ := json.Marshal(map[string]any{
		"question": spec.Question, "options": spec.Options, "multiple": spec.Multiple,
		"votes": voters, "mine": mine,
	})
	return string(out)
}

// CreatePoll sends a poll; optionsJSON is a JSON array of option names.
func (c *Client) CreatePoll(chat, question, optionsJSON string, multiple bool) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	var options []string
	if err := json.Unmarshal([]byte(optionsJSON), &options); err != nil || len(options) < 2 {
		return "", errors.New("a poll needs at least two options")
	}
	selectable := 1
	if multiple {
		selectable = 0 // any number
	}
	ctx, cancel := timeout()
	defer cancel()
	poll := c.wa.BuildPollCreation(question, options, selectable)
	expires := c.stampExpiry(jid, poll)
	resp, err := c.wa.SendMessage(ctx, jid, poll)
	if err != nil {
		return "", err
	}
	spec, _ := json.Marshal(pollSpec{Question: question, Options: options, Multiple: multiple})
	from := resp.Sender
	if from.IsEmpty() {
		from = c.ownJID()
	}
	sent := row{
		Chat: jid.String(), ID: resp.ID, Sender: c.ownJID().String(), FromMe: true, Ts: resp.Timestamp.UnixMilli(),
		Kind: "poll", Text: "", Status: "sent", Poll: withOrigin(string(spec), jid, from),
		ExpiresAt: expiresAt(resp.Timestamp.UnixMilli(), expires),
	}
	if err := c.store.putMessage(sent); err != nil {
		return "", err
	}
	emit(c.listener, "chats", nil)
	return marshal(c.toJSONMessage(sent, nil))
}

// VotePoll replaces our vote; optionsJSON is a JSON array of option indexes.
func (c *Client) VotePoll(chat, id, optionsJSON string) error {
	jid, err := parseChat(chat)
	if err != nil {
		return err
	}
	var chosen []int
	if err := json.Unmarshal([]byte(optionsJSON), &chosen); err != nil {
		return err
	}
	poll, err := c.store.message(jid.String(), id)
	if err != nil || poll == nil {
		return errors.New("poll not found")
	}
	spec, ok := decodePoll(poll.Poll)
	if !ok {
		return errors.New("not a poll")
	}
	names := make([]string, 0, len(chosen))
	for _, i := range chosen {
		if i < 0 || i >= len(spec.Options) {
			return errors.New("no such option")
		}
		names = append(names, spec.Options[i])
	}
	ctx, cancel := timeout()
	defer cancel()
	var msg *waE2E.Message
	for _, info := range c.pollOrigins(ctx, jid, poll, spec) {
		if msg, err = c.wa.BuildPollVote(ctx, &info, names); err == nil {
			break
		}
	}
	if err != nil {
		if errors.Is(err, whatsmeow.ErrOriginalMessageSecretNotFound) {
			return errors.New("this poll can't be voted on from this device")
		}
		return err
	}
	resp, err := c.wa.SendMessage(ctx, jid, msg)
	if err != nil {
		return err
	}
	if err := c.store.setVote(jid.String(), id, c.ownJID().String(), chosen, resp.Timestamp.UnixMilli()); err != nil {
		return err
	}
	emit(c.listener, "messages", map[string]string{"chat": jid.String()})
	return nil
}

// pollOrigins lists the chat/sender pairs whatsmeow may have keyed the poll's
// secret under: as recorded, then with phone numbers and LIDs swapped.
func (c *Client) pollOrigins(ctx context.Context, chat types.JID, poll *row, spec pollSpec) []types.MessageInfo {
	chats := []types.JID{chat}
	if parsed, err := types.ParseJID(spec.Chat); err == nil && parsed != chat {
		chats = append([]types.JID{parsed}, chats...)
	}
	sender, _ := types.ParseJID(poll.Sender)
	if poll.FromMe {
		sender = c.ownJID()
	}
	senders := []types.JID{sender}
	if parsed, err := types.ParseJID(spec.From); err == nil && parsed != sender {
		senders = append([]types.JID{parsed}, senders...)
	}
	for _, s := range senders {
		if alt := c.alternate(ctx, s); !alt.IsEmpty() {
			senders = append(senders, alt)
		}
	}
	for _, ch := range chats {
		if alt := c.alternate(ctx, ch); !alt.IsEmpty() {
			chats = append(chats, alt)
		}
	}
	var out []types.MessageInfo
	for _, ch := range chats {
		for _, s := range senders {
			out = append(out, types.MessageInfo{
				MessageSource: types.MessageSource{Chat: ch, Sender: s, IsFromMe: poll.FromMe, IsGroup: ch.Server == types.GroupServer},
				ID:            poll.ID,
			})
		}
	}
	return out
}

// alternate is a user's LID for a phone-number JID, or the reverse.
func (c *Client) alternate(ctx context.Context, jid types.JID) types.JID {
	var alt types.JID
	switch jid.Server {
	case types.DefaultUserServer:
		if c.isMe(jid) {
			return c.wa.Store.LID.ToNonAD()
		}
		alt, _ = c.wa.Store.LIDs.GetLIDForPN(ctx, jid)
	case types.HiddenUserServer:
		alt, _ = c.wa.Store.LIDs.GetPNForLID(ctx, jid)
	}
	return alt.ToNonAD()
}

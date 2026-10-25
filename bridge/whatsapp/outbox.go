package wabridge

import (
	"errors"
	"os"
	"path/filepath"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// Our messages are stored before they're sent, under the ID they'll be sent
// with, so the chat shows them at once and a send is tried exactly once. A
// failed one is retried only when asked, under the same ID: WhatsApp drops
// a message whose ID it already has, so nobody sees it twice even if the
// first attempt got through.

// outgoing is a stored message waiting for its one attempt.
type outgoing struct {
	jid   types.JID
	id    string
	build func() (*waE2E.Message, error)
}

// sendQueued works through the outbox one message at a time, so they arrive in order.
func (c *Client) sendQueued() {
	for o := range c.outbox {
		c.attempt(o.jid, o.id, o.build)
	}
}

// queue stores r as a message from us that's being sent and returns it at
// once; the outbox then makes the one attempt to build and send it. Errors
// are returned only when nothing was stored.
func (c *Client) queue(jid types.JID, r row, build func() (*waE2E.Message, error)) (string, error) {
	r.Chat, r.Sender, r.FromMe, r.Status = jid.String(), c.ownJID().String(), true, "sending"
	if r.ID == "" {
		r.ID = c.wa.GenerateMessageID()
	}
	r.Ts = time.Now().UnixMilli()
	r.ExpiresAt = expiresAt(r.Ts, c.store.chatTimer(r.Chat))
	if err := c.store.putMessage(r); err != nil {
		return "", err
	}
	c.messagesChanged(r.Chat)
	c.outbox <- outgoing{jid, r.ID, build}
	return marshal(c.toJSONMessage(r, nil))
}

// Retry sends a failed message again under its first ID.
func (c *Client) Retry(chat, id string) (string, error) {
	jid, err := parseChat(chat)
	if err != nil {
		return "", err
	}
	stored, err := c.store.message(jid.String(), id)
	if err != nil || stored == nil || !stored.FromMe {
		return "", errors.New("message not found")
	}
	if stored.Status != "failed" {
		return marshal(c.toJSONMessage(*stored, nil))
	}
	raw, _ := c.store.raw(jid.String(), id)
	build := func() (*waE2E.Message, error) {
		if len(raw) > 0 {
			var msg waE2E.Message
			return &msg, proto.Unmarshal(raw, &msg)
		}
		// The upload never finished: upload our copy again.
		data, err := os.ReadFile(c.mediaPath(id, stored.Mime))
		if err != nil {
			return nil, errors.New("the file to send is gone")
		}
		return c.uploadMedia(jid, *stored, data)
	}
	if err := c.store.settle(jid.String(), id, "sending"); err != nil {
		return "", err
	}
	c.messagesChanged(jid.String())
	c.outbox <- outgoing{jid, id, build}
	stored.Status = "sending"
	return marshal(c.toJSONMessage(*stored, nil))
}

// attempt builds and sends a stored message once and records how it went.
func (c *Client) attempt(jid types.JID, id string, build func() (*waE2E.Message, error)) {
	c.awaitConnection(5 * time.Minute)
	status := "sent"
	if err := c.sendBuilt(jid, id, build); err != nil {
		c.log.Warnf("send %s failed: %v", id, err)
		status = "failed"
	}
	if err := c.store.settle(jid.String(), id, status); err != nil {
		c.log.Warnf("record send of %s: %v", id, err)
	}
	c.messagesChanged(jid.String())
	emit(c.listener, "chats", nil)
}

func (c *Client) sendBuilt(jid types.JID, id string, build func() (*waE2E.Message, error)) error {
	msg, err := build()
	if err != nil {
		return err
	}
	// Kept so a retry sends exactly the same message, and media can be downloaded.
	raw, err := proto.Marshal(msg)
	if err != nil {
		return err
	}
	if err := c.store.setRaw(jid.String(), id, raw); err != nil {
		return err
	}
	ctx, cancel := timeout()
	defer cancel()
	_, err = c.wa.SendMessage(ctx, jid, msg, whatsmeow.SendRequestExtra{ID: types.MessageID(id)})
	return err
}

// awaitConnection waits (up to limit) for WhatsApp to be connected, so a
// message written offline goes when the connection is back instead of failing.
func (c *Client) awaitConnection(limit time.Duration) {
	for deadline := time.Now().Add(limit); !c.IsConnected() && time.Now().Before(deadline); {
		time.Sleep(time.Second)
	}
}

func (c *Client) messagesChanged(chat string) {
	emit(c.listener, "messages", map[string]string{"chat": chat})
}

// mediaPath is where a message's file is kept once downloaded (or sent).
func (c *Client) mediaPath(id, mime string) string {
	return filepath.Join(c.dir, "media", unsafeName.ReplaceAllString(id, "_")+extensionFor(mime))
}

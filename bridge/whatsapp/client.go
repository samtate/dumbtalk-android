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
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
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

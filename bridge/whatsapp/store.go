package wabridge

import (
	"database/sql"
	"strings"
)

// whatsmeow only keeps keys and sessions, so chats, messages and reactions
// live in this separate database, as wacli does.
const schema = `
CREATE TABLE IF NOT EXISTS chats (
	jid TEXT PRIMARY KEY,
	name TEXT NOT NULL DEFAULT '',
	archived INTEGER NOT NULL DEFAULT 0,
	pinned INTEGER NOT NULL DEFAULT 0,
	muted_until INTEGER NOT NULL DEFAULT 0,
	unread INTEGER NOT NULL DEFAULT 0,
	last_ts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS messages (
	chat TEXT NOT NULL,
	id TEXT NOT NULL,
	sender TEXT NOT NULL DEFAULT '',
	from_me INTEGER NOT NULL,
	ts INTEGER NOT NULL,
	kind TEXT NOT NULL,
	text TEXT NOT NULL DEFAULT '',
	filename TEXT NOT NULL DEFAULT '',
	mime TEXT NOT NULL DEFAULT '',
	size INTEGER NOT NULL DEFAULT 0,
	quote_id TEXT NOT NULL DEFAULT '',
	quote_sender TEXT NOT NULL DEFAULT '',
	quote_text TEXT NOT NULL DEFAULT '',
	edited INTEGER NOT NULL DEFAULT 0,
	deleted INTEGER NOT NULL DEFAULT 0,
	forwarded INTEGER NOT NULL DEFAULT 0,
	status TEXT NOT NULL DEFAULT '',
	poll TEXT NOT NULL DEFAULT '',
	raw BLOB,
	PRIMARY KEY (chat, id)
);
CREATE INDEX IF NOT EXISTS messages_by_time ON messages (chat, ts);
CREATE TABLE IF NOT EXISTS reactions (
	chat TEXT NOT NULL,
	msg TEXT NOT NULL,
	sender TEXT NOT NULL,
	emoji TEXT NOT NULL,
	PRIMARY KEY (chat, msg, sender)
);
`

type msgStore struct{ db *sql.DB }

func openStore(path string) (*msgStore, error) {
	db, err := sql.Open("sqlite3", "file:"+path+"?_foreign_keys=on&_journal_mode=WAL&_busy_timeout=5000")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(1)
	if _, err := db.Exec(schema); err != nil {
		db.Close()
		return nil, err
	}
	return &msgStore{db: db}, nil
}

type row struct {
	Chat, ID, Sender                       string
	FromMe                                 bool
	Ts                                     int64
	Kind, Text, Filename, Mime             string
	Size                                   int64
	QuoteID, QuoteSender, QuoteText        string
	Edited, Deleted, Forwarded             bool
	Status, Poll                           string
	Raw                                    []byte
}

func b2i(value bool) int {
	if value {
		return 1
	}
	return 0
}

func (s *msgStore) ensureChat(jid string, ts int64) error {
	_, err := s.db.Exec(`INSERT INTO chats (jid, last_ts) VALUES (?, ?)
		ON CONFLICT (jid) DO UPDATE SET last_ts = MAX(last_ts, excluded.last_ts)`, jid, ts)
	return err
}

// putMessage inserts or refreshes a message, keeping its delivery status
// unless the new row knows better.
func (s *msgStore) putMessage(r row) error {
	_, err := s.db.Exec(`INSERT INTO messages
		(chat, id, sender, from_me, ts, kind, text, filename, mime, size, quote_id, quote_sender, quote_text, edited, deleted, forwarded, status, poll, raw)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (chat, id) DO UPDATE SET
			sender = excluded.sender, kind = excluded.kind, text = excluded.text,
			filename = excluded.filename, mime = excluded.mime, size = excluded.size,
			quote_id = excluded.quote_id, quote_sender = excluded.quote_sender, quote_text = excluded.quote_text,
			forwarded = excluded.forwarded, poll = excluded.poll, raw = excluded.raw,
			status = CASE WHEN excluded.status = '' THEN messages.status ELSE excluded.status END`,
		r.Chat, r.ID, r.Sender, b2i(r.FromMe), r.Ts, r.Kind, r.Text, r.Filename, r.Mime, r.Size,
		r.QuoteID, r.QuoteSender, r.QuoteText, b2i(r.Edited), b2i(r.Deleted), b2i(r.Forwarded), r.Status, r.Poll, r.Raw)
	if err != nil {
		return err
	}
	return s.ensureChat(r.Chat, r.Ts)
}

func (s *msgStore) editMessage(chat, id, text string) error {
	_, err := s.db.Exec(`UPDATE messages SET text = ?, edited = 1 WHERE chat = ? AND id = ?`, text, chat, id)
	return err
}

func (s *msgStore) deleteMessage(chat, id string) error {
	_, err := s.db.Exec(`UPDATE messages SET text = '', deleted = 1, raw = NULL WHERE chat = ? AND id = ?`, chat, id)
	return err
}

var statusRank = map[string]int{"": 0, "pending": 1, "sent": 2, "delivered": 3, "read": 4}

// setStatus only ever moves a message's status forward.
func (s *msgStore) setStatus(chat string, ids []string, status string) error {
	for _, id := range ids {
		var current string
		err := s.db.QueryRow(`SELECT status FROM messages WHERE chat = ? AND id = ?`, chat, id).Scan(&current)
		if err != nil || statusRank[status] <= statusRank[current] {
			continue
		}
		if _, err := s.db.Exec(`UPDATE messages SET status = ? WHERE chat = ? AND id = ?`, status, chat, id); err != nil {
			return err
		}
	}
	return nil
}

func (s *msgStore) setReaction(chat, msg, sender, emoji string) error {
	if emoji == "" {
		_, err := s.db.Exec(`DELETE FROM reactions WHERE chat = ? AND msg = ? AND sender = ?`, chat, msg, sender)
		return err
	}
	_, err := s.db.Exec(`INSERT INTO reactions (chat, msg, sender, emoji) VALUES (?, ?, ?, ?)
		ON CONFLICT (chat, msg, sender) DO UPDATE SET emoji = excluded.emoji`, chat, msg, sender, emoji)
	return err
}

func (s *msgStore) updateChat(jid, column string, value any) error {
	if err := s.ensureChat(jid, 0); err != nil {
		return err
	}
	switch column {
	case "name", "archived", "pinned", "muted_until", "unread":
	default:
		panic("unknown chat column " + column)
	}
	_, err := s.db.Exec(`UPDATE chats SET `+column+` = ? WHERE jid = ?`, value, jid)
	return err
}

func (s *msgStore) bumpUnread(jid string) error {
	_, err := s.db.Exec(`UPDATE chats SET unread = unread + 1 WHERE jid = ?`, jid)
	return err
}

type chatRow struct {
	JID, Name                 string
	Archived, Pinned          bool
	MutedUntil, LastTs        int64
	Unread                    int
}

func (s *msgStore) chats(archived bool) ([]chatRow, int, error) {
	rows, err := s.db.Query(`SELECT jid, name, archived, pinned, muted_until, unread, last_ts FROM chats
		WHERE last_ts > 0 AND archived = ? ORDER BY pinned DESC, last_ts DESC LIMIT 500`, b2i(archived))
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []chatRow
	for rows.Next() {
		var c chatRow
		var a, p int
		if err := rows.Scan(&c.JID, &c.Name, &a, &p, &c.MutedUntil, &c.Unread, &c.LastTs); err != nil {
			return nil, 0, err
		}
		c.Archived, c.Pinned = a == 1, p == 1
		out = append(out, c)
	}
	var count int
	err = s.db.QueryRow(`SELECT COUNT(*) FROM chats WHERE last_ts > 0 AND archived = 1`).Scan(&count)
	return out, count, err
}

func (s *msgStore) chat(jid string) (chatRow, error) {
	var c chatRow
	var a, p int
	err := s.db.QueryRow(`SELECT jid, name, archived, pinned, muted_until, unread, last_ts FROM chats WHERE jid = ?`, jid).
		Scan(&c.JID, &c.Name, &a, &p, &c.MutedUntil, &c.Unread, &c.LastTs)
	c.Archived, c.Pinned = a == 1, p == 1
	return c, err
}

const messageColumns = `chat, id, sender, from_me, ts, kind, text, filename, mime, size, quote_id, quote_sender, quote_text, edited, deleted, forwarded, status, poll`

func scanRows(rows *sql.Rows) ([]row, error) {
	defer rows.Close()
	var out []row
	for rows.Next() {
		var r row
		var fromMe, edited, deleted, forwarded int
		if err := rows.Scan(&r.Chat, &r.ID, &r.Sender, &fromMe, &r.Ts, &r.Kind, &r.Text, &r.Filename, &r.Mime, &r.Size,
			&r.QuoteID, &r.QuoteSender, &r.QuoteText, &edited, &deleted, &forwarded, &r.Status, &r.Poll); err != nil {
			return nil, err
		}
		r.FromMe, r.Edited, r.Deleted, r.Forwarded = fromMe == 1, edited == 1, deleted == 1, forwarded == 1
		out = append(out, r)
	}
	return out, rows.Err()
}

func (s *msgStore) lastMessage(chat string) (*row, error) {
	rows, err := s.db.Query(`SELECT `+messageColumns+` FROM messages WHERE chat = ? ORDER BY ts DESC, id DESC LIMIT 1`, chat)
	if err != nil {
		return nil, err
	}
	list, err := scanRows(rows)
	if err != nil || len(list) == 0 {
		return nil, err
	}
	return &list[0], nil
}

// messages returns up to limit messages older than (beforeTs, beforeID), oldest first.
func (s *msgStore) messages(chat string, beforeTs int64, beforeID string, limit int) ([]row, error) {
	var rows *sql.Rows
	var err error
	if beforeTs > 0 {
		rows, err = s.db.Query(`SELECT `+messageColumns+` FROM messages WHERE chat = ? AND (ts < ? OR (ts = ? AND id < ?))
			ORDER BY ts DESC, id DESC LIMIT ?`, chat, beforeTs, beforeTs, beforeID, limit)
	} else {
		rows, err = s.db.Query(`SELECT `+messageColumns+` FROM messages WHERE chat = ? ORDER BY ts DESC, id DESC LIMIT ?`, chat, limit)
	}
	if err != nil {
		return nil, err
	}
	list, err := scanRows(rows)
	for i, j := 0, len(list)-1; i < j; i, j = i+1, j-1 {
		list[i], list[j] = list[j], list[i]
	}
	return list, err
}

func (s *msgStore) message(chat, id string) (*row, error) {
	rows, err := s.db.Query(`SELECT `+messageColumns+` FROM messages WHERE chat = ? AND id = ?`, chat, id)
	if err != nil {
		return nil, err
	}
	list, err := scanRows(rows)
	if err != nil || len(list) == 0 {
		return nil, err
	}
	return &list[0], nil
}

func (s *msgStore) unreadIncoming(chat string, limit int) ([]row, error) {
	rows, err := s.db.Query(`SELECT `+messageColumns+` FROM messages WHERE chat = ? AND from_me = 0 AND kind != 'system'
		ORDER BY ts DESC LIMIT ?`, chat, limit)
	if err != nil {
		return nil, err
	}
	return scanRows(rows)
}

func (s *msgStore) search(query string, limit int) ([]row, error) {
	escaped := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(query)
	rows, err := s.db.Query(`SELECT `+messageColumns+` FROM messages WHERE deleted = 0 AND text LIKE ? ESCAPE '\'
		ORDER BY ts DESC LIMIT ?`, "%"+escaped+"%", limit)
	if err != nil {
		return nil, err
	}
	return scanRows(rows)
}

type reactionRow struct{ Msg, Sender, Emoji string }

func (s *msgStore) reactions(chat string, ids []string) (map[string][]reactionRow, error) {
	out := map[string][]reactionRow{}
	if len(ids) == 0 {
		return out, nil
	}
	args := []any{chat}
	for _, id := range ids {
		args = append(args, id)
	}
	rows, err := s.db.Query(`SELECT msg, sender, emoji FROM reactions WHERE chat = ? AND msg IN (?`+
		strings.Repeat(",?", len(ids)-1)+`)`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var r reactionRow
		if err := rows.Scan(&r.Msg, &r.Sender, &r.Emoji); err != nil {
			return nil, err
		}
		out[r.Msg] = append(out[r.Msg], r)
	}
	return out, rows.Err()
}

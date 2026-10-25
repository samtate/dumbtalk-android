package wabridge

import (
	"database/sql"
	"encoding/json"
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
	pinned INTEGER NOT NULL DEFAULT 0,
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
	_, _ = db.Exec(`ALTER TABLE messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0`)
	// Message pins live apart from the messages they target: a pin can
	// arrive before (or without) its message reaching this device. until is
	// the expiry in ms, 0 for none.
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS pins (chat TEXT NOT NULL, msg TEXT NOT NULL, at INTEGER NOT NULL, until INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (chat, msg));
		INSERT OR IGNORE INTO pins (chat, msg, at) SELECT chat, id, ts FROM messages WHERE pinned = 1;
		UPDATE messages SET pinned = 0 WHERE pinned = 1`); err != nil {
		db.Close()
		return nil, err
	}
	// Each voter's latest choice in a poll, as a JSON array of option indexes.
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS poll_votes (chat TEXT NOT NULL, msg TEXT NOT NULL, voter TEXT NOT NULL, options TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY (chat, msg, voter))`); err != nil {
		db.Close()
		return nil, err
	}
	// Added later: mentions (JSON array of JIDs), when a disappearing
	// message goes (ms, 0 for never), and per chat the timer (seconds),
	// whether it's blocked and whether we're a group admin.
	for _, column := range []string{
		`ALTER TABLE messages ADD COLUMN mentions TEXT NOT NULL DEFAULT ''`,
		`ALTER TABLE messages ADD COLUMN expires_at INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE chats ADD COLUMN expire_timer INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE chats ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE chats ADD COLUMN admin INTEGER NOT NULL DEFAULT 0`,
	} {
		_, _ = db.Exec(column)
	}
	return &msgStore{db: db}, nil
}

type row struct {
	Chat, ID, Sender                string
	FromMe                          bool
	Ts                              int64
	Kind, Text, Filename, Mime      string
	Size                            int64
	QuoteID, QuoteSender, QuoteText string
	Edited, Deleted, Forwarded      bool
	Status, Poll                    string
	Pinned                          bool
	Raw                             []byte
	Mentions                        []string // JIDs the text mentions
	ExpiresAt                       int64    // ms; 0 if it doesn't disappear
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
		(chat, id, sender, from_me, ts, kind, text, filename, mime, size, quote_id, quote_sender, quote_text, edited, deleted, forwarded, status, poll, raw, mentions, expires_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		ON CONFLICT (chat, id) DO UPDATE SET
			sender = excluded.sender, kind = excluded.kind, text = excluded.text, mentions = excluded.mentions, expires_at = excluded.expires_at,
			filename = excluded.filename, mime = excluded.mime, size = excluded.size,
			quote_id = excluded.quote_id, quote_sender = excluded.quote_sender, quote_text = excluded.quote_text,
			forwarded = excluded.forwarded, poll = excluded.poll, raw = excluded.raw,
			status = CASE WHEN excluded.status = '' THEN messages.status ELSE excluded.status END`,
		r.Chat, r.ID, r.Sender, b2i(r.FromMe), r.Ts, r.Kind, r.Text, r.Filename, r.Mime, r.Size,
		r.QuoteID, r.QuoteSender, r.QuoteText, b2i(r.Edited), b2i(r.Deleted), b2i(r.Forwarded), r.Status, r.Poll, r.Raw,
		encodeList(r.Mentions), r.ExpiresAt)
	if err != nil {
		return err
	}
	return s.ensureChat(r.Chat, r.Ts)
}

func (s *msgStore) editMessage(chat, id, text string, mentions []string) error {
	_, err := s.db.Exec(`UPDATE messages SET text = ?, mentions = ?, edited = 1 WHERE chat = ? AND id = ?`, text, encodeList(mentions), chat, id)
	return err
}

func encodeList(values []string) string {
	if len(values) == 0 {
		return ""
	}
	encoded, _ := json.Marshal(values)
	return string(encoded)
}

func decodeList(encoded string) []string {
	var values []string
	if encoded != "" {
		_ = json.Unmarshal([]byte(encoded), &values)
	}
	return values
}

// chatTimer is the chat's disappearing-message timer in seconds, 0 for off.
func (s *msgStore) chatTimer(jid string) uint32 {
	var seconds uint32
	_ = s.db.QueryRow(`SELECT expire_timer FROM chats WHERE jid = ?`, jid).Scan(&seconds)
	return seconds
}

// setChatTimer reports whether the timer changed.
func (s *msgStore) setChatTimer(jid string, seconds uint32) (bool, error) {
	if s.chatTimer(jid) == seconds {
		return false, nil
	}
	if err := s.ensureChat(jid, 0); err != nil {
		return false, err
	}
	_, err := s.db.Exec(`UPDATE chats SET expire_timer = ? WHERE jid = ?`, seconds, jid)
	return err == nil, err
}

// setBlocked makes exactly the given chats blocked when all is true, else
// sets one chat's state.
func (s *msgStore) setBlocked(jids []string, blocked bool, all bool) error {
	if all {
		if _, err := s.db.Exec(`UPDATE chats SET blocked = 0`); err != nil {
			return err
		}
	}
	for _, jid := range jids {
		if err := s.ensureChat(jid, 0); err != nil {
			return err
		}
		if _, err := s.db.Exec(`UPDATE chats SET blocked = ? WHERE jid = ?`, b2i(blocked), jid); err != nil {
			return err
		}
	}
	return nil
}

func (s *msgStore) setAdmin(jid string, admin bool) error {
	_, err := s.db.Exec(`UPDATE chats SET admin = ? WHERE jid = ?`, b2i(admin), jid)
	return err
}

// purgeExpired removes disappearing messages whose time is up and returns their chats.
func (s *msgStore) purgeExpired(now int64) ([]string, error) {
	rows, err := s.db.Query(`SELECT chat, id FROM messages WHERE expires_at > 0 AND expires_at <= ?`, now)
	if err != nil {
		return nil, err
	}
	type key struct{ chat, id string }
	var gone []key
	for rows.Next() {
		var k key
		if err := rows.Scan(&k.chat, &k.id); err != nil {
			rows.Close()
			return nil, err
		}
		gone = append(gone, k)
	}
	rows.Close()
	seen := map[string]bool{}
	var chats []string
	for _, k := range gone {
		for _, statement := range []string{
			`DELETE FROM messages WHERE chat = ? AND id = ?`, `DELETE FROM reactions WHERE chat = ? AND msg = ?`,
			`DELETE FROM pins WHERE chat = ? AND msg = ?`, `DELETE FROM poll_votes WHERE chat = ? AND msg = ?`,
		} {
			if _, err := s.db.Exec(statement, k.chat, k.id); err != nil {
				return nil, err
			}
		}
		if !seen[k.chat] {
			seen[k.chat] = true
			chats = append(chats, k.chat)
		}
	}
	return chats, nil
}

// nextExpiry is when the next disappearing message goes (ms), or 0 for none.
func (s *msgStore) nextExpiry() int64 {
	var next sql.NullInt64
	_ = s.db.QueryRow(`SELECT MIN(expires_at) FROM messages WHERE expires_at > 0`).Scan(&next)
	return next.Int64
}

func (s *msgStore) deleteMessage(chat, id string) error {
	_, err := s.db.Exec(`UPDATE messages SET text = '', deleted = 1, raw = NULL WHERE chat = ? AND id = ?`, chat, id)
	return err
}

// "sending" and "failed" are our own messages in the outbox; WhatsApp's
// acknowledgements still move a failed one on if it got through after all.
var statusRank = map[string]int{"": 0, "pending": 1, "sending": 1, "failed": 1, "sent": 2, "delivered": 3, "read": 4}

// settle records how sending a message went, unless an acknowledgement
// already moved it further.
func (s *msgStore) settle(chat, id, status string) error {
	_, err := s.db.Exec(`UPDATE messages SET status = ? WHERE chat = ? AND id = ? AND status IN ('sending', 'failed')`, status, chat, id)
	return err
}

// failInterrupted marks sends cut short (the app was killed) as failed, to retry.
func (s *msgStore) failInterrupted() error {
	_, err := s.db.Exec(`UPDATE messages SET status = 'failed' WHERE from_me = 1 AND status = 'sending'`)
	return err
}

// setRaw stores the protobuf of a message once it's built (after an upload).
func (s *msgStore) setRaw(chat, id string, raw []byte) error {
	_, err := s.db.Exec(`UPDATE messages SET raw = ? WHERE chat = ? AND id = ?`, raw, chat, id)
	return err
}

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
	case "name", "archived", "pinned", "muted_until", "unread", "last_ts":
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
	JID, Name          string
	Archived, Pinned   bool
	MutedUntil, LastTs int64
	Unread             int
	ExpireTimer        uint32
	Blocked, Admin     bool
}

const chatColumns = `jid, name, archived, pinned, muted_until, unread, last_ts, expire_timer, blocked, admin`

func scanChat(scan func(...any) error) (chatRow, error) {
	var c chatRow
	var a, p, b, admin int
	err := scan(&c.JID, &c.Name, &a, &p, &c.MutedUntil, &c.Unread, &c.LastTs, &c.ExpireTimer, &b, &admin)
	c.Archived, c.Pinned, c.Blocked, c.Admin = a == 1, p == 1, b == 1, admin == 1
	return c, err
}

func (s *msgStore) chats(archived bool) ([]chatRow, int, error) {
	rows, err := s.db.Query(`SELECT `+chatColumns+` FROM chats
		WHERE last_ts > 0 AND archived = ? ORDER BY pinned DESC, last_ts DESC LIMIT 500`, b2i(archived))
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []chatRow
	for rows.Next() {
		c, err := scanChat(rows.Scan)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, c)
	}
	var count int
	err = s.db.QueryRow(`SELECT COUNT(*) FROM chats WHERE last_ts > 0 AND archived = 1`).Scan(&count)
	return out, count, err
}

func (s *msgStore) chat(jid string) (chatRow, error) {
	return scanChat(s.db.QueryRow(`SELECT `+chatColumns+` FROM chats WHERE jid = ?`, jid).Scan)
}

const messageColumns = `chat, id, sender, from_me, ts, kind, text, filename, mime, size, quote_id, quote_sender, quote_text, edited, deleted, forwarded, status, poll, mentions, expires_at,
	EXISTS (SELECT 1 FROM pins p WHERE p.chat = messages.chat AND p.msg = messages.id AND (p.until = 0 OR p.until > CAST(strftime('%s','now') AS INTEGER) * 1000)) AS pinned`

func scanRows(rows *sql.Rows) ([]row, error) {
	defer rows.Close()
	var out []row
	for rows.Next() {
		var r row
		var fromMe, edited, deleted, forwarded, pinned int
		var mentions string
		if err := rows.Scan(&r.Chat, &r.ID, &r.Sender, &fromMe, &r.Ts, &r.Kind, &r.Text, &r.Filename, &r.Mime, &r.Size,
			&r.QuoteID, &r.QuoteSender, &r.QuoteText, &edited, &deleted, &forwarded, &r.Status, &r.Poll, &mentions, &r.ExpiresAt, &pinned); err != nil {
			return nil, err
		}
		r.Mentions = decodeList(mentions)
		r.FromMe, r.Edited, r.Deleted, r.Forwarded, r.Pinned = fromMe == 1, edited == 1, deleted == 1, forwarded == 1, pinned == 1
		out = append(out, r)
	}
	return out, rows.Err()
}

type voteRow struct {
	Voter   string
	Options []int
}

// setVote keeps a voter's newest vote; an empty one withdraws it.
func (s *msgStore) setVote(chat, msg, voter string, options []int, ts int64) error {
	if len(options) == 0 {
		_, err := s.db.Exec(`DELETE FROM poll_votes WHERE chat = ? AND msg = ? AND voter = ? AND ts <= ?`, chat, msg, voter, ts)
		return err
	}
	encoded, _ := json.Marshal(options)
	_, err := s.db.Exec(`INSERT INTO poll_votes (chat, msg, voter, options, ts) VALUES (?, ?, ?, ?, ?)
		ON CONFLICT (chat, msg, voter) DO UPDATE SET options = excluded.options, ts = excluded.ts WHERE excluded.ts >= poll_votes.ts`,
		chat, msg, voter, string(encoded), ts)
	return err
}

func (s *msgStore) votes(chat, msg string) ([]voteRow, error) {
	rows, err := s.db.Query(`SELECT voter, options FROM poll_votes WHERE chat = ? AND msg = ? ORDER BY ts`, chat, msg)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []voteRow
	for rows.Next() {
		var v voteRow
		var encoded string
		if err := rows.Scan(&v.Voter, &encoded); err != nil {
			return nil, err
		}
		if json.Unmarshal([]byte(encoded), &v.Options) == nil {
			out = append(out, v)
		}
	}
	return out, rows.Err()
}

// maxPins is how many messages a WhatsApp chat keeps pinned; a newer pin
// replaces the oldest.
const maxPins = 3

// setMessagePinned pins (until = expiry in ms, 0 for none) or unpins a
// message at time at, then drops expired pins and any beyond the newest maxPins.
func (s *msgStore) setMessagePinned(chat, id string, pinned bool, at, until int64) error {
	var err error
	if pinned {
		_, err = s.db.Exec(`INSERT INTO pins (chat, msg, at, until) VALUES (?, ?, ?, ?)
			ON CONFLICT (chat, msg) DO UPDATE SET at = excluded.at, until = excluded.until`, chat, id, at, until)
	} else {
		_, err = s.db.Exec(`DELETE FROM pins WHERE chat = ? AND msg = ?`, chat, id)
	}
	if err != nil {
		return err
	}
	_, err = s.db.Exec(`DELETE FROM pins WHERE chat = ? AND ((until > 0 AND until <= ?)
		OR msg NOT IN (SELECT msg FROM pins WHERE chat = ? ORDER BY at DESC LIMIT ?))`, chat, at, chat, maxPins)
	return err
}

// pinnedMessages lists the chat's live pins that reached this device, newest pin first.
func (s *msgStore) pinnedMessages(chat string) ([]row, error) {
	rows, err := s.db.Query(`SELECT `+messageColumns+` FROM messages
		JOIN (SELECT msg, at FROM pins WHERE chat = ?1 AND (until = 0 OR until > CAST(strftime('%s','now') AS INTEGER) * 1000)) live
		ON live.msg = messages.id
		WHERE messages.chat = ?1 AND deleted = 0 ORDER BY live.at DESC`, chat)
	if err != nil {
		return nil, err
	}
	return scanRows(rows)
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

// raw returns the stored protobuf of a media message, for downloading.
func (s *msgStore) raw(chat, id string) ([]byte, error) {
	var raw []byte
	err := s.db.QueryRow(`SELECT raw FROM messages WHERE chat = ? AND id = ?`, chat, id).Scan(&raw)
	return raw, err
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

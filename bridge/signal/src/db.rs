//! Chats, messages, reactions and receipts for the UI. presage keeps its own
//! protocol state and message archive; this is the flat, queryable view, with
//! the same shape as the WhatsApp bridge's store.

use serde::Serialize;
use sqlx::sqlite::{SqliteConnectOptions, SqliteJournalMode, SqlitePoolOptions};
use sqlx::{AssertSqlSafe, Row, SqlitePool};
use std::path::Path;

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS chats (
    id TEXT PRIMARY KEY,
    archived INTEGER NOT NULL DEFAULT 0,
    pinned INTEGER NOT NULL DEFAULT 0,
    muted INTEGER NOT NULL DEFAULT 0,
    unread INTEGER NOT NULL DEFAULT 0,
    last_ts INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS messages (
    chat TEXT NOT NULL,
    id TEXT NOT NULL,
    sender TEXT NOT NULL,
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
    status TEXT NOT NULL DEFAULT '',
    pinned INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (chat, id)
);
CREATE INDEX IF NOT EXISTS messages_by_time ON messages (chat, ts);
CREATE INDEX IF NOT EXISTS messages_by_id ON messages (id);
CREATE TABLE IF NOT EXISTS names (
    chat TEXT PRIMARY KEY,
    name TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS reactions (
    chat TEXT NOT NULL,
    msg TEXT NOT NULL,
    sender TEXT NOT NULL,
    emoji TEXT NOT NULL,
    PRIMARY KEY (chat, msg, sender)
);
"#;

#[derive(Clone, Debug, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageRow {
    pub chat: String,
    pub id: String,
    pub sender: String,
    pub from_me: bool,
    pub ts: i64,
    pub kind: String,
    pub text: String,
    pub filename: String,
    pub mime: String,
    pub size: i64,
    pub quote_id: String,
    pub quote_sender: String,
    pub quote_text: String,
    pub edited: bool,
    pub deleted: bool,
    pub status: String,
    pub pinned: bool,
    /// Styles and mentions over `text`: a JSON array of {start, length,
    /// style, id?} in UTF-16 units, or empty.
    #[serde(skip)]
    pub ranges: String,
}

#[derive(Clone, Debug, Default)]
pub struct PollRow {
    pub question: String,
    pub options: Vec<String>,
    pub multiple: bool,
    pub closed: bool,
}

#[derive(Clone, Debug, Default)]
pub struct ChatRow {
    pub id: String,
    pub archived: bool,
    pub pinned: bool,
    pub muted: bool,
    pub unread: i64,
    pub expire_timer: i64,
    pub last_ts: i64,
    /// A direct chat nobody here has accepted, written in or saved.
    pub request: bool,
    pub blocked: bool,
}

/// Chat `request` states: unknown (a request until we reply), accepted, blocked.
pub const REQUEST_UNKNOWN: i64 = 0;
pub const REQUEST_ACCEPTED: i64 = 1;
pub const REQUEST_BLOCKED: i64 = 2;

#[derive(Clone, Debug)]
pub struct ReactionRow {
    pub msg: String,
    pub sender: String,
    pub emoji: String,
}

#[derive(Clone)]
pub struct Db {
    pool: SqlitePool,
}

/// The message columns, as a macro so queries stay compile-time constants.
macro_rules! columns {
    () => {
        "chat, id, sender, from_me, ts, kind, text, filename, mime, size, quote_id, quote_sender, quote_text, edited, deleted, status, ranges, \
         EXISTS (SELECT 1 FROM pins p WHERE p.chat = messages.chat AND p.msg = messages.id \
           AND (p.until = 0 OR p.until > CAST(strftime('%s','now') AS INTEGER) * 1000)) AS pinned"
    };
}

fn message_from(row: &sqlx::sqlite::SqliteRow) -> MessageRow {
    MessageRow {
        chat: row.get("chat"),
        id: row.get("id"),
        sender: row.get("sender"),
        from_me: row.get::<i64, _>("from_me") == 1,
        ts: row.get("ts"),
        kind: row.get("kind"),
        text: row.get("text"),
        filename: row.get("filename"),
        mime: row.get("mime"),
        size: row.get("size"),
        quote_id: row.get("quote_id"),
        quote_sender: row.get("quote_sender"),
        quote_text: row.get("quote_text"),
        edited: row.get::<i64, _>("edited") == 1,
        deleted: row.get::<i64, _>("deleted") == 1,
        status: row.get("status"),
        pinned: row.get::<i64, _>("pinned") != 0,
        ranges: row.get("ranges"),
    }
}

fn chat_from(row: &sqlx::sqlite::SqliteRow) -> ChatRow {
    ChatRow {
        id: row.get("id"),
        archived: row.get::<i64, _>("archived") == 1,
        pinned: row.get::<i64, _>("pinned") == 1,
        muted: row.get::<i64, _>("muted") == 1,
        unread: row.get("unread"),
        expire_timer: row.try_get("expire_timer").unwrap_or(0),
        last_ts: row.get("last_ts"),
        request: row.try_get::<i64, _>("is_request").unwrap_or(0) == 1,
        blocked: row.try_get::<i64, _>("request").unwrap_or(0) == REQUEST_BLOCKED,
    }
}

/// "sending" and "failed" are our own messages in the outbox; a receipt
/// still moves a failed one on if it got through after all.
fn rank(status: &str) -> u8 {
    match status {
        "sent" => 2,
        "delivered" => 3,
        "read" => 4,
        "pending" | "sending" | "failed" => 1,
        _ => 0,
    }
}

pub type Result<T> = std::result::Result<T, sqlx::Error>;

impl Db {
    pub async fn open(path: &Path) -> Result<Db> {
        let options = SqliteConnectOptions::new()
            .filename(path)
            .create_if_missing(true)
            .journal_mode(SqliteJournalMode::Wal);
        let pool = SqlitePoolOptions::new()
            .max_connections(1)
            .connect_with(options)
            .await?;
        sqlx::raw_sql(SCHEMA).execute(&pool).await?;
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS message_attachments (chat TEXT NOT NULL, id TEXT NOT NULL, idx INTEGER NOT NULL, pointer BLOB NOT NULL, PRIMARY KEY(chat,id,idx))").execute(&pool).await?;
        // Added after the first release: the attachment pointer, for downloads.
        let _ = sqlx::raw_sql("ALTER TABLE messages ADD COLUMN attachment BLOB")
            .execute(&pool)
            .await;
        // Message pins live apart from the messages they target: a pin can
        // arrive before (or without) its message reaching this device.
        // `until` is the expiry in ms, 0 for a pin without one.
        let _ = sqlx::raw_sql("ALTER TABLE messages ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0").execute(&pool).await;
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS pins (chat TEXT NOT NULL, msg TEXT NOT NULL, at INTEGER NOT NULL, until INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat,msg))").execute(&pool).await?;
        // Polls: the question and options of a "poll" message, and each
        // voter's latest choice (Signal numbers a voter's votes with `count`).
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS polls (chat TEXT NOT NULL, msg TEXT NOT NULL, question TEXT NOT NULL, options TEXT NOT NULL, multiple INTEGER NOT NULL, closed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat,msg));
            CREATE TABLE IF NOT EXISTS poll_votes (chat TEXT NOT NULL, msg TEXT NOT NULL, voter TEXT NOT NULL, options TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(chat,msg,voter))").execute(&pool).await?;
        // Link previews the sender attached, as a JSON array of {url, title, description}.
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS link_previews (chat TEXT NOT NULL, msg TEXT NOT NULL, previews TEXT NOT NULL, PRIMARY KEY(chat,msg))").execute(&pool).await?;
        // Disappearing messages: each chat's timer (seconds) and its version,
        // which Signal bumps on every change; and each message's countdown,
        // which starts when it is sent, or for incoming messages when read.
        let _ = sqlx::raw_sql("ALTER TABLE chats ADD COLUMN expire_timer INTEGER NOT NULL DEFAULT 0").execute(&pool).await;
        let _ = sqlx::raw_sql("ALTER TABLE chats ADD COLUMN expire_version INTEGER NOT NULL DEFAULT 0").execute(&pool).await;
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS expiries (chat TEXT NOT NULL, msg TEXT NOT NULL, seconds INTEGER NOT NULL, started INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat,msg))").execute(&pool).await?;
        // Mentions and text styles over a message's text, as JSON.
        let _ = sqlx::raw_sql("ALTER TABLE messages ADD COLUMN ranges TEXT NOT NULL DEFAULT ''").execute(&pool).await;
        // Message requests: chats from before this column are all accepted.
        if sqlx::raw_sql("ALTER TABLE chats ADD COLUMN request INTEGER NOT NULL DEFAULT 0").execute(&pool).await.is_ok() {
            sqlx::raw_sql("UPDATE chats SET request = 1").execute(&pool).await?;
        }
        // Our messages until they're sent: the encoded DataMessage and, while
        // its attachments aren't uploaded yet, the files to upload (JSON).
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS outbox (chat TEXT NOT NULL, id TEXT NOT NULL, body BLOB NOT NULL, files TEXT NOT NULL DEFAULT '', voice INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat,id))").execute(&pool).await?;
        // How far each chat has been read here (ms), so read receipts go once.
        let _ = sqlx::raw_sql("ALTER TABLE chats ADD COLUMN read_through INTEGER NOT NULL DEFAULT 0").execute(&pool).await;
        // When each recipient got, read or played (viewed) a message of ours, in ms.
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS receipts (chat TEXT NOT NULL, msg TEXT NOT NULL, reader TEXT NOT NULL, delivered_at INTEGER NOT NULL DEFAULT 0, read_at INTEGER NOT NULL DEFAULT 0, played_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(chat,msg,reader))").execute(&pool).await?;
        // Sends cut short (the app was killed) are failed, to retry.
        sqlx::raw_sql("UPDATE messages SET status = 'failed' WHERE from_me = 1 AND status = 'sending'").execute(&pool).await?;
        // One-off upgrades already done, by name.
        sqlx::raw_sql("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)").execute(&pool).await?;
        // Pins from builds that flagged the message row instead.
        sqlx::raw_sql("INSERT OR IGNORE INTO pins (chat, msg, at) SELECT chat, id, ts FROM messages WHERE pinned = 1; UPDATE messages SET pinned = 0 WHERE pinned = 1").execute(&pool).await?;
        Ok(Db { pool })
    }

    pub async fn clear(&self) -> Result<()> {
        sqlx::raw_sql("DELETE FROM reactions; DELETE FROM pins; DELETE FROM polls; DELETE FROM poll_votes; DELETE FROM link_previews; DELETE FROM expiries; DELETE FROM message_attachments; DELETE FROM outbox; DELETE FROM receipts; DELETE FROM messages; DELETE FROM chats; DELETE FROM names;")
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn ensure_chat(&self, chat: &str, ts: i64) -> Result<()> {
        sqlx::query(
            "INSERT INTO chats (id, last_ts) VALUES (?, ?)
             ON CONFLICT (id) DO UPDATE SET last_ts = MAX(last_ts, excluded.last_ts)",
        )
        .bind(chat)
        .bind(ts)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    /// The display name the main phone uses for a chat (from storage service).
    pub async fn set_name(&self, chat: &str, name: &str) -> Result<()> {
        sqlx::query("INSERT INTO names (chat, name) VALUES (?, ?) ON CONFLICT (chat) DO UPDATE SET name = excluded.name")
            .bind(chat)
            .bind(name)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn name(&self, chat: &str) -> Result<Option<String>> {
        Ok(sqlx::query("SELECT name FROM names WHERE chat = ?")
            .bind(chat)
            .fetch_optional(&self.pool)
            .await?
            .map(|row| row.get("name")))
    }

    /// Names of direct-chat contacts from the main phone, sorted by name.
    pub async fn contact_names(&self) -> Result<Vec<(String, String)>> {
        Ok(sqlx::query("SELECT chat, name FROM names WHERE chat LIKE 'c:%' AND name != '' ORDER BY name COLLATE NOCASE")
            .fetch_all(&self.pool)
            .await?
            .iter()
            .map(|row| (row.get("chat"), row.get("name")))
            .collect())
    }

    /// Adds a chat with no messages yet, leaving existing chats untouched.
    pub async fn seed_chat(&self, chat: &str, ts: i64) -> Result<()> {
        sqlx::query("INSERT OR IGNORE INTO chats (id, last_ts) VALUES (?, ?)")
            .bind(chat)
            .bind(ts)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// Inserts a message, or refreshes it while keeping a known status.
    pub async fn put_message(&self, m: &MessageRow) -> Result<()> {
        sqlx::query(
            "INSERT INTO messages (chat, id, sender, from_me, ts, kind, text, filename, mime, size, quote_id, quote_sender, quote_text, edited, deleted, status, ranges)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT (chat, id) DO UPDATE SET
                kind = excluded.kind, text = excluded.text, ranges = excluded.ranges, filename = excluded.filename, mime = excluded.mime,
                size = excluded.size, quote_id = excluded.quote_id, quote_sender = excluded.quote_sender,
                quote_text = excluded.quote_text,
                status = CASE WHEN excluded.status = '' THEN messages.status ELSE excluded.status END",
        )
        .bind(&m.chat)
        .bind(&m.id)
        .bind(&m.sender)
        .bind(m.from_me as i64)
        .bind(m.ts)
        .bind(&m.kind)
        .bind(&m.text)
        .bind(&m.filename)
        .bind(&m.mime)
        .bind(m.size)
        .bind(&m.quote_id)
        .bind(&m.quote_sender)
        .bind(&m.quote_text)
        .bind(m.edited as i64)
        .bind(m.deleted as i64)
        .bind(&m.status)
        .bind(&m.ranges)
        .execute(&self.pool)
        .await?;
        self.ensure_chat(&m.chat, m.ts).await?;
        if m.from_me && m.kind != "system" {
            // Writing in a chat accepts it.
            sqlx::query("UPDATE chats SET request = 1 WHERE id = ? AND request = 0").bind(&m.chat).execute(&self.pool).await?;
        }
        if m.kind != "system" {
            // Messages take the chat's timer; ours count down from sending.
            sqlx::query("INSERT OR IGNORE INTO expiries (chat, msg, seconds, started)
                 SELECT id, ?, expire_timer, ? FROM chats WHERE id = ? AND expire_timer > 0")
                .bind(&m.id).bind(if m.from_me { m.ts } else { 0 }).bind(&m.chat)
                .execute(&self.pool).await?;
        }
        Ok(())
    }

    /// The chat's disappearing timer in seconds (0 for off) and its version.
    pub async fn chat_timer(&self, chat: &str) -> Result<(u32, u32)> {
        let row = sqlx::query("SELECT expire_timer, expire_version FROM chats WHERE id = ?")
            .bind(chat).fetch_optional(&self.pool).await?;
        Ok(row.map(|r| (r.get::<i64, _>("expire_timer") as u32, r.get::<i64, _>("expire_version") as u32)).unwrap_or((0, 0)))
    }

    /// Takes a timer only from a newer version than the one stored, as
    /// Signal's apps do; true if it changed. Senders without versions (0)
    /// only set it while we have none. `force` takes it regardless (our own
    /// changes, and groups, whose timer is group state).
    pub async fn set_chat_timer(&self, chat: &str, seconds: u32, version: u32, force: bool) -> Result<bool> {
        self.ensure_chat(chat, 0).await?;
        let (current, stored) = self.chat_timer(chat).await?;
        let newer = version > stored || (version == 0 && stored == 0 && seconds != current);
        if !force && !newer {
            return Ok(false);
        }
        if force && seconds == current {
            return Ok(false);
        }
        sqlx::query("UPDATE chats SET expire_timer = ?, expire_version = ? WHERE id = ?")
            .bind(seconds as i64).bind(version.max(stored) as i64).bind(chat).execute(&self.pool).await?;
        Ok(seconds != current)
    }

    /// Starts the countdown on the chat's incoming messages read so far (up to `through`, ms).
    pub async fn start_expiries(&self, chat: &str, through: i64, now: i64) -> Result<()> {
        sqlx::query("UPDATE expiries SET started = ? WHERE chat = ? AND started = 0 AND msg IN (SELECT id FROM messages WHERE chat = ? AND ts <= ?)")
            .bind(now).bind(chat).bind(chat).bind(through).execute(&self.pool).await?;
        Ok(())
    }

    /// When the chat's next started countdown ends, if any (ms).
    pub async fn next_expiry(&self) -> Result<Option<i64>> {
        Ok(sqlx::query_scalar("SELECT MIN(started + seconds * 1000) FROM expiries WHERE started > 0")
            .fetch_one(&self.pool).await?)
    }

    pub async fn set_request_state(&self, chat: &str, state: i64) -> Result<()> {
        self.ensure_chat(chat, 0).await?;
        sqlx::query("UPDATE chats SET request = ? WHERE id = ?").bind(state).bind(chat).execute(&self.pool).await?;
        Ok(())
    }

    /// Accepts a chat we know of from the main phone, unless it is blocked.
    pub async fn accept_known(&self, chat: &str) -> Result<()> {
        sqlx::query("UPDATE chats SET request = 1 WHERE id = ? AND request = 0").bind(chat).execute(&self.pool).await?;
        Ok(())
    }

    /// Makes exactly `chats` blocked: unblocks (accepts) the rest.
    pub async fn set_blocked_list(&self, chats: &[String]) -> Result<()> {
        sqlx::query("UPDATE chats SET request = 1 WHERE request = 2").execute(&self.pool).await?;
        for chat in chats {
            self.set_request_state(chat, REQUEST_BLOCKED).await?;
        }
        Ok(())
    }

    pub async fn request_state(&self, chat: &str) -> Result<i64> {
        Ok(sqlx::query_scalar("SELECT request FROM chats WHERE id = ?").bind(chat).fetch_optional(&self.pool).await?.unwrap_or(REQUEST_UNKNOWN))
    }

    /// Deletes a chat and everything in it from this device.
    pub async fn delete_chat(&self, chat: &str) -> Result<()> {
        for table in ["messages WHERE chat = ?1", "message_attachments WHERE chat = ?1", "reactions WHERE chat = ?1", "pins WHERE chat = ?1",
            "polls WHERE chat = ?1", "poll_votes WHERE chat = ?1", "link_previews WHERE chat = ?1", "expiries WHERE chat = ?1"] {
            sqlx::query(AssertSqlSafe(format!("DELETE FROM {table}"))).bind(chat).execute(&self.pool).await?;
        }
        sqlx::query("UPDATE chats SET last_ts = 0, unread = 0 WHERE id = ?").bind(chat).execute(&self.pool).await?;
        Ok(())
    }

    /// Whether the one-off upgrade `key` still has to run; marks it done.
    pub async fn first_time(&self, key: &str) -> Result<bool> {
        Ok(sqlx::query("INSERT OR IGNORE INTO meta (key, value) VALUES (?, 1)").bind(key).execute(&self.pool).await?.rows_affected() == 1)
    }

    /// Removes every trace of messages whose time is up; returns their chats.
    pub async fn purge_expired(&self, now: i64) -> Result<Vec<String>> {
        let rows = sqlx::query("SELECT chat, msg FROM expiries WHERE started > 0 AND started + seconds * 1000 <= ?")
            .bind(now).fetch_all(&self.pool).await?;
        let mut chats = Vec::new();
        for row in &rows {
            let (chat, msg): (String, String) = (row.get("chat"), row.get("msg"));
            for table in ["messages WHERE chat = ?1 AND id = ?2", "message_attachments WHERE chat = ?1 AND id = ?2",
                "reactions WHERE chat = ?1 AND msg = ?2", "pins WHERE chat = ?1 AND msg = ?2", "polls WHERE chat = ?1 AND msg = ?2",
                "poll_votes WHERE chat = ?1 AND msg = ?2", "link_previews WHERE chat = ?1 AND msg = ?2", "expiries WHERE chat = ?1 AND msg = ?2"] {
                sqlx::query(AssertSqlSafe(format!("DELETE FROM {table}"))).bind(&chat).bind(&msg).execute(&self.pool).await?;
            }
            if !chats.contains(&chat) { chats.push(chat); }
        }
        Ok(chats)
    }

    pub async fn set_attachment(&self, chat: &str, id: &str, pointer: &[u8]) -> Result<()> {
        sqlx::query("UPDATE messages SET attachment = ? WHERE chat = ? AND id = ?")
            .bind(pointer)
            .bind(chat)
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn set_attachments(&self, chat: &str, id: &str, pointers: &[Vec<u8>]) -> Result<()> {
        let mut tx = self.pool.begin().await?;
        sqlx::query("DELETE FROM message_attachments WHERE chat=? AND id=?")
            .bind(chat)
            .bind(id)
            .execute(&mut *tx)
            .await?;
        for (idx, pointer) in pointers.iter().enumerate() {
            sqlx::query(
                "INSERT OR REPLACE INTO message_attachments(chat,id,idx,pointer) VALUES(?,?,?,?)",
            )
            .bind(chat)
            .bind(id)
            .bind(idx as i64)
            .bind(pointer)
            .execute(&mut *tx)
            .await?;
        }
        tx.commit().await?;
        if let Some(first) = pointers.first() {
            self.set_attachment(chat, id, first).await?;
        }
        Ok(())
    }

    pub async fn attachments(&self, chat: &str, id: &str) -> Result<Vec<Vec<u8>>> {
        let rows = sqlx::query(
            "SELECT pointer FROM message_attachments WHERE chat=? AND id=? ORDER BY idx",
        )
        .bind(chat)
        .bind(id)
        .fetch_all(&self.pool)
        .await?;
        if rows.is_empty() {
            return Ok(self.attachment(chat, id).await?.into_iter().collect());
        }
        Ok(rows.into_iter().map(|r| r.get("pointer")).collect())
    }

    pub async fn attachment(&self, chat: &str, id: &str) -> Result<Option<Vec<u8>>> {
        Ok(
            sqlx::query("SELECT attachment FROM messages WHERE chat = ? AND id = ?")
                .bind(chat)
                .bind(id)
                .fetch_optional(&self.pool)
                .await?
                .and_then(|row| row.get::<Option<Vec<u8>>, _>("attachment")),
        )
    }

    pub async fn edit_message(&self, chat: &str, id: &str, text: &str, ranges: &str) -> Result<()> {
        sqlx::query("UPDATE messages SET text = ?, ranges = ?, edited = 1 WHERE chat = ? AND id = ?")
            .bind(text)
            .bind(ranges)
            .bind(chat)
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// Rewrites a body stored without its mentions and styles (not an edit).
    pub async fn repair_text(&self, chat: &str, id: &str, text: &str, ranges: &str) -> Result<()> {
        sqlx::query("UPDATE messages SET text = ?, ranges = ? WHERE chat = ? AND id = ? AND edited = 0 AND deleted = 0")
            .bind(text).bind(ranges).bind(chat).bind(id).execute(&self.pool).await?;
        Ok(())
    }

    pub async fn chat_ids(&self) -> Result<Vec<String>> {
        Ok(sqlx::query_scalar("SELECT id FROM chats").fetch_all(&self.pool).await?)
    }

    pub async fn set_previews(&self, chat: &str, id: &str, previews: &serde_json::Value) -> Result<()> {
        sqlx::query("INSERT OR REPLACE INTO link_previews (chat, msg, previews) VALUES (?, ?, ?)")
            .bind(chat).bind(id).bind(previews.to_string()).execute(&self.pool).await?;
        Ok(())
    }

    pub async fn previews(&self, chat: &str, id: &str) -> Result<Option<serde_json::Value>> {
        let row = sqlx::query("SELECT previews FROM link_previews WHERE chat = ? AND msg = ?")
            .bind(chat).bind(id).fetch_optional(&self.pool).await?;
        Ok(row.and_then(|row| serde_json::from_str(&row.get::<String, _>("previews")).ok()))
    }

    pub async fn put_poll(&self, chat: &str, id: &str, question: &str, options: &[String], multiple: bool) -> Result<()> {
        sqlx::query("INSERT OR REPLACE INTO polls (chat, msg, question, options, multiple) VALUES (?, ?, ?, ?, ?)")
            .bind(chat).bind(id).bind(question).bind(serde_json::to_string(options).unwrap_or_default()).bind(multiple as i64)
            .execute(&self.pool).await?;
        Ok(())
    }

    pub async fn poll(&self, chat: &str, id: &str) -> Result<Option<PollRow>> {
        let row = sqlx::query("SELECT question, options, multiple, closed FROM polls WHERE chat = ? AND msg = ?")
            .bind(chat).bind(id).fetch_optional(&self.pool).await?;
        Ok(row.map(|row| PollRow {
            question: row.get("question"),
            options: serde_json::from_str(&row.get::<String, _>("options")).unwrap_or_default(),
            multiple: row.get::<i64, _>("multiple") == 1,
            closed: row.get::<i64, _>("closed") == 1,
        }))
    }

    pub async fn close_poll(&self, chat: &str, id: &str) -> Result<()> {
        sqlx::query("UPDATE polls SET closed = 1 WHERE chat = ? AND msg = ?").bind(chat).bind(id).execute(&self.pool).await?;
        Ok(())
    }

    /// Keeps a voter's vote unless one with a higher count is already stored.
    pub async fn set_poll_vote(&self, chat: &str, id: &str, voter: &str, options: &[u32], count: i64) -> Result<()> {
        sqlx::query("INSERT INTO poll_votes (chat, msg, voter, options, count) VALUES (?, ?, ?, ?, ?)
             ON CONFLICT(chat, msg, voter) DO UPDATE SET options = excluded.options, count = excluded.count WHERE excluded.count >= poll_votes.count")
            .bind(chat).bind(id).bind(voter).bind(serde_json::to_string(options).unwrap_or_default()).bind(count)
            .execute(&self.pool).await?;
        Ok(())
    }

    /// (voter, chosen option indexes, vote count) for each voter.
    pub async fn poll_votes(&self, chat: &str, id: &str) -> Result<Vec<(String, Vec<u32>, i64)>> {
        let rows = sqlx::query("SELECT voter, options, count FROM poll_votes WHERE chat = ? AND msg = ? ORDER BY voter")
            .bind(chat).bind(id).fetch_all(&self.pool).await?;
        Ok(rows.iter().map(|row| (
            row.get("voter"),
            serde_json::from_str(&row.get::<String, _>("options")).unwrap_or_default(),
            row.get("count"),
        )).collect())
    }

    /// Most pins a Signal chat keeps; a newer pin replaces the oldest.
    pub const MAX_PINS: i64 = 3;

    /// Pins (`until` = expiry in ms, 0 for none) or unpins a message, then
    /// drops expired pins and any beyond the newest MAX_PINS.
    pub async fn set_message_pinned(&self, chat: &str, id: &str, pinned: bool, at: i64, until: i64) -> Result<()> {
        if pinned {
            sqlx::query("INSERT INTO pins (chat, msg, at, until) VALUES (?, ?, ?, ?) ON CONFLICT(chat, msg) DO UPDATE SET at = excluded.at, until = excluded.until")
                .bind(chat).bind(id).bind(at).bind(until).execute(&self.pool).await?;
        } else {
            sqlx::query("DELETE FROM pins WHERE chat = ? AND msg = ?").bind(chat).bind(id).execute(&self.pool).await?;
        }
        sqlx::query("DELETE FROM pins WHERE chat = ? AND ((until > 0 AND until <= ?) OR msg NOT IN (SELECT msg FROM pins WHERE chat = ? ORDER BY at DESC LIMIT ?))")
            .bind(chat).bind(at).bind(chat).bind(Self::MAX_PINS).execute(&self.pool).await?;
        Ok(())
    }

    /// The chat's live pins that reached this device, newest pin first.
    pub async fn pinned_messages(&self, chat: &str) -> Result<Vec<MessageRow>> {
        let rows = sqlx::query(concat!("SELECT ", columns!(), " FROM messages
             JOIN (SELECT msg, at FROM pins WHERE chat = ?1 AND (until = 0 OR until > CAST(strftime('%s','now') AS INTEGER) * 1000)) live
               ON live.msg = messages.id
             WHERE messages.chat = ?1 AND deleted = 0 ORDER BY live.at DESC"))
            .bind(chat).fetch_all(&self.pool).await?;
        Ok(rows.iter().map(message_from).collect())
    }

    pub async fn delete_message(&self, chat: &str, id: &str) -> Result<()> {
        sqlx::query("UPDATE messages SET text = '', deleted = 1 WHERE chat = ? AND id = ?")
            .bind(chat)
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// Records when our message `id` reached `reader` in one way ("delivered",
    /// "read" or "played"), keeping the first time it did.
    pub async fn set_receipt(&self, chat: &str, id: &str, reader: &str, kind: &str, at: i64) -> Result<()> {
        let column = match kind {
            "delivered" => "delivered_at",
            "read" => "read_at",
            "played" => "played_at",
            _ => return Ok(()),
        };
        let sql = format!(
            "INSERT INTO receipts (chat, msg, reader, {column}) VALUES (?, ?, ?, ?) \
             ON CONFLICT (chat, msg, reader) DO UPDATE SET {column} = CASE WHEN {column} = 0 THEN excluded.{column} ELSE {column} END"
        );
        sqlx::query(AssertSqlSafe(sql)).bind(chat).bind(id).bind(reader).bind(at).execute(&self.pool).await?;
        Ok(())
    }

    /// Who our message reached and when: (reader, delivered, read, played).
    pub async fn receipts(&self, chat: &str, id: &str) -> Result<Vec<(String, i64, i64, i64)>> {
        let rows = sqlx::query("SELECT reader, delivered_at, read_at, played_at FROM receipts WHERE chat = ? AND msg = ? ORDER BY reader")
            .bind(chat)
            .bind(id)
            .fetch_all(&self.pool)
            .await?;
        Ok(rows.iter().map(|r| (r.get("reader"), r.get("delivered_at"), r.get("read_at"), r.get("played_at"))).collect())
    }

    /// Marks the chat read up to `ts`: what's left unread is at most the
    /// incoming messages after it.
    pub async fn read_through(&self, chat: &str, ts: i64) -> Result<()> {
        sqlx::query(
            "UPDATE chats SET read_through = MAX(read_through, ?), unread = MIN(unread,
             (SELECT COUNT(*) FROM messages WHERE chat = ? AND from_me = 0 AND kind != 'system' AND ts > ?)) WHERE id = ?",
        )
        .bind(ts)
        .bind(chat)
        .bind(ts)
        .bind(chat)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    /// Keeps a message to send (or resend) under its id.
    pub async fn put_outbox(&self, chat: &str, id: &str, body: &[u8], files: &str, voice: bool) -> Result<()> {
        sqlx::query("INSERT OR REPLACE INTO outbox (chat, id, body, files, voice) VALUES (?, ?, ?, ?, ?)")
            .bind(chat)
            .bind(id)
            .bind(body)
            .bind(files)
            .bind(voice as i64)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// The stored message to send: its encoded body, files still to upload (JSON or "") and whether it's a voice note.
    pub async fn outbox(&self, chat: &str, id: &str) -> Result<Option<(Vec<u8>, String, bool)>> {
        let row = sqlx::query("SELECT body, files, voice FROM outbox WHERE chat = ? AND id = ?")
            .bind(chat)
            .bind(id)
            .fetch_optional(&self.pool)
            .await?;
        Ok(row.map(|r| (r.get("body"), r.get("files"), r.get::<i64, _>("voice") == 1)))
    }

    pub async fn delete_outbox(&self, chat: &str, id: &str) -> Result<()> {
        sqlx::query("DELETE FROM outbox WHERE chat = ? AND id = ?").bind(chat).bind(id).execute(&self.pool).await?;
        Ok(())
    }

    /// Records how sending our message went, unless a receipt already moved it further.
    pub async fn settle(&self, chat: &str, id: &str, status: &str) -> Result<()> {
        sqlx::query("UPDATE messages SET status = ? WHERE chat = ? AND id = ? AND status IN ('sending', 'failed')")
            .bind(status)
            .bind(chat)
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    /// Moves the status of our message `id` forward, in whichever chat it's in.
    /// Returns the chat when something changed.
    pub async fn advance_status(&self, id: &str, status: &str) -> Result<Option<String>> {
        let row = sqlx::query("SELECT chat, status FROM messages WHERE id = ? AND from_me = 1")
            .bind(id)
            .fetch_optional(&self.pool)
            .await?;
        let Some(row) = row else { return Ok(None) };
        let chat: String = row.get("chat");
        let current: String = row.get("status");
        if rank(status) <= rank(&current) {
            return Ok(None);
        }
        sqlx::query("UPDATE messages SET status = ? WHERE chat = ? AND id = ?")
            .bind(status)
            .bind(&chat)
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(Some(chat))
    }

    pub async fn chat_of_message(&self, id: &str) -> Result<Option<String>> {
        Ok(sqlx::query("SELECT chat FROM messages WHERE id = ?")
            .bind(id)
            .fetch_optional(&self.pool)
            .await?
            .map(|row| row.get("chat")))
    }

    pub async fn set_reaction(
        &self,
        chat: &str,
        msg: &str,
        sender: &str,
        emoji: &str,
    ) -> Result<()> {
        if emoji.is_empty() {
            sqlx::query("DELETE FROM reactions WHERE chat = ? AND msg = ? AND sender = ?")
                .bind(chat)
                .bind(msg)
                .bind(sender)
                .execute(&self.pool)
                .await?;
        } else {
            sqlx::query(
                "INSERT INTO reactions (chat, msg, sender, emoji) VALUES (?, ?, ?, ?)
                 ON CONFLICT (chat, msg, sender) DO UPDATE SET emoji = excluded.emoji",
            )
            .bind(chat)
            .bind(msg)
            .bind(sender)
            .bind(emoji)
            .execute(&self.pool)
            .await?;
        }
        Ok(())
    }

    pub async fn set_flag(&self, chat: &str, column: &str, value: i64) -> Result<()> {
        assert!(
            matches!(column, "archived" | "pinned" | "muted" | "unread"),
            "unknown column {column}"
        );
        self.ensure_chat(chat, 0).await?;
        sqlx::query(AssertSqlSafe(format!(
            "UPDATE chats SET {column} = ? WHERE id = ?"
        )))
        .bind(value)
        .bind(chat)
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn bump_unread(&self, chat: &str) -> Result<()> {
        sqlx::query("UPDATE chats SET unread = unread + 1 WHERE id = ?")
            .bind(chat)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn chats(&self, archived: bool) -> Result<(Vec<ChatRow>, i64)> {
        let rows = sqlx::query(
            "SELECT *, (request = 0 AND id LIKE 'c:%' AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.chat = chats.id AND m.from_me = 1)) AS is_request
             FROM chats WHERE last_ts > 0 AND archived = ? ORDER BY pinned DESC, last_ts DESC LIMIT 500",
        )
        .bind(archived as i64)
        .fetch_all(&self.pool)
        .await?;
        let count: i64 =
            sqlx::query("SELECT COUNT(*) AS n FROM chats WHERE last_ts > 0 AND archived = 1")
                .fetch_one(&self.pool)
                .await?
                .get("n");
        Ok((rows.iter().map(chat_from).collect(), count))
    }

    pub async fn chat(&self, chat: &str) -> Result<Option<ChatRow>> {
        Ok(sqlx::query("SELECT *, (request = 0 AND id LIKE 'c:%' AND NOT EXISTS (SELECT 1 FROM messages m WHERE m.chat = chats.id AND m.from_me = 1)) AS is_request FROM chats WHERE id = ?")
            .bind(chat)
            .fetch_optional(&self.pool)
            .await?
            .as_ref()
            .map(chat_from))
    }

    pub async fn last_message(&self, chat: &str) -> Result<Option<MessageRow>> {
        Ok(sqlx::query(concat!(
            "SELECT ",
            columns!(),
            " FROM messages WHERE chat = ? ORDER BY ts DESC, id DESC LIMIT 1"
        ))
        .bind(chat)
        .fetch_optional(&self.pool)
        .await?
        .as_ref()
        .map(message_from))
    }

    pub async fn message(&self, chat: &str, id: &str) -> Result<Option<MessageRow>> {
        Ok(sqlx::query(concat!(
            "SELECT ",
            columns!(),
            " FROM messages WHERE chat = ? AND id = ?"
        ))
        .bind(chat)
        .bind(id)
        .fetch_optional(&self.pool)
        .await?
        .as_ref()
        .map(message_from))
    }

    /// Up to `limit` messages older than (before_ts, before_id), oldest first.
    pub async fn messages(
        &self,
        chat: &str,
        before_ts: i64,
        before_id: &str,
        limit: i64,
    ) -> Result<Vec<MessageRow>> {
        let rows = if before_ts > 0 {
            sqlx::query(concat!(
                "SELECT ",
                columns!(),
                " FROM messages WHERE chat = ? AND (ts < ? OR (ts = ? AND id < ?))
                 ORDER BY ts DESC, id DESC LIMIT ?"
            ))
            .bind(chat)
            .bind(before_ts)
            .bind(before_ts)
            .bind(before_id)
            .bind(limit)
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(concat!(
                "SELECT ",
                columns!(),
                " FROM messages WHERE chat = ? ORDER BY ts DESC, id DESC LIMIT ?"
            ))
            .bind(chat)
            .bind(limit)
            .fetch_all(&self.pool)
            .await?
        };
        let mut list: Vec<_> = rows.iter().map(message_from).collect();
        list.reverse();
        Ok(list)
    }

    /// Incoming messages after where the chat was read up to, through `ts`,
    /// newest first: the ones reading through `ts` reads.
    pub async fn unread_through(&self, chat: &str, ts: i64, limit: i64) -> Result<Vec<MessageRow>> {
        let rows = sqlx::query(concat!(
            "SELECT ",
            columns!(),
            " FROM messages WHERE chat = ? AND from_me = 0 AND kind != 'system'
             AND ts > COALESCE((SELECT read_through FROM chats WHERE id = ?), 0) AND ts <= ?
             ORDER BY ts DESC LIMIT ?"
        ))
        .bind(chat)
        .bind(chat)
        .bind(ts)
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows.iter().map(message_from).collect())
    }

    pub async fn search(&self, query: &str, limit: i64) -> Result<Vec<MessageRow>> {
        let escaped = query
            .replace('\\', "\\\\")
            .replace('%', "\\%")
            .replace('_', "\\_");
        let rows = sqlx::query(concat!(
            "SELECT ",
            columns!(),
            " FROM messages WHERE deleted = 0 AND text LIKE ? ESCAPE '\\'
             ORDER BY ts DESC LIMIT ?"
        ))
        .bind(format!("%{escaped}%"))
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows.iter().map(message_from).collect())
    }

    pub async fn reactions(&self, chat: &str, ids: &[String]) -> Result<Vec<ReactionRow>> {
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        let placeholders = vec!["?"; ids.len()].join(",");
        let sql = format!(
            "SELECT msg, sender, emoji FROM reactions WHERE chat = ? AND msg IN ({placeholders})"
        );
        let mut query = sqlx::query(AssertSqlSafe(sql)).bind(chat);
        for id in ids {
            query = query.bind(id);
        }
        Ok(query
            .fetch_all(&self.pool)
            .await?
            .iter()
            .map(|row| ReactionRow {
                msg: row.get("msg"),
                sender: row.get("sender"),
                emoji: row.get("emoji"),
            })
            .collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn message(id: &str, ts: i64) -> MessageRow {
        MessageRow {
            chat: "c:abc".into(),
            id: id.into(),
            sender: "me".into(),
            from_me: true,
            ts,
            kind: "text".into(),
            text: id.into(),
            status: "sent".into(),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn stores_pages_and_tracks_status() {
        let dir = std::env::temp_dir().join(format!("sigbridge-test-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let _ = std::fs::remove_file(&path);
        let db = Db::open(&path).await.unwrap();
        for (i, id) in ["1:me", "2:me", "3:me"].iter().enumerate() {
            db.put_message(&message(id, 100 + i as i64)).await.unwrap();
        }

        // Status only moves forward, wherever the message is.
        assert_eq!(
            db.advance_status("2:me", "read").await.unwrap().as_deref(),
            Some("c:abc")
        );
        assert_eq!(db.advance_status("2:me", "delivered").await.unwrap(), None);
        db.put_message(&MessageRow {
            status: String::new(),
            ..message("2:me", 101)
        })
        .await
        .unwrap();
        assert_eq!(
            db.message("c:abc", "2:me").await.unwrap().unwrap().status,
            "read"
        );

        db.set_reaction("c:abc", "1:me", "x", "❤️").await.unwrap();
        db.set_reaction("c:abc", "1:me", "x", "👍").await.unwrap();
        db.edit_message("c:abc", "3:me", "edited", "").await.unwrap();
        db.delete_message("c:abc", "1:me").await.unwrap();

        let newest = db.messages("c:abc", 0, "", 2).await.unwrap();
        assert_eq!(
            newest.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            ["2:me", "3:me"]
        );
        assert!(newest[1].edited && newest[1].text == "edited");
        let older = db
            .messages("c:abc", newest[0].ts, &newest[0].id, 10)
            .await
            .unwrap();
        assert_eq!(older.len(), 1);
        assert!(older[0].deleted);
        let reactions = db.reactions("c:abc", &["1:me".to_string()]).await.unwrap();
        assert_eq!(reactions.len(), 1);
        assert_eq!(reactions[0].emoji, "👍");

        // A chat we wrote in isn't a request; a new sender's is, until accepted.
        assert!(!db.chat("c:abc").await.unwrap().unwrap().request);
        db.put_message(&MessageRow { chat: "c:new".into(), from_me: false, ..message("9:them", 200) }).await.unwrap();
        assert!(db.chat("c:new").await.unwrap().unwrap().request);
        db.set_request_state("c:new", REQUEST_ACCEPTED).await.unwrap();
        assert!(!db.chat("c:new").await.unwrap().unwrap().request);
        db.delete_chat("c:new").await.unwrap();
        assert!(db.messages("c:new", 0, "", 10).await.unwrap().is_empty());

        // Disappearing messages: ours count down from sending, incoming from reading.
        assert!(db.set_chat_timer("c:abc", 60, 1, false).await.unwrap());
        assert!(!db.set_chat_timer("c:abc", 0, 0, false).await.unwrap());
        // The same version again (say, a message without the timer field) changes nothing.
        assert!(!db.set_chat_timer("c:abc", 0, 1, false).await.unwrap());
        db.put_message(&message("400:me", 1_000)).await.unwrap();
        db.put_message(&MessageRow { from_me: false, ..message("401:them", 1_000) }).await.unwrap();
        assert_eq!(db.purge_expired(61_000).await.unwrap(), ["c:abc"]);
        assert!(db.message("c:abc", "400:me").await.unwrap().is_none());
        assert!(db.message("c:abc", "401:them").await.unwrap().is_some());
        db.start_expiries("c:abc", i64::MAX, 70_000).await.unwrap();
        assert!(db.purge_expired(129_000).await.unwrap().is_empty());
        db.purge_expired(130_000).await.unwrap();
        assert!(db.message("c:abc", "401:them").await.unwrap().is_none());
        assert!(db.first_time("x").await.unwrap() && !db.first_time("x").await.unwrap());

        db.set_flag("c:abc", "archived", 1).await.unwrap();
        let (active, archived) = db.chats(false).await.unwrap();
        assert!(active.is_empty());
        assert_eq!(archived, 1);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[tokio::test]
    async fn outbox_sends_once_and_receipts_win() {
        let dir = std::env::temp_dir().join(format!("sigbridge-outbox-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let _ = std::fs::remove_file(&path);
        let status = |db: &Db, id: &'static str| {
            let db = db.clone();
            async move { db.message("c:abc", id).await.unwrap().unwrap().status }
        };
        {
            let db = Db::open(&path).await.unwrap();
            for id in ["1:me", "2:me", "3:me"] {
                db.put_message(&MessageRow { status: "sending".into(), ..message(id, 1) }).await.unwrap();
            }
            db.put_outbox("c:abc", "1:me", b"body", "", false).await.unwrap();
            // A receipt can beat the send call back; settling doesn't undo it.
            db.advance_status("2:me", "delivered").await.unwrap();
            db.settle("c:abc", "2:me", "failed").await.unwrap();
            assert_eq!(status(&db, "2:me").await, "delivered");
            // A send we thought failed is moved on when a receipt says it arrived.
            db.settle("c:abc", "3:me", "failed").await.unwrap();
            db.advance_status("3:me", "read").await.unwrap();
            assert_eq!(status(&db, "3:me").await, "read");
        }
        // Reopening (the app was killed mid-send) fails what was still sending,
        // and keeps what's needed to retry it.
        let db = Db::open(&path).await.unwrap();
        assert_eq!(status(&db, "1:me").await, "failed");
        assert_eq!(db.outbox("c:abc", "1:me").await.unwrap(), Some((b"body".to_vec(), String::new(), false)));
        db.settle("c:abc", "1:me", "sending").await.unwrap();
        db.settle("c:abc", "1:me", "sent").await.unwrap();
        db.delete_outbox("c:abc", "1:me").await.unwrap();
        assert_eq!(status(&db, "1:me").await, "sent");
        assert_eq!(db.outbox("c:abc", "1:me").await.unwrap(), None);
        std::fs::remove_dir_all(&dir).ok();
    }

    #[tokio::test]
    async fn receipts_keep_each_recipients_first_times() {
        let dir = std::env::temp_dir().join(format!("sigbridge-receipts-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let _ = std::fs::remove_file(&path);
        let db = Db::open(&path).await.unwrap();
        db.set_receipt("g:1", "5:me", "ann", "delivered", 100).await.unwrap();
        db.set_receipt("g:1", "5:me", "ann", "read", 200).await.unwrap();
        db.set_receipt("g:1", "5:me", "ann", "read", 300).await.unwrap();
        db.set_receipt("g:1", "5:me", "bob", "delivered", 150).await.unwrap();
        assert_eq!(
            db.receipts("g:1", "5:me").await.unwrap(),
            vec![("ann".to_string(), 100, 200, 0), ("bob".to_string(), 150, 0, 0)],
        );
        std::fs::remove_dir_all(&dir).ok();
    }

    #[tokio::test]
    async fn reading_up_to_a_message_reads_only_that_far() {
        let dir = std::env::temp_dir().join(format!("sigbridge-read-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("t.db");
        let _ = std::fs::remove_file(&path);
        let db = Db::open(&path).await.unwrap();
        for (i, id) in ["1:ann", "2:ann", "3:ann", "4:ann"].iter().enumerate() {
            db.put_message(&MessageRow { from_me: false, sender: "ann".into(), ..message(id, (i as i64 + 1) * 100) }).await.unwrap();
        }
        db.set_flag("c:abc", "unread", 4).await.unwrap();
        let ids = |rows: Vec<MessageRow>| rows.into_iter().map(|r| r.id).collect::<Vec<_>>();
        assert_eq!(ids(db.unread_through("c:abc", 200, 100).await.unwrap()), ["2:ann", "1:ann"]);
        db.read_through("c:abc", 200).await.unwrap();
        // Only what's new is read next time, and two are left unread.
        assert_eq!(ids(db.unread_through("c:abc", 400, 100).await.unwrap()), ["4:ann", "3:ann"]);
        let unread: i64 = sqlx::query_scalar("SELECT unread FROM chats WHERE id = 'c:abc'").fetch_one(&db.pool).await.unwrap();
        assert_eq!(unread, 2);
        std::fs::remove_dir_all(&dir).ok();
    }
}

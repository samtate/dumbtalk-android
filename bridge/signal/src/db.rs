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
}

#[derive(Clone, Debug)]
pub struct ChatRow {
    pub id: String,
    pub archived: bool,
    pub pinned: bool,
    pub muted: bool,
    pub unread: i64,
    pub last_ts: i64,
}

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
        "chat, id, sender, from_me, ts, kind, text, filename, mime, size, quote_id, quote_sender, quote_text, edited, deleted, status"
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
    }
}

fn chat_from(row: &sqlx::sqlite::SqliteRow) -> ChatRow {
    ChatRow {
        id: row.get("id"),
        archived: row.get::<i64, _>("archived") == 1,
        pinned: row.get::<i64, _>("pinned") == 1,
        muted: row.get::<i64, _>("muted") == 1,
        unread: row.get("unread"),
        last_ts: row.get("last_ts"),
    }
}

fn rank(status: &str) -> u8 {
    match status {
        "sent" => 2,
        "delivered" => 3,
        "read" => 4,
        "pending" => 1,
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
        let pool = SqlitePoolOptions::new().max_connections(1).connect_with(options).await?;
        sqlx::raw_sql(SCHEMA).execute(&pool).await?;
        Ok(Db { pool })
    }

    pub async fn clear(&self) -> Result<()> {
        sqlx::raw_sql("DELETE FROM reactions; DELETE FROM messages; DELETE FROM chats; DELETE FROM names;")
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
            "INSERT INTO messages (chat, id, sender, from_me, ts, kind, text, filename, mime, size, quote_id, quote_sender, quote_text, edited, deleted, status)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             ON CONFLICT (chat, id) DO UPDATE SET
                kind = excluded.kind, text = excluded.text, filename = excluded.filename, mime = excluded.mime,
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
        .execute(&self.pool)
        .await?;
        self.ensure_chat(&m.chat, m.ts).await
    }

    pub async fn edit_message(&self, chat: &str, id: &str, text: &str) -> Result<()> {
        sqlx::query("UPDATE messages SET text = ?, edited = 1 WHERE chat = ? AND id = ?")
            .bind(text)
            .bind(chat)
            .bind(id)
            .execute(&self.pool)
            .await?;
        Ok(())
    }

    pub async fn delete_message(&self, chat: &str, id: &str) -> Result<()> {
        sqlx::query("UPDATE messages SET text = '', deleted = 1 WHERE chat = ? AND id = ?")
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

    pub async fn set_reaction(&self, chat: &str, msg: &str, sender: &str, emoji: &str) -> Result<()> {
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
        assert!(matches!(column, "archived" | "pinned" | "muted" | "unread"), "unknown column {column}");
        self.ensure_chat(chat, 0).await?;
        sqlx::query(AssertSqlSafe(format!("UPDATE chats SET {column} = ? WHERE id = ?")))
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
            "SELECT * FROM chats WHERE last_ts > 0 AND archived = ? ORDER BY pinned DESC, last_ts DESC LIMIT 500",
        )
        .bind(archived as i64)
        .fetch_all(&self.pool)
        .await?;
        let count: i64 = sqlx::query("SELECT COUNT(*) AS n FROM chats WHERE last_ts > 0 AND archived = 1")
            .fetch_one(&self.pool)
            .await?
            .get("n");
        Ok((rows.iter().map(chat_from).collect(), count))
    }

    pub async fn chat(&self, chat: &str) -> Result<Option<ChatRow>> {
        Ok(sqlx::query("SELECT * FROM chats WHERE id = ?")
            .bind(chat)
            .fetch_optional(&self.pool)
            .await?
            .as_ref()
            .map(chat_from))
    }

    pub async fn last_message(&self, chat: &str) -> Result<Option<MessageRow>> {
        Ok(sqlx::query(concat!("SELECT ", columns!(), " FROM messages WHERE chat = ? ORDER BY ts DESC, id DESC LIMIT 1"))
        .bind(chat)
        .fetch_optional(&self.pool)
        .await?
        .as_ref()
        .map(message_from))
    }

    pub async fn message(&self, chat: &str, id: &str) -> Result<Option<MessageRow>> {
        Ok(sqlx::query(concat!("SELECT ", columns!(), " FROM messages WHERE chat = ? AND id = ?"))
            .bind(chat)
            .bind(id)
            .fetch_optional(&self.pool)
            .await?
            .as_ref()
            .map(message_from))
    }

    /// Up to `limit` messages older than (before_ts, before_id), oldest first.
    pub async fn messages(&self, chat: &str, before_ts: i64, before_id: &str, limit: i64) -> Result<Vec<MessageRow>> {
        let rows = if before_ts > 0 {
            sqlx::query(concat!("SELECT ", columns!(), " FROM messages WHERE chat = ? AND (ts < ? OR (ts = ? AND id < ?))
                 ORDER BY ts DESC, id DESC LIMIT ?"))
            .bind(chat)
            .bind(before_ts)
            .bind(before_ts)
            .bind(before_id)
            .bind(limit)
            .fetch_all(&self.pool)
            .await?
        } else {
            sqlx::query(concat!("SELECT ", columns!(), " FROM messages WHERE chat = ? ORDER BY ts DESC, id DESC LIMIT ?"))
            .bind(chat)
            .bind(limit)
            .fetch_all(&self.pool)
            .await?
        };
        let mut list: Vec<_> = rows.iter().map(message_from).collect();
        list.reverse();
        Ok(list)
    }

    pub async fn recent_incoming(&self, chat: &str, limit: i64) -> Result<Vec<MessageRow>> {
        let rows = sqlx::query(concat!("SELECT ", columns!(), " FROM messages WHERE chat = ? AND from_me = 0 AND kind != 'system'
             ORDER BY ts DESC LIMIT ?"))
        .bind(chat)
        .bind(limit)
        .fetch_all(&self.pool)
        .await?;
        Ok(rows.iter().map(message_from).collect())
    }

    pub async fn search(&self, query: &str, limit: i64) -> Result<Vec<MessageRow>> {
        let escaped = query.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_");
        let rows = sqlx::query(concat!("SELECT ", columns!(), " FROM messages WHERE deleted = 0 AND text LIKE ? ESCAPE '\\'
             ORDER BY ts DESC LIMIT ?"))
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
        let sql = format!("SELECT msg, sender, emoji FROM reactions WHERE chat = ? AND msg IN ({placeholders})");
        let mut query = sqlx::query(AssertSqlSafe(sql)).bind(chat);
        for id in ids {
            query = query.bind(id);
        }
        Ok(query
            .fetch_all(&self.pool)
            .await?
            .iter()
            .map(|row| ReactionRow { msg: row.get("msg"), sender: row.get("sender"), emoji: row.get("emoji") })
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
        assert_eq!(db.advance_status("2:me", "read").await.unwrap().as_deref(), Some("c:abc"));
        assert_eq!(db.advance_status("2:me", "delivered").await.unwrap(), None);
        db.put_message(&MessageRow { status: String::new(), ..message("2:me", 101) }).await.unwrap();
        assert_eq!(db.message("c:abc", "2:me").await.unwrap().unwrap().status, "read");

        db.set_reaction("c:abc", "1:me", "x", "❤️").await.unwrap();
        db.set_reaction("c:abc", "1:me", "x", "👍").await.unwrap();
        db.edit_message("c:abc", "3:me", "edited").await.unwrap();
        db.delete_message("c:abc", "1:me").await.unwrap();

        let newest = db.messages("c:abc", 0, "", 2).await.unwrap();
        assert_eq!(newest.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(), ["2:me", "3:me"]);
        assert!(newest[1].edited && newest[1].text == "edited");
        let older = db.messages("c:abc", newest[0].ts, &newest[0].id, 10).await.unwrap();
        assert_eq!(older.len(), 1);
        assert!(older[0].deleted);
        let reactions = db.reactions("c:abc", &["1:me".to_string()]).await.unwrap();
        assert_eq!(reactions.len(), 1);
        assert_eq!(reactions[0].emoji, "👍");

        db.set_flag("c:abc", "archived", 1).await.unwrap();
        let (active, archived) = db.chats(false).await.unwrap();
        assert!(active.is_empty());
        assert_eq!(archived, 1);
        std::fs::remove_dir_all(&dir).ok();
    }
}

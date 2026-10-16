//! Display names and the JSON queries the Kotlin side reads.

use super::*;

impl Engine {
    /// Contact name from the main phone, else profile name, else number.
    pub(super) async fn contact_name(self: &Rc<Self>, id: &ServiceId) -> String {
        if Some(id.raw_uuid().to_string()) == self.own_aci() {
            return "You".into();
        }
        let contact = self.store.contact_by_id(id).await.ok().flatten();
        if let Some(contact) = &contact {
            if !contact.name.trim().is_empty() {
                return contact.name.clone();
            }
        }
        let uuid = id.raw_uuid();
        if let Ok(Some(key)) = self.store.profile_key(id).await {
            if let Ok(Some(profile)) = self.store.profile(uuid, key).await {
                if let Some(name) = profile.name {
                    let name = name.to_string();
                    if !name.trim().is_empty() {
                        return name;
                    }
                }
            } else {
                self.fetch_profile(uuid, key);
            }
        }
        contact
            .and_then(|c| c.phone_number.map(|p| p.to_string()))
            .unwrap_or_else(|| "Signal user".into())
    }

    /// Fetches a profile in the background, once per session.
    pub(super) fn fetch_profile(self: &Rc<Self>, uuid: Uuid, key: presage::libsignal_service::prelude::ProfileKey) {
        if !self.profiles_requested.borrow_mut().insert(uuid) {
            return;
        }
        let engine = self.clone();
        spawn_local(async move {
            let Ok(mut manager) = engine.manager() else { return };
            if manager.retrieve_profile_by_uuid(uuid, key).await.is_ok() {
                engine.emit("chats", json!({}));
            }
        });
    }

    pub(super) async fn name_for(self: &Rc<Self>, author: &str) -> String {
        if let Ok(Some(name)) = self.db.name(&format!("c:{author}")).await {
            return name;
        }
        match Uuid::parse_str(author) {
            Ok(uuid) => self.contact_name(&ServiceId::Aci(uuid.into())).await,
            Err(_) => "Signal user".into(),
        }
    }

    pub(super) async fn chat_title(self: &Rc<Self>, chat: &str) -> String {
        if let Ok(Some(name)) = self.db.name(chat).await {
            if chat.starts_with("c:") {
                return name;
            }
        }
        match thread_of(chat) {
            Ok(Thread::Contact(id)) => self.contact_name(&id).await,
            Ok(Thread::Group(key)) => match self.store.group(key).await {
                Ok(Some(group)) if !group.title.is_empty() => group.title,
                _ => "Signal group".into(),
            },
            Err(_) => "Signal chat".into(),
        }
    }

    pub(super) async fn message_json(self: &Rc<Self>, row: &MessageRow, reactions: &[crate::db::ReactionRow]) -> Value {
        let me = self.own_aci().unwrap_or_default();
        let mut reacted = Vec::new();
        for reaction in reactions.iter().filter(|r| r.msg == row.id) {
            reacted.push(json!({
                "emoji": reaction.emoji,
                "name": self.name_for(&reaction.sender).await,
                "fromMe": reaction.sender == me,
            }));
        }
        let quote_sender = if row.quote_sender.is_empty() { String::new() } else { self.name_for(&row.quote_sender).await };
        let mut value = serde_json::to_value(row).unwrap_or(Value::Null);
        value["senderName"] = json!(if row.from_me { "You".to_string() } else { self.name_for(&row.sender).await });
        value["quoteSender"] = json!(quote_sender);
        value["reactions"] = Value::Array(reacted);
        value
    }

    pub(super) async fn chat_json_for(self: &Rc<Self>, row: crate::db::ChatRow) -> Value {
        let last = match self.db.last_message(&row.id).await {
            Ok(Some(message)) => self.message_json(&message, &[]).await,
            _ => Value::Null,
        };
        let me = self.own_aci().map(|aci| format!("c:{aci}"));
        let note_to_self = Some(&row.id) == me.as_ref();
        json!({
            "jid": row.id,
            "name": if note_to_self { "Note to Self".to_string() } else { self.chat_title(&row.id).await },
            "group": row.id.starts_with("g:"),
            "archived": row.archived,
            "pinned": row.pinned,
            "muted": row.muted,
            "unread": row.unread,
            "lastTs": row.last_ts,
            "last": last,
            "noteToSelf": note_to_self,
        })
    }

    pub(super) async fn chats(self: &Rc<Self>, archived: bool) -> Result<String, BridgeError> {
        let (rows, archived_count) = self.db.chats(archived).await.map_err(fail)?;
        let mut chats = Vec::with_capacity(rows.len());
        for row in rows {
            chats.push(self.chat_json_for(row).await);
        }
        Ok(json!({ "chats": chats, "archivedCount": archived_count }).to_string())
    }

    pub(super) async fn chat(self: &Rc<Self>, chat: &str) -> Result<Value, BridgeError> {
        thread_of(chat)?;
        let row = self.db.chat(chat).await.map_err(fail)?.unwrap_or(crate::db::ChatRow { id: chat.to_string(), ..Default::default() });
        Ok(self.chat_json_for(row).await)
    }

    pub(super) async fn messages(self: &Rc<Self>, chat: &str, before_ts: i64, before_id: &str, limit: i64) -> Result<String, BridgeError> {
        let rows = self.db.messages(chat, before_ts, before_id, limit).await.map_err(fail)?;
        let ids: Vec<String> = rows.iter().map(|r| r.id.clone()).collect();
        let reactions = self.db.reactions(chat, &ids).await.map_err(fail)?;
        let mut out = Vec::with_capacity(rows.len());
        for row in &rows {
            out.push(self.message_json(row, &reactions).await);
        }
        Ok(Value::Array(out).to_string())
    }

    pub(super) async fn search(self: &Rc<Self>, query: &str) -> Result<String, BridgeError> {
        let rows = self.db.search(query, 50).await.map_err(fail)?;
        let mut out = Vec::with_capacity(rows.len());
        for row in &rows {
            out.push(self.message_json(row, &[]).await);
        }
        Ok(Value::Array(out).to_string())
    }
}

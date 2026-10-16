//! Sending: messages, reactions, edits, deletes, receipts and typing.

use super::*;

impl Engine {
    pub(super) fn group_context(thread: &Thread) -> Option<GroupContextV2> {
        match thread {
            Thread::Group(key) => Some(GroupContextV2 { master_key: Some(key.to_vec()), revision: Some(0), ..Default::default() }),
            Thread::Contact(_) => None,
        }
    }

    pub(super) async fn deliver(&self, thread: &Thread, body: impl Into<ContentBody>, ts: u64) -> Result<(), BridgeError> {
        let mut manager = self.manager()?;
        match thread {
            Thread::Contact(id) => manager.send_message(*id, body, ts).await.map_err(fail),
            Thread::Group(key) => manager.send_message_to_group(key, body, ts).await.map_err(fail),
        }
    }

    pub(super) async fn send_text(self: &Rc<Self>, chat: &str, text: &str, quote_id: &str) -> Result<String, BridgeError> {
        let thread = thread_of(chat)?;
        let me = self.own_aci().ok_or_else(|| fail("Signal is not linked"))?;
        let ts = now_ms();
        let quoted = if quote_id.is_empty() { None } else { self.db.message(chat, quote_id).await.map_err(fail)? };
        let quote = quoted.as_ref().and_then(|q| {
            let (sent, author) = q.id.split_once(':')?;
            Some(Quote {
                id: sent.parse().ok(),
                author_aci: Some(author.to_string()),
                text: Some(q.text.clone()),
                ..Default::default()
            })
        });
        let message = DataMessage {
            body: Some(text.to_string()),
            timestamp: Some(ts),
            quote,
            group_v2: Self::group_context(&thread),
            profile_key: Some(self.manager()?.registration_data().profile_key().get_bytes().to_vec()),
            ..Default::default()
        };
        self.deliver(&thread, message, ts).await?;
        let row = MessageRow {
            chat: chat.to_string(),
            id: message_id(ts, &me),
            sender: me.clone(),
            from_me: true,
            ts: ts as i64,
            kind: "text".into(),
            text: text.to_string(),
            quote_id: quoted.as_ref().map(|q| q.id.clone()).unwrap_or_default(),
            quote_sender: quoted.as_ref().map(|q| q.sender.clone()).unwrap_or_default(),
            quote_text: quoted.as_ref().map(|q| q.text.clone()).unwrap_or_default(),
            status: "sent".into(),
            ..Default::default()
        };
        self.db.put_message(&row).await.map_err(fail)?;
        self.emit("chats", json!({}));
        Ok(self.message_json(&row, &[]).await.to_string())
    }

    pub(super) async fn react(self: &Rc<Self>, chat: &str, id: &str, emoji: &str, remove: bool) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let me = self.own_aci().ok_or_else(|| fail("Signal is not linked"))?;
        let (sent, author) = id.split_once(':').ok_or_else(|| fail("Unknown message"))?;
        let ts = now_ms();
        let message = DataMessage {
            reaction: Some(Reaction {
                emoji: Some(emoji.to_string()),
                remove: Some(remove),
                target_author_aci: Some(author.to_string()),
                target_sent_timestamp: sent.parse().ok(),
                ..Default::default()
            }),
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            ..Default::default()
        };
        self.deliver(&thread, message, ts).await?;
        self.db.set_reaction(chat, id, &me, if remove { "" } else { emoji }).await.map_err(fail)?;
        self.emit("messages", json!({ "chat": chat }));
        Ok(())
    }

    pub(super) async fn edit(self: &Rc<Self>, chat: &str, id: &str, text: &str) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let (sent, _) = id.split_once(':').ok_or_else(|| fail("Unknown message"))?;
        let ts = now_ms();
        let edit = EditMessage {
            target_sent_timestamp: sent.parse().ok(),
            data_message: Some(DataMessage {
                body: Some(text.to_string()),
                timestamp: Some(ts),
                group_v2: Self::group_context(&thread),
                ..Default::default()
            }),
        };
        self.deliver(&thread, ContentBody::EditMessage(edit), ts).await?;
        self.db.edit_message(chat, id, text).await.map_err(fail)?;
        self.emit("messages", json!({ "chat": chat }));
        Ok(())
    }

    pub(super) async fn delete(self: &Rc<Self>, chat: &str, id: &str) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let (sent, _) = id.split_once(':').ok_or_else(|| fail("Unknown message"))?;
        let ts = now_ms();
        let message = DataMessage {
            delete: Some(Delete { target_sent_timestamp: sent.parse().ok() }),
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            ..Default::default()
        };
        self.deliver(&thread, message, ts).await?;
        self.db.delete_message(chat, id).await.map_err(fail)?;
        self.emit("messages", json!({ "chat": chat }));
        Ok(())
    }

    /// Sends read receipts to each author of recent incoming messages.
    pub(super) async fn mark_read(self: &Rc<Self>, chat: &str) -> Result<(), BridgeError> {
        let rows = self.db.recent_incoming(chat, 20).await.map_err(fail)?;
        let mut by_author: HashMap<String, Vec<u64>> = HashMap::new();
        for row in rows {
            by_author.entry(row.sender).or_default().push(row.ts as u64);
        }
        let mut manager = self.manager()?;
        for (author, timestamps) in by_author {
            let Ok(uuid) = Uuid::parse_str(&author) else { continue };
            let receipt = ReceiptMessage { r#type: Some(receipt_message::Type::Read.into()), timestamp: timestamps };
            if let Err(error) = manager.send_message(ServiceId::Aci(uuid.into()), receipt, now_ms()).await {
                self.log(format!("read receipt failed: {error}"));
            }
        }
        self.db.set_flag(chat, "unread", 0).await.map_err(fail)?;
        self.emit("chats", json!({}));
        Ok(())
    }

    /// Typing notices for direct chats; groups would need a group send.
    pub(super) async fn set_typing(&self, chat: &str, active: bool) -> Result<(), BridgeError> {
        let Thread::Contact(id) = thread_of(chat)? else { return Ok(()) };
        let action = if active { typing_message::Action::Started } else { typing_message::Action::Stopped };
        let ts = now_ms();
        let typing = TypingMessage { timestamp: Some(ts), action: Some(action.into()), group_id: None };
        self.manager()?.send_message(id, typing, ts).await.map_err(fail)
    }

    pub(super) async fn resolve_phone(&self, phone: &str) -> Result<String, BridgeError> {
        let digits: String = phone.chars().filter(|c| c.is_ascii_digit()).collect();
        if digits.len() < 7 {
            return Err(fail("Enter a full phone number with its country code"));
        }
        let mut manager = self.manager()?;
        // Your own number is Note to Self (lookup would return the PNI).
        let own: String = manager.registration_data().phone_number.to_string().chars().filter(|c| c.is_ascii_digit()).collect();
        if own == digits {
            return Ok(format!("c:{}", manager.registration_data().service_ids.aci));
        }
        let number = presage::libsignal_service::prelude::phonenumber::parse(None, format!("+{digits}")).map_err(fail)?;
        let found = manager.discover_contacts_by_phone_number([number]).await.map_err(fail)?;
        found
            .into_iter()
            .find_map(|(_, id)| id)
            .map(|id| format!("c:{}", id.service_id_string()))
            .ok_or_else(|| fail("That number isn't on Signal"))
    }
}

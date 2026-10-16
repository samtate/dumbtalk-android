//! Turning received Signal content into stored messages, receipts and
//! typing notices.

use super::*;

impl Engine {
    pub(super) async fn on_received(self: &Rc<Self>, received: Received) {
        match received {
            Received::QueueEmpty => {
                self.online.set(true);
                self.emit("status", json!({}));
                self.emit("chats", json!({}));
            }
            Received::Contacts => {
                self.seed_chats_from_contacts().await;
                self.emit("chats", json!({}));
            }
            Received::DecryptionError(sender) => self.log(format!("could not decrypt a message from {}", sender.service_id_string())),
            Received::Content(content) => {
                if let Err(error) = self.ingest(&content).await {
                    self.log(format!("failed to store message: {}", error));
                }
            }
        }
    }

    pub(super) async fn ingest(self: &Rc<Self>, content: &Content) -> Result<(), BridgeError> {
        let Ok(thread) = Thread::try_from(content) else { return Ok(()) };
        let chat = chat_id(&thread);
        let sender = content.metadata.sender.raw_uuid().to_string();
        let me = self.own_aci().unwrap_or_default();
        let group = matches!(thread, Thread::Group(_));
        match &content.body {
            ContentBody::DataMessage(message) => {
                self.ingest_data(&chat, &sender, false, content.timestamp(), message, true).await?;
            }
            ContentBody::SynchronizeMessage(SyncMessage { content: sync, read, .. }) => {
                match sync {
                    Some(SyncContent::Sent(Sent { message: Some(message), timestamp, .. })) => {
                        let ts = timestamp.unwrap_or_else(|| content.timestamp());
                        self.ingest_data(&chat, &me, true, ts, message, true).await?;
                    }
                    Some(SyncContent::Sent(Sent { edit_message: Some(edit), .. })) => {
                        self.apply_edit(&chat, &me, edit).await?;
                    }
                    // The main phone changed storage (names, pins, archive…).
                    Some(SyncContent::FetchLatest(_)) => self.clone().sync_storage(),
                    _ => {}
                }
                // We read these on another device.
                for item in read {
                    let (Some(author), Some(ts)) = (item.sender_aci.as_ref(), item.timestamp) else { continue };
                    if let Some(chat) = self.db.chat_of_message(&message_id(ts, author)).await.map_err(fail)? {
                        self.db.set_flag(&chat, "unread", 0).await.map_err(fail)?;
                        self.emit("chats", json!({}));
                    }
                }
            }
            ContentBody::EditMessage(edit) => self.apply_edit(&chat, &sender, edit).await?,
            ContentBody::ReceiptMessage(ReceiptMessage { r#type, timestamp }) => {
                let status = match receipt_message::Type::try_from(r#type.unwrap_or_default()) {
                    Ok(receipt_message::Type::Delivery) => "delivered",
                    Ok(receipt_message::Type::Read) | Ok(receipt_message::Type::Viewed) => "read",
                    _ => return Ok(()),
                };
                let mut changed = HashSet::new();
                for ts in timestamp {
                    if let Some(chat) = self.db.advance_status(&message_id(*ts, &me), status).await.map_err(fail)? {
                        changed.insert(chat);
                    }
                }
                for chat in changed {
                    self.emit("messages", json!({ "chat": chat }));
                }
                self.emit("chats", json!({}));
            }
            ContentBody::TypingMessage(TypingMessage { action, .. }) => {
                let active = matches!(typing_message::Action::try_from(action.unwrap_or_default()), Ok(typing_message::Action::Started));
                let name = self.contact_name(&content.metadata.sender).await;
                self.emit("typing", json!({ "chat": chat, "sender": name, "active": active }));
            }
            ContentBody::CallMessage(_) if !group => {
                // Calls aren't supported; leave a note so they aren't missed.
                let row = MessageRow {
                    chat: chat.clone(),
                    id: message_id(content.timestamp(), &sender),
                    sender: sender.clone(),
                    ts: content.timestamp() as i64,
                    kind: "system".into(),
                    text: "📞 Signal call — answer on your main phone".into(),
                    ..Default::default()
                };
                self.db.put_message(&row).await.map_err(fail)?;
                self.emit("messages", json!({ "chat": chat }));
                self.emit("chats", json!({}));
            }
            _ => {}
        }
        Ok(())
    }

    pub(super) async fn apply_edit(&self, chat: &str, author: &str, edit: &EditMessage) -> Result<(), BridgeError> {
        let (Some(target), Some(message)) = (edit.target_sent_timestamp, edit.data_message.as_ref()) else { return Ok(()) };
        self.db.edit_message(chat, &message_id(target, author), message.body.as_deref().unwrap_or_default()).await.map_err(fail)?;
        self.emit("messages", json!({ "chat": chat }));
        self.emit("chats", json!({}));
        Ok(())
    }

    pub(super) async fn ingest_data(
        self: &Rc<Self>,
        chat: &str,
        author: &str,
        from_me: bool,
        ts: u64,
        message: &DataMessage,
        live: bool,
    ) -> Result<(), BridgeError> {
        if let Some(Reaction { emoji: Some(emoji), remove, target_author_aci: Some(target_author), target_sent_timestamp: Some(target), .. }) =
            &message.reaction
        {
            let emoji = if remove.unwrap_or(false) { "" } else { emoji.as_str() };
            self.db.set_reaction(chat, &message_id(*target, target_author), author, emoji).await.map_err(fail)?;
        } else if let Some(Delete { target_sent_timestamp: Some(target) }) = &message.delete {
            self.db.delete_message(chat, &message_id(*target, author)).await.map_err(fail)?;
        } else {
            let attachment = message.attachments.first();
            let kind = if let Some(attachment) = attachment {
                match attachment.content_type.as_deref().unwrap_or_default() {
                    t if t.starts_with("image/") => "image",
                    t if t.starts_with("video/") => "video",
                    t if t.starts_with("audio/") => "audio",
                    _ => "document",
                }
            } else if message.sticker.is_some() {
                "sticker"
            } else if message.body.as_deref().is_some_and(|b| !b.is_empty()) {
                "text"
            } else {
                // Profile key updates, timers, group changes, etc.
                return Ok(());
            };
            let quote = message.quote.as_ref();
            let row = MessageRow {
                chat: chat.to_string(),
                id: message_id(ts, author),
                sender: author.to_string(),
                from_me,
                ts: ts as i64,
                kind: kind.into(),
                text: message.body.clone().unwrap_or_default(),
                filename: attachment.and_then(|a| a.file_name.clone()).unwrap_or_default(),
                mime: attachment.and_then(|a| a.content_type.clone()).unwrap_or_default(),
                size: attachment.and_then(|a| a.size).unwrap_or(0) as i64,
                quote_id: quote
                    .and_then(|q| Some(message_id(q.id?, q.author_aci.as_deref()?)))
                    .unwrap_or_default(),
                quote_sender: quote.and_then(|q| q.author_aci.clone()).unwrap_or_default(),
                quote_text: quote.and_then(|q| q.text.clone()).unwrap_or_default(),
                status: if from_me { "sent".into() } else { String::new() },
                ..Default::default()
            };
            self.db.put_message(&row).await.map_err(fail)?;
            if let Some(pointer) = attachment {
                self.db.set_attachment(chat, &row.id, &pointer.encode_to_vec()).await.map_err(fail)?;
            }
            if live && !from_me {
                self.db.bump_unread(chat).await.map_err(fail)?;
                self.emit("incoming", json!({ "chat": chat, "id": row.id }));
            }
        }
        self.emit("messages", json!({ "chat": chat }));
        self.emit("chats", json!({}));
        Ok(())
    }
}

//! Turning received Signal content into stored messages, receipts and
//! typing notices.

use super::*;
use presage::proto::BodyRange;
use presage::proto::data_message::{self, PollTerminate};
use presage::proto::data_message::pin_message::PinDuration;

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
            Received::DecryptionError(sender) => self.log(format!(
                "could not decrypt a message from {}",
                sender.service_id_string()
            )),
            Received::Content(content) => {
                if let Err(error) = self.ingest(&content).await {
                    self.log(format!("failed to store message: {}", error));
                }
            }
        }
    }

    pub(super) async fn ingest(self: &Rc<Self>, content: &Content) -> Result<(), BridgeError> {
        let Ok(thread) = Thread::try_from(content) else {
            return Ok(());
        };
        let chat = chat_id(&thread);
        let sender = content.metadata.sender.raw_uuid().to_string();
        let me = self.own_aci().unwrap_or_default();
        let group = matches!(thread, Thread::Group(_));
        match &content.body {
            ContentBody::DataMessage(message) => {
                self.ingest_data(&chat, &sender, false, content.timestamp(), message, true)
                    .await?;
            }
            ContentBody::SynchronizeMessage(SyncMessage {
                content: sync,
                read,
                ..
            }) => {
                match sync {
                    Some(SyncContent::Sent(Sent {
                        message: Some(message),
                        timestamp,
                        ..
                    })) => {
                        let ts = timestamp.unwrap_or_else(|| content.timestamp());
                        self.ingest_data(&chat, &me, true, ts, message, true)
                            .await?;
                    }
                    Some(SyncContent::Sent(Sent {
                        edit_message: Some(edit),
                        ..
                    })) => {
                        self.apply_edit(&chat, &me, edit).await?;
                    }
                    // The main phone changed storage (names, pins, archive…).
                    Some(SyncContent::FetchLatest(_)) => self.clone().sync_storage(),
                    // Accepted, blocked or deleted on another device.
                    Some(SyncContent::MessageRequestResponse(response)) => {
                        if let Some(thread) = aci(response.thread_aci.as_ref(), response.thread_aci_binary.as_ref()) {
                            self.apply_request_response(&format!("c:{thread}"), response.r#type.unwrap_or_default()).await?;
                        }
                    }
                    Some(SyncContent::Blocked(blocked)) => {
                        let mut listed: HashSet<String> = blocked.acis.iter().cloned().collect();
                        listed.extend(blocked.acis_binary.iter().filter_map(|b| aci(None, Some(b))));
                        self.db.set_blocked_list(&listed.iter().map(|a| format!("c:{a}")).collect::<Vec<_>>()).await.map_err(fail)?;
                        self.emit("chats", json!({}));
                    }
                    _ => {}
                }
                // We read these on another device.
                for item in read {
                    let (Some(author), Some(ts)) =
                        (aci(item.sender_aci.as_ref(), item.sender_aci_binary.as_ref()), item.timestamp)
                    else {
                        continue;
                    };
                    if let Some(chat) = self
                        .db
                        .chat_of_message(&message_id(ts, &author))
                        .await
                        .map_err(fail)?
                    {
                        self.db.set_flag(&chat, "unread", 0).await.map_err(fail)?;
                        self.db.start_expiries(&chat, now_ms() as i64).await.map_err(fail)?;
                        self.emit("chats", json!({}));
                    }
                }
            }
            ContentBody::EditMessage(edit) => self.apply_edit(&chat, &sender, edit).await?,
            ContentBody::ReceiptMessage(ReceiptMessage { r#type, timestamp }) => {
                // The message's status, and the kind of receipt kept per recipient.
                let (status, kind) = match receipt_message::Type::try_from(r#type.unwrap_or_default()) {
                    Ok(receipt_message::Type::Delivery) => ("delivered", "delivered"),
                    Ok(receipt_message::Type::Read) => ("read", "read"),
                    Ok(receipt_message::Type::Viewed) => ("read", "played"),
                    _ => return Ok(()),
                };
                let mut changed = HashSet::new();
                for ts in timestamp {
                    let id = message_id(*ts, &me);
                    if let Some(chat) = self.db.advance_status(&id, status).await.map_err(fail)? {
                        changed.insert(chat);
                    }
                    // Who sent this receipt and when: in a group, each member does.
                    if let Some(chat) = self.db.chat_of_message(&id).await.map_err(fail)? {
                        self.db.set_receipt(&chat, &id, &sender, kind, content.timestamp() as i64).await.map_err(fail)?;
                        changed.insert(chat);
                    }
                }
                for chat in changed {
                    self.messages_changed(&chat);
                }
                self.emit("chats", json!({}));
            }
            ContentBody::TypingMessage(TypingMessage { action, .. }) => {
                let active = matches!(
                    typing_message::Action::try_from(action.unwrap_or_default()),
                    Ok(typing_message::Action::Started)
                );
                let name = self.contact_name(&content.metadata.sender).await;
                self.emit(
                    "typing",
                    json!({ "chat": chat, "sender": name, "active": active }),
                );
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
                self.messages_changed(&chat);
                self.emit("chats", json!({}));
            }
            _ => {}
        }
        Ok(())
    }

    pub(super) async fn apply_edit(
        self: &Rc<Self>,
        chat: &str,
        author: &str,
        edit: &EditMessage,
    ) -> Result<(), BridgeError> {
        let (Some(target), Some(message)) =
            (edit.target_sent_timestamp, edit.data_message.as_ref())
        else {
            return Ok(());
        };
        let (body, ranges) = self.body_text(message).await;
        self.db
            .edit_message(chat, &message_id(target, author), &body, &ranges)
            .await
            .map_err(fail)?;
        self.messages_changed(chat);
        self.emit("chats", json!({}));
        Ok(())
    }

    /// Reapplies what older builds dropped from the history presage keeps:
    /// reactions that named their target by binary ACI, and mention
    /// placeholders stored as raw U+FFFC.
    pub(super) async fn repair_from_store(self: &Rc<Self>) {
        // Bodies once, for builds that stored them without their spans.
        let redo_bodies = self.db.first_time("bodies-v2").await.unwrap_or(false);
        let redo_reactions = self.db.first_time("reactions-v2").await.unwrap_or(false);
        if !redo_bodies && !redo_reactions {
            return;
        }
        let Ok(chats) = self.db.chat_ids().await else { return };
        let me = self.own_aci().unwrap_or_default();
        for chat in chats {
            let Ok(thread) = thread_of(&chat) else { continue };
            let Ok(contents) = self.store.messages(&thread, ..).await else { continue };
            for content in contents.flatten() {
                let (author, ts, message) = match &content.body {
                    ContentBody::DataMessage(m) => (content.metadata.sender.raw_uuid().to_string(), content.timestamp(), m),
                    ContentBody::SynchronizeMessage(SyncMessage {
                        content: Some(SyncContent::Sent(Sent { message: Some(m), timestamp, .. })),
                        ..
                    }) => (me.clone(), timestamp.unwrap_or_else(|| content.timestamp()), m),
                    _ => continue,
                };
                if let Some(reaction) = &message.reaction {
                    if redo_reactions && reaction.target_author_aci.is_none() && reaction.target_author_aci_binary.is_some() {
                        let _ = self.ingest_data(&chat, &author, author == me, ts, message, false).await;
                    }
                } else if !message.body_ranges.is_empty() && redo_bodies {
                    let (text, ranges) = self.body_text(message).await;
                    let _ = self.db.repair_text(&chat, &message_id(ts, &author), &text, &ranges).await;
                }
            }
        }
        self.emit("chats", json!({}));
    }

    /// A message body as shown, with mentions written out as "@Name", and
    /// its spans as stored JSON (empty when it has none).
    pub(super) async fn body_text(self: &Rc<Self>, message: &DataMessage) -> (String, String) {
        self.render(message.body.as_deref().unwrap_or_default(), &message.body_ranges).await
    }

    pub(super) async fn render(self: &Rc<Self>, body: &str, ranges: &[BodyRange]) -> (String, String) {
        let mut names = HashMap::new();
        for range in ranges {
            if let Some(who) = mentioned(range) {
                if !names.contains_key(&who) {
                    let name = self.name_for(&who).await;
                    names.insert(who, name);
                }
            }
        }
        let (text, spans) = render_body(body, ranges, |who| names.get(who).cloned().unwrap_or_else(|| "Signal user".into()));
        let spans = if spans.is_empty() { String::new() } else { serde_json::to_string(&spans).unwrap_or_default() };
        (text, spans)
    }

    /// Follows the disappearing-message timer a message carries, noting a
    /// change in the chat. A timer update (`explicit`) always counts; other
    /// messages only when they carry a timer field.
    async fn take_timer(self: &Rc<Self>, chat: &str, author: &str, from_me: bool, ts: u64, message: &DataMessage, explicit: bool) -> Result<(), BridgeError> {
        if !explicit && message.expire_timer.is_none() && message.expire_timer_version.is_none() {
            return Ok(());
        }
        let seconds = message.expire_timer.unwrap_or(0);
        let group = chat.starts_with("g:");
        // In groups the timer is group state; members' messages carry it.
        let changed = self.db.set_chat_timer(chat, seconds, message.expire_timer_version.unwrap_or(0), group).await.map_err(fail)?;
        if changed {
            let who = if from_me { "You".to_string() } else { self.name_for(author).await };
            self.note_timer(chat, &who, seconds, ts).await?;
        }
        Ok(())
    }

    /// A line in the chat saying who set the timer to what.
    pub(super) async fn note_timer(&self, chat: &str, who: &str, seconds: u32, ts: u64) -> Result<(), BridgeError> {
        let text = if seconds == 0 {
            format!("{who} turned off disappearing messages")
        } else {
            format!("{who} set disappearing messages to {}", timer_label(seconds))
        };
        let row = MessageRow {
            chat: chat.to_string(),
            id: format!("timer:{ts}"),
            sender: String::new(),
            ts: ts as i64,
            kind: "system".into(),
            text,
            ..Default::default()
        };
        self.db.put_message(&row).await.map_err(fail)
    }

    /// Accepts, blocks or deletes a direct chat as another device decided.
    pub(super) async fn apply_request_response(&self, chat: &str, kind: i32) -> Result<(), BridgeError> {
        use presage::proto::sync_message::message_request_response::Type;
        match Type::try_from(kind).unwrap_or(Type::Unknown) {
            Type::Accept => self.db.set_request_state(chat, crate::db::REQUEST_ACCEPTED).await.map_err(fail)?,
            Type::Delete => self.db.delete_chat(chat).await.map_err(fail)?,
            Type::Block | Type::BlockAndSpam => self.db.set_request_state(chat, crate::db::REQUEST_BLOCKED).await.map_err(fail)?,
            Type::BlockAndDelete => {
                self.db.set_request_state(chat, crate::db::REQUEST_BLOCKED).await.map_err(fail)?;
                self.db.delete_chat(chat).await.map_err(fail)?;
            }
            Type::Spam => self.db.delete_chat(chat).await.map_err(fail)?,
            Type::Unknown => return Ok(()),
        }
        self.messages_changed(chat);
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
        // Nothing from a blocked contact reaches the chat.
        if !from_me && chat.starts_with("c:") && self.db.request_state(chat).await.map_err(fail)? == crate::db::REQUEST_BLOCKED {
            return Ok(());
        }
        if message.flags.unwrap_or(0) & data_message::Flags::ExpirationTimerUpdate as u32 != 0 {
            self.take_timer(chat, author, from_me, ts, message, true).await?;
            self.messages_changed(chat);
            self.emit("chats", json!({}));
            return Ok(());
        }
        if let Some(pin) = &message.pin_message {
            if let (Some(timestamp), Some(bytes)) = (pin.target_sent_timestamp, pin.target_author_aci_binary.as_ref()) {
                if let Ok(author) = Uuid::from_slice(bytes) {
                    // Signal pins for a number of seconds from the pin, or forever.
                    let until = match pin.pin_duration {
                        Some(PinDuration::PinDurationSeconds(seconds)) => ts as i64 + seconds as i64 * 1000,
                        _ => 0,
                    };
                    self.db.set_message_pinned(chat, &message_id(timestamp, &author.to_string()), true, ts as i64, until).await.map_err(fail)?;
                }
            }
            self.messages_changed(chat);
            return Ok(());
        } else if let Some(unpin) = &message.unpin_message {
            if let (Some(timestamp), Some(bytes)) = (unpin.target_sent_timestamp, unpin.target_author_aci_binary.as_ref()) {
                if let Ok(author) = Uuid::from_slice(bytes) {
                    self.db.set_message_pinned(chat, &message_id(timestamp, &author.to_string()), false, ts as i64, 0).await.map_err(fail)?;
                }
            }
            self.messages_changed(chat);
            return Ok(());
        } else if let Some(vote) = &message.poll_vote {
            if let (Some(target), Some(bytes)) = (vote.target_sent_timestamp, vote.target_author_aci_binary.as_ref()) {
                if let Ok(poll_author) = Uuid::from_slice(bytes) {
                    let poll = message_id(target, &poll_author.to_string());
                    self.db.set_poll_vote(chat, &poll, author, &vote.option_indexes, vote.vote_count.unwrap_or(0) as i64).await.map_err(fail)?;
                }
            }
            self.messages_changed(chat);
            return Ok(());
        } else if let Some(PollTerminate { target_sent_timestamp: Some(target) }) = &message.poll_terminate {
            // Only a poll's author can end it, so the target shares this author.
            self.db.close_poll(chat, &message_id(*target, author)).await.map_err(fail)?;
        } else if let Some(Reaction {
            emoji: Some(emoji),
            remove,
            target_author_aci,
            target_sent_timestamp: Some(target),
            target_author_aci_binary,
        }) = &message.reaction
        {
            let Some(target_author) = aci(target_author_aci.as_ref(), target_author_aci_binary.as_ref()) else {
                return Ok(());
            };
            let emoji = if remove.unwrap_or(false) {
                ""
            } else {
                emoji.as_str()
            };
            self.db
                .set_reaction(chat, &message_id(*target, &target_author), author, emoji)
                .await
                .map_err(fail)?;
        } else if let Some(Delete {
            target_sent_timestamp: Some(target),
        }) = &message.delete
        {
            self.db
                .delete_message(chat, &message_id(*target, author))
                .await
                .map_err(fail)?;
        } else {
            let sticker = message.sticker.as_ref().and_then(|s| s.data.as_ref());
            let attachment = message.attachments.first().or(sticker);
            let kind = if message.poll_create.is_some() {
                "poll"
            } else if message.sticker.is_some() {
                "sticker"
            } else if let Some(attachment) = attachment {
                match attachment.content_type.as_deref().unwrap_or_default() {
                    t if t.starts_with("image/") => "image",
                    t if t.starts_with("video/") => "video",
                    t if t.starts_with("audio/") => "audio",
                    _ => "document",
                }
            } else if message.body.as_deref().is_some_and(|b| !b.is_empty()) {
                "text"
            } else {
                // A group change: presage has the new group state, timer included.
                if let (Some(context), Ok(Thread::Group(key))) = (&message.group_v2, thread_of(chat)) {
                    if context.group_change.is_some() {
                        if let Ok(Some(group)) = self.store.group(key).await {
                            let seconds = group.disappearing_messages_timer.map(|t| t.duration).unwrap_or(0);
                            if self.db.set_chat_timer(chat, seconds, 0, true).await.map_err(fail)? {
                                let who = if from_me { "You".to_string() } else { self.name_for(author).await };
                                self.note_timer(chat, &who, seconds, ts).await?;
                            }
                        }
                        self.messages_changed(chat);
                        self.emit("chats", json!({}));
                    }
                }
                // Profile key updates and the like.
                return Ok(());
            };
            self.take_timer(chat, author, from_me, ts, message, false).await?;
            let (text, ranges) = self.body_text(message).await;
            let quote = message.quote.as_ref();
            let quote_author = quote.and_then(|q| aci(q.author_aci.as_ref(), q.author_aci_binary.as_ref()));
            let row = MessageRow {
                chat: chat.to_string(),
                id: message_id(ts, author),
                sender: author.to_string(),
                from_me,
                ts: ts as i64,
                kind: kind.into(),
                text: text.clone(),
                ranges,
                filename: attachment
                    .and_then(|a| a.file_name.clone())
                    .unwrap_or_default(),
                mime: attachment
                    .and_then(|a| a.content_type.clone())
                    .unwrap_or_default(),
                size: attachment.and_then(|a| a.size).unwrap_or(0) as i64,
                quote_id: quote
                    .and_then(|q| Some(message_id(q.id?, quote_author.as_deref()?)))
                    .unwrap_or_default(),
                quote_sender: quote_author.clone().unwrap_or_default(),
                quote_text: match quote {
                    Some(q) => self.render(q.text.as_deref().unwrap_or_default(), &q.body_ranges).await.0,
                    None => String::new(),
                },
                status: if from_me {
                    "sent".into()
                } else {
                    String::new()
                },
                ..Default::default()
            };
            self.db.put_message(&row).await.map_err(fail)?;
            if !message.preview.is_empty() {
                let previews: Vec<Value> = message.preview.iter().map(|p| json!({
                    "url": p.url, "title": p.title, "description": p.description,
                })).collect();
                self.db.set_previews(chat, &row.id, &Value::Array(previews)).await.map_err(fail)?;
            }
            if let Some(poll) = &message.poll_create {
                self.db
                    .put_poll(chat, &row.id, poll.question.as_deref().unwrap_or_default(), &poll.options, poll.allow_multiple.unwrap_or(false))
                    .await
                    .map_err(fail)?;
            }
            if let Some(pointer) = attachment {
                let pointers: Vec<Vec<u8>> = if message.attachments.is_empty() {
                    vec![pointer.encode_to_vec()]
                } else {
                    message
                        .attachments
                        .iter()
                        .map(|a| a.encode_to_vec())
                        .collect()
                };
                self.db
                    .set_attachments(chat, &row.id, &pointers)
                    .await
                    .map_err(fail)?;
            }
            if live && !from_me {
                self.db.bump_unread(chat).await.map_err(fail)?;
                self.emit("incoming", json!({ "chat": chat, "id": row.id }));
            }
        }
        self.messages_changed(chat);
        self.emit("chats", json!({}));
        Ok(())
    }
}

/// "30 seconds", "8 hours", "1 week": how Signal names its timers.
pub(super) fn timer_label(seconds: u32) -> String {
    let (count, unit) = match seconds {
        s if s % 604_800 == 0 => (s / 604_800, "week"),
        s if s % 86_400 == 0 => (s / 86_400, "day"),
        s if s % 3_600 == 0 => (s / 3_600, "hour"),
        s if s % 60 == 0 => (s / 60, "minute"),
        s => (s, "second"),
    };
    format!("{count} {unit}{}", if count == 1 { "" } else { "s" })
}

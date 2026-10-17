//! Sending: messages, reactions, edits, deletes, receipts and typing.

use super::*;
use presage::proto::data_message::{self, PinMessage, PollCreate, PollTerminate, PollVote, ProtocolVersion, UnpinMessage, pin_message};

impl Engine {
    pub(super) async fn pin_message(self: &Rc<Self>, chat: &str, id: &str, pinned: bool) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let (sent, author) = split_id(id)?;
        let author = Uuid::parse_str(author).map_err(fail)?;
        let ts = now_ms();
        let data = if pinned {
            DataMessage { pin_message: Some(PinMessage {
                target_author_aci_binary: Some(author.as_bytes().to_vec()),
                target_sent_timestamp: sent,
                pin_duration: Some(pin_message::PinDuration::PinDurationForever(true)),
            }), timestamp: Some(ts), group_v2: Self::group_context(&thread), ..Default::default() }
        } else {
            DataMessage { unpin_message: Some(UnpinMessage {
                target_author_aci_binary: Some(author.as_bytes().to_vec()),
                target_sent_timestamp: sent,
            }), timestamp: Some(ts), group_v2: Self::group_context(&thread), ..Default::default() }
        };
        self.deliver(&thread, data, ts).await?;
        self.db.set_message_pinned(chat, id, pinned, ts as i64, 0).await.map_err(fail)?;
        self.messages_changed(chat);
        Ok(())
    }

    pub(super) async fn create_poll(self: &Rc<Self>, chat: &str, question: &str, options_json: &str, multiple: bool) -> Result<String, BridgeError> {
        let thread = thread_of(chat)?;
        let me = self.me()?;
        let options: Vec<String> = serde_json::from_str(options_json).map_err(fail)?;
        if options.len() < 2 {
            return Err(fail("A poll needs at least two options"));
        }
        let ts = now_ms();
        let data = DataMessage {
            poll_create: Some(PollCreate { question: Some(question.to_string()), allow_multiple: Some(multiple), options: options.clone() }),
            required_protocol_version: Some(ProtocolVersion::Polls as u32),
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            profile_key: Some(self.profile_key()?),
            ..Default::default()
        };
        self.deliver(&thread, data, ts).await?;
        let row = sent_row(chat, ts, &me, "poll", None);
        self.db.put_message(&row).await.map_err(fail)?;
        self.db.put_poll(chat, &row.id, question, &options, multiple).await.map_err(fail)?;
        self.emit("chats", json!({}));
        Ok(self.message_json(&row, &[]).await.to_string())
    }

    pub(super) async fn vote_poll(self: &Rc<Self>, chat: &str, id: &str, options_json: &str) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let me = self.me()?;
        let (sent, author) = split_id(id)?;
        let author = Uuid::parse_str(author).map_err(fail)?;
        let options: Vec<u32> = serde_json::from_str(options_json).map_err(fail)?;
        // Each new vote must count higher than our last for others to take it.
        let count = self.db.poll_votes(chat, id).await.map_err(fail)?
            .into_iter().find(|(voter, _, _)| *voter == me).map(|(_, _, count)| count).unwrap_or(0) + 1;
        let ts = now_ms();
        let data = DataMessage {
            poll_vote: Some(PollVote {
                target_author_aci_binary: Some(author.as_bytes().to_vec()),
                target_sent_timestamp: sent,
                option_indexes: options.clone(),
                vote_count: Some(count as u32),
            }),
            required_protocol_version: Some(ProtocolVersion::Polls as u32),
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            ..Default::default()
        };
        self.deliver(&thread, data, ts).await?;
        self.db.set_poll_vote(chat, id, &me, &options, count).await.map_err(fail)?;
        self.messages_changed(chat);
        Ok(())
    }

    pub(super) async fn close_poll(self: &Rc<Self>, chat: &str, id: &str) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let (sent, _) = split_id(id)?;
        let ts = now_ms();
        let data = DataMessage {
            poll_terminate: Some(PollTerminate { target_sent_timestamp: sent }),
            required_protocol_version: Some(ProtocolVersion::Polls as u32),
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            ..Default::default()
        };
        self.deliver(&thread, data, ts).await?;
        self.db.close_poll(chat, id).await.map_err(fail)?;
        self.messages_changed(chat);
        Ok(())
    }

    /// Our ACI; sending needs a linked account.
    pub(super) fn me(&self) -> Result<String, BridgeError> {
        self.own_aci().ok_or_else(|| fail("Signal is not linked"))
    }

    /// Our profile key, sent with new messages so people see our name and photo.
    pub(super) fn profile_key(&self) -> Result<Vec<u8>, BridgeError> {
        Ok(self.manager()?.registration_data().profile_key().get_bytes().to_vec())
    }

    /// The message being replied to (none for an empty id), and the quote of it to send.
    pub(super) async fn quoting(&self, chat: &str, quote_id: &str) -> Result<(Option<MessageRow>, Option<Quote>), BridgeError> {
        let quoted = if quote_id.is_empty() { None } else { self.db.message(chat, quote_id).await.map_err(fail)? };
        let quote = quoted.as_ref().and_then(|q| {
            let (sent, author) = split_id(&q.id).ok()?;
            Some(Quote { id: sent, author_aci: Some(author.to_string()), text: Some(q.text.clone()), ..Default::default() })
        });
        Ok((quoted, quote))
    }

    pub(super) fn group_context(thread: &Thread) -> Option<GroupContextV2> {
        match thread {
            Thread::Group(key) => Some(group_context(key)),
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

    /// `spans_json` lists mentions ({start, length, style: "mention", id}) and
    /// styles over `text`, in UTF-16 units; "" for none.
    pub(super) async fn send_text(self: &Rc<Self>, chat: &str, text: &str, quote_id: &str, spans_json: &str) -> Result<String, BridgeError> {
        let thread = thread_of(chat)?;
        let spans: Vec<Span> = if spans_json.is_empty() { Vec::new() } else { serde_json::from_str(spans_json).map_err(fail)? };
        let (body, body_ranges) = encode_body(text, &spans);
        let me = self.me()?;
        let ts = now_ms();
        let (quoted, quote) = self.quoting(chat, quote_id).await?;
        let message = DataMessage {
            body: Some(body),
            body_ranges,
            timestamp: Some(ts),
            quote,
            group_v2: Self::group_context(&thread),
            profile_key: Some(self.profile_key()?),
            ..Default::default()
        };
        self.deliver(&thread, message, ts).await?;
        let row = MessageRow {
            text: text.to_string(),
            ranges: if spans.is_empty() { String::new() } else { serde_json::to_string(&spans).unwrap_or_default() },
            ..sent_row(chat, ts, &me, "text", quoted.as_ref())
        };
        self.db.put_message(&row).await.map_err(fail)?;
        self.emit("chats", json!({}));
        Ok(self.message_json(&row, &[]).await.to_string())
    }

    pub(super) async fn react(self: &Rc<Self>, chat: &str, id: &str, emoji: &str, remove: bool) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let me = self.me()?;
        let (sent, author) = split_id(id)?;
        let ts = now_ms();
        let message = DataMessage {
            reaction: Some(Reaction {
                emoji: Some(emoji.to_string()),
                remove: Some(remove),
                target_author_aci: Some(author.to_string()),
                target_sent_timestamp: sent,
                target_author_aci_binary: Uuid::parse_str(author).ok().map(|u| u.as_bytes().to_vec()),
            }),
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            ..Default::default()
        };
        self.deliver(&thread, message, ts).await?;
        self.db.set_reaction(chat, id, &me, if remove { "" } else { emoji }).await.map_err(fail)?;
        self.messages_changed(chat);
        Ok(())
    }

    pub(super) async fn edit(self: &Rc<Self>, chat: &str, id: &str, text: &str) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let (sent, _) = split_id(id)?;
        let ts = now_ms();
        let edit = EditMessage {
            target_sent_timestamp: sent,
            data_message: Some(DataMessage {
                body: Some(text.to_string()),
                timestamp: Some(ts),
                group_v2: Self::group_context(&thread),
                ..Default::default()
            }),
        };
        self.deliver(&thread, ContentBody::EditMessage(edit), ts).await?;
        self.db.edit_message(chat, id, text, "").await.map_err(fail)?;
        self.messages_changed(chat);
        Ok(())
    }

    pub(super) async fn delete(self: &Rc<Self>, chat: &str, id: &str) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        let (sent, _) = split_id(id)?;
        let ts = now_ms();
        let message = DataMessage {
            delete: Some(Delete { target_sent_timestamp: sent }),
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            ..Default::default()
        };
        self.deliver(&thread, message, ts).await?;
        self.db.delete_message(chat, id).await.map_err(fail)?;
        self.messages_changed(chat);
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
        // Incoming disappearing messages count down from being read.
        self.db.start_expiries(chat, now_ms() as i64).await.map_err(fail)?;
        self.emit("chats", json!({}));
        Ok(())
    }

    /// Sets a direct chat's disappearing-message timer (0 turns it off).
    /// presage numbers the change and keeps its own copy of the timer.
    pub(super) async fn set_expire_timer(self: &Rc<Self>, chat: &str, seconds: u32) -> Result<(), BridgeError> {
        let thread = thread_of(chat)?;
        if let Thread::Group(key) = thread {
            return self.set_group_timer(chat, key, seconds).await;
        }
        let ts = now_ms();
        let message = DataMessage {
            flags: Some(data_message::Flags::ExpirationTimerUpdate as u32),
            expire_timer: Some(seconds),
            timestamp: Some(ts),
            ..Default::default()
        };
        self.deliver(&thread, message, ts).await?;
        let (_, version) = self.db.chat_timer(chat).await.map_err(fail)?;
        self.db.set_chat_timer(chat, seconds, version + 1, true).await.map_err(fail)?;
        self.note_timer(chat, "You", seconds, ts).await?;
        self.messages_changed(chat);
        self.emit("chats", json!({}));
        Ok(())
    }

    /// Answers a message request: "accept", "block", "unblock" or "delete".
    /// Our other devices hear it as a sync message; accepting also shares
    /// our profile (name and photo) with the sender.
    pub(super) async fn respond_request(self: &Rc<Self>, chat: &str, action: &str) -> Result<(), BridgeError> {
        use presage::proto::sync_message::{MessageRequestResponse, message_request_response::Type};
        let Thread::Contact(them) = thread_of(chat)? else {
            return Err(fail("Group invitations are answered on your main phone"));
        };
        let kind = match action {
            "accept" | "unblock" => Type::Accept,
            "block" => Type::Block,
            "delete" => Type::Delete,
            _ => return Err(fail("Unknown response")),
        };
        let me = self.me()?;
        let own = ServiceId::Aci(Uuid::parse_str(&me).map_err(fail)?.into());
        let ts = now_ms();
        let sync = SyncMessage {
            content: Some(SyncContent::MessageRequestResponse(MessageRequestResponse {
                thread_aci: Some(them.raw_uuid().to_string()),
                thread_aci_binary: Some(them.raw_uuid().as_bytes().to_vec()),
                group_id: None,
                r#type: Some(kind as i32),
            })),
            ..Default::default()
        };
        if let Err(error) = self.manager()?.send_message(own, sync, ts).await {
            self.log(format!("request sync failed: {error}"));
        }
        if kind == Type::Accept {
            let ts = now_ms();
            let share = DataMessage { flags: Some(data_message::Flags::ProfileKeyUpdate as u32), timestamp: Some(ts), ..Default::default() };
            self.deliver(&Thread::Contact(them), share, ts).await?;
        }
        self.apply_request_response(chat, kind as i32).await
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

/// The stored copy of a message we have just sent, replying to `quoted` if any.
pub(super) fn sent_row(chat: &str, ts: u64, me: &str, kind: &str, quoted: Option<&MessageRow>) -> MessageRow {
    MessageRow {
        chat: chat.to_string(),
        id: message_id(ts, me),
        sender: me.to_string(),
        from_me: true,
        ts: ts as i64,
        kind: kind.into(),
        quote_id: quoted.map(|q| q.id.clone()).unwrap_or_default(),
        quote_sender: quoted.map(|q| q.sender.clone()).unwrap_or_default(),
        quote_text: quoted.map(|q| q.text.clone()).unwrap_or_default(),
        status: "sent".into(),
        ..Default::default()
    }
}

/// A message id's parts: when it was sent (its Signal timestamp) and its author's ACI.
fn split_id(id: &str) -> Result<(Option<u64>, &str), BridgeError> {
    let (sent, author) = id.split_once(':').ok_or_else(|| fail("Unknown message"))?;
    Ok((sent.parse().ok(), author))
}

//! The engine thread: presage's manager, the receive loop and command handling.

use crate::db::{Db, MessageRow};
use crate::{BridgeError, Command, Listener, Reply};
use futures::{channel::oneshot as fo, pin_mut, StreamExt};
use presage::libsignal_service::configuration::SignalServers;
use presage::libsignal_service::content::{Content, ContentBody, DataMessage, GroupContextV2};
use presage::libsignal_service::prelude::Uuid;
use presage::libsignal_service::protocol::ServiceId;
use presage::manager::Registered;
use presage::model::identity::OnNewIdentity;
use presage::model::messages::Received;
use presage::proto::data_message::{Delete, Quote, Reaction};
use presage::proto::sync_message::{Content as SyncContent, Sent};
use presage::proto::{receipt_message, typing_message, EditMessage, ReceiptMessage, SyncMessage, TypingMessage};
use presage::store::{ContentExt, ContentsStore, StateStore, Thread};
use presage::Manager;
use presage_store_sqlite::SqliteStore;
use serde_json::{json, Value};
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::mpsc;
use tokio::task::{spawn_local, LocalSet};

type SignalManager = Manager<SqliteStore, Registered>;

pub(crate) fn run(
    dir: String,
    device_name: String,
    listener: Arc<dyn Listener>,
    mut commands: mpsc::UnboundedReceiver<Command>,
    ready: std::sync::mpsc::Sender<Result<(), BridgeError>>,
) {
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(error) => {
            let _ = ready.send(Err(BridgeError::new(error)));
            return;
        }
    };
    LocalSet::new().block_on(&runtime, async move {
        let engine = match Engine::open(PathBuf::from(dir), device_name, listener).await {
            Ok(engine) => Rc::new(engine),
            Err(error) => {
                let _ = ready.send(Err(error));
                return;
            }
        };
        let _ = ready.send(Ok(()));
        while let Some(command) = commands.recv().await {
            let engine = engine.clone();
            spawn_local(async move { engine.handle(command).await });
        }
    });
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

fn fail(error: impl std::fmt::Display) -> BridgeError {
    BridgeError::new(error)
}

/// Chat ids: "c:<service id>" for direct chats, "g:<hex master key>" for groups.
fn chat_id(thread: &Thread) -> String {
    match thread {
        Thread::Contact(service_id) => format!("c:{}", service_id.service_id_string()),
        Thread::Group(key) => format!("g:{}", hex::encode(key)),
    }
}

fn thread_of(chat: &str) -> Result<Thread, BridgeError> {
    if let Some(id) = chat.strip_prefix("c:") {
        return ServiceId::parse_from_service_id_string(id).map(Thread::Contact).ok_or_else(|| fail("Invalid Signal chat"));
    }
    if let Some(key) = chat.strip_prefix("g:") {
        let bytes = hex::decode(key).map_err(fail)?;
        return <[u8; 32]>::try_from(bytes.as_slice()).map(Thread::Group).map_err(|_| fail("Invalid Signal group"));
    }
    Err(fail("Invalid Signal chat"))
}

/// Signal identifies a message by its author and sent timestamp.
fn message_id(ts: u64, author: &str) -> String {
    format!("{ts}:{author}")
}

struct Engine {
    dir: PathBuf,
    device_name: String,
    listener: Arc<dyn Listener>,
    store: SqliteStore,
    db: Db,
    manager: RefCell<Option<SignalManager>>,
    online: Cell<bool>,
    receiving: Cell<bool>,
    linking: Cell<bool>,
    /// Profiles fetched (or being fetched) this session, so each is tried once.
    profiles_requested: RefCell<HashSet<Uuid>>,
}

impl Engine {
    async fn open(dir: PathBuf, device_name: String, listener: Arc<dyn Listener>) -> Result<Engine, BridgeError> {
        std::fs::create_dir_all(&dir).map_err(fail)?;
        let url = format!("sqlite://{}?mode=rwc", dir.join("signal.db3").display());
        let store = SqliteStore::open_with_passphrase(&url, None, OnNewIdentity::Trust).await.map_err(fail)?;
        let db = Db::open(&dir.join("dumbtalk.db")).await.map_err(fail)?;
        Ok(Engine {
            dir,
            device_name,
            listener,
            store,
            db,
            manager: RefCell::new(None),
            online: Cell::new(false),
            receiving: Cell::new(false),
            linking: Cell::new(false),
            profiles_requested: RefCell::new(HashSet::new()),
        })
    }

    fn emit(&self, kind: &str, payload: Value) {
        self.listener.on_event(kind.to_string(), payload.to_string());
    }

    fn log(&self, message: impl std::fmt::Display) {
        self.emit("log", json!({ "message": message.to_string() }));
    }

    fn manager(&self) -> Result<SignalManager, BridgeError> {
        self.manager.borrow().clone().ok_or_else(|| fail("Signal is not linked"))
    }

    fn own_aci(&self) -> Option<String> {
        self.manager.borrow().as_ref().map(|m| m.registration_data().service_ids.aci.to_string())
    }

    async fn handle(self: Rc<Self>, command: Command) {
        fn send(reply: Reply, result: Result<String, BridgeError>) {
            let _ = reply.send(result);
        }
        fn ok() -> Result<String, BridgeError> {
            Ok(String::new())
        }
        match command {
            Command::IsLinked(reply) => send(reply, Ok(self.store.is_registered().await.to_string())),
            Command::Account(reply) => send(reply, Ok(self.account().to_string())),
            Command::StartLink(reply) => send(reply, self.clone().start_link().map(|_| String::new())),
            Command::Connect(reply) => send(reply, self.clone().connect().await.and_then(|_| ok())),
            Command::Logout(reply) => send(reply, self.logout().await.and_then(|_| ok())),
            Command::Chats { archived, reply } => send(reply, self.chats(archived).await),
            Command::Chat { chat, reply } => send(reply, self.chat(&chat).await.map(|c| c.to_string())),
            Command::Messages { chat, before_ts, before_id, limit, reply } => {
                send(reply, self.messages(&chat, before_ts, &before_id, limit).await)
            }
            Command::Search { query, reply } => send(reply, self.search(&query).await),
            Command::SendText { chat, text, quote_id, reply } => send(reply, self.send_text(&chat, &text, &quote_id).await),
            Command::React { chat, id, emoji, remove, reply } => {
                send(reply, self.react(&chat, &id, &emoji, remove).await.and_then(|_| ok()))
            }
            Command::Edit { chat, id, text, reply } => send(reply, self.edit(&chat, &id, &text).await.and_then(|_| ok())),
            Command::Delete { chat, id, reply } => send(reply, self.delete(&chat, &id).await.and_then(|_| ok())),
            Command::MarkRead { chat, reply } => send(reply, self.mark_read(&chat).await.and_then(|_| ok())),
            Command::SetTyping { chat, active, reply } => send(reply, self.set_typing(&chat, active).await.and_then(|_| ok())),
            Command::SetFlag { chat, flag, value, reply } => {
                let result = self.db.set_flag(&chat, flag, value as i64).await.map_err(fail);
                self.emit("chats", json!({}));
                send(reply, result.and_then(|_| ok()))
            }
            Command::ResolvePhone { phone, reply } => send(reply, self.resolve_phone(&phone).await),
        }
    }

    fn account(&self) -> Value {
        match self.manager.borrow().as_ref() {
            Some(manager) => json!({
                "phone": manager.registration_data().phone_number.to_string(),
                "online": self.online.get(),
            }),
            None => json!({}),
        }
    }

    // region Linking and connection

    fn start_link(self: Rc<Self>) -> Result<(), BridgeError> {
        if self.linking.replace(true) {
            return Err(fail("Linking is already in progress"));
        }
        let (tx, rx) = fo::channel();
        let engine = self.clone();
        spawn_local(async move {
            let link = Manager::link_secondary_device(engine.store.clone(), SignalServers::Production, engine.device_name.clone(), tx);
            let show = {
                let engine = engine.clone();
                async move {
                    if let Ok(url) = rx.await {
                        engine.emit("qr", json!({ "code": url.to_string() }));
                    }
                }
            };
            let (result, _) = futures::future::join(link, show).await;
            engine.linking.set(false);
            match result {
                Ok(manager) => {
                    *engine.manager.borrow_mut() = Some(manager);
                    engine.emit("paired", json!({}));
                    engine.emit("status", json!({}));
                    let mut manager = engine.manager().expect("just linked");
                    if let Err(error) = manager.request_contacts().await {
                        engine.log(format!("contact sync request failed: {error}"));
                    }
                    engine.clone().start_receiving();
                    engine.clone().sync_storage();
                }
                Err(error) => engine.emit("link-error", json!({ "error": error.to_string() })),
            }
        });
        Ok(())
    }

    async fn connect(self: Rc<Self>) -> Result<(), BridgeError> {
        if self.manager.borrow().is_none() {
            if !self.store.is_registered().await {
                return Err(fail("Signal is not linked"));
            }
            let manager = Manager::load_registered(self.store.clone()).await.map_err(fail)?;
            *self.manager.borrow_mut() = Some(manager);
        }
        self.seed_chats_from_contacts().await;
        self.clone().start_receiving();
        self.sync_storage();
        Ok(())
    }

    /// Builds the chat list (names, archive, mute, pins) from the storage
    /// service, then fetches group details. Runs in the background.
    fn sync_storage(self: Rc<Self>) {
        spawn_local(async move {
            let Ok(manager) = self.manager() else { return };
            let chats = match crate::storage::read(&self.store, manager.registration_data()).await {
                Ok(chats) => chats,
                Err(error) => return self.log(format!("storage sync failed: {error}")),
            };
            let synced_at = now_ms() as i64;
            let mut ordered: Vec<_> = chats.iter().collect();
            ordered.sort_by_key(|c| c.name.clone().unwrap_or_default().to_lowercase());
            for (index, chat) in ordered.iter().enumerate() {
                let _ = self.db.seed_chat(&chat.chat, synced_at - index as i64 * 1000).await;
                if let Some(name) = &chat.name {
                    let _ = self.db.set_name(&chat.chat, name).await;
                }
                let _ = self.db.set_flag(&chat.chat, "archived", chat.archived as i64).await;
                let _ = self.db.set_flag(&chat.chat, "muted", chat.muted as i64).await;
                let _ = self.db.set_flag(&chat.chat, "pinned", chat.pinned as i64).await;
                if chat.unread {
                    let _ = self.db.set_flag(&chat.chat, "unread", 1).await;
                }
            }
            let groups = chats.iter().filter(|c| c.group_key.is_some()).count();
            self.log(format!("storage sync: {} chats ({groups} groups)", chats.len()));
            self.emit("chats", json!({}));
            // Group titles need the group's state from the groups service.
            for chat in chats {
                let Some(key) = chat.group_key else { continue };
                if matches!(self.store.group(key).await, Ok(Some(_))) {
                    continue;
                }
                let Ok(mut manager) = self.manager() else { return };
                let context = GroupContextV2 { master_key: Some(key.to_vec()), revision: Some(0), ..Default::default() };
                if let Err(error) = manager.retrieve_group_avatar(context).await {
                    self.log(format!("group fetch failed: {error}"));
                }
                self.emit("chats", json!({}));
            }
        });
    }

    /// A new linked device gets no message history, but the main phone's
    /// contact sync says which contacts have conversations and in what order
    /// (inbox position). Those become chats, below anything with messages.
    async fn seed_chats_from_contacts(&self) {
        let contacts = match self.store.contacts().await {
            Ok(contacts) => contacts,
            Err(error) => return self.log(format!("contacts unavailable: {error}")),
        };
        let synced_at = now_ms() as i64;
        let mut seeded = 0;
        let mut total = 0;
        for contact in contacts.flatten() {
            total += 1;
            if contact.inbox_position == 0 {
                continue;
            }
            let chat = format!("c:{}", contact.uuid);
            if self.db.seed_chat(&chat, synced_at - contact.inbox_position as i64 * 1000).await.is_ok() {
                seeded += 1;
            }
        }
        self.log(format!("contact sync: {total} contacts, {seeded} with conversations"));
        if seeded > 0 {
            self.emit("chats", json!({}));
        }
    }

    /// Keeps the websocket open, reconnecting with backoff.
    fn start_receiving(self: Rc<Self>) {
        if self.receiving.replace(true) {
            return;
        }
        spawn_local(async move {
            let mut backoff = Duration::from_secs(2);
            while self.receiving.get() {
                let Ok(mut manager) = self.manager() else { break };
                match manager.receive_messages().await {
                    Ok(stream) => {
                        backoff = Duration::from_secs(2);
                        pin_mut!(stream);
                        while let Some(received) = stream.next().await {
                            self.on_received(received).await;
                        }
                    }
                    Err(error) => self.log(format!("receive failed: {error}")),
                }
                if self.online.replace(false) {
                    self.emit("status", json!({}));
                }
                tokio::time::sleep(backoff).await;
                backoff = (backoff * 2).min(Duration::from_secs(120));
            }
            self.receiving.set(false);
        });
    }

    async fn logout(&self) -> Result<(), BridgeError> {
        self.receiving.set(false);
        *self.manager.borrow_mut() = None;
        let mut store = self.store.clone();
        store.clear_registration().await.map_err(fail)?;
        self.db.clear().await.map_err(fail)?;
        let _ = std::fs::remove_dir_all(self.dir.join("attachments"));
        self.emit("status", json!({}));
        self.emit("chats", json!({}));
        Ok(())
    }

    // endregion

    // region Incoming

    async fn on_received(self: &Rc<Self>, received: Received) {
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

    async fn ingest(self: &Rc<Self>, content: &Content) -> Result<(), BridgeError> {
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

    async fn apply_edit(&self, chat: &str, author: &str, edit: &EditMessage) -> Result<(), BridgeError> {
        let (Some(target), Some(message)) = (edit.target_sent_timestamp, edit.data_message.as_ref()) else { return Ok(()) };
        self.db.edit_message(chat, &message_id(target, author), message.body.as_deref().unwrap_or_default()).await.map_err(fail)?;
        self.emit("messages", json!({ "chat": chat }));
        self.emit("chats", json!({}));
        Ok(())
    }

    async fn ingest_data(
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
            if live && !from_me {
                self.db.bump_unread(chat).await.map_err(fail)?;
                self.emit("incoming", json!({ "chat": chat, "id": row.id }));
            }
        }
        self.emit("messages", json!({ "chat": chat }));
        self.emit("chats", json!({}));
        Ok(())
    }

    // endregion

    // region Names

    /// Contact name from the main phone, else profile name, else number.
    async fn contact_name(self: &Rc<Self>, id: &ServiceId) -> String {
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
    fn fetch_profile(self: &Rc<Self>, uuid: Uuid, key: presage::libsignal_service::prelude::ProfileKey) {
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

    async fn name_for(self: &Rc<Self>, author: &str) -> String {
        if let Ok(Some(name)) = self.db.name(&format!("c:{author}")).await {
            return name;
        }
        match Uuid::parse_str(author) {
            Ok(uuid) => self.contact_name(&ServiceId::Aci(uuid.into())).await,
            Err(_) => "Signal user".into(),
        }
    }

    async fn chat_title(self: &Rc<Self>, chat: &str) -> String {
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

    // endregion

    // region Queries

    async fn message_json(self: &Rc<Self>, row: &MessageRow, reactions: &[crate::db::ReactionRow]) -> Value {
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

    async fn chat_json_for(self: &Rc<Self>, row: crate::db::ChatRow) -> Value {
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

    async fn chats(self: &Rc<Self>, archived: bool) -> Result<String, BridgeError> {
        let (rows, archived_count) = self.db.chats(archived).await.map_err(fail)?;
        let mut chats = Vec::with_capacity(rows.len());
        for row in rows {
            chats.push(self.chat_json_for(row).await);
        }
        Ok(json!({ "chats": chats, "archivedCount": archived_count }).to_string())
    }

    async fn chat(self: &Rc<Self>, chat: &str) -> Result<Value, BridgeError> {
        thread_of(chat)?;
        let row = self.db.chat(chat).await.map_err(fail)?.unwrap_or(crate::db::ChatRow {
            id: chat.to_string(),
            archived: false,
            pinned: false,
            muted: false,
            unread: 0,
            last_ts: 0,
        });
        Ok(self.chat_json_for(row).await)
    }

    async fn messages(self: &Rc<Self>, chat: &str, before_ts: i64, before_id: &str, limit: i64) -> Result<String, BridgeError> {
        let rows = self.db.messages(chat, before_ts, before_id, limit).await.map_err(fail)?;
        let ids: Vec<String> = rows.iter().map(|r| r.id.clone()).collect();
        let reactions = self.db.reactions(chat, &ids).await.map_err(fail)?;
        let mut out = Vec::with_capacity(rows.len());
        for row in &rows {
            out.push(self.message_json(row, &reactions).await);
        }
        Ok(Value::Array(out).to_string())
    }

    async fn search(self: &Rc<Self>, query: &str) -> Result<String, BridgeError> {
        let rows = self.db.search(query, 50).await.map_err(fail)?;
        let mut out = Vec::with_capacity(rows.len());
        for row in &rows {
            out.push(self.message_json(row, &[]).await);
        }
        Ok(Value::Array(out).to_string())
    }

    // endregion

    // region Sending

    fn group_context(thread: &Thread) -> Option<GroupContextV2> {
        match thread {
            Thread::Group(key) => Some(GroupContextV2 { master_key: Some(key.to_vec()), revision: Some(0), ..Default::default() }),
            Thread::Contact(_) => None,
        }
    }

    async fn deliver(&self, thread: &Thread, body: impl Into<ContentBody>, ts: u64) -> Result<(), BridgeError> {
        let mut manager = self.manager()?;
        match thread {
            Thread::Contact(id) => manager.send_message(*id, body, ts).await.map_err(fail),
            Thread::Group(key) => manager.send_message_to_group(key, body, ts).await.map_err(fail),
        }
    }

    async fn send_text(self: &Rc<Self>, chat: &str, text: &str, quote_id: &str) -> Result<String, BridgeError> {
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

    async fn react(self: &Rc<Self>, chat: &str, id: &str, emoji: &str, remove: bool) -> Result<(), BridgeError> {
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

    async fn edit(self: &Rc<Self>, chat: &str, id: &str, text: &str) -> Result<(), BridgeError> {
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

    async fn delete(self: &Rc<Self>, chat: &str, id: &str) -> Result<(), BridgeError> {
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
    async fn mark_read(self: &Rc<Self>, chat: &str) -> Result<(), BridgeError> {
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
    async fn set_typing(&self, chat: &str, active: bool) -> Result<(), BridgeError> {
        let Thread::Contact(id) = thread_of(chat)? else { return Ok(()) };
        let action = if active { typing_message::Action::Started } else { typing_message::Action::Stopped };
        let ts = now_ms();
        let typing = TypingMessage { timestamp: Some(ts), action: Some(action.into()), group_id: None };
        self.manager()?.send_message(id, typing, ts).await.map_err(fail)
    }

    async fn resolve_phone(&self, phone: &str) -> Result<String, BridgeError> {
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

    // endregion
}

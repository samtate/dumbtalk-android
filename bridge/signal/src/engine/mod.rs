//! The engine thread: presage's manager, the receive loop and command handling.

mod incoming;
mod media;
mod outgoing;
mod queries;

use crate::db::{Db, MessageRow};
use crate::{BridgeError, Command, Listener, Reply};
use futures::{channel::oneshot as fo, pin_mut, StreamExt};
use presage::libsignal_service::configuration::SignalServers;
use presage::libsignal_service::content::{Content, ContentBody, DataMessage, GroupContextV2};
use presage::libsignal_service::prelude::{ProtobufMessage, Uuid};
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
            Command::Contacts(reply) => send(reply, self.contacts().await),
            Command::DownloadMedia { chat, id, reply } => send(reply, self.download_media(&chat, &id).await),
            Command::SendMedia { chat, path, mime, caption, voice_note, reply } => {
                send(reply, self.send_media(&chat, &path, &mime, &caption, voice_note).await)
            }
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


}

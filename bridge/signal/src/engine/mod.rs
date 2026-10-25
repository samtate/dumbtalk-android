//! The engine thread: presage's manager, the receive loop and command handling.

mod groups;
mod incoming;
mod media;
mod outbox;
mod outgoing;
use outgoing::sent_row;
mod queries;

use crate::db::{Db, MessageRow};
use crate::{BridgeError, Command, Listener, Reply};
use futures::{StreamExt, channel::oneshot as fo, pin_mut};
use presage::Manager;
use presage::libsignal_service::configuration::SignalServers;
use presage::libsignal_service::content::{Content, ContentBody, DataMessage, GroupContextV2};
use presage::libsignal_service::prelude::{ProtobufMessage, Uuid};
use presage::libsignal_service::protocol::ServiceId;
use presage::manager::Registered;
use presage::model::identity::OnNewIdentity;
use presage::model::messages::Received;
use presage::proto::BodyRange;
use presage::proto::body_range::AssociatedValue;
use presage::proto::data_message::{Delete, Quote, Reaction};
use presage::proto::sync_message::{Content as SyncContent, Sent};
use presage::proto::{
    EditMessage, ReceiptMessage, SyncMessage, TypingMessage, receipt_message, typing_message,
};
use presage::store::{ContentExt, ContentsStore, StateStore, Thread};
use presage_store_sqlite::SqliteStore;
use serde_json::{Value, json};
use std::cell::{Cell, RefCell};
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::rc::Rc;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::mpsc;
use tokio::task::{LocalSet, spawn_local};

type SignalManager = Manager<SqliteStore, Registered>;

pub(crate) fn run(
    dir: String,
    device_name: String,
    listener: Arc<dyn Listener>,
    mut commands: mpsc::UnboundedReceiver<Command>,
    ready: std::sync::mpsc::Sender<Result<(), BridgeError>>,
) {
    let runtime = match tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    {
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
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
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
        return ServiceId::parse_from_service_id_string(id)
            .map(Thread::Contact)
            .ok_or_else(|| fail("Invalid Signal chat"));
    }
    if let Some(key) = chat.strip_prefix("g:") {
        let bytes = hex::decode(key).map_err(fail)?;
        return <[u8; 32]>::try_from(bytes.as_slice())
            .map(Thread::Group)
            .map_err(|_| fail("Invalid Signal group"));
    }
    Err(fail("Invalid Signal chat"))
}

/// Signal identifies a message by its author and sent timestamp.
fn message_id(ts: u64, author: &str) -> String {
    format!("{ts}:{author}")
}

/// An ACI sent as text or, by newer clients, only as 16 binary bytes.
fn aci(text: Option<&String>, binary: Option<&Vec<u8>>) -> Option<String> {
    text.filter(|t| !t.is_empty()).cloned().or_else(|| {
        binary.and_then(|b| Uuid::from_slice(b).ok()).map(|u| u.to_string())
    })
}

/// A mention or text style over a message's text, in UTF-16 units as the
/// Kotlin side counts them. `id` is the mentioned ACI.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub(crate) struct Span {
    pub start: u32,
    pub length: u32,
    #[serde(default)]
    pub style: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
}

fn style_name(style: i32) -> Option<&'static str> {
    use presage::proto::body_range::Style;
    match Style::try_from(style).ok()? {
        Style::Bold => Some("bold"),
        Style::Italic => Some("italic"),
        Style::Spoiler => Some("spoiler"),
        Style::Strikethrough => Some("strike"),
        Style::Monospace => Some("mono"),
        Style::None => None,
    }
}

fn style_code(style: &str) -> Option<i32> {
    use presage::proto::body_range::Style;
    Some(match style {
        "bold" => Style::Bold,
        "italic" => Style::Italic,
        "spoiler" => Style::Spoiler,
        "strike" => Style::Strikethrough,
        "mono" => Style::Monospace,
        _ => return None,
    } as i32)
}

/// A group's context for messages to it; recipients fetch the current revision themselves.
fn group_context(master_key: &[u8; 32]) -> GroupContextV2 {
    GroupContextV2 { master_key: Some(master_key.to_vec()), revision: Some(0), ..Default::default() }
}

/// The ACI a body range mentions, if it is a mention.
fn mentioned(range: &BodyRange) -> Option<String> {
    match &range.associated_value {
        Some(AssociatedValue::MentionAci(text)) => aci(Some(text), None),
        Some(AssociatedValue::MentionAciBinary(bytes)) => aci(None, Some(bytes)),
        _ => None,
    }
}

/// A received body as shown: each mention placeholder (U+FFFC) becomes
/// "@Name", with mention and style spans moved to match. `name` resolves an ACI.
fn render_body(body: &str, ranges: &[BodyRange], name: impl Fn(&str) -> String) -> (String, Vec<Span>) {
    let units: Vec<u16> = body.encode_utf16().collect();
    let mut mentions: Vec<(usize, usize, String)> = ranges
        .iter()
        .filter_map(|range| {
            let who = mentioned(range)?;
            let start = range.start.unwrap_or(0) as usize;
            let end = (start + range.length.unwrap_or(1) as usize).min(units.len());
            (start <= end).then_some((start, end, who))
        })
        .collect();
    mentions.sort_by_key(|(start, ..)| *start);
    mentions.dedup_by_key(|(start, ..)| *start);

    let mut out: Vec<u16> = Vec::with_capacity(units.len());
    let mut spans = Vec::new();
    // Where each original offset lands in the output: (original, shown) pairs.
    let mut shifts: Vec<(usize, isize)> = Vec::new();
    let mut at = 0;
    for (start, end, who) in &mentions {
        if *start < at {
            continue;
        }
        out.extend_from_slice(&units[at..*start]);
        let tag: Vec<u16> = format!("@{}", name(who)).encode_utf16().collect();
        spans.push(Span { start: out.len() as u32, length: tag.len() as u32, style: "mention".into(), id: Some(who.clone()) });
        out.extend_from_slice(&tag);
        shifts.push((*end, tag.len() as isize - (*end - *start) as isize));
        at = *end;
    }
    out.extend_from_slice(&units[at.min(units.len())..]);
    let moved = |offset: usize| -> usize {
        let shift: isize = shifts.iter().filter(|(end, _)| *end <= offset).map(|(_, d)| d).sum();
        (offset as isize + shift).max(0) as usize
    };
    for range in ranges {
        let Some(AssociatedValue::Style(style)) = range.associated_value else { continue };
        let Some(style) = style_name(style) else { continue };
        let start = moved(range.start.unwrap_or(0) as usize);
        let end = moved(range.start.unwrap_or(0) as usize + range.length.unwrap_or(0) as usize).min(out.len());
        if end > start {
            spans.push(Span { start: start as u32, length: (end - start) as u32, style: style.into(), id: None });
        }
    }
    (String::from_utf16_lossy(&out), spans)
}

/// The reverse, for sending: each mention span (over "@Name" in `text`)
/// becomes a placeholder and a mention range; styles carry over.
fn encode_body(text: &str, spans: &[Span]) -> (String, Vec<BodyRange>) {
    let units: Vec<u16> = text.encode_utf16().collect();
    let mut mentions: Vec<&Span> = spans.iter().filter(|s| s.style == "mention" && s.id.is_some()).collect();
    mentions.sort_by_key(|s| s.start);
    let mut out = Vec::with_capacity(units.len());
    let mut ranges = Vec::new();
    let mut shifts: Vec<(usize, isize)> = Vec::new();
    let mut at = 0;
    for mention in mentions {
        let (start, end) = (mention.start as usize, (mention.start + mention.length) as usize);
        if start < at || end > units.len() {
            continue;
        }
        out.extend_from_slice(&units[at..start]);
        let uuid = mention.id.as_deref().and_then(|id| Uuid::parse_str(id).ok());
        ranges.push(BodyRange {
            start: Some(out.len() as u32),
            length: Some(1),
            associated_value: Some(match uuid {
                Some(uuid) => AssociatedValue::MentionAciBinary(uuid.as_bytes().to_vec()),
                None => AssociatedValue::MentionAci(mention.id.clone().unwrap_or_default()),
            }),
        });
        out.push(0xFFFC);
        shifts.push((end, 1 - (end - start) as isize));
        at = end;
    }
    out.extend_from_slice(&units[at..]);
    let moved = |offset: usize| -> usize {
        let shift: isize = shifts.iter().filter(|(end, _)| *end <= offset).map(|(_, d)| d).sum();
        (offset as isize + shift).max(0) as usize
    };
    for span in spans {
        let Some(code) = style_code(&span.style) else { continue };
        let start = moved(span.start as usize);
        let end = moved((span.start + span.length) as usize).min(out.len());
        if end > start {
            ranges.push(BodyRange { start: Some(start as u32), length: Some((end - start) as u32), associated_value: Some(AssociatedValue::Style(code)) });
        }
    }
    (String::from_utf16_lossy(&out), ranges)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bodies_round_trip_mentions_and_styles() {
        let ann = "6f1c9c1e-2a5b-4e7d-9d65-0a1b2c3d4e5f";
        let ranges = vec![
            BodyRange { start: Some(6), length: Some(1), associated_value: Some(AssociatedValue::MentionAci(ann.into())) },
            BodyRange { start: Some(0), length: Some(2), associated_value: Some(AssociatedValue::MentionAciBinary(vec![0; 16])) },
            // "hi" after the first placeholder, and "!" at the end.
            BodyRange { start: Some(3), length: Some(2), associated_value: Some(AssociatedValue::Style(1)) },
            BodyRange { start: Some(7), length: Some(1), associated_value: Some(AssociatedValue::Style(3)) },
        ];
        let (text, spans) = render_body("😀 hi \u{fffc}!", &ranges, |id| if id == ann { "Ann".into() } else { "Bo".into() });
        assert_eq!(text, "@Bo hi @Ann!");
        let find = |style: &str| spans.iter().filter(|s| s.style == style).map(|s| (s.start, s.length)).collect::<Vec<_>>();
        assert_eq!(find("mention"), [(0, 3), (7, 4)]);
        assert_eq!(find("bold"), [(4, 2)]);
        assert_eq!(find("spoiler"), [(11, 1)]);

        let (body, encoded) = encode_body(&text, &spans);
        assert_eq!(body, "\u{fffc} hi \u{fffc}!");
        let (again, respans) = render_body(&body, &encoded, |id| if id == ann { "Ann".into() } else { "Bo".into() });
        assert_eq!(again, text);
        assert_eq!(respans.len(), spans.len());
        assert_eq!(aci(None, Some(&vec![0; 16])).as_deref(), Some("00000000-0000-0000-0000-000000000000"));
    }
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
    /// Whether the disappearing-message sweep is running.
    expiring: Cell<bool>,
    /// Cuts short the wait between reconnection attempts.
    wake: tokio::sync::Notify,
    /// Drops a connection that looks open, to reconnect on a new network.
    restart: tokio::sync::Notify,
    /// Our messages waiting to be sent, in order: (chat, id).
    outbox: RefCell<std::collections::VecDeque<(String, String)>>,
    /// Whether the outbox is being worked through.
    sending: Cell<bool>,
}

impl Engine {
    async fn open(
        dir: PathBuf,
        device_name: String,
        listener: Arc<dyn Listener>,
    ) -> Result<Engine, BridgeError> {
        std::fs::create_dir_all(&dir).map_err(fail)?;
        let url = format!("sqlite://{}?mode=rwc", dir.join("signal.db3").display());
        let store = SqliteStore::open_with_passphrase(&url, None, OnNewIdentity::Trust)
            .await
            .map_err(fail)?;
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
            expiring: Cell::new(false),
            wake: tokio::sync::Notify::new(),
            restart: tokio::sync::Notify::new(),
            outbox: RefCell::new(Default::default()),
            sending: Cell::new(false),
        })
    }

    fn emit(&self, kind: &str, payload: Value) {
        self.listener
            .on_event(kind.to_string(), payload.to_string());
    }

    /// Tells the app a chat's messages changed, so an open chat reloads.
    fn messages_changed(&self, chat: &str) {
        self.emit("messages", json!({ "chat": chat }));
    }

    fn log(&self, message: impl std::fmt::Display) {
        self.emit("log", json!({ "message": message.to_string() }));
    }

    fn manager(&self) -> Result<SignalManager, BridgeError> {
        self.manager
            .borrow()
            .clone()
            .ok_or_else(|| fail("Signal is not linked"))
    }

    fn own_aci(&self) -> Option<String> {
        self.manager
            .borrow()
            .as_ref()
            .map(|m| m.registration_data().service_ids.aci.to_string())
    }

    async fn handle(self: Rc<Self>, command: Command) {
        fn send(reply: Reply, result: Result<String, BridgeError>) {
            let _ = reply.send(result);
        }
        fn ok() -> Result<String, BridgeError> {
            Ok(String::new())
        }
        match command {
            Command::IsLinked(reply) => {
                send(reply, Ok(self.store.is_registered().await.to_string()))
            }
            Command::Account(reply) => send(reply, Ok(self.account().to_string())),
            Command::StartLink(reply) => {
                send(reply, self.clone().start_link().map(|_| String::new()))
            }
            Command::Connect(reply) => send(reply, self.clone().connect().await.and_then(|_| ok())),
            Command::Logout(reply) => send(reply, self.logout().await.and_then(|_| ok())),
            Command::Reconnect { network_changed, reply } => {
                self.reconnect(network_changed);
                send(reply, ok())
            }
            Command::Chats { archived, reply } => send(reply, self.chats(archived).await),
            Command::Chat { chat, reply } => {
                send(reply, self.chat(&chat).await.map(|c| c.to_string()))
            }
            Command::Messages {
                chat,
                before_ts,
                before_id,
                limit,
                reply,
            } => send(
                reply,
                self.messages(&chat, before_ts, &before_id, limit).await,
            ),
            Command::PinnedMessages { chat, reply } => send(reply, self.pinned_messages(&chat).await),
            Command::PinMessage { chat, id, pinned, reply } => send(reply, self.pin_message(&chat, &id, pinned).await.and_then(|_| ok())),
            Command::CreatePoll { chat, question, options_json, multiple, reply } =>
                send(reply, self.create_poll(&chat, &question, &options_json, multiple).await),
            Command::VotePoll { chat, id, options_json, reply } => send(reply, self.vote_poll(&chat, &id, &options_json).await.and_then(|_| ok())),
            Command::ClosePoll { chat, id, reply } => send(reply, self.close_poll(&chat, &id).await.and_then(|_| ok())),
            Command::Search { query, reply } => send(reply, self.search(&query).await),
            Command::Retry { chat, id, reply } => send(reply, self.retry(&chat, &id).await),
            Command::Receipts { chat, id, reply } => send(reply, self.receipts_json(&chat, &id).await),
            Command::SendText {
                chat,
                text,
                quote_id,
                spans_json,
                reply,
            } => send(reply, self.send_text(&chat, &text, &quote_id, &spans_json).await),
            Command::SetExpireTimer { chat, seconds, reply } => send(reply, self.set_expire_timer(&chat, seconds).await.and_then(|_| ok())),
            Command::RespondRequest { chat, action, reply } => send(reply, self.respond_request(&chat, &action).await.and_then(|_| ok())),
            Command::Members { chat, reply } => send(reply, self.members(&chat).await),
            Command::CreateGroup { name, members_json, reply } => send(reply, self.create_group(&name, &members_json).await),
            Command::RenameGroup { chat, name, reply } => send(reply, self.rename_group(&chat, &name).await.and_then(|_| ok())),
            Command::AddMembers { chat, members_json, reply } => send(reply, self.add_members(&chat, &members_json).await.and_then(|_| ok())),
            Command::RemoveMember { chat, member, reply } => send(reply, self.remove_member(&chat, &member).await.and_then(|_| ok())),
            Command::LeaveGroup { chat, reply } => send(reply, self.leave_group(&chat).await.and_then(|_| ok())),
            Command::React {
                chat,
                id,
                emoji,
                remove,
                reply,
            } => send(
                reply,
                self.react(&chat, &id, &emoji, remove)
                    .await
                    .and_then(|_| ok()),
            ),
            Command::Edit {
                chat,
                id,
                text,
                reply,
            } => send(reply, self.edit(&chat, &id, &text).await.and_then(|_| ok())),
            Command::Delete { chat, id, reply } => {
                send(reply, self.delete(&chat, &id).await.and_then(|_| ok()))
            }
            Command::MarkRead { chat, reply } => {
                send(reply, self.mark_read(&chat).await.and_then(|_| ok()))
            }
            Command::SetTyping {
                chat,
                active,
                reply,
            } => send(
                reply,
                self.set_typing(&chat, active).await.and_then(|_| ok()),
            ),
            Command::SetFlag {
                chat,
                flag,
                value,
                reply,
            } => {
                let result = self
                    .db
                    .set_flag(&chat, flag, value as i64)
                    .await
                    .map_err(fail);
                self.emit("chats", json!({}));
                send(reply, result.and_then(|_| ok()))
            }
            Command::ResolvePhone { phone, reply } => send(reply, self.resolve_phone(&phone).await),
            Command::Contacts(reply) => send(reply, self.contacts().await),
            Command::ConversationAvatar { chat, reply } => {
                send(reply, self.conversation_avatar(&chat).await)
            }
            Command::DownloadAttachment {
                chat,
                id,
                index,
                reply,
            } => send(
                reply,
                self.download_attachment(&chat, &id, index as usize).await,
            ),
            Command::SendMediaBatch {
                chat,
                files,
                caption,
                quote_id,
                reply,
            } => send(
                reply,
                self.send_media_batch(&chat, &files, &caption, &quote_id)
                    .await,
            ),
            Command::DownloadMedia { chat, id, reply } => {
                send(reply, self.download_media(&chat, &id).await)
            }
            Command::SendMedia {
                chat,
                path,
                mime,
                caption,
                voice_note,
                reply,
            } => send(
                reply,
                self.send_media(&chat, &path, &mime, &caption, voice_note)
                    .await,
            ),
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
            let link = Manager::link_secondary_device(
                engine.store.clone(),
                SignalServers::Production,
                engine.device_name.clone(),
                tx,
            );
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
            let manager = Manager::load_registered(self.store.clone())
                .await
                .map_err(fail)?;
            *self.manager.borrow_mut() = Some(manager);
        }
        self.seed_chats_from_contacts().await;
        self.repair_from_store().await;
        self.clone().start_receiving();
        self.clone().start_expiring();
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
                let _ = self
                    .db
                    .seed_chat(&chat.chat, synced_at - index as i64 * 1000)
                    .await;
                if let Some(name) = &chat.name {
                    let _ = self.db.set_name(&chat.chat, name).await;
                }
                // The main phone accepted (whitelisted) these.
                let _ = self.db.accept_known(&chat.chat).await;
                let _ = self
                    .db
                    .set_flag(&chat.chat, "archived", chat.archived as i64)
                    .await;
                let _ = self
                    .db
                    .set_flag(&chat.chat, "muted", chat.muted as i64)
                    .await;
                let _ = self
                    .db
                    .set_flag(&chat.chat, "pinned", chat.pinned as i64)
                    .await;
                if chat.unread {
                    let _ = self.db.set_flag(&chat.chat, "unread", 1).await;
                }
            }
            let groups = chats.iter().filter(|c| c.group_key.is_some()).count();
            self.log(format!(
                "storage sync: {} chats ({groups} groups)",
                chats.len()
            ));
            self.emit("chats", json!({}));
            // Group titles need the group's state from the groups service.
            for chat in chats {
                let Some(key) = chat.group_key else { continue };
                if matches!(self.store.group(key).await, Ok(Some(_))) {
                    continue;
                }
                let Ok(mut manager) = self.manager() else {
                    return;
                };
                let context = group_context(&key);
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
            let _ = self.db.accept_known(&chat).await;
            if self
                .db
                .seed_chat(&chat, synced_at - contact.inbox_position as i64 * 1000)
                .await
                .is_ok()
            {
                seeded += 1;
            }
        }
        self.log(format!(
            "contact sync: {total} contacts, {seeded} with conversations"
        ));
        if seeded > 0 {
            self.emit("chats", json!({}));
        }
    }

    /// Deletes disappearing messages as their time runs out.
    fn start_expiring(self: Rc<Self>) {
        if self.expiring.replace(true) {
            return;
        }
        spawn_local(async move {
            loop {
                match self.db.purge_expired(now_ms() as i64).await {
                    Ok(chats) => {
                        for chat in &chats {
                            self.messages_changed(chat);
                        }
                        if !chats.is_empty() {
                            self.emit("chats", json!({}));
                        }
                    }
                    Err(error) => self.log(format!("expiry failed: {error}")),
                }
                // Wake for the next expiry, but at least every minute.
                let next = self.db.next_expiry().await.ok().flatten().unwrap_or(i64::MAX);
                let wait = (next - now_ms() as i64).clamp(1_000, 60_000);
                tokio::time::sleep(Duration::from_millis(wait as u64)).await;
            }
        });
    }

    /// Keeps the websocket open, reconnecting with backoff.
    fn start_receiving(self: Rc<Self>) {
        if self.receiving.replace(true) {
            return;
        }
        spawn_local(async move {
            let mut backoff = Duration::from_secs(2);
            while self.receiving.get() {
                let Ok(mut manager) = self.manager() else {
                    break;
                };
                let mut restarted = false;
                match manager.receive_messages().await {
                    Ok(stream) => {
                        backoff = Duration::from_secs(2);
                        pin_mut!(stream);
                        loop {
                            tokio::select! {
                                received = stream.next() => match received {
                                    Some(received) => self.on_received(received).await,
                                    None => break,
                                },
                                _ = self.restart.notified() => {
                                    restarted = true;
                                    break;
                                }
                            }
                        }
                    }
                    Err(error) => self.log(format!("receive failed: {error}")),
                }
                if self.online.replace(false) {
                    self.emit("status", json!({}));
                }
                // Wait to retry, unless told the network is back.
                if restarted || tokio::time::timeout(backoff, self.wake.notified()).await.is_ok() {
                    backoff = Duration::from_secs(2);
                } else {
                    backoff = (backoff * 2).min(Duration::from_secs(120));
                }
            }
            self.receiving.set(false);
        });
    }

    /// Retries the connection straight away if it's down. After a network
    /// change an open-looking connection may be dead, so it's dropped and
    /// opened again.
    fn reconnect(self: &Rc<Self>, network_changed: bool) {
        if self.manager.borrow().is_none() {
            return;
        }
        if self.online.get() {
            if network_changed {
                self.restart.notify_one();
            }
            return;
        }
        if self.receiving.get() {
            self.wake.notify_one();
        } else {
            self.clone().start_receiving();
        }
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

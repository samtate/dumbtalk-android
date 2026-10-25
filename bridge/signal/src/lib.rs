//! Signal for DumbTalk: a linked device built on presage, exposed to Kotlin
//! through UniFFI. The API mirrors the WhatsApp bridge: blocking calls that
//! take and return strings (JSON), plus one listener for events.
//!
//! presage's futures aren't Send, so everything runs on one thread with a
//! single-threaded runtime; exported methods post commands to it.

mod db;
mod engine;
mod storage;

use std::sync::Arc;
use std::thread;
use tokio::sync::{mpsc, oneshot};

uniffi::setup_scaffolding!();

/// Lets rustls (used by Signal's networking) reach Android's certificate
/// verifier. Kotlin calls this once with the application context.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_dev_dumbtalk_signal_SignalNative_init<'caller>(
    mut env: jni::EnvUnowned<'caller>,
    _class: jni::objects::JClass<'caller>,
    context: jni::objects::JObject<'caller>,
) {
    env.with_env(|env| -> Result<(), jni::errors::Error> {
        rustls_platform_verifier::android::init_with_env(env, context)
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>();
}

/// Events: "status", "chats", "messages" {chat}, "incoming" {chat,id},
/// "typing" {chat,sender,active}, "qr" {code}, "paired", "link-error"
/// {error} and "log" {message}.
#[uniffi::export(callback_interface)]
pub trait Listener: Send + Sync {
    fn on_event(&self, kind: String, payload: String);
}

/// Flat so Kotlin sees just the message (a field named `message` would clash
/// with Throwable.message in the generated code).
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum BridgeError {
    #[error("{0}")]
    Failed(String),
}

impl BridgeError {
    pub(crate) fn new(message: impl ToString) -> Self {
        BridgeError::Failed(message.to_string())
    }
}

pub(crate) type Reply = oneshot::Sender<Result<String, BridgeError>>;

pub(crate) enum Command {
    IsLinked(Reply),
    Account(Reply),
    StartLink(Reply),
    Connect(Reply),
    Logout(Reply),
    Reconnect { network_changed: bool, reply: Reply },
    Chats {
        archived: bool,
        reply: Reply,
    },
    Chat {
        chat: String,
        reply: Reply,
    },
    Messages {
        chat: String,
        before_ts: i64,
        before_id: String,
        limit: i64,
        reply: Reply,
    },
    PinnedMessages { chat: String, reply: Reply },
    PinMessage { chat: String, id: String, pinned: bool, reply: Reply },
    CreatePoll { chat: String, question: String, options_json: String, multiple: bool, reply: Reply },
    VotePoll { chat: String, id: String, options_json: String, reply: Reply },
    ClosePoll { chat: String, id: String, reply: Reply },
    Search {
        query: String,
        reply: Reply,
    },
    Retry { chat: String, id: String, reply: Reply },
    SendText {
        chat: String,
        text: String,
        quote_id: String,
        spans_json: String,
        reply: Reply,
    },
    SetExpireTimer { chat: String, seconds: u32, reply: Reply },
    RespondRequest { chat: String, action: String, reply: Reply },
    Members { chat: String, reply: Reply },
    CreateGroup { name: String, members_json: String, reply: Reply },
    RenameGroup { chat: String, name: String, reply: Reply },
    AddMembers { chat: String, members_json: String, reply: Reply },
    RemoveMember { chat: String, member: String, reply: Reply },
    LeaveGroup { chat: String, reply: Reply },
    React {
        chat: String,
        id: String,
        emoji: String,
        remove: bool,
        reply: Reply,
    },
    Edit {
        chat: String,
        id: String,
        text: String,
        reply: Reply,
    },
    Delete {
        chat: String,
        id: String,
        reply: Reply,
    },
    MarkRead {
        chat: String,
        reply: Reply,
    },
    SetTyping {
        chat: String,
        active: bool,
        reply: Reply,
    },
    SetFlag {
        chat: String,
        flag: &'static str,
        value: bool,
        reply: Reply,
    },
    ResolvePhone {
        phone: String,
        reply: Reply,
    },
    Contacts(Reply),
    ConversationAvatar {
        chat: String,
        reply: Reply,
    },
    DownloadAttachment {
        chat: String,
        id: String,
        index: u32,
        reply: Reply,
    },
    SendMediaBatch {
        chat: String,
        files: String,
        caption: String,
        quote_id: String,
        reply: Reply,
    },
    DownloadMedia {
        chat: String,
        id: String,
        reply: Reply,
    },
    SendMedia {
        chat: String,
        path: String,
        mime: String,
        caption: String,
        voice_note: bool,
        reply: Reply,
    },
}

#[derive(uniffi::Object)]
pub struct SignalClient {
    commands: mpsc::UnboundedSender<Command>,
}

impl SignalClient {
    fn call(&self, make: impl FnOnce(Reply) -> Command) -> Result<String, BridgeError> {
        let (tx, rx) = oneshot::channel();
        self.commands
            .send(make(tx))
            .map_err(|_| BridgeError::new("Signal stopped"))?;
        rx.blocking_recv()
            .map_err(|_| BridgeError::new("Signal stopped"))?
    }

    fn unit(&self, make: impl FnOnce(Reply) -> Command) -> Result<(), BridgeError> {
        self.call(make).map(|_| ())
    }
}

#[uniffi::export]
impl SignalClient {
    /// Opens (or creates) the Signal databases in `dir` and starts the engine thread.
    #[uniffi::constructor]
    pub fn new(
        dir: String,
        device_name: String,
        listener: Box<dyn Listener>,
    ) -> Result<Arc<Self>, BridgeError> {
        let (commands, receiver) = mpsc::unbounded_channel();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        thread::Builder::new()
            .name("signal".into())
            .spawn(move || engine::run(dir, device_name, Arc::from(listener), receiver, ready_tx))
            .map_err(BridgeError::new)?;
        ready_rx
            .recv()
            .map_err(|_| BridgeError::new("Signal failed to start"))??;
        Ok(Arc::new(SignalClient { commands }))
    }

    pub fn is_linked(&self) -> bool {
        self.call(Command::IsLinked)
            .map(|v| v == "true")
            .unwrap_or(false)
    }

    /// {"phone","name","online"}
    pub fn account_json(&self) -> String {
        self.call(Command::Account).unwrap_or_else(|_| "{}".into())
    }

    /// Starts linking; emits "qr" with the link to show, then "paired" or "link-error".
    pub fn start_link(&self) -> Result<(), BridgeError> {
        self.unit(Command::StartLink)
    }

    /// Starts receiving for an already linked device.
    pub fn connect(&self) -> Result<(), BridgeError> {
        self.unit(Command::Connect)
    }

    pub fn logout(&self) -> Result<(), BridgeError> {
        self.unit(Command::Logout)
    }

    /// Reconnects now rather than after the retry wait: the network is back,
    /// or the watchdog found us offline. `network_changed` also drops a
    /// connection that looks open, which may have died with the old network.
    pub fn reconnect(&self, network_changed: bool) -> Result<(), BridgeError> {
        self.unit(|reply| Command::Reconnect { network_changed, reply })
    }

    pub fn chats_json(&self, archived: bool) -> Result<String, BridgeError> {
        self.call(|reply| Command::Chats { archived, reply })
    }

    pub fn chat_json(&self, chat: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::Chat { chat, reply })
    }

    pub fn messages_json(
        &self,
        chat: String,
        before_ts: i64,
        before_id: String,
        limit: i64,
    ) -> Result<String, BridgeError> {
        self.call(|reply| Command::Messages {
            chat,
            before_ts,
            before_id,
            limit,
            reply,
        })
    }

    pub fn pinned_messages_json(&self, chat: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::PinnedMessages { chat, reply })
    }

    pub fn pin_message(&self, chat: String, id: String, pinned: bool) -> Result<(), BridgeError> {
        self.unit(|reply| Command::PinMessage { chat, id, pinned, reply })
    }

    /// `options_json` is a JSON array of option names; returns the sent message.
    pub fn create_poll(&self, chat: String, question: String, options_json: String, multiple: bool) -> Result<String, BridgeError> {
        self.call(|reply| Command::CreatePoll { chat, question, options_json, multiple, reply })
    }

    /// `options_json` is a JSON array of option indexes.
    pub fn vote_poll(&self, chat: String, id: String, options_json: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::VotePoll { chat, id, options_json, reply })
    }

    pub fn close_poll(&self, chat: String, id: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::ClosePoll { chat, id, reply })
    }

    pub fn search_json(&self, query: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::Search { query, reply })
    }

    /// Sends a failed message of ours again, with its first timestamp; returns it.
    pub fn retry(&self, chat: String, id: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::Retry { chat, id, reply })
    }

    /// `spans_json`: mentions ({start, length, style: "mention", id: ACI})
    /// and styles over `text` in UTF-16 units, or "" for none.
    pub fn send_text(
        &self,
        chat: String,
        text: String,
        quote_id: String,
        spans_json: String,
    ) -> Result<String, BridgeError> {
        self.call(|reply| Command::SendText {
            chat,
            text,
            quote_id,
            spans_json,
            reply,
        })
    }

    /// Sets a chat's disappearing-message timer in seconds; 0 turns it off.
    pub fn set_expire_timer(&self, chat: String, seconds: u32) -> Result<(), BridgeError> {
        self.unit(|reply| Command::SetExpireTimer { chat, seconds, reply })
    }

    /// Answers a message request: "accept", "block", "unblock" or "delete".
    pub fn respond_request(&self, chat: String, action: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::RespondRequest { chat, action, reply })
    }

    /// The chat's people other than us: [{"id": ACI, "name", "admin"}], by name.
    pub fn members_json(&self, chat: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::Members { chat, reply })
    }

    /// Creates a group with the chat ids in `members_json`; returns its chat id.
    pub fn create_group(&self, name: String, members_json: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::CreateGroup { name, members_json, reply })
    }

    pub fn rename_group(&self, chat: String, name: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::RenameGroup { chat, name, reply })
    }

    /// Adds the chat ids (people) in `members_json` to a group.
    pub fn add_members(&self, chat: String, members_json: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::AddMembers { chat, members_json, reply })
    }

    /// Removes a member (by ACI) from a group.
    pub fn remove_member(&self, chat: String, member: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::RemoveMember { chat, member, reply })
    }

    pub fn leave_group(&self, chat: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::LeaveGroup { chat, reply })
    }

    pub fn react(
        &self,
        chat: String,
        id: String,
        emoji: String,
        remove: bool,
    ) -> Result<(), BridgeError> {
        self.unit(|reply| Command::React {
            chat,
            id,
            emoji,
            remove,
            reply,
        })
    }

    pub fn edit(&self, chat: String, id: String, text: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::Edit {
            chat,
            id,
            text,
            reply,
        })
    }

    pub fn delete(&self, chat: String, id: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::Delete { chat, id, reply })
    }

    pub fn mark_read(&self, chat: String) -> Result<(), BridgeError> {
        self.unit(|reply| Command::MarkRead { chat, reply })
    }

    pub fn set_typing(&self, chat: String, active: bool) -> Result<(), BridgeError> {
        self.unit(|reply| Command::SetTyping {
            chat,
            active,
            reply,
        })
    }

    /// Local only: presage doesn't sync Signal's storage service yet.
    pub fn set_archived(&self, chat: String, value: bool) -> Result<(), BridgeError> {
        self.unit(|reply| Command::SetFlag {
            chat,
            flag: "archived",
            value,
            reply,
        })
    }

    pub fn set_pinned(&self, chat: String, value: bool) -> Result<(), BridgeError> {
        self.unit(|reply| Command::SetFlag {
            chat,
            flag: "pinned",
            value,
            reply,
        })
    }

    pub fn set_muted(&self, chat: String, value: bool) -> Result<(), BridgeError> {
        self.unit(|reply| Command::SetFlag {
            chat,
            flag: "muted",
            value,
            reply,
        })
    }

    /// Saved contacts from the main phone: [{"id","name"}], sorted by name.
    pub fn contacts_json(&self) -> Result<String, BridgeError> {
        self.call(Command::Contacts)
    }

    /// Fetches a contact or group avatar and returns a cached local path, or empty if absent.
    pub fn conversation_avatar(&self, chat: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::ConversationAvatar { chat, reply })
    }

    /// Downloads (once) a received attachment; returns its local path.
    pub fn download_media(&self, chat: String, id: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::DownloadMedia { chat, id, reply })
    }

    pub fn download_attachment(
        &self,
        chat: String,
        id: String,
        index: u32,
    ) -> Result<String, BridgeError> {
        self.call(|reply| Command::DownloadAttachment {
            chat,
            id,
            index,
            reply,
        })
    }

    pub fn send_media_batch(
        &self,
        chat: String,
        files: String,
        caption: String,
        quote_id: String,
    ) -> Result<String, BridgeError> {
        self.call(|reply| Command::SendMediaBatch {
            chat,
            files,
            caption,
            quote_id,
            reply,
        })
    }

    /// Sends a file (photo, video, audio or document by MIME type); returns the message JSON.
    pub fn send_media(
        &self,
        chat: String,
        path: String,
        mime: String,
        caption: String,
        voice_note: bool,
    ) -> Result<String, BridgeError> {
        self.call(|reply| Command::SendMedia {
            chat,
            path,
            mime,
            caption,
            voice_note,
            reply,
        })
    }

    /// Finds the Signal account for a phone number; returns its chat id.
    pub fn resolve_phone(&self, phone: String) -> Result<String, BridgeError> {
        self.call(|reply| Command::ResolvePhone { phone, reply })
    }
}

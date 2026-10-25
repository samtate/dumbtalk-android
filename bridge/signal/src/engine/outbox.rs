//! Our messages are stored before they're sent, so the chat shows them at
//! once and a send is tried exactly once. A failed one is retried only when
//! asked, with its first timestamp: Signal apps drop a message whose author
//! and timestamp they already have, so nobody sees it twice even if the
//! first attempt got through to them.

use super::media::OutgoingFile;
use super::*;

impl Engine {
    /// Stores `row` as our message being sent, with the DataMessage to send
    /// and any files still to upload into it, and returns it at once; the
    /// outbox then makes the one attempt to send it. Errors are returned only
    /// when nothing was stored.
    pub(super) async fn queue(
        self: &Rc<Self>,
        mut row: MessageRow,
        message: DataMessage,
        files: &[OutgoingFile],
        voice_note: bool,
    ) -> Result<String, BridgeError> {
        let files = if files.is_empty() { String::new() } else { serde_json::to_string(files).map_err(fail)? };
        row.status = "sending".into();
        self.db.put_message(&row).await.map_err(fail)?;
        self.db.put_outbox(&row.chat, &row.id, &message.encode_to_vec(), &files, voice_note).await.map_err(fail)?;
        self.messages_changed(&row.chat);
        self.emit("chats", json!({}));
        self.enqueue(&row.chat, &row.id);
        Ok(self.message_json(&row, &[]).await.to_string())
    }

    /// Sends a failed message of ours again, with its first timestamp.
    pub(super) async fn retry(self: &Rc<Self>, chat: &str, id: &str) -> Result<String, BridgeError> {
        thread_of(chat)?;
        let mut row = self.db.message(chat, id).await.map_err(fail)?
            .filter(|row| row.from_me)
            .ok_or_else(|| fail("Message not found"))?;
        if row.status == "failed" {
            self.db.settle(chat, id, "sending").await.map_err(fail)?;
            self.messages_changed(chat);
            self.enqueue(chat, id);
            row.status = "sending".into();
        }
        Ok(self.message_json(&row, &[]).await.to_string())
    }

    /// Adds a stored message to the outbox, which sends one at a time in order.
    fn enqueue(self: &Rc<Self>, chat: &str, id: &str) {
        self.outbox.borrow_mut().push_back((chat.to_string(), id.to_string()));
        if self.sending.replace(true) {
            return;
        }
        let engine = self.clone();
        spawn_local(async move {
            loop {
                let next = engine.outbox.borrow_mut().pop_front();
                let Some((chat, id)) = next else { break };
                if let Ok(thread) = thread_of(&chat) {
                    engine.attempt(&thread, &chat, &id).await;
                }
            }
            engine.sending.set(false);
        });
    }

    /// Makes one attempt to send a stored message and records how it went.
    async fn attempt(self: &Rc<Self>, thread: &Thread, chat: &str, id: &str) {
        // Wait (up to five minutes) for the connection, so a message written
        // offline goes when it's back instead of failing.
        for _ in 0..300 {
            if self.online.get() {
                break;
            }
            tokio::time::sleep(Duration::from_secs(1)).await;
        }
        let status = match self.send_stored(thread, chat, id).await {
            Ok(()) => "sent",
            Err(error) => {
                self.log(format!("send {id} failed: {error}"));
                "failed"
            }
        };
        if let Err(error) = self.db.settle(chat, id, status).await {
            self.log(format!("record send of {id}: {error}"));
        }
        if status == "sent" {
            let _ = self.db.delete_outbox(chat, id).await;
        }
        self.messages_changed(chat);
        self.emit("chats", json!({}));
    }

    /// Uploads the message's files if that hasn't happened yet, then sends it.
    async fn send_stored(&self, thread: &Thread, chat: &str, id: &str) -> Result<(), BridgeError> {
        let (body, files, voice_note) = self.db.outbox(chat, id).await.map_err(fail)?
            .ok_or_else(|| fail("There's nothing left to send for this message"))?;
        let mut message = DataMessage::decode(body.as_slice()).map_err(fail)?;
        if !files.is_empty() {
            let files: Vec<OutgoingFile> = serde_json::from_str(&files).map_err(fail)?;
            let pointers = self.upload(&files, voice_note).await?;
            let encoded: Vec<Vec<u8>> = pointers.iter().map(|p| p.encode_to_vec()).collect();
            self.db.set_attachments(chat, id, &encoded).await.map_err(fail)?;
            message.attachments = pointers;
            // Uploaded: a retry resends these pointers rather than uploading again.
            self.db.put_outbox(chat, id, &message.encode_to_vec(), "", voice_note).await.map_err(fail)?;
        }
        let ts = message.timestamp.ok_or_else(|| fail("Message has no timestamp"))?;
        self.deliver(thread, message, ts).await
    }
}

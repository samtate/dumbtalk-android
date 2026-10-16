//! Attachments: fetching received ones and sending files.

use super::*;
use presage::libsignal_service::sender::AttachmentSpec;
use presage::proto::AttachmentPointer;

fn extension(mime: &str) -> &'static str {
    match mime.split(';').next().unwrap_or_default() {
        "image/jpeg" => ".jpg",
        "image/png" => ".png",
        "image/gif" => ".gif",
        "image/webp" => ".webp",
        "video/mp4" => ".mp4",
        "audio/aac" => ".aac",
        "audio/mp4" | "audio/m4a" => ".m4a",
        "audio/ogg" => ".ogg",
        "audio/mpeg" => ".mp3",
        "application/pdf" => ".pdf",
        _ => ".bin",
    }
}

fn safe(id: &str) -> String {
    id.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' { c } else { '_' }).collect()
}

fn kind_of(mime: &str) -> &'static str {
    if mime.starts_with("image/") {
        "image"
    } else if mime.starts_with("video/") {
        "video"
    } else if mime.starts_with("audio/") {
        "audio"
    } else {
        "document"
    }
}

impl Engine {
    fn media_path(&self, id: &str, mime: &str) -> std::path::PathBuf {
        self.dir.join("attachments").join(format!("{}{}", safe(id), extension(mime)))
    }

    /// Fetches and decrypts a received attachment (once); returns its path.
    pub(super) async fn download_media(&self, chat: &str, id: &str) -> Result<String, BridgeError> {
        let message = self.db.message(chat, id).await.map_err(fail)?.ok_or_else(|| fail("Message not found"))?;
        let path = self.media_path(id, &message.mime);
        if path.exists() {
            return Ok(path.display().to_string());
        }
        let pointer = self.db.attachment(chat, id).await.map_err(fail)?.ok_or_else(|| fail("This attachment can't be downloaded"))?;
        let pointer = AttachmentPointer::decode(pointer.as_slice()).map_err(fail)?;
        let data = self.manager()?.get_attachment(&pointer).await.map_err(fail)?;
        std::fs::create_dir_all(path.parent().expect("attachments dir")).map_err(fail)?;
        std::fs::write(&path, data).map_err(fail)?;
        Ok(path.display().to_string())
    }

    /// Uploads a file and sends it, with an optional caption.
    pub(super) async fn send_media(
        self: &Rc<Self>,
        chat: &str,
        path: &str,
        mime: &str,
        caption: &str,
        voice_note: bool,
    ) -> Result<String, BridgeError> {
        let thread = thread_of(chat)?;
        let me = self.own_aci().ok_or_else(|| fail("Signal is not linked"))?;
        let data = std::fs::read(path).map_err(fail)?;
        let file_name = std::path::Path::new(path).file_name().map(|n| n.to_string_lossy().to_string());
        let spec = AttachmentSpec {
            content_type: mime.to_string(),
            length: data.len(),
            file_name: file_name.clone(),
            preview: None,
            voice_note: Some(voice_note),
            borderless: Some(false),
            width: None,
            height: None,
            caption: None,
            blur_hash: None,
        };
        let manager = self.manager()?;
        let pointer = manager
            .upload_attachments(vec![(spec, data.clone())])
            .await
            .map_err(fail)?
            .into_iter()
            .next()
            .ok_or_else(|| fail("Upload failed"))?
            .map_err(|e| fail(format!("Upload failed: {e:?}")))?;
        let ts = now_ms();
        let message = DataMessage {
            body: (!caption.is_empty()).then(|| caption.to_string()),
            attachments: vec![pointer.clone()],
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            profile_key: Some(manager.registration_data().profile_key().get_bytes().to_vec()),
            ..Default::default()
        };
        self.deliver(&thread, message, ts).await?;
        let row = MessageRow {
            chat: chat.to_string(),
            id: message_id(ts, &me),
            sender: me,
            from_me: true,
            ts: ts as i64,
            kind: kind_of(mime).into(),
            text: caption.to_string(),
            filename: file_name.unwrap_or_default(),
            mime: mime.to_string(),
            size: data.len() as i64,
            status: "sent".into(),
            ..Default::default()
        };
        self.db.put_message(&row).await.map_err(fail)?;
        self.db.set_attachment(chat, &row.id, &pointer.encode_to_vec()).await.map_err(fail)?;
        // Keep our copy so it opens without downloading it back.
        let local = self.media_path(&row.id, mime);
        if std::fs::create_dir_all(local.parent().expect("attachments dir")).is_ok() {
            let _ = std::fs::write(local, &data);
        }
        self.emit("chats", json!({}));
        Ok(self.message_json(&row, &[]).await.to_string())
    }
}

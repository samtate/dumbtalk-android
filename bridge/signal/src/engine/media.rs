//! Attachments: fetching received ones and sending files.

use super::*;
use presage::libsignal_service::prelude::ProfileKey;
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
    id.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' {
                c
            } else {
                '_'
            }
        })
        .collect()
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
        self.dir
            .join("attachments")
            .join(format!("{}{}", safe(id), extension(mime)))
    }

    /// Returns a photo from the linked phone, profile service, or group service.
    /// presage caches downloaded bytes in its store; this file lets Android decode them.
    pub(super) async fn conversation_avatar(&self, chat: &str) -> Result<String, BridgeError> {
        let thread = thread_of(chat)?;
        let path = self.dir.join("avatars").join(format!("{}.img", safe(chat)));
        if path.exists() {
            return Ok(path.display().to_string());
        }

        let avatar = match thread {
            Thread::Contact(id) => {
                let contact = self.store.contact_by_id(&id).await.map_err(fail)?;
                let contact_key = contact
                    .as_ref()
                    .and_then(|contact| <[u8; 32]>::try_from(contact.profile_key.as_slice()).ok())
                    .map(ProfileKey::create);
                if let Some(bytes) = contact
                    .as_ref()
                    .and_then(|contact| contact.avatar.as_ref())
                    .map(|avatar| avatar.reader.to_vec())
                {
                    Some(bytes)
                } else if let Some(key) =
                    contact_key.or(self.store.profile_key(&id).await.map_err(fail)?)
                {
                    let mut manager = self.manager()?;
                    manager
                        .retrieve_profile_avatar_by_uuid(id.raw_uuid(), key)
                        .await
                        .map_err(fail)?
                } else {
                    None
                }
            }
            Thread::Group(key) => {
                if let Some(bytes) = self.store.group_avatar(key).await.map_err(fail)? {
                    Some(bytes)
                } else {
                    let context = group_context(&key);
                    let mut manager = self.manager()?;
                    manager
                        .retrieve_group_avatar(context)
                        .await
                        .map_err(fail)?
                }
            }
        };
        let Some(bytes) = avatar.filter(|bytes| !bytes.is_empty()) else {
            return Ok(String::new());
        };
        std::fs::create_dir_all(path.parent().expect("avatars dir")).map_err(fail)?;
        std::fs::write(&path, bytes).map_err(fail)?;
        Ok(path.display().to_string())
    }

    /// Fetches and decrypts a received attachment (once); returns its path.
    pub(super) async fn download_media(&self, chat: &str, id: &str) -> Result<String, BridgeError> {
        self.download_attachment(chat, id, 0).await
    }

    pub(super) async fn download_attachment(
        &self,
        chat: &str,
        id: &str,
        index: usize,
    ) -> Result<String, BridgeError> {
        let pointers = self.db.attachments(chat, id).await.map_err(fail)?;
        let bytes = pointers
            .get(index)
            .ok_or_else(|| fail("Attachment not found"))?;
        let pointer = AttachmentPointer::decode(bytes.as_slice()).map_err(fail)?;
        let mime = pointer
            .content_type
            .as_deref()
            .unwrap_or("application/octet-stream");
        let key = if index == 0 {
            id.to_string()
        } else {
            format!("{id}-{index}")
        };
        let path = self.media_path(&key, mime);
        if path.exists() {
            return Ok(path.display().to_string());
        }
        let data = self
            .manager()?
            .get_attachment(&pointer)
            .await
            .map_err(fail)?;
        std::fs::create_dir_all(path.parent().expect("attachments dir")).map_err(fail)?;
        std::fs::write(&path, data).map_err(fail)?;
        Ok(path.display().to_string())
    }

    /// Sends up to ten files (JSON [{path, mime, width?, height?}]) as one
    /// message, as Signal allows, with a caption and optional quote.
    pub(super) async fn send_media_batch(
        self: &Rc<Self>,
        chat: &str,
        files: &str,
        caption: &str,
        quote_id: &str,
    ) -> Result<String, BridgeError> {
        let files: Vec<OutgoingFile> = serde_json::from_str(files).map_err(fail)?;
        if files.is_empty() || files.len() > 10 {
            return Err(fail("Choose between 1 and 10 attachments"));
        }
        self.send_files(chat, &files, caption, quote_id, false).await
    }

    /// Sends one file, with an optional caption; a voice note plays inline.
    pub(super) async fn send_media(
        self: &Rc<Self>,
        chat: &str,
        path: &str,
        mime: &str,
        caption: &str,
        voice_note: bool,
    ) -> Result<String, BridgeError> {
        let file = OutgoingFile { path: path.to_string(), mime: mime.to_string(), width: None, height: None };
        self.send_files(chat, &[file], caption, "", voice_note).await
    }

    /// Shows the files as one message from us at once, then uploads and
    /// sends them in the outbox's one attempt. Our copies are kept: they open
    /// without downloading them back, and a failed upload is retried from them.
    async fn send_files(
        self: &Rc<Self>,
        chat: &str,
        files: &[OutgoingFile],
        caption: &str,
        quote_id: &str,
        voice_note: bool,
    ) -> Result<String, BridgeError> {
        let thread = thread_of(chat)?;
        let me = self.me()?;
        let ts = now_ms();
        let (quoted, quote) = self.quoting(chat, quote_id).await?;
        let first = &files[0];
        let row = MessageRow {
            text: caption.into(),
            mime: first.mime.clone(),
            filename: std::path::Path::new(&first.path).file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default(),
            size: std::fs::metadata(&first.path).map(|m| m.len() as i64).unwrap_or(0),
            ..sent_row(chat, ts, &me, kind_of(&first.mime), quoted.as_ref())
        };
        let mut kept = Vec::new();
        for (index, file) in files.iter().enumerate() {
            let key = if index == 0 { row.id.clone() } else { format!("{}-{index}", row.id) };
            let path = self.media_path(&key, &file.mime);
            std::fs::create_dir_all(path.parent().expect("attachments dir")).map_err(fail)?;
            std::fs::copy(&file.path, &path).map_err(fail)?;
            kept.push(OutgoingFile { path: path.display().to_string(), ..file.clone() });
        }
        let message = DataMessage {
            body: (!caption.is_empty()).then(|| caption.to_string()),
            quote,
            timestamp: Some(ts),
            group_v2: Self::group_context(&thread),
            profile_key: Some(self.profile_key()?),
            ..Default::default()
        };
        self.queue(row, message, &kept, voice_note).await
    }

    /// Uploads files for a message, in order.
    pub(super) async fn upload(&self, files: &[OutgoingFile], voice_note: bool) -> Result<Vec<AttachmentPointer>, BridgeError> {
        let mut uploads = Vec::new();
        for file in files {
            let data = std::fs::read(&file.path).map_err(fail)?;
            let spec = AttachmentSpec {
                content_type: file.mime.clone(),
                length: data.len(),
                file_name: std::path::Path::new(&file.path)
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string()),
                preview: None,
                voice_note: Some(voice_note),
                borderless: Some(false),
                width: file.width,
                height: file.height,
                caption: None,
                blur_hash: None,
            };
            uploads.push((spec, data));
        }
        self.manager()?
            .upload_attachments(uploads)
            .await
            .map_err(fail)?
            .into_iter()
            .map(|p| p.map_err(|e| fail(format!("Upload failed: {e:?}"))))
            .collect()
    }
}

/// A file to send: where it is, its type and, for pictures, its size.
#[derive(Clone, serde::Deserialize, serde::Serialize)]
pub(super) struct OutgoingFile {
    path: String,
    mime: String,
    width: Option<u32>,
    height: Option<u32>,
}

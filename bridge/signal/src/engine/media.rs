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
                    let context = GroupContextV2 {
                        master_key: Some(key.to_vec()),
                        revision: Some(0),
                        ..Default::default()
                    };
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

    /// Signal supports multiple attachments in a single DataMessage.
    pub(super) async fn send_media_batch(
        self: &Rc<Self>,
        chat: &str,
        files: &str,
        caption: &str,
        quote_id: &str,
    ) -> Result<String, BridgeError> {
        #[derive(serde::Deserialize)]
        struct FileSpec {
            path: String,
            mime: String,
            width: Option<u32>,
            height: Option<u32>,
        }
        let files: Vec<FileSpec> = serde_json::from_str(files).map_err(fail)?;
        if files.is_empty() || files.len() > 10 {
            return Err(fail("Choose between 1 and 10 attachments"));
        }
        let thread = thread_of(chat)?;
        let me = self.own_aci().ok_or_else(|| fail("Signal is not linked"))?;
        let mut uploads = Vec::new();
        let mut sizes: Vec<usize> = Vec::new();
        for file in &files {
            let data = std::fs::read(&file.path).map_err(fail)?;
            let spec = AttachmentSpec {
                content_type: file.mime.clone(),
                length: data.len(),
                file_name: std::path::Path::new(&file.path)
                    .file_name()
                    .map(|n| n.to_string_lossy().to_string()),
                preview: None,
                voice_note: Some(false),
                borderless: Some(false),
                width: file.width,
                height: file.height,
                caption: None,
                blur_hash: None,
            };
            sizes.push(data.len());
            uploads.push((spec, data));
        }
        let quoted = if quote_id.is_empty() {
            None
        } else {
            self.db.message(chat, quote_id).await.map_err(fail)?
        };
        let quote = quoted.as_ref().and_then(|q| {
            let (sent, author) = q.id.split_once(':')?;
            Some(Quote {
                id: sent.parse().ok(),
                author_aci: Some(author.to_string()),
                text: Some(q.text.clone()),
                ..Default::default()
            })
        });
        let manager = self.manager()?;
        let pointers: Vec<AttachmentPointer> = manager
            .upload_attachments(uploads)
            .await
            .map_err(fail)?
            .into_iter()
            .map(|p| p.map_err(|e| fail(format!("Upload failed: {e:?}"))))
            .collect::<Result<_, _>>()?;
        let ts = now_ms();
        self.deliver(
            &thread,
            DataMessage {
                body: (!caption.is_empty()).then(|| caption.to_string()),
                attachments: pointers.clone(),
                quote,
                timestamp: Some(ts),
                group_v2: Self::group_context(&thread),
                profile_key: Some(
                    manager
                        .registration_data()
                        .profile_key()
                        .get_bytes()
                        .to_vec(),
                ),
                ..Default::default()
            },
            ts,
        )
        .await?;
        let first = &files[0];
        let row = MessageRow {
            chat: chat.into(),
            id: message_id(ts, &me),
            sender: me,
            from_me: true,
            ts: ts as i64,
            kind: kind_of(&first.mime).into(),
            text: caption.into(),
            mime: first.mime.clone(),
            filename: pointers[0].file_name.clone().unwrap_or_default(),
            size: sizes[0] as i64,
            quote_id: quoted.as_ref().map(|q| q.id.clone()).unwrap_or_default(),
            quote_sender: quoted
                .as_ref()
                .map(|q| q.sender.clone())
                .unwrap_or_default(),
            quote_text: quoted.as_ref().map(|q| q.text.clone()).unwrap_or_default(),
            status: "sent".into(),
            ..Default::default()
        };
        self.db.put_message(&row).await.map_err(fail)?;
        self.db
            .set_attachments(
                chat,
                &row.id,
                &pointers
                    .iter()
                    .map(|p| p.encode_to_vec())
                    .collect::<Vec<_>>(),
            )
            .await
            .map_err(fail)?;
        for (index, file) in files.iter().enumerate() {
            let key = if index == 0 {
                row.id.clone()
            } else {
                format!("{}-{index}", row.id)
            };
            let path = self.media_path(&key, &file.mime);
            std::fs::create_dir_all(path.parent().expect("attachments dir")).map_err(fail)?;
            let _ = std::fs::copy(&file.path, path);
        }
        self.emit("chats", json!({}));
        Ok(self.message_json(&row, &[]).await.to_string())
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
        let file_name = std::path::Path::new(path)
            .file_name()
            .map(|n| n.to_string_lossy().to_string());
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
            profile_key: Some(
                manager
                    .registration_data()
                    .profile_key()
                    .get_bytes()
                    .to_vec(),
            ),
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
        self.db
            .set_attachment(chat, &row.id, &pointer.encode_to_vec())
            .await
            .map_err(fail)?;
        // Keep our copy so it opens without downloading it back.
        let local = self.media_path(&row.id, mime);
        if std::fs::create_dir_all(local.parent().expect("attachments dir")).is_ok() {
            let _ = std::fs::write(local, &data);
        }
        self.emit("chats", json!({}));
        Ok(self.message_json(&row, &[]).await.to_string())
    }
}

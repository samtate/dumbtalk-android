//! Group membership and settings. presage only reads groups, so changes
//! are built here: encrypted with the group's keys, sent to Signal's group
//! server with a zero-knowledge auth credential, then announced to the
//! members in a group message carrying the signed change.

use super::*;
use base64::Engine as _;
use presage::model::groups::Group as StoredGroup;
use presage::libsignal_service::configuration::{Endpoint, ServiceConfiguration};
use presage::libsignal_service::groups_v2::{decrypt_group, AccessControl, AccessRequired, GroupMemberCandidate, GroupOperations, Role, Timer};
use presage::libsignal_service::protocol::{Aci, Pni};
use presage::libsignal_service::push_service::{HttpAuth, HttpAuthOverride, PushService};
use presage::libsignal_service::zkgroup::auth::AuthCredentialWithPniResponse;
use presage::libsignal_service::zkgroup::groups::{GroupMasterKey, GroupSecretParams};
use presage::libsignal_service::zkgroup::profiles::{ExpiringProfileKeyCredential, ExpiringProfileKeyCredentialResponse, ProfileKey};
use presage::libsignal_service::zkgroup::{self, ServerPublicParams, Timestamp};
use presage::proto::group_change::Actions;
use presage::proto::group_change::actions::{ModifyDisappearingMessageTimerAction, ModifyTitleAction};
use presage::proto::{self as proto, GroupChangeResponse};
use reqwest::Method;

const DAY: u64 = 86_400;

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct AuthCredentials {
    credentials: Vec<AuthCredential>,
}

#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct AuthCredential {
    credential: String,
    redemption_time: u64,
}

#[derive(serde::Deserialize)]
struct ProfileCredential {
    credential: Option<String>,
}

fn secret_params(key: &[u8; 32]) -> GroupSecretParams {
    GroupSecretParams::derive_from_master_key(GroupMasterKey::new(*key))
}

fn random_bytes() -> [u8; 32] {
    rand::random()
}

/// The group server's reply, as a readable failure.
async fn checked(response: reqwest::Response, what: &str) -> Result<reqwest::Response, BridgeError> {
    let status = response.status();
    if status.is_success() {
        return Ok(response);
    }
    Err(fail(match status.as_u16() {
        403 => format!("Signal refused to {what}: only group admins can do that"),
        409 => format!("Signal refused to {what}: the group changed meanwhile, try again"),
        code => format!("Signal refused to {what} ({code})"),
    }))
}

impl Engine {
    fn server_params(&self) -> Result<ServerPublicParams, BridgeError> {
        let manager = self.manager()?;
        Ok(ServiceConfiguration::from(manager.registration_data().signal_servers).zkgroup_server_public_params)
    }

    fn push(&self) -> Result<PushService, BridgeError> {
        crate::storage::push_service(self.manager()?.registration_data()).map_err(fail)
    }

    fn own_ids(&self) -> Result<(Aci, Pni), BridgeError> {
        let manager = self.manager()?;
        let ids = &manager.registration_data().service_ids;
        Ok((ids.aci(), ids.pni()))
    }

    /// The group server's basic auth for one group: a presentation of
    /// today's auth credential, which proves membership without naming us.
    async fn group_auth(&self, params: &GroupSecretParams) -> Result<HttpAuth, BridgeError> {
        let now = now_ms() / 1000;
        let today = now - now % DAY;
        let path = format!(
            "/v1/certificate/auth/group?redemptionStartSeconds={today}&redemptionEndSeconds={}&pniAsServiceId=true",
            today + 7 * DAY
        );
        let response = self.push()?.request(Method::GET, Endpoint::service(path), HttpAuthOverride::NoOverride).map_err(fail)?
            .send().await.map_err(fail)?;
        let credentials: AuthCredentials = checked(response, "authorise the group change").await?.json().await.map_err(fail)?;
        let credential = credentials.credentials.into_iter().find(|c| c.redemption_time == today)
            .ok_or_else(|| fail("Signal sent no group credential for today"))?;
        let bytes = base64::engine::general_purpose::STANDARD.decode(credential.credential).map_err(fail)?;
        let response = AuthCredentialWithPniResponse::new(&bytes).map_err(fail)?;
        let server = self.server_params()?;
        let (aci, pni) = self.own_ids()?;
        let credential = response.receive(&server, aci, pni, Timestamp::from_epoch_seconds(today)).map_err(fail)?;
        let presentation = credential.present(&server, params, random_bytes());
        Ok(HttpAuth {
            username: hex::encode(zkgroup::serialize(&params.get_public_params())),
            password: hex::encode(zkgroup::serialize(&presentation)),
        })
    }

    /// A member's expiring profile key credential, which adding them to a
    /// group needs. None when we lack their profile key or the server won't say.
    async fn profile_credential(&self, who: Uuid) -> Option<ExpiringProfileKeyCredential> {
        let id = ServiceId::Aci(who.into());
        let key: ProfileKey = self.store.profile_key(&id).await.ok().flatten()?;
        let aci = Aci::from(who);
        let server = self.server_params().ok()?;
        let context = server.create_profile_key_credential_request_context(random_bytes(), aci, key);
        let request = hex::encode(zkgroup::serialize(&context.get_request()));
        let version = key.get_profile_key_version(aci);
        let path = format!("/v1/profile/{who}/{}/{request}?credentialType=expiringProfileKey", version.as_ref());
        let response = self.push().ok()?.request(Method::GET, Endpoint::service(path), HttpAuthOverride::NoOverride).ok()?
            .send().await.ok()?;
        let body: ProfileCredential = checked(response, "share a profile").await.ok()?.json().await.ok()?;
        let bytes = base64::engine::general_purpose::STANDARD.decode(body.credential?).ok()?;
        let response: ExpiringProfileKeyCredentialResponse = zkgroup::deserialize(&bytes).ok()?;
        server.receive_expiring_profile_key_credential(&context, &response, Timestamp::from_epoch_seconds(now_ms() / 1000)).ok()
    }

    /// The group as the server has it now, saved over presage's copy.
    async fn refresh_group(&self, key: &[u8; 32]) -> Result<StoredGroup, BridgeError> {
        let params = secret_params(key);
        let auth = self.group_auth(&params).await?;
        let response = self.push()?.request(Method::GET, Endpoint::storage("/v1/groups/"), HttpAuthOverride::Identified(auth)).map_err(fail)?
            .send().await.map_err(fail)?;
        let bytes = checked(response, "fetch the group").await?.bytes().await.map_err(fail)?;
        let encrypted = proto::Group::decode(bytes.as_ref()).map_err(fail)?;
        let group = decrypt_group(key, encrypted).map_err(fail)?;
        self.store.save_group(*key, group.clone()).await.map_err(fail)?;
        Ok(group.into())
    }

    /// Applies `build`'s actions at the next revision, then tells the members.
    async fn change_group(
        self: &Rc<Self>,
        chat: &str,
        what: &str,
        build: impl FnOnce(&GroupOperations, &StoredGroup) -> Result<Actions, BridgeError>,
    ) -> Result<(), BridgeError> {
        let Thread::Group(key) = thread_of(chat)? else { return Err(fail("Not a group")) };
        let params = secret_params(&key);
        let operations = GroupOperations::new(params);
        let group = self.refresh_group(&key).await?;
        let mut actions = build(&operations, &group)?;
        actions.version = group.revision + 1;
        let auth = self.group_auth(&params).await?;
        let response = self.push()?.request(Method::PATCH, Endpoint::storage("/v1/groups/"), HttpAuthOverride::Identified(auth)).map_err(fail)?
            .header("Content-Type", "application/x-protobuf")
            .body(actions.encode_to_vec())
            .send().await.map_err(fail)?;
        let bytes = checked(response, what).await?.bytes().await.map_err(fail)?;
        let signed = GroupChangeResponse::decode(bytes.as_ref()).ok().and_then(|r| r.group_change)
            .or_else(|| proto::GroupChange::decode(bytes.as_ref()).ok())
            .ok_or_else(|| fail("Signal's reply to the group change was unreadable"))?;
        // Members (including any just removed) learn of it from a message
        // carrying the signed change, sent before our copy moves on.
        let ts = now_ms();
        let notice = DataMessage {
            group_v2: Some(GroupContextV2 { master_key: Some(key.to_vec()), revision: Some(actions.version), group_change: Some(signed.encode_to_vec()) }),
            timestamp: Some(ts),
            ..Default::default()
        };
        if let Err(error) = self.deliver(&Thread::Group(key), notice, ts).await {
            self.log(format!("group change notice failed: {error}"));
        }
        if let Err(error) = self.refresh_group(&key).await {
            self.log(format!("group refresh failed: {error}"));
        }
        self.emit("chats", json!({}));
        self.emit("messages", json!({ "chat": chat }));
        Ok(())
    }

    /// A line in the chat about a change we made.
    async fn note(&self, chat: &str, text: String) -> Result<(), BridgeError> {
        let ts = now_ms();
        let row = MessageRow { chat: chat.into(), id: format!("group:{ts}"), ts: ts as i64, kind: "system".into(), text, ..Default::default() };
        self.db.put_message(&row).await.map_err(fail)
    }

    pub(super) async fn set_group_timer(self: &Rc<Self>, chat: &str, _key: [u8; 32], seconds: u32) -> Result<(), BridgeError> {
        self.change_group(chat, "change the timer", |operations, _| {
            let timer = (seconds > 0).then_some(Timer { duration: seconds });
            Ok(Actions {
                modify_disappearing_message_timer: Some(ModifyDisappearingMessageTimerAction {
                    timer: operations.encrypt_disappearing_messages_timer(timer.as_ref(), &mut rand::rng()),
                }),
                ..Default::default()
            })
        }).await?;
        self.db.set_chat_timer(chat, seconds, 0, true).await.map_err(fail)?;
        self.note_timer(chat, "You", seconds, now_ms()).await
    }

    pub(super) async fn rename_group(self: &Rc<Self>, chat: &str, name: &str) -> Result<(), BridgeError> {
        let name = name.trim();
        if name.is_empty() {
            return Err(fail("A group needs a name"));
        }
        self.change_group(chat, "rename the group", |operations, _| Ok(Actions {
            modify_title: Some(ModifyTitleAction { title: operations.encrypt_title(name, &mut rand::rng()) }),
            ..Default::default()
        })).await?;
        self.note(chat, format!("You renamed the group to “{name}”")).await
    }

    /// Adds people by chat id ("c:<aci>"). Those whose profile we can't
    /// prove are invited instead, as Signal does.
    pub(super) async fn add_members(self: &Rc<Self>, chat: &str, members_json: &str) -> Result<(), BridgeError> {
        let ids = Self::member_ids(members_json)?;
        let mut candidates = Vec::new();
        for id in &ids {
            candidates.push((*id, self.profile_credential(*id).await));
        }
        let server = self.server_params()?;
        let (me, _) = self.own_ids()?;
        self.change_group(chat, "add members", |operations, group| {
            let mut actions = Actions::default();
            for (id, credential) in &candidates {
                if group.members.iter().any(|m| Uuid::from(m.aci) == *id) {
                    continue;
                }
                match credential {
                    Some(credential) => actions.add_members.push(operations.build_add_member_action_with_credential(credential, Role::Default, &server)),
                    None => actions.add_members_pending_profile_key.push(
                        operations.build_add_pending_member_action(ServiceId::Aci((*id).into()), me, Role::Default).map_err(fail)?,
                    ),
                }
            }
            if actions.add_members.is_empty() && actions.add_members_pending_profile_key.is_empty() {
                return Err(fail("They're already in the group"));
            }
            Ok(actions)
        }).await?;
        let mut names = Vec::new();
        for id in &ids {
            names.push(self.name_for(&id.to_string()).await);
        }
        self.note(chat, format!("You added {}", names.join(", "))).await
    }

    pub(super) async fn remove_member(self: &Rc<Self>, chat: &str, member: &str) -> Result<(), BridgeError> {
        let who = Uuid::parse_str(member.trim_start_matches("c:")).map_err(fail)?;
        self.change_group(chat, "remove them", |operations, group| {
            let mut actions = Actions::default();
            if group.members.iter().any(|m| Uuid::from(m.aci) == who) {
                actions.delete_members.push(operations.build_remove_member_action(who.into()).map_err(fail)?);
            } else {
                actions.delete_members_pending_profile_key.push(operations.build_remove_pending_member_action(ServiceId::Aci(who.into())).map_err(fail)?);
            }
            Ok(actions)
        }).await?;
        let name = self.name_for(&who.to_string()).await;
        self.note(chat, format!("You removed {name}")).await
    }

    pub(super) async fn leave_group(self: &Rc<Self>, chat: &str) -> Result<(), BridgeError> {
        let (me, _) = self.own_ids()?;
        self.change_group(chat, "leave the group", |operations, group| {
            let admins = group.members.iter().filter(|m| m.role == Role::Administrator).count();
            let mine = group.members.iter().find(|m| m.aci == me);
            if mine.is_some_and(|m| m.role == Role::Administrator) && admins == 1 && group.members.len() > 1 {
                return Err(fail("You're the only admin: make someone else an admin on your main phone first"));
            }
            Ok(Actions { delete_members: vec![operations.build_remove_member_action(me).map_err(fail)?], ..Default::default() })
        }).await?;
        self.note(chat, "You left the group".into()).await?;
        self.db.set_flag(chat, "archived", 1).await.map_err(fail)?;
        self.emit("chats", json!({}));
        Ok(())
    }

    /// Creates a group with us as admin; returns its chat id.
    pub(super) async fn create_group(self: &Rc<Self>, name: &str, members_json: &str) -> Result<String, BridgeError> {
        let name = name.trim();
        if name.is_empty() {
            return Err(fail("A group needs a name"));
        }
        let ids = Self::member_ids(members_json)?;
        let (me, _) = self.own_ids()?;
        let own = self.profile_credential(Uuid::from(me)).await
            .ok_or_else(|| fail("Signal couldn't confirm your own profile; try again once connected"))?;
        let mut candidates = Vec::new();
        for id in &ids {
            candidates.push(GroupMemberCandidate { service_id: ServiceId::Aci((*id).into()), credential: self.profile_credential(*id).await });
        }
        let key: [u8; 32] = random_bytes();
        let params = secret_params(&key);
        let operations = GroupOperations::new(params);
        let server = self.server_params()?;
        // Signal's defaults: members may edit and add, invite links are off.
        let access = AccessControl {
            attributes: AccessRequired::Member,
            members: AccessRequired::Member,
            add_from_invite_link: AccessRequired::Unsatisfiable,
            member_label: AccessRequired::Member,
        };
        let group = operations
            .encrypt_group_with_credentials(name, None, None, Some(&access), &own, &candidates, &server, String::new(), &mut rand::rng())
            .map_err(fail)?;
        let auth = self.group_auth(&params).await?;
        let response = self.push()?.request(Method::PUT, Endpoint::storage("/v1/groups/"), HttpAuthOverride::Identified(auth)).map_err(fail)?
            .header("Content-Type", "application/x-protobuf")
            .body(group.encode_to_vec())
            .send().await.map_err(fail)?;
        checked(response, "create the group").await?;
        self.refresh_group(&key).await?;
        let chat = format!("g:{}", hex::encode(key));
        self.db.ensure_chat(&chat, now_ms() as i64).await.map_err(fail)?;
        self.db.set_request_state(&chat, crate::db::REQUEST_ACCEPTED).await.map_err(fail)?;
        // Members see the group once a message reaches them.
        let ts = now_ms();
        let hello = DataMessage {
            group_v2: Some(GroupContextV2 { master_key: Some(key.to_vec()), revision: Some(0), ..Default::default() }),
            timestamp: Some(ts),
            ..Default::default()
        };
        if let Err(error) = self.deliver(&Thread::Group(key), hello, ts).await {
            self.log(format!("new group notice failed: {error}"));
        }
        self.note(&chat, "You created the group".into()).await?;
        self.emit("chats", json!({}));
        Ok(chat)
    }

    fn member_ids(members_json: &str) -> Result<Vec<Uuid>, BridgeError> {
        let ids: Vec<String> = serde_json::from_str(members_json).map_err(fail)?;
        let ids: Vec<Uuid> = ids.iter().filter_map(|id| Uuid::parse_str(id.trim_start_matches("c:")).ok()).collect();
        if ids.is_empty() {
            return Err(fail("Choose at least one person"));
        }
        Ok(ids)
    }

    /// The people in a chat besides us, for mentions and group info:
    /// [{"id": ACI, "name", "admin", "pending"}], by name.
    pub(super) async fn members(self: &Rc<Self>, chat: &str) -> Result<String, BridgeError> {
        let me = self.own_aci().unwrap_or_default();
        let mut out = Vec::new();
        match thread_of(chat)? {
            Thread::Contact(id) => {
                let aci = id.raw_uuid().to_string();
                if aci != me {
                    out.push(json!({ "id": aci, "name": self.name_for(&aci).await, "admin": false, "pending": false }));
                }
            }
            Thread::Group(key) => {
                let group = match self.store.group(key).await.map_err(fail)? {
                    Some(group) => group,
                    None => self.refresh_group(&key).await?,
                };
                for member in &group.members {
                    let aci = Uuid::from(member.aci).to_string();
                    if aci == me {
                        continue;
                    }
                    out.push(json!({ "id": aci, "name": self.name_for(&aci).await, "admin": member.role == Role::Administrator, "pending": false }));
                }
                for pending in &group.pending_members {
                    let aci = pending.uuid.to_string();
                    out.push(json!({ "id": aci, "name": self.name_for(&aci).await, "admin": false, "pending": true }));
                }
            }
        }
        out.sort_by_key(|m| m["name"].as_str().unwrap_or_default().to_lowercase());
        Ok(Value::Array(out).to_string())
    }

    /// Whether we're an admin of the group (who may change members).
    pub(super) async fn is_group_admin(&self, key: [u8; 32]) -> bool {
        let Some(me) = self.own_aci() else { return false };
        matches!(self.store.group(key).await, Ok(Some(group)) if group.members.iter()
            .any(|m| Uuid::from(m.aci).to_string() == me && m.role == Role::Administrator))
    }
}

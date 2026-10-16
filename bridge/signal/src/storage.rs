//! Reads Signal's storage service: the encrypted, server-held account state
//! (contacts, groups, pins) that modern primaries share with linked devices
//! instead of contact sync. A fresh linked device has no messages, so this is
//! where its chat list comes from.

use presage::libsignal_service::configuration::{ServiceCredentials, SignalServers};
use presage::libsignal_service::master_key::{MasterKey, StorageServiceKey};
use presage::libsignal_service::prelude::phonenumber::PhoneNumber;
use presage::libsignal_service::prelude::Uuid;
use presage::libsignal_service::utils::TryIntoE164;
use presage::libsignal_service::proto::account_record::pinned_conversation::Identifier as Pinned;
use presage::libsignal_service::proto::manifest_record::identifier::Type as ItemType;
use presage::libsignal_service::proto::storage_record::Record;
use presage::libsignal_service::push_service::PushService;
use presage::libsignal_service::StorageService;
use presage::manager::RegistrationData;
use presage::store::StateStore;
use presage_store_sqlite::SqliteStore;
use serde_json::Value;
use std::collections::HashSet;

/// What the storage service says about one conversation.
pub struct StoredChat {
    pub chat: String,
    pub name: Option<String>,
    pub archived: bool,
    pub muted: bool,
    pub unread: bool,
    pub pinned: bool,
    /// For groups: the master key, so the group's details can be fetched.
    pub group_key: Option<[u8; 32]>,
}

/// Newer clients store service IDs only as 16 raw bytes; older ones as text.
fn service_id(text: &str, binary: &[u8]) -> Option<String> {
    if !text.is_empty() {
        return Some(text.to_string());
    }
    Uuid::from_slice(binary).ok().map(|uuid| uuid.to_string())
}

fn joined(given: &str, family: &str) -> Option<String> {
    let name = format!("{given} {family}").trim().to_string();
    (!name.is_empty()).then_some(name)
}

/// presage keeps its credentials private, but `RegistrationData` serialises
/// them; rebuild an authenticated push service from that.
fn push_service(data: &RegistrationData) -> Result<PushService, String> {
    let value = serde_json::to_value(data).map_err(|e| e.to_string())?;
    let uuid = |key: &str| value.get(key).and_then(Value::as_str).and_then(|s| Uuid::parse_str(s).ok());
    let password = value.get("password").and_then(Value::as_str).ok_or("no stored password")?.to_string();
    let phone: &PhoneNumber = &data.phone_number;
    let credentials = ServiceCredentials {
        aci: uuid("uuid"),
        pni: uuid("pni"),
        phonenumber: phone.try_into_e164().map_err(|e| format!("{e:?}"))?,
        password: Some(password),
        device_id: data.device_id.and_then(|d| d.try_into().ok()),
    };
    let servers: SignalServers = data.signal_servers;
    Ok(PushService::new(servers, Some(credentials), "presage-rs-dumbtalk"))
}

async fn master_key(store: &SqliteStore) -> Result<MasterKey, String> {
    if let Ok(Some(key)) = store.fetch_master_key().await {
        return Ok(key);
    }
    let pool = store.fetch_account_entropy_pool().await.map_err(|e| e.to_string())?.ok_or("no account key")?;
    MasterKey::from_slice(&pool.derive_svr_key()).map_err(|e| format!("{e:?}"))
}

pub async fn read(store: &SqliteStore, data: &RegistrationData) -> Result<Vec<StoredChat>, String> {
    let key = StorageServiceKey::from_master_key(&master_key(store).await?);
    let storage = StorageService::new(push_service(data)?, key).await.map_err(|e| e.to_string())?;
    let manifest = storage.manifest().await.map_err(|e| e.to_string())?;
    let wanted = [ItemType::Contact, ItemType::Groupv2, ItemType::Account];
    let keys: Vec<Vec<u8>> = manifest
        .identifiers
        .iter()
        .filter(|id| ItemType::try_from(id.r#type).is_ok_and(|t| wanted.contains(&t)))
        .map(|id| id.raw.clone())
        .collect();
    let ikm = (!manifest.record_ikm.is_empty()).then_some(manifest.record_ikm.as_slice());

    let mut records = Vec::new();
    for batch in keys.chunks(100) {
        records.extend(storage.read_items(batch.to_vec(), ikm).await.map_err(|e| e.to_string())?);
    }

    let mut pinned_contacts = HashSet::new();
    let mut pinned_groups = HashSet::new();
    for record in &records {
        if let Some(Record::Account(account)) = &record.record {
            for pin in &account.pinned_conversations {
                match &pin.identifier {
                    Some(Pinned::Contact(contact)) => {
                        if let Some(id) = service_id(&contact.service_id, &contact.service_id_binary) {
                            pinned_contacts.insert(id);
                        }
                    }
                    Some(Pinned::GroupMasterKey(key)) => {
                        pinned_groups.insert(key.clone());
                    }
                    _ => {}
                }
            }
        }
    }

    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    let mut chats = Vec::new();
    for record in records {
        match record.record {
            Some(Record::Contact(contact)) => {
                // Contacts you've chatted with or accepted; skip hidden and blocked ones.
                let Some(aci) = service_id(&contact.aci, &contact.aci_binary) else { continue };
                if !contact.whitelisted || contact.hidden || contact.blocked {
                    continue;
                }
                let nickname = contact.nickname.as_ref().and_then(|n| joined(&n.given, &n.family));
                let name = nickname
                    .or_else(|| (!contact.system_nickname.is_empty()).then(|| contact.system_nickname.clone()))
                    .or_else(|| joined(&contact.system_given_name, &contact.system_family_name))
                    .or_else(|| joined(&contact.given_name, &contact.family_name))
                    .or_else(|| (!contact.e164.is_empty()).then(|| contact.e164.clone()));
                chats.push(StoredChat {
                    pinned: pinned_contacts.contains(&aci),
                    chat: format!("c:{aci}"),
                    name,
                    archived: contact.archived,
                    muted: contact.muted_until_timestamp > now,
                    unread: contact.marked_unread,
                    group_key: None,
                });
            }
            Some(Record::GroupV2(group)) => {
                let Ok(key) = <[u8; 32]>::try_from(group.master_key.as_slice()) else { continue };
                if group.blocked {
                    continue;
                }
                chats.push(StoredChat {
                    pinned: pinned_groups.contains(&group.master_key),
                    chat: format!("g:{}", hex::encode(key)),
                    name: None,
                    archived: group.archived,
                    muted: group.muted_until_timestamp > now,
                    unread: group.marked_unread,
                    group_key: Some(key),
                });
            }
            _ => {}
        }
    }
    Ok(chats)
}

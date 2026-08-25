use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use ring::rand::{SecureRandom, SystemRandom};
use ring::signature::{Ed25519KeyPair, KeyPair as _};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::app_config::config_dir;

const IDENTITY_VERSION: u32 = 1;
const PAIRED_DEVICES_VERSION: u32 = 1;
const ED25519_SEED_LEN: usize = 32;

/// identity.json: this server's long-lived Ed25519 identity key.
pub fn identity_path() -> PathBuf {
    config_dir().join("identity.json")
}

/// paired_devices.json: clients allowed to stream to this server.
pub fn paired_devices_path() -> PathBuf {
    config_dir().join("paired_devices.json")
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
struct IdentityFile {
    version: u32,
    #[serde(alias = "ed25519_secret_b64")]
    ed25519_secret_b64: String,
}

/// Server-side Ed25519 identity used to sign secure-handshake transcripts.
///
/// The key is stored as the raw 32-byte Ed25519 seed (base64), not PKCS8,
/// so the same seed loads identically on every platform ring supports.
pub struct ServerIdentity {
    key_pair: Ed25519KeyPair,
    seed: [u8; ED25519_SEED_LEN],
    public_key: Vec<u8>,
}

impl ServerIdentity {
    /// Load the identity from the shared config dir, creating it on first use.
    pub fn load_or_create() -> Result<Self, String> {
        Self::load_or_create_in(&config_dir())
    }

    pub fn load_or_create_in(dir: &Path) -> Result<Self, String> {
        let path = dir.join("identity.json");
        if let Ok(text) = fs::read_to_string(&path) {
            let file: IdentityFile = serde_json::from_str(&text)
                .map_err(|e| format!("parse {} failed: {e}", path.display()))?;
            if file.ed25519_secret_b64.is_empty() {
                return Err(format!("{} has an empty seed", path.display()));
            }
            let seed = BASE64
                .decode(file.ed25519_secret_b64.as_bytes())
                .map_err(|e| format!("decode identity seed failed: {e}"))?;
            return Self::from_seed(&seed);
        }

        fs::create_dir_all(dir).map_err(|e| format!("create config dir failed: {e}"))?;
        let identity = Self::generate()?;
        let file = IdentityFile {
            version: IDENTITY_VERSION,
            ed25519_secret_b64: BASE64.encode(identity.seed),
        };
        let json =
            serde_json::to_string_pretty(&file).map_err(|e| format!("serialize identity failed: {e}"))?;
        write_private_file(&path, &json)?;
        Ok(identity)
    }

    pub fn generate() -> Result<Self, String> {
        let rng = SystemRandom::new();
        let mut seed = [0u8; ED25519_SEED_LEN];
        rng.fill(&mut seed)
            .map_err(|_| "generate identity seed failed".to_string())?;
        Self::from_seed(&seed)
    }

    pub fn from_seed(seed: &[u8]) -> Result<Self, String> {
        if seed.len() != ED25519_SEED_LEN {
            return Err(format!(
                "ed25519 seed must be {ED25519_SEED_LEN} bytes, got {}",
                seed.len()
            ));
        }
        let mut owned = [0u8; ED25519_SEED_LEN];
        owned.copy_from_slice(seed);
        let key_pair = Ed25519KeyPair::from_seed_unchecked(&owned)
            .map_err(|e| format!("load ed25519 key pair failed: {e}"))?;
        Ok(Self {
            public_key: key_pair.public_key().as_ref().to_vec(),
            key_pair,
            seed: owned,
        })
    }

    pub fn public_key(&self) -> &[u8] {
        &self.public_key
    }

    pub fn public_key_b64(&self) -> String {
        BASE64.encode(self.public_key.as_slice())
    }

    pub fn sign(&self, msg: &[u8]) -> Vec<u8> {
        self.key_pair.sign(msg).as_ref().to_vec()
    }
}

#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct PairedDevice {
    pub pubkey_b64: String,
    pub name: String,
    pub paired_at_ms: i64,
    pub last_seen_ms: i64,
}

#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
struct PairedDevicesFile {
    version: u32,
    devices: Vec<PairedDevice>,
}

/// In-memory view of paired_devices.json; mutations are persisted immediately.
pub struct PairedDeviceStore {
    path: PathBuf,
    devices: Vec<PairedDevice>,
}

impl PairedDeviceStore {
    pub fn load_or_create(path: PathBuf) -> Result<Self, String> {
        match fs::read_to_string(&path) {
            Ok(text) => {
                let file: PairedDevicesFile = serde_json::from_str(&text)
                    .map_err(|e| format!("parse {} failed: {e}", path.display()))?;
                Ok(Self {
                    path,
                    devices: file.devices,
                })
            }
            Err(_) => {
                if let Some(parent) = path.parent() {
                    fs::create_dir_all(parent)
                        .map_err(|e| format!("create config dir failed: {e}"))?;
                }
                let store = Self {
                    path,
                    devices: Vec::new(),
                };
                store.save()?;
                Ok(store)
            }
        }
    }

    pub fn find(&self, pubkey_b64: &str) -> Option<&PairedDevice> {
        self.devices.iter().find(|d| d.pubkey_b64 == pubkey_b64)
    }

    /// Insert or rename a device and refresh its timestamps. Rolls back on save failure.
    pub fn upsert(&mut self, name: &str, pubkey_b64: &str) -> Result<(), String> {
        let now = now_ms();
        let previous = self.find(pubkey_b64).cloned();
        match self.devices.iter_mut().find(|d| d.pubkey_b64 == pubkey_b64) {
            Some(device) => {
                device.name = name.to_string();
                device.last_seen_ms = now;
            }
            None => self.devices.push(PairedDevice {
                pubkey_b64: pubkey_b64.to_string(),
                name: name.to_string(),
                paired_at_ms: now,
                last_seen_ms: now,
            }),
        }
        if let Err(e) = self.save() {
            restore(&mut self.devices, previous, pubkey_b64);
            return Err(e);
        }
        Ok(())
    }

    /// Refresh last_seen for a known device. Persisting a liveness timestamp is
    /// best-effort; returns false when the device was never paired.
    pub fn touch(&mut self, pubkey_b64: &str) -> bool {
        match self.devices.iter_mut().find(|d| d.pubkey_b64 == pubkey_b64) {
            Some(device) => {
                device.last_seen_ms = now_ms();
                let _ = self.save();
                true
            }
            None => false,
        }
    }

    /// Remove a device. Returns Ok(false) when absent; rolls back on save failure.
    pub fn remove(&mut self, pubkey_b64: &str) -> Result<bool, String> {
        let previous = self.find(pubkey_b64).cloned();
        let Some(previous) = previous else {
            return Ok(false);
        };
        let original_len = self.devices.len();
        self.devices.retain(|d| d.pubkey_b64 != pubkey_b64);
        if let Err(e) = self.save() {
            self.devices.insert(original_len - 1, previous);
            return Err(e);
        }
        Ok(true)
    }

    pub fn list(&self) -> &[PairedDevice] {
        &self.devices
    }

    fn save(&self) -> Result<(), String> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("create config dir failed: {e}"))?;
        }
        let file = PairedDevicesFile {
            version: PAIRED_DEVICES_VERSION,
            devices: self.devices.clone(),
        };
        let json = serde_json::to_string_pretty(&file)
            .map_err(|e| format!("serialize paired devices failed: {e}"))?;
        fs::write(&self.path, json).map_err(|e| format!("write paired devices failed: {e}"))
    }
}

fn restore(devices: &mut Vec<PairedDevice>, previous: Option<PairedDevice>, pubkey_b64: &str) {
    match previous {
        Some(device) => {
            if let Some(slot) = devices.iter_mut().find(|d| d.pubkey_b64 == pubkey_b64) {
                *slot = device;
            }
        }
        None => devices.retain(|d| d.pubkey_b64 != pubkey_b64),
    }
}

fn write_private_file(path: &Path, contents: &str) -> Result<(), String> {
    #[cfg(unix)]
    {
        use std::io::Write;
        use std::os::unix::fs::OpenOptionsExt;
        let mut file = fs::OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .open(path)
            .map_err(|e| format!("open {} failed: {e}", path.display()))?;
        file.write_all(contents.as_bytes())
            .map_err(|e| format!("write {} failed: {e}", path.display()))
    }
    #[cfg(not(unix))]
    {
        fs::write(path, contents).map_err(|e| format!("write {} failed: {e}", path.display()))
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ring::signature::{UnparsedPublicKey, ED25519};
    use std::sync::atomic::{AtomicU64, Ordering};

    struct TempDir(PathBuf);

    impl TempDir {
        fn new(label: &str) -> Self {
            static COUNTER: AtomicU64 = AtomicU64::new(0);
            let unique = COUNTER.fetch_add(1, Ordering::Relaxed);
            let nanos = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let dir = std::env::temp_dir().join(format!(
                "micyou-security-test-{}-{label}-{nanos}-{unique}",
                std::process::id()
            ));
            fs::create_dir_all(&dir).expect("create temp dir");
            TempDir(dir)
        }
    }

    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn identity_roundtrips_through_disk_with_same_public_key() {
        let tmp = TempDir::new("identity");
        let first = ServerIdentity::load_or_create_in(&tmp.0).unwrap();
        let second = ServerIdentity::load_or_create_in(&tmp.0).unwrap();
        assert_eq!(first.public_key(), second.public_key());
        assert_eq!(first.public_key().len(), ED25519_SEED_LEN);

        let text = fs::read_to_string(tmp.0.join("identity.json")).unwrap();
        let file: IdentityFile = serde_json::from_str(&text).unwrap();
        assert_eq!(file.version, IDENTITY_VERSION);
        assert_eq!(BASE64.decode(&file.ed25519_secret_b64).unwrap().len(), 32);
    }

    #[test]
    fn snake_case_identity_file_spelling_is_accepted() {
        let tmp = TempDir::new("identity-alias");
        let json = format!(
            r#"{{"version":1,"ed25519_secret_b64":"{}"}}"#,
            BASE64.encode([9u8; ED25519_SEED_LEN])
        );
        fs::write(tmp.0.join("identity.json"), json).unwrap();

        let loaded = ServerIdentity::load_or_create_in(&tmp.0).unwrap();
        let expected = ServerIdentity::from_seed(&[9u8; ED25519_SEED_LEN]).unwrap();
        assert_eq!(loaded.public_key(), expected.public_key());
    }

    #[test]
    fn identity_signatures_verify_and_tampering_breaks_them() {
        let identity = ServerIdentity::from_seed(&[7u8; ED25519_SEED_LEN]).unwrap();
        let msg = b"micyou identity test";
        let sig = identity.sign(msg);
        assert_eq!(sig.len(), 64);

        let verifier = UnparsedPublicKey::new(&ED25519, identity.public_key());
        assert!(verifier.verify(msg, &sig).is_ok());
        let mut tampered = sig.clone();
        tampered[0] ^= 1;
        assert!(verifier.verify(msg, &tampered).is_err());

        let other = ServerIdentity::generate().unwrap();
        assert_ne!(identity.public_key(), other.public_key());
    }

    #[test]
    fn corrupt_identity_seed_is_rejected_not_panicked_on() {
        let tmp = TempDir::new("identity-corrupt");
        fs::write(tmp.0.join("identity.json"), r#"{"version":1,"ed25519SecretB64":"!!!"}"#)
            .unwrap();
        assert!(ServerIdentity::load_or_create_in(&tmp.0).is_err());
        fs::write(
            tmp.0.join("identity.json"),
            format!(
                r#"{{"version":1,"ed25519SecretB64":"{}"}}"#,
                BASE64.encode([1u8; 16])
            ),
        )
        .unwrap();
        assert!(ServerIdentity::load_or_create_in(&tmp.0).is_err());
    }

    #[test]
    fn paired_device_store_roundtrip_upsert_touch_remove() {
        let tmp = TempDir::new("paired");
        let path = tmp.0.join("paired_devices.json");

        let mut store = PairedDeviceStore::load_or_create(path.clone()).unwrap();
        assert!(store.list().is_empty());

        store.upsert("Pixel", "AAA=").unwrap();
        store.upsert("Book", "BBB=").unwrap();
        store.upsert("Pixel-renamed", "AAA=").unwrap();
        assert_eq!(store.list().len(), 2);
        assert_eq!(store.find("AAA=").unwrap().name, "Pixel-renamed");

        let first = store.find("AAA=").unwrap().clone();
        assert!(first.paired_at_ms <= first.last_seen_ms);
        assert!(store.touch("AAA="));
        assert!(!store.touch("CCC="));
        let touched = store.find("AAA=").unwrap().clone();
        assert!(touched.last_seen_ms >= first.last_seen_ms);

        let mut reloaded = PairedDeviceStore::load_or_create(path.clone()).unwrap();
        assert_eq!(reloaded.list().len(), 2);
        assert_eq!(reloaded.find("BBB=").unwrap().name, "Book");

        assert!(reloaded.remove("AAA=").unwrap());
        assert!(!reloaded.remove("AAA=").unwrap());
        assert!(reloaded.find("AAA=").is_none());

        let after_remove = PairedDeviceStore::load_or_create(path).unwrap();
        assert_eq!(after_remove.list().len(), 1);
        assert!(after_remove.find("BBB=").is_some());
    }
}

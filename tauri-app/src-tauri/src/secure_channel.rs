use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use micyou_protocol::micyou::{SecureClientHello, SecureServerHello};
use micyou_protocol::{SECURE_SUITE_V1, SECURE_TRANSCRIPT_TAG, UDP_SECURE_MAGIC};
use prost::Message as _;
use ring::aead::{Aad, LessSafeKey, Nonce, UnboundKey, AES_256_GCM, NONCE_LEN};
use ring::agreement::{self, EphemeralPrivateKey};
use ring::digest;
use ring::hkdf::{self, Salt, HKDF_SHA256};
use ring::rand::SystemRandom;
use ring::signature;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use crate::security::{PairedDeviceStore, ServerIdentity};

const KEYS_INFO: &[u8] = b"MICYOU-KEYS-V1";
const SAS_INFO: &[u8] = b"MICYOU-SAS-V1";
const MASTER_SECRET_LEN: usize = 104;
const SYMMETRIC_KEY_LEN: usize = 32;
const UDP_NONCE_PREFIX_LEN: usize = 4;
const AEAD_TAG_LEN: usize = 16;
const X25519_KEY_LEN: usize = 32;
const ED25519_PUBLIC_KEY_LEN: usize = 32;
const UDP_HEADER_LEN: usize = 4 + 8;
const UDP_REPLAY_WINDOW: u64 = 2048;
const UDP_REPLAY_WORDS: usize = (UDP_REPLAY_WINDOW / 64) as usize;
const SAS_MODULUS: u64 = 1_000_000;

/// Every failure mode of the secure channel; none of them may panic on untrusted input.
#[derive(Debug)]
pub enum SecureError {
    MalformedInput(String),
    UnsupportedSuite(u32),
    InvalidKeyLength { context: &'static str },
    SignatureInvalid,
    KeyAgreementFailed,
    DecryptionFailed,
    ReplayDetected,
    CryptoBackend(String),
}

impl std::fmt::Display for SecureError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SecureError::MalformedInput(detail) => write!(f, "malformed secure message: {detail}"),
            SecureError::UnsupportedSuite(suite) => write!(f, "unsupported secure suite: {suite}"),
            SecureError::InvalidKeyLength { context } => write!(f, "invalid key length: {context}"),
            SecureError::SignatureInvalid => write!(f, "transcript signature verification failed"),
            SecureError::KeyAgreementFailed => write!(f, "x25519 key agreement failed"),
            SecureError::DecryptionFailed => write!(f, "aead decryption failed"),
            SecureError::ReplayDetected => write!(f, "replayed udp packet rejected"),
            SecureError::CryptoBackend(detail) => write!(f, "crypto backend error: {detail}"),
        }
    }
}

impl std::error::Error for SecureError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    ClientToServer,
    ServerToClient,
}

fn signed_transcript(parts: &[&[u8]]) -> Vec<u8> {
    let mut input = SECURE_TRANSCRIPT_TAG.to_vec();
    for part in parts {
        input.extend_from_slice(&(part.len() as u32).to_be_bytes());
        input.extend_from_slice(part);
    }
    input
}

/// Canonical client-hello value string built from decoded fields only, never
/// from protobuf re-encoding: two independent protobuf implementations may
/// serialize differently, so signatures and keys must not depend on encoding.
fn client_hello_core(hello: &SecureClientHello) -> Vec<u8> {
    signed_transcript(&[
        &hello.suite_mask.to_be_bytes(),
        &hello.ephemeral_pub_key,
        &hello.identity_pub_key,
        &hello.device_name.as_bytes(),
    ])[SECURE_TRANSCRIPT_TAG.len()..]
    .to_vec()
}

/// Canonical server-hello value string (signature field excluded by design).
fn server_hello_core(suite: u32, identity_pub: &[u8], ephemeral_pub: &[u8]) -> Vec<u8> {
    signed_transcript(&[&suite.to_be_bytes(), identity_pub, ephemeral_pub])[SECURE_TRANSCRIPT_TAG.len()..]
        .to_vec()
}

fn transcript_digest(ch_core: &[u8], sh_core: &[u8]) -> Vec<u8> {
    digest::digest(&digest::SHA256, &signed_transcript(&[ch_core, sh_core])).as_ref().to_vec()
}

fn transcript_hash(parts: &[&[u8]]) -> Vec<u8> {
    let mut joined = SECURE_TRANSCRIPT_TAG.to_vec();
    for part in parts {
        joined.extend_from_slice(part);
    }
    digest::digest(&digest::SHA256, &joined).as_ref().to_vec()
}

struct FixedLen(usize);

impl hkdf::KeyType for FixedLen {
    fn len(&self) -> usize {
        self.0
    }
}

#[derive(Debug, Clone)]
struct SessionKeys {
    tcp_key_c2s: [u8; SYMMETRIC_KEY_LEN],
    tcp_key_s2c: [u8; SYMMETRIC_KEY_LEN],
    udp_key_c2s: [u8; SYMMETRIC_KEY_LEN],
    udp_nonce_prefix: [u8; UDP_NONCE_PREFIX_LEN],
}

fn derive_session_keys(
    transcript: &[u8],
    shared_secret: &[u8],
) -> Result<(SessionKeys, u64), SecureError> {
    let prk = Salt::new(HKDF_SHA256, transcript).extract(shared_secret);
    let backend = |stage: &str, e: ring::error::Unspecified| {
        SecureError::CryptoBackend(format!("hkdf {stage} failed: {e}"))
    };

    let mut master = vec![0u8; MASTER_SECRET_LEN];
    prk.expand(&[KEYS_INFO], FixedLen(MASTER_SECRET_LEN))
        .map_err(|e| backend("expand", e))?
        .fill(&mut master)
        .map_err(|e| backend("fill", e))?;

    let mut sas_raw = [0u8; 8];
    prk.expand(&[SAS_INFO], FixedLen(sas_raw.len()))
        .map_err(|e| backend("expand", e))?
        .fill(&mut sas_raw)
        .map_err(|e| backend("fill", e))?;
    let sas_value = u64::from_be_bytes(sas_raw) % SAS_MODULUS;

    let mut keys = SessionKeys {
        tcp_key_c2s: [0u8; SYMMETRIC_KEY_LEN],
        tcp_key_s2c: [0u8; SYMMETRIC_KEY_LEN],
        udp_key_c2s: [0u8; SYMMETRIC_KEY_LEN],
        udp_nonce_prefix: [0u8; UDP_NONCE_PREFIX_LEN],
    };
    keys.tcp_key_c2s.copy_from_slice(&master[0..32]);
    keys.tcp_key_s2c.copy_from_slice(&master[32..64]);
    keys.udp_key_c2s.copy_from_slice(&master[64..96]);
    keys.udp_nonce_prefix.copy_from_slice(&master[96..100]);
    Ok((keys, sas_value))
}

fn sas_string(sas_value: u64) -> String {
    format!("{sas_value:06}")
}

fn make_nonce(prefix: &[u8; UDP_NONCE_PREFIX_LEN], seq: u64) -> Result<Nonce, SecureError> {
    let mut bytes = [0u8; NONCE_LEN];
    bytes[..UDP_NONCE_PREFIX_LEN].copy_from_slice(prefix);
    bytes[UDP_NONCE_PREFIX_LEN..].copy_from_slice(&seq.to_be_bytes());
    Nonce::try_assume_unique_for_key(&bytes)
        .map_err(|e| SecureError::CryptoBackend(format!("nonce construction failed: {e}")))
}

struct TcpCipher {
    seal_key: LessSafeKey,
    open_key: LessSafeKey,
    send_seq: AtomicU64,
    recv_seq: AtomicU64,
}

impl TcpCipher {
    fn new(key: &[u8; SYMMETRIC_KEY_LEN]) -> Result<Self, SecureError> {
        let backend = |e| SecureError::CryptoBackend(format!("aes-256-gcm key setup failed: {e}"));
        let seal_key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).map_err(backend)?);
        let open_key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).map_err(backend)?);
        Ok(Self {
            seal_key,
            open_key,
            send_seq: AtomicU64::new(0),
            recv_seq: AtomicU64::new(0),
        })
    }

    fn seal(&self, header8: &[u8; 8], payload: &[u8]) -> Result<Vec<u8>, SecureError> {
        let seq = self.send_seq.fetch_add(1, Ordering::SeqCst);
        let mut buf = payload.to_vec();
        let nonce = make_nonce(&[0u8; UDP_NONCE_PREFIX_LEN], seq)?;
        self.seal_key
            .seal_in_place_append_tag(nonce, Aad::from(header8.as_slice()), &mut buf)
            .map_err(|_| SecureError::CryptoBackend("tcp seal failed".to_string()))?;
        Ok(buf)
    }

    fn open(&self, header8: &[u8; 8], sealed: &[u8]) -> Result<Vec<u8>, SecureError> {
        let seq = self.recv_seq.fetch_add(1, Ordering::SeqCst);
        let mut buf = sealed.to_vec();
        let nonce = make_nonce(&[0u8; UDP_NONCE_PREFIX_LEN], seq)?;
        match self
            .open_key
            .open_in_place(nonce, Aad::from(header8.as_slice()), &mut buf)
        {
            Ok(plaintext) => Ok(plaintext.to_vec()),
            Err(_) => Err(SecureError::DecryptionFailed),
        }
    }
}

struct ReplayWindow {
    highest: u64,
    started: bool,
    bitmap: [u64; UDP_REPLAY_WORDS],
}

impl ReplayWindow {
    fn new() -> Self {
        Self {
            highest: 0,
            started: false,
            bitmap: [0; UDP_REPLAY_WORDS],
        }
    }

    /// RFC 6479-style sliding window: accepts strictly-new sequences, replays of
    /// anything already seen (including old-but-still-inside-the-window values).
    fn accept(&mut self, seq: u64) -> bool {
        if !self.started {
            self.started = true;
            self.highest = seq;
            self.bitmap[0] = 1;
            return true;
        }
        if seq > self.highest {
            let delta = seq - self.highest;
            if delta >= UDP_REPLAY_WINDOW {
                self.bitmap = [0; UDP_REPLAY_WORDS];
            } else {
                advance_bitmap(&mut self.bitmap, delta as usize);
            }
            self.highest = seq;
            self.bitmap[0] |= 1;
            return true;
        }
        let delta = self.highest - seq;
        if delta >= UDP_REPLAY_WINDOW {
            return false;
        }
        let word = (delta / 64) as usize;
        let bit = delta % 64;
        if self.bitmap[word] & (1 << bit) != 0 {
            return false;
        }
        self.bitmap[word] |= 1 << bit;
        true
    }
}

/// Shifts the window bitmap toward newer sequence numbers (bit index k moves
/// to k+delta), treating the words as one little-endian 2048-bit integer.
fn advance_bitmap(bitmap: &mut [u64; UDP_REPLAY_WORDS], delta: usize) {
    let words = delta / 64;
    let bits = delta % 64;
    if words >= bitmap.len() {
        *bitmap = [0; UDP_REPLAY_WORDS];
        return;
    }
    for i in (0..bitmap.len()).rev() {
        let mut value = if i >= words {
            bitmap[i - words] << bits
        } else {
            0
        };
        if bits > 0 && i > words {
            value |= bitmap[i - words - 1] >> (64 - bits);
        }
        bitmap[i] = value;
    }
}

/// Symmetric AES-256-GCM codec for the encrypted UDP audio datagrams.
///
/// Datagram layout: `UDP_SECURE_MAGIC` (i32 BE) || seq (u64 BE) || ciphertext+tag.
/// The replay window is shared per session and owned by the receiving side.
pub struct UdpSealer {
    seal_key: LessSafeKey,
    open_key: LessSafeKey,
    nonce_prefix: [u8; UDP_NONCE_PREFIX_LEN],
    send_seq: AtomicU64,
    replay: Mutex<ReplayWindow>,
}

impl UdpSealer {
    fn new(key: &[u8; SYMMETRIC_KEY_LEN], nonce_prefix: &[u8; UDP_NONCE_PREFIX_LEN]) -> Result<Self, SecureError> {
        let backend = |e| SecureError::CryptoBackend(format!("aes-256-gcm key setup failed: {e}"));
        let seal_key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).map_err(backend)?);
        let open_key = LessSafeKey::new(UnboundKey::new(&AES_256_GCM, key).map_err(backend)?);
        Ok(Self {
            seal_key,
            open_key,
            nonce_prefix: *nonce_prefix,
            send_seq: AtomicU64::new(0),
            replay: Mutex::new(ReplayWindow::new()),
        })
    }

    pub fn seal(&self, plaintext: &[u8]) -> Result<Vec<u8>, SecureError> {
        let seq = self.send_seq.fetch_add(1, Ordering::SeqCst);
        self.seal_with_seq(seq, plaintext)
    }

    fn seal_with_seq(&self, seq: u64, plaintext: &[u8]) -> Result<Vec<u8>, SecureError> {
        let magic_bytes = UDP_SECURE_MAGIC.to_be_bytes();
        let mut sealed = plaintext.to_vec();
        let nonce = make_nonce(&self.nonce_prefix, seq)?;
        self.seal_key
            .seal_in_place_append_tag(nonce, Aad::from(magic_bytes.as_slice()), &mut sealed)
            .map_err(|_| SecureError::CryptoBackend("udp seal failed".to_string()))?;
        let mut datagram = Vec::with_capacity(UDP_HEADER_LEN + sealed.len());
        datagram.extend_from_slice(&magic_bytes);
        datagram.extend_from_slice(&seq.to_be_bytes());
        datagram.extend_from_slice(&sealed);
        Ok(datagram)
    }

    pub fn open(&self, datagram: &[u8]) -> Result<Vec<u8>, SecureError> {
        let magic_bytes = UDP_SECURE_MAGIC.to_be_bytes();
        if datagram.len() < UDP_HEADER_LEN + AEAD_TAG_LEN {
            return Err(SecureError::MalformedInput(format!(
                "secure udp datagram too short: {} bytes",
                datagram.len()
            )));
        }
        if datagram[..4] != magic_bytes {
            return Err(SecureError::MalformedInput(
                "secure udp magic mismatch".to_string(),
            ));
        }
        let mut seq_bytes = [0u8; 8];
        seq_bytes.copy_from_slice(&datagram[4..UDP_HEADER_LEN]);
        let seq = u64::from_be_bytes(seq_bytes);

        let mut ciphertext = datagram[UDP_HEADER_LEN..].to_vec();
        let nonce = make_nonce(&self.nonce_prefix, seq)?;
        // Authenticate before touching the replay bitmap so forged datagrams
        // cannot advance the window and evict legitimate sequence numbers.
        let plaintext = self
            .open_key
            .open_in_place(nonce, Aad::from(magic_bytes.as_slice()), &mut ciphertext)
            .map_err(|_| SecureError::DecryptionFailed)?
            .to_vec();

        let mut window = self
            .replay
            .lock()
            .map_err(|_| SecureError::CryptoBackend("udp replay window lock poisoned".to_string()))?;
        if !window.accept(seq) {
            return Err(SecureError::ReplayDetected);
        }
        Ok(plaintext)
    }
}

/// Per-session crypto material: directional TCP frame codecs plus the UDP codec.
///
/// Sequence counters live inside the ciphers; every TCP frame must be opened in
/// transmission order and any mismatch or auth failure is a hard error (the
/// caller closes the connection).
pub struct SessionCrypto {
    tcp_cipher_c2s: TcpCipher,
    tcp_cipher_s2c: TcpCipher,
    udp: UdpSealer,
}

impl SessionCrypto {
    fn new(keys: &SessionKeys) -> Result<Self, SecureError> {
        Ok(Self {
            tcp_cipher_c2s: TcpCipher::new(&keys.tcp_key_c2s)?,
            tcp_cipher_s2c: TcpCipher::new(&keys.tcp_key_s2c)?,
            udp: UdpSealer::new(&keys.udp_key_c2s, &keys.udp_nonce_prefix)?,
        })
    }

    /// Seal one TCP frame payload. `header8` must be the exact 8-byte frame
    /// header transmitted on the wire (PACKET_MAGIC i32 BE || payload_len i32 BE),
    /// typically covering ciphertext+tag length. Returns ciphertext||tag.
    pub fn seal_tcp_payload(
        &self,
        direction: Direction,
        header8: &[u8; 8],
        payload: &[u8],
    ) -> Result<Vec<u8>, SecureError> {
        match direction {
            Direction::ClientToServer => self.tcp_cipher_c2s.seal(header8, payload),
            Direction::ServerToClient => self.tcp_cipher_s2c.seal(header8, payload),
        }
    }

    /// Open one TCP frame payload received in order for `direction`.
    pub fn open_tcp_payload(
        &self,
        direction: Direction,
        header8: &[u8; 8],
        sealed: &[u8],
    ) -> Result<Vec<u8>, SecureError> {
        match direction {
            Direction::ClientToServer => self.tcp_cipher_c2s.open(header8, sealed),
            Direction::ServerToClient => self.tcp_cipher_s2c.open(header8, sealed),
        }
    }

    pub fn udp_sealer(&self) -> &UdpSealer {
        &self.udp
    }
}

/// Server-side secure-handshake driver.
pub struct SecureHandshake {
    identity: Arc<ServerIdentity>,
    paired_devices: Option<Arc<Mutex<PairedDeviceStore>>>,
}

/// Everything derivable right after validating the client hello. One-shot per
/// connection; call [`PendingSession::finish`] once the final wire encodings exist.
#[derive(Debug)]
pub struct PendingSession {
    shared_secret: [u8; X25519_KEY_LEN],
    client_identity_b64: String,
    ch_core: Vec<u8>,
    sh_core: Vec<u8>,
}

impl SecureHandshake {
    pub fn new(identity: Arc<ServerIdentity>) -> Self {
        Self {
            identity,
            paired_devices: None,
        }
    }

    pub fn with_paired_devices(
        identity: Arc<ServerIdentity>,
        paired_devices: Arc<Mutex<PairedDeviceStore>>,
    ) -> Self {
        Self {
            identity,
            paired_devices: Some(paired_devices),
        }
    }

    pub fn identity(&self) -> &Arc<ServerIdentity> {
        &self.identity
    }

    /// Validate a SecureClientHello frame payload and produce the signed
    /// SecureServerHello plus everything derivable so far:
    /// `(server_hello, pending_session, sas, client_known, claimed_name)`.
    pub fn accept_client_hello(
        &self,
        hello_frame_payload: &[u8],
    ) -> Result<(SecureServerHello, PendingSession, String, bool, String), SecureError> {
        let hello = SecureClientHello::decode(hello_frame_payload)
            .map_err(|e| SecureError::MalformedInput(format!("client hello decode failed: {e}")))?;

        let suite_bit = 1u32 << (SECURE_SUITE_V1 - 1);
        if hello.suite_mask & suite_bit == 0 {
            return Err(SecureError::UnsupportedSuite(hello.suite_mask));
        }
        if hello.ephemeral_pub_key.len() != X25519_KEY_LEN {
            return Err(SecureError::InvalidKeyLength {
                context: "client ephemeral x25519 public key",
            });
        }
        if hello.identity_pub_key.len() != ED25519_PUBLIC_KEY_LEN {
            return Err(SecureError::InvalidKeyLength {
                context: "client identity ed25519 public key",
            });
        }

        let ch_core = client_hello_core(&hello);
        signature::UnparsedPublicKey::new(&signature::ED25519, hello.identity_pub_key.as_slice())
            .verify(&signed_transcript(&[&ch_core]), &hello.transcript_signature)
            .map_err(|_| SecureError::SignatureInvalid)?;

        let client_known = self
            .paired_devices
            .as_ref()
            .map(|store| match store.lock() {
                Ok(guard) => guard.find(&BASE64.encode(&hello.identity_pub_key)).is_some(),
                Err(_) => false,
            })
            .unwrap_or(false);

        let rng = SystemRandom::new();
        let eph_private = EphemeralPrivateKey::generate(&agreement::X25519, &rng)
            .map_err(|_| SecureError::CryptoBackend("x25519 generation failed".to_string()))?;
        let eph_public = eph_private
            .compute_public_key()
            .map_err(|_| SecureError::CryptoBackend("x25519 public key failed".to_string()))?
            .as_ref()
            .to_vec();

        let sh_core_vals = server_hello_core(SECURE_SUITE_V1, self.identity.public_key(), &eph_public);
        let server_signature = self.identity.sign(&signed_transcript(&[&ch_core, &sh_core_vals]));
        let server_hello = SecureServerHello {
            suite: SECURE_SUITE_V1,
            identity_pub_key: self.identity.public_key().to_vec(),
            ephemeral_pub_key: eph_public,
            transcript_signature: server_signature,
        };

        let peer_public = agreement::UnparsedPublicKey::new(&agreement::X25519, hello.ephemeral_pub_key.clone());
        let shared_secret: [u8; X25519_KEY_LEN] =
            agreement::agree_ephemeral(eph_private, &peer_public, |shared| {
                let mut out = [0u8; X25519_KEY_LEN];
                out.copy_from_slice(shared);
                out
            })
            .map_err(|_| SecureError::KeyAgreementFailed)?;

        let transcript = transcript_digest(&ch_core, &sh_core_vals);
        let (_, sas_value) = derive_session_keys(&transcript, &shared_secret)?;

        Ok((
            server_hello,
            PendingSession {
                shared_secret,
                client_identity_b64: BASE64.encode(&hello.identity_pub_key),
                ch_core,
                sh_core: sh_core_vals,
            },
            sas_string(sas_value),
            client_known,
            hello.device_name,
        ))
    }
}

impl PendingSession {
    pub fn client_identity_b64(&self) -> &str {
        &self.client_identity_b64
    }

    /// Derive the session crypto from the canonical hello value strings
    /// captured during `accept_client_hello`.
    pub fn finish(&self) -> Result<SessionCrypto, SecureError> {
        let transcript = transcript_digest(&self.ch_core, &self.sh_core);
        let (keys, _) = derive_session_keys(&transcript, &self.shared_secret)?;
        SessionCrypto::new(&keys)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use micyou_protocol::PACKET_MAGIC;

    fn hex_decode(value: &str) -> Vec<u8> {
        (0..value.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&value[i..i + 2], 16))
            .collect::<Result<Vec<u8>, _>>()
            .unwrap()
    }

    fn hex_encode(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }

    fn frame_header(payload_len: usize) -> [u8; 8] {
        let mut header = [0u8; 8];
        header[..4].copy_from_slice(&PACKET_MAGIC.to_be_bytes());
        header[4..].copy_from_slice(&(payload_len as i32).to_be_bytes());
        header
    }

    fn fixed_sessions() -> (SessionCrypto, SessionCrypto) {
        let transcript = transcript_hash(&[b"tcp-test-ch", b"tcp-test-sh"]);
        let shared = [0x33u8; X25519_KEY_LEN];
        let (keys, _) = derive_session_keys(&transcript, &shared).unwrap();
        (
            SessionCrypto::new(&keys).unwrap(),
            SessionCrypto::new(&keys).unwrap(),
        )
    }

    #[test]
    fn kdf_vectors_are_deterministic_for_cross_implementation_checks() {
        // Synthetic canonical hello cores; the Android implementation locks the
        // same values, so any divergence breaks this test on either side.
        let ch_core = hex_decode("00112233445566778899aabbccddeeff");
        let sh_core = hex_decode("fedcba98765432100123456789abcdef");
        let shared = [0x42u8; X25519_KEY_LEN];

        let transcript = transcript_digest(&ch_core, &sh_core);
        assert_eq!(
            hex_encode(&transcript),
            "68a8dc9addad9b69d0369cd4e82f2b13061841442bb293697f1e67e7bfa19a0e"
        );

        // Full 104-byte OKM cross-check (bytes [100..104) are reserved).
        let prk = Salt::new(HKDF_SHA256, &transcript).extract(&shared);
        let mut full_master = vec![0u8; MASTER_SECRET_LEN];
        prk.expand(&[KEYS_INFO], FixedLen(MASTER_SECRET_LEN))
            .unwrap()
            .fill(&mut full_master)
            .unwrap();
        assert_eq!(
            hex_encode(&full_master),
            concat!(
                "e17231f9fec9ff7a",
                "e501c5a47f19f78a",
                "9a5c2bd9a7576f7f",
                "b3eac165b612c6ef",
                "fd0638a0184d349c",
                "2545c3d91fb52aa7",
                "b1ffa68d57e8dc25",
                "4e3c43a65968dc87",
                "0d30fa0271f0432e",
                "175f5389c7b2cbfa",
                "4f073589bc0ee7dd",
                "a981db7519edfb94",
                "952ea86b26331aca"
            )
        );

        let (keys, sas_value) = derive_session_keys(&transcript, &shared).unwrap();
        assert_eq!(keys.tcp_key_c2s.as_slice(), &full_master[0..32]);
        assert_eq!(keys.tcp_key_s2c.as_slice(), &full_master[32..64]);
        assert_eq!(keys.udp_key_c2s.as_slice(), &full_master[64..96]);
        assert_eq!(keys.udp_nonce_prefix.as_slice(), &full_master[96..100]);
        assert_eq!(sas_string(sas_value), "406831");
    }

    struct TestClient {
        identity: ServerIdentity,
        eph_private: Option<EphemeralPrivateKey>,
        hello: SecureClientHello,
    }

    fn make_client(device_name: &str) -> TestClient {
        let identity = ServerIdentity::generate().unwrap();
        let rng = SystemRandom::new();
        let eph_private = EphemeralPrivateKey::generate(&agreement::X25519, &rng).unwrap();
        let mut hello = SecureClientHello {
            suite_mask: 1 << (SECURE_SUITE_V1 - 1),
            ephemeral_pub_key: eph_private.compute_public_key().unwrap().as_ref().to_vec(),
            identity_pub_key: identity.public_key().to_vec(),
            transcript_signature: Vec::new(),
            device_name: device_name.to_string(),
        };
        let core = client_hello_core(&hello);
        hello.transcript_signature = identity.sign(&signed_transcript(&[&core]));
        TestClient {
            identity,
            eph_private: Some(eph_private),
            hello,
        }
    }

    fn client_complete(client: &mut TestClient, sh_full: &[u8]) -> (SessionCrypto, String) {
        let server_hello = SecureServerHello::decode(sh_full).unwrap();
        let ch_core = client_hello_core(&client.hello);
        let sh_core = server_hello_core(
            server_hello.suite,
            &server_hello.identity_pub_key,
            &server_hello.ephemeral_pub_key,
        );
        let signature_input = signed_transcript(&[&ch_core, &sh_core]);
        signature::UnparsedPublicKey::new(&signature::ED25519, server_hello.identity_pub_key.as_slice())
            .verify(&signature_input, &server_hello.transcript_signature)
            .expect("server transcript signature must verify on the client side");

        let peer =
            agreement::UnparsedPublicKey::new(&agreement::X25519, server_hello.ephemeral_pub_key.clone());
        let shared: [u8; X25519_KEY_LEN] =
            agreement::agree_ephemeral(client.eph_private.take().unwrap(), &peer, |shared| {
                let mut out = [0u8; X25519_KEY_LEN];
                out.copy_from_slice(shared);
                out
            })
            .expect("client x25519 agreement");

        let transcript = transcript_digest(&ch_core, &sh_core);
        let (keys, sas_value) = derive_session_keys(&transcript, &shared).unwrap();
        (SessionCrypto::new(&keys).unwrap(), sas_string(sas_value))
    }

    #[test]
    fn full_handshake_roundtrip_produces_interoperating_sessions() {
        let handshake = SecureHandshake::new(Arc::new(ServerIdentity::generate().unwrap()));
        let mut client = make_client("Pixel 9");

        let ch_full = client.hello.encode_to_vec();
        let (server_hello, pending, sas_server, client_known, claimed_name) =
            handshake.accept_client_hello(&ch_full).unwrap();

        assert_eq!(claimed_name, "Pixel 9");
        assert!(!client_known);
        assert_eq!(server_hello.suite, SECURE_SUITE_V1);
        assert_eq!(pending.client_identity_b64(), BASE64.encode(client.identity.public_key()));

        let sh_full = server_hello.encode_to_vec();
        let (session_client, sas_client) = client_complete(&mut client, &sh_full);
        let session_server = pending.finish().unwrap();

        assert_eq!(sas_client, sas_server);
        assert_eq!(sas_server.len(), 6);
        assert!(sas_server.bytes().all(|b| b.is_ascii_digit()));

        let payload = b"c2s audio frame";
        let sealed_header = frame_header(payload.len() + AEAD_TAG_LEN);
        let sealed = session_client
            .seal_tcp_payload(Direction::ClientToServer, &sealed_header, payload)
            .unwrap();
        assert_eq!(sealed.len(), payload.len() + AEAD_TAG_LEN);
        let opened = session_server
            .open_tcp_payload(Direction::ClientToServer, &sealed_header, &sealed)
            .unwrap();
        assert_eq!(opened, payload);

        let reply = b"s2c ack";
        let reply_header = frame_header(reply.len() + AEAD_TAG_LEN);
        let sealed_reply = session_server
            .seal_tcp_payload(Direction::ServerToClient, &reply_header, reply)
            .unwrap();
        let opened_reply = session_client
            .open_tcp_payload(Direction::ServerToClient, &reply_header, &sealed_reply)
            .unwrap();
        assert_eq!(opened_reply, reply);

        let second = session_client
            .seal_tcp_payload(Direction::ClientToServer, &sealed_header, payload)
            .unwrap();
        assert_ne!(second, sealed);
        let reopened = session_server
            .open_tcp_payload(Direction::ClientToServer, &sealed_header, &second)
            .unwrap();
        assert_eq!(reopened, payload);

        let udp_datagram = session_client.udp_sealer().seal(b"udp audio").unwrap();
        assert_eq!(
            &udp_datagram[0..4],
            UDP_SECURE_MAGIC.to_be_bytes().as_slice()
        );
        let opened_udp = session_server.udp_sealer().open(&udp_datagram).unwrap();
        assert_eq!(opened_udp, b"udp audio");
    }

    #[test]
    fn tampered_client_signature_is_rejected() {
        let handshake = SecureHandshake::new(Arc::new(ServerIdentity::generate().unwrap()));
        let client = make_client("tamper");
        let mut ch_full = client.hello.encode_to_vec();
        let last = ch_full.len() - 1;
        ch_full[last] ^= 1;
        assert!(client.eph_private.is_some());

        let error = handshake.accept_client_hello(&ch_full).unwrap_err();
        assert!(matches!(error, SecureError::SignatureInvalid));
    }

    #[test]
    fn signer_identity_mismatch_is_rejected() {
        let handshake = SecureHandshake::new(Arc::new(ServerIdentity::generate().unwrap()));
        let signer = ServerIdentity::generate().unwrap();
        let claimed = ServerIdentity::generate().unwrap();
        let rng = SystemRandom::new();
        let eph = EphemeralPrivateKey::generate(&agreement::X25519, &rng).unwrap();
        let mut hello = SecureClientHello {
            suite_mask: 1 << (SECURE_SUITE_V1 - 1),
            ephemeral_pub_key: eph.compute_public_key().unwrap().as_ref().to_vec(),
            identity_pub_key: claimed.public_key().to_vec(),
            transcript_signature: Vec::new(),
            device_name: "mismatch".to_string(),
        };
        let core = client_hello_core(&hello);
        hello.transcript_signature = signer.sign(&signed_transcript(&[&core]));

        let error = handshake
            .accept_client_hello(&hello.encode_to_vec())
            .unwrap_err();
        assert!(matches!(error, SecureError::SignatureInvalid));
    }

    #[test]
    fn unsupported_suite_and_short_keys_are_rejected_without_panicking() {
        let handshake = SecureHandshake::new(Arc::new(ServerIdentity::generate().unwrap()));
        let identity = ServerIdentity::generate().unwrap();

        let no_suite = SecureClientHello {
            suite_mask: 0,
            ephemeral_pub_key: vec![0u8; X25519_KEY_LEN],
            identity_pub_key: identity.public_key().to_vec(),
            transcript_signature: Vec::new(),
            device_name: "x".to_string(),
        };
        assert!(matches!(
            handshake.accept_client_hello(&no_suite.encode_to_vec()).unwrap_err(),
            SecureError::UnsupportedSuite(0)
        ));

        let wrong_suite_bit = SecureClientHello {
            suite_mask: 0b10,
            ephemeral_pub_key: vec![0u8; X25519_KEY_LEN],
            identity_pub_key: identity.public_key().to_vec(),
            transcript_signature: Vec::new(),
            device_name: "x".to_string(),
        };
        assert!(matches!(
            handshake.accept_client_hello(&wrong_suite_bit.encode_to_vec()).unwrap_err(),
            SecureError::UnsupportedSuite(2)
        ));

        let short_eph = SecureClientHello {
            suite_mask: 1 << (SECURE_SUITE_V1 - 1),
            ephemeral_pub_key: vec![0u8; X25519_KEY_LEN - 1],
            identity_pub_key: identity.public_key().to_vec(),
            transcript_signature: Vec::new(),
            device_name: "x".to_string(),
        };
        assert!(matches!(
            handshake.accept_client_hello(&short_eph.encode_to_vec()).unwrap_err(),
            SecureError::InvalidKeyLength { .. }
        ));

        let garbage = [0xffu8; 40];
        assert!(handshake.accept_client_hello(&garbage).is_err());
    }

    #[test]
    fn paired_device_store_marks_client_as_known() {
        let identity = Arc::new(ServerIdentity::generate().unwrap());
        let client = make_client("known-phone");
        let mut store = PairedDeviceStore::load_or_create(std::env::temp_dir().join(format!(
            "micyou-known-test-{}-{}.json",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        )))
        .unwrap();
        store.upsert("known-phone", &BASE64.encode(client.identity.public_key())).unwrap();
        let paired = Arc::new(Mutex::new(store));

        let handshake = SecureHandshake::with_paired_devices(Arc::clone(&identity), Arc::clone(&paired));
        let ch_full = client.hello.encode_to_vec();
        let (_, _, _, client_known, name) = handshake.accept_client_hello(&ch_full).unwrap();
        assert!(client_known);
        assert_eq!(name, "known-phone");

        let unknown_handshake = SecureHandshake::with_paired_devices(identity, paired);
        let mut stranger = make_client("stranger");
        let stranger_hello = stranger.hello.encode_to_vec();
        let (_, _, _, client_known, _) = unknown_handshake
            .accept_client_hello(&stranger_hello)
            .unwrap();
        assert!(!client_known);
        let _ = stranger.eph_private.take();
    }

    #[test]
    fn tampered_tcp_payload_or_header_fails_to_open() {
        let (client, server) = fixed_sessions();

        let payload = b"secret-frame";
        let header = frame_header(payload.len() + AEAD_TAG_LEN);
        let mut corrupted = client
            .seal_tcp_payload(Direction::ClientToServer, &header, payload)
            .unwrap();
        let last = corrupted.len() - 1;
        corrupted[last] ^= 1;
        assert!(matches!(
            server.open_tcp_payload(Direction::ClientToServer, &header, &corrupted),
            Err(SecureError::DecryptionFailed)
        ));

        let sealed = client
            .seal_tcp_payload(Direction::ClientToServer, &header, payload)
            .unwrap();
        let mut wrong_header = header;
        wrong_header[7] ^= 1;
        assert!(matches!(
            server.open_tcp_payload(Direction::ClientToServer, &wrong_header, &sealed),
            Err(SecureError::DecryptionFailed)
        ));
    }

    #[test]
    fn out_of_order_tcp_frames_are_rejected_and_counters_advance() {
        let (client, server) = fixed_sessions();

        let header = frame_header(b"frame-x".len() + AEAD_TAG_LEN);
        let first = client
            .seal_tcp_payload(Direction::ClientToServer, &header, b"frame-0")
            .unwrap();
        let second = client
            .seal_tcp_payload(Direction::ClientToServer, &header, b"frame-1")
            .unwrap();

        assert!(matches!(
            server.open_tcp_payload(Direction::ClientToServer, &header, &second),
            Err(SecureError::DecryptionFailed)
        ));
        assert!(matches!(
            server.open_tcp_payload(Direction::ClientToServer, &header, &first),
            Err(SecureError::DecryptionFailed)
        ));

        // Hard-error contract: after a violation the receiver is unusable and a
        // fresh session is required.
        let (_, fresh_server) = fixed_sessions();
        assert_eq!(
            fresh_server
                .open_tcp_payload(Direction::ClientToServer, &header, &first)
                .unwrap(),
            b"frame-0"
        );
        assert_eq!(
            fresh_server
                .open_tcp_payload(Direction::ClientToServer, &header, &second)
                .unwrap(),
            b"frame-1"
        );
    }

    #[test]
    fn udp_replay_window_rejects_duplicates_and_stale_seen_frames() {
        let (client, server) = fixed_sessions();
        let sealer = client.udp_sealer();
        let opener = server.udp_sealer();

        for seq in 0..=2u64 {
            let datagram = sealer.seal_with_seq(seq, b"p").unwrap();
            assert!(opener.open(&datagram).is_ok(), "seq {seq} should open");
        }
        let dup = sealer.seal_with_seq(1, b"p").unwrap();
        assert!(matches!(opener.open(&dup), Err(SecureError::ReplayDetected)));

        // Slide forward by less than the window: the markers for 0..=2 stay
        // tracked, so resending one of them is a stale in-window replay.
        let mid_jump = sealer.seal_with_seq(1500, b"p").unwrap();
        assert!(opener.open(&mid_jump).is_ok());
        let stale_seen = sealer.seal_with_seq(1, b"p").unwrap();
        assert!(matches!(
            opener.open(&stale_seen),
            Err(SecureError::ReplayDetected)
        ));

        // An unseen sequence inside the window is a legitimate late arrival.
        let unseen_in_window = sealer.seal_with_seq(1000, b"p").unwrap();
        assert!(opener.open(&unseen_in_window).is_ok());

        // Jumping further than the window flushes everything older.
        let far_jump = sealer.seal_with_seq(4000, b"p").unwrap();
        assert!(opener.open(&far_jump).is_ok());

        // Now seq 1000 is below the window edge: rejected outright.
        let below_window = sealer.seal_with_seq(1000, b"p").unwrap();
        assert!(matches!(
            opener.open(&below_window),
            Err(SecureError::ReplayDetected)
        ));

        // Unseen value just below the newest sequence: accepted once, then a
        // duplicate of it is rejected.
        let near_top = sealer.seal_with_seq(3999, b"p").unwrap();
        assert!(opener.open(&near_top).is_ok());
        let dup_near_top = sealer.seal_with_seq(3999, b"p").unwrap();
        assert!(matches!(
            opener.open(&dup_near_top),
            Err(SecureError::ReplayDetected)
        ));
    }

    #[test]
    fn forged_udp_datagrams_fail_auth_without_poisoning_the_window() {
        let (client, server) = fixed_sessions();
        let opener = server.udp_sealer();

        let mut forged = Vec::new();
        forged.extend_from_slice(&UDP_SECURE_MAGIC.to_be_bytes());
        forged.extend_from_slice(&7u64.to_be_bytes());
        forged.extend_from_slice(&[0u8; 32]);
        assert!(matches!(opener.open(&forged), Err(SecureError::DecryptionFailed)));

        let genuine = client.udp_sealer().seal_with_seq(7, b"real").unwrap();
        assert_eq!(opener.open(&genuine).unwrap(), b"real");
    }

    #[test]
    fn sas_values_always_format_as_six_digits() {
        for i in 0..50u64 {
            let mut shared = [0u8; X25519_KEY_LEN];
            shared[..8].copy_from_slice(&i.to_be_bytes());
            shared[8..16].copy_from_slice(&i.wrapping_mul(0x9E3779B97F4A7C15).to_be_bytes());
            let transcript = transcript_hash(&[&i.to_be_bytes(), &[b's', b'a', b's']]);
            let (_, sas_value) = derive_session_keys(&transcript, &shared).unwrap();
            let text = sas_string(sas_value);
            assert_eq!(text.len(), 6);
            assert!(text.bytes().all(|b| b.is_ascii_digit()));
            assert_eq!(text.parse::<u64>().unwrap(), sas_value);
            assert!(sas_value < SAS_MODULUS);
        }
    }
}

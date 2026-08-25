pub mod micyou {
    include!(concat!(env!("OUT_DIR"), "/micyou.rs"));
}

pub const PACKET_MAGIC: i32 = 0x4D696359; // "MicY"
pub const UDP_PACKET_MAGIC: i32 = 0x4D696355; // "MicU"
pub const UDP_SECURE_MAGIC: i32 = 0x4D696356; // "MicV" - AEAD-sealed audio datagram
/// Secure channel suite 1: X25519 ECDH + Ed25519 identity signatures +
/// HKDF-SHA256 + AES-256-GCM with an 8-byte big-endian packet sequence nonce.
pub const SECURE_SUITE_V1: u32 = 1;
/// Domain-separation prefix hashed into every transcript signature and KDF call.
pub const SECURE_TRANSCRIPT_TAG: &[u8] = b"MICYOU-SECURE-V1";
pub const PORT: u16 = 9123;
pub const UDP_PORT: u16 = 9124;
pub const MDNS_SERVICE_TYPE: &str = "_micyou._tcp.local.";
pub const MDNS_WEB_SERVICE_TYPE: &str = "_micyou-web._tcp.local.";
pub const HANDSHAKE_CLIENT_STR: &[u8] = b"MicYouCheck1";
pub const HANDSHAKE_SERVER_STR: &[u8] = b"MicYouCheck2";

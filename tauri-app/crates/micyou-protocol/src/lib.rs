/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

pub mod micyou {
    include!(concat!(env!("OUT_DIR"), "/micyou.rs"));
}

pub const PACKET_MAGIC: i32 = 0x4D696359; // "MicY"
pub const UDP_PACKET_MAGIC: i32 = 0x4D696355; // "MicU"

/// Audio buffer codecs carried in `AudioPacketMessage.codec`.
pub const CODEC_PCM: i32 = 0;
pub const CODEC_OPUS: i32 = 1;
pub const PORT: u16 = 9123;
pub const UDP_PORT: u16 = 9124;
pub const MDNS_SERVICE_TYPE: &str = "_micyou._tcp.local.";
pub const MDNS_WEB_SERVICE_TYPE: &str = "_micyou-web._tcp.local.";
pub const HANDSHAKE_CLIENT_STR: &[u8] = b"MicYouCheck1";
pub const HANDSHAKE_SERVER_STR: &[u8] = b"MicYouCheck2";

pub const UDP_SECURE_MAGIC: i32 = 0x4D696356; // "MicV"
pub const SECURE_SUITE_V1: u32 = 1;
pub const SECURE_TRANSCRIPT_TAG: &[u8] = b"MICYOU-SECURE-V1";

#[cfg(test)]
mod tests {
    use super::micyou::*;
    use prost::Message;

    #[test]
    fn security_extension_does_not_reuse_upstream_plugin_or_statistics_tags() {
        let wrapper = MessageWrapper {
            plugin_message: Some(PluginMessage {
                topic: "hello".into(),
                ..Default::default()
            }),
            stats: Some(StatsMessage {
                rtt_ms: 5,
                ..Default::default()
            }),
            secure_client_hello: Some(SecureClientHello {
                suite_mask: 1,
                ..Default::default()
            }),
            ..Default::default()
        };
        let encoded = wrapper.encode_to_vec();
        let decoded = MessageWrapper::decode(encoded.as_slice()).unwrap();
        assert_eq!(decoded.plugin_message.unwrap().topic, "hello");
        assert_eq!(decoded.stats.unwrap().rtt_ms, 5);
        assert_eq!(decoded.secure_client_hello.unwrap().suite_mask, 1);
        let hello_only = MessageWrapper {
            secure_client_hello: Some(SecureClientHello {
                suite_mask: 1,
                ..Default::default()
            }),
            ..Default::default()
        }
        .encode_to_vec();
        assert_eq!(&hello_only[..2], &[0xa2, 0x06]); // tag 100, length-delimited
    }

    #[test]
    fn audio_health_preserves_legacy_ping_timestamp() {
        let ping = PingMessage {
            timestamp: 123,
            audio_health_supported: true,
            audio_healthy: true,
        };
        let decoded = PingMessage::decode(ping.encode_to_vec().as_slice()).unwrap();
        assert_eq!(decoded.timestamp, 123);
        assert!(decoded.audio_health_supported && decoded.audio_healthy);
    }
}

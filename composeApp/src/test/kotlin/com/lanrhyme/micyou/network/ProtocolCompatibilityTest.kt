package com.lanrhyme.micyou.network

import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolCompatibilityTest {
    @Test
    fun pluginFieldSevenAndSecureFieldOneHundredDoNotCollide() {
        val proto = ProtoBuf { }
        val plugin = MessageWrapper(pluginMessage = PluginMessage(source = "soundpad", topic = "state"))
        val secure = MessageWrapper(secureClientHello = SecureClientHello(deviceName = "phone"))
        assertEquals(0x3a, proto.encodeToByteArray(plugin)[0].toInt() and 0xff)
        val secureBytes = proto.encodeToByteArray(secure)
        assertEquals(listOf(0xa2, 0x06), secureBytes.take(2).map { it.toInt() and 0xff })
        assertEquals("soundpad", proto.decodeFromByteArray<MessageWrapper>(proto.encodeToByteArray(plugin)).pluginMessage?.source)
        assertEquals("phone", proto.decodeFromByteArray<MessageWrapper>(secureBytes).secureClientHello?.deviceName)
    }

    @Test
    fun opusAndAudioHealthSurviveWireRoundTrip() {
        val proto = ProtoBuf { }
        val packet = AudioPacketMessage(byteArrayOf(1, 2), 48000, 1, 2, CODEC_OPUS)
        val decoded = proto.decodeFromByteArray<MessageWrapper>(proto.encodeToByteArray(
            MessageWrapper(audioPacket = AudioPacketMessageOrdered(1, packet, sessionId = 9))
        ))
        assertEquals(CODEC_OPUS, decoded.audioPacket?.audioPacket?.codec)
        val health = proto.decodeFromByteArray<PingMessage>(proto.encodeToByteArray(PingMessage(12, true, true)))
        assertTrue(health.audioHealthSupported)
        assertTrue(health.audioHealthy)
    }
}

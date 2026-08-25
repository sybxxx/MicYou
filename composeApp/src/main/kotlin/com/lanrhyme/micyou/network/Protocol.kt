package com.lanrhyme.micyou.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoNumber
import com.lanrhyme.micyou.audio.AudioFormat
import com.lanrhyme.micyou.audio.ChannelCount
import com.lanrhyme.micyou.audio.SampleRate
import com.lanrhyme.micyou.network.AudioPacketMessage
import com.lanrhyme.micyou.network.AudioPacketMessageOrdered
import com.lanrhyme.micyou.network.calculateUdpPort
import com.lanrhyme.micyou.network.MessageWrapper
import com.lanrhyme.micyou.network.PACKET_MAGIC
import com.lanrhyme.micyou.network.UDP_PACKET_MAGIC
import com.lanrhyme.micyou.network.UDP_PORT_OFFSET

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AudioPacketMessage(
    @ProtoNumber(1)
    val buffer: ByteArray,
    @ProtoNumber(2)
    val sampleRate: Int,
    @ProtoNumber(3)
    val channelCount: Int,
    @ProtoNumber(4)
    val audioFormat: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as AudioPacketMessage

        if (!buffer.contentEquals(other.buffer)) return false
        if (sampleRate != other.sampleRate) return false
        if (channelCount != other.channelCount) return false
        if (audioFormat != other.audioFormat) return false

        return true
    }

    override fun hashCode(): Int {
        var result = buffer.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + audioFormat
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AudioPacketMessageOrdered(
    @ProtoNumber(1)
    val sequenceNumber: Int,
    @ProtoNumber(2)
    val audioPacket: AudioPacketMessage,
    @ProtoNumber(3)
    val timestamp: Long = 0,
    @ProtoNumber(4)
    val fecBuffer: ByteArray? = null,
    @ProtoNumber(5)
    val fecSequenceNumber: Int = -1,
    @ProtoNumber(6)
    val sessionId: Long = 0,
    // 原始源包长度；空列表表示旧版发送端，接收端保持旧版恢复行为。
    @ProtoNumber(7)
    val fecPacketLengths: List<Int> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MuteMessage(
    @ProtoNumber(1)
    val isMuted: Boolean = false
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConnectMessage(
    @ProtoNumber(1)
    val sessionId: Long = 0
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PluginInfoMessage(
    @ProtoNumber(1)
    val id: String,
    @ProtoNumber(2)
    val name: String,
    @ProtoNumber(3)
    val version: String
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PluginSyncMessage(
    @ProtoNumber(1)
    val plugins: List<PluginInfoMessage> = emptyList(),
    @ProtoNumber(2)
    val platform: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PingMessage(
    @ProtoNumber(1)
    val timestamp: Long,
    @ProtoNumber(2)
    val audioHealthSupported: Boolean = false,
    @ProtoNumber(3)
    val audioHealthy: Boolean = false
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PongMessage(
    @ProtoNumber(1)
    val timestamp: Long
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SecureClientHello(
    // Bitmask of supported suites; bit N set means suite N+1 is supported.
    @ProtoNumber(1)
    val suiteMask: Int = 0,
    @ProtoNumber(2)
    val ephemeralPubKey: ByteArray = ByteArray(0),
    @ProtoNumber(3)
    val identityPubKey: ByteArray = ByteArray(0),
    @ProtoNumber(4)
    val transcriptSignature: ByteArray = ByteArray(0),
    @ProtoNumber(5)
    val deviceName: String = ""
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SecureClientHello

        if (suiteMask != other.suiteMask) return false
        if (!ephemeralPubKey.contentEquals(other.ephemeralPubKey)) return false
        if (!identityPubKey.contentEquals(other.identityPubKey)) return false
        if (!transcriptSignature.contentEquals(other.transcriptSignature)) return false
        if (deviceName != other.deviceName) return false

        return true
    }

    override fun hashCode(): Int {
        var result = suiteMask
        result = 31 * result + ephemeralPubKey.contentHashCode()
        result = 31 * result + identityPubKey.contentHashCode()
        result = 31 * result + transcriptSignature.contentHashCode()
        result = 31 * result + deviceName.hashCode()
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SecureServerHello(
    @ProtoNumber(1)
    val suite: Int = 0,
    @ProtoNumber(2)
    val identityPubKey: ByteArray = ByteArray(0),
    @ProtoNumber(3)
    val ephemeralPubKey: ByteArray = ByteArray(0),
    @ProtoNumber(4)
    val transcriptSignature: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as SecureServerHello

        if (suite != other.suite) return false
        if (!identityPubKey.contentEquals(other.identityPubKey)) return false
        if (!ephemeralPubKey.contentEquals(other.ephemeralPubKey)) return false
        if (!transcriptSignature.contentEquals(other.transcriptSignature)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = suite
        result = 31 * result + identityPubKey.contentHashCode()
        result = 31 * result + ephemeralPubKey.contentHashCode()
        result = 31 * result + transcriptSignature.contentHashCode()
        return result
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SecureConfirm(
    @ProtoNumber(1)
    val deviceName: String = ""
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class SecureResult(
    @ProtoNumber(1)
    val accepted: Boolean = false
)

const val PACKET_MAGIC = 0x4D696359 // "MicY" in ASCII
const val UDP_PACKET_MAGIC = 0x4D696355 // "MicU" in ASCII
// "MicV" in ASCII, marks AEAD-sealed audio datagrams (secure UDP transport).
const val UDP_SECURE_MAGIC = 0x4D696356
// Suite 1: X25519 ECDH + Ed25519 identity signatures + HKDF-SHA256 + AES-256-GCM.
const val SECURE_SUITE_V1 = 1
val SECURE_TRANSCRIPT_TAG: ByteArray = "MICYOU-SECURE-V1".toByteArray()
const val UDP_CUSTOM_HEADER_SIZE = 8
const val UDP_MAX_DATAGRAM_SIZE = 1472
// 为自定义头、嵌套 protobuf、64 位字段及 FEC 长度元数据预留最坏情况预算。
const val UDP_PCM_PAYLOAD_SIZE = 1320

/** UDP 端口 = TCP 端口 + 1 */
const val UDP_PORT_OFFSET = 1

/**
 * 计算 UDP 端口，带边界校验防止端口溢出。
 * @param tcpPort TCP 端口号
 * @return UDP 端口号
 * @throws IllegalArgumentException 当计算结果超出有效端口范围 (0-65535)
 */
fun calculateUdpPort(tcpPort: Int): Int {
    val udpPort = tcpPort + UDP_PORT_OFFSET
    if (udpPort !in 0..65535) {
        throw IllegalArgumentException("UDP 端口溢出: TCP 端口 $tcpPort + 偏移量 $UDP_PORT_OFFSET = $udpPort，超出有效范围 0-65535")
    }
    return udpPort
}

/** 判断 MessageWrapper 是否包含控制消息（应通过 TCP 发送） */
fun MessageWrapper.hasControlMessage(): Boolean {
    return connect != null || mute != null || pluginSync != null || ping != null || pong != null
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class MessageWrapper(
    @ProtoNumber(1)
    val audioPacket: AudioPacketMessageOrdered? = null,
    @ProtoNumber(2)
    val connect: ConnectMessage? = null,
    @ProtoNumber(3)
    val mute: MuteMessage? = null,
    @ProtoNumber(4)
    val pluginSync: PluginSyncMessage? = null,
    @ProtoNumber(5)
    val ping: PingMessage? = null,
    @ProtoNumber(6)
    val pong: PongMessage? = null,
    @ProtoNumber(7)
    val secureClientHello: SecureClientHello? = null,
    @ProtoNumber(8)
    val secureServerHello: SecureServerHello? = null,
    @ProtoNumber(9)
    val secureConfirm: SecureConfirm? = null,
    @ProtoNumber(10)
    val secureResult: SecureResult? = null
)

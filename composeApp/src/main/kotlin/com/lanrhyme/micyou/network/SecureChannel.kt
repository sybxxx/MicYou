package com.lanrhyme.micyou.network

import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.X25519
import com.lanrhyme.micyou.settings.Settings
import com.lanrhyme.micyou.util.Logger
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf

const val SECURE_IDENTITY_SEED_KEY = "secure_identity_seed"
const val SECURE_PAIRED_SERVER_KEY_PREFIX = "paired_server_"

/** Every recoverable secure-channel failure; none of these may crash on untrusted input. */
open class SecureChannelException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ReplayDetectedException : SecureChannelException("secure udp datagram is a replay")

class SessionKeys(
    val tcpKeyC2S: ByteArray,
    val tcpKeyS2C: ByteArray,
    val udpKeyC2S: ByteArray,
    val udpNoncePrefix: ByteArray
)

class HandshakeSecrets(val keys: SessionKeys, val sasValue: Long)

object SecureChannel {
    private const val LOG_TAG = "SecureChannel"

    private val proto = ProtoBuf { }
    private val KEYS_INFO = "MICYOU-KEYS-V1".toByteArray()
    private val SAS_INFO = "MICYOU-SAS-V1".toByteArray()
    private const val MASTER_SECRET_BYTES = 104
    private const val HASH_BYTES = 32
    private const val SAS_BYTES = 8
    private const val SAS_MODULUS = 1_000_000L

    const val SYMMETRIC_KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val AEAD_TAG_BYTES = 16
    const val TCP_HEADER_BYTES = 8
    const val UDP_SECURE_HEADER_BYTES = 12
    const val IDENTITY_SEED_BYTES = 32

    private const val AEAD_TRANSFORMATION = "AES/GCM/NoPadding"

    /** TAG || u32be(len) || part ... — the input format for every transcript signature. */
    fun signedTranscript(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SECURE_TRANSCRIPT_TAG)
        for (part in parts) {
            val lenBytes = ByteArray(4)
            writeI32Be(lenBytes, 0, part.size)
            out.write(lenBytes)
            out.write(part)
        }
        return out.toByteArray()
    }

    fun transcriptHash(chCore: ByteArray, shCore: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(signedTranscript(chCore, shCore))
        return digest.digest()
    }

    /** Wire encoding of the client hello as transmitted (signature included). */
    fun clientHelloWireBytes(hello: SecureClientHello): ByteArray =
        proto.encodeToByteArray(hello)

    /**
     * Canonical client-hello value string built from decoded fields only. Signatures
     * and keys must never depend on protobuf re-encoding because independent protobuf
     * implementations may serialize the same message differently.
     */
    fun clientHelloCore(hello: SecureClientHello): ByteArray {
        val maskBytes = ByteArray(4)
        writeI32Be(maskBytes, 0, hello.suiteMask)
        val tagged = signedTranscript(
            maskBytes,
            hello.ephemeralPubKey,
            hello.identityPubKey,
            hello.deviceName.toByteArray(Charsets.UTF_8)
        )
        return tagged.copyOfRange(SECURE_TRANSCRIPT_TAG.size, tagged.size)
    }

    /** Canonical server-hello value string (the signature field is excluded by design). */
    fun serverHelloCore(suite: Int, identityPubKey: ByteArray, ephemeralPubKey: ByteArray): ByteArray {
        val suiteBytes = ByteArray(4)
        writeI32Be(suiteBytes, 0, suite)
        val tagged = signedTranscript(suiteBytes, identityPubKey, ephemeralPubKey)
        return tagged.copyOfRange(SECURE_TRANSCRIPT_TAG.size, tagged.size)
    }

    fun sign(seed: ByteArray, message: ByteArray): ByteArray = try {
        Ed25519Sign(seed).sign(message)
    } catch (e: GeneralSecurityException) {
        throw SecureChannelException("ed25519 signing failed", e)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        Ed25519Verify(publicKey).verify(signature, message)
        true
    } catch (_: GeneralSecurityException) {
        false
    }

    fun generateIdentitySeed(): ByteArray =
        ByteArray(IDENTITY_SEED_BYTES).also { SecureRandom().nextBytes(it) }

    fun identityPublicKey(seed: ByteArray): ByteArray = try {
        Ed25519Sign.KeyPair.newKeyPairFromSeed(seed).publicKey
    } catch (e: GeneralSecurityException) {
        throw SecureChannelException("ed25519 public key derivation failed", e)
    }

    fun generateX25519PrivateKey(): ByteArray = try {
        X25519.generatePrivateKey()
    } catch (e: GeneralSecurityException) {
        throw SecureChannelException("x25519 key generation failed", e)
    }

    fun x25519PublicKey(privateKey: ByteArray): ByteArray = try {
        X25519.publicFromPrivate(privateKey)
    } catch (e: GeneralSecurityException) {
        throw SecureChannelException("x25519 public key derivation failed", e)
    }

    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray = try {
        X25519.computeSharedSecret(privateKey, peerPublicKey)
    } catch (e: GeneralSecurityException) {
        throw SecureChannelException("x25519 key agreement failed", e)
    }

    fun hkdfSha256(salt: ByteArray, ikm: ByteArray, info: ByteArray, outputBytes: Int): ByteArray {
        if (outputBytes <= 0) throw SecureChannelException("hkdf output length must be positive")
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, "HmacSHA256"))
        val prk = extract.doFinal(ikm)

        val okm = ByteArray(outputBytes)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outputBytes) {
            val expand = Mac.getInstance("HmacSHA256")
            expand.init(SecretKeySpec(prk, "HmacSHA256"))
            expand.update(previous)
            expand.update(info)
            expand.update(counter.toByte())
            previous = expand.doFinal()
            val chunk = minOf(previous.size, outputBytes - offset)
            System.arraycopy(previous, 0, okm, offset, chunk)
            offset += chunk
            counter += 1
        }
        return okm
    }

    fun deriveHandshakeSecrets(transcriptHash: ByteArray, sharedSecret: ByteArray): HandshakeSecrets {
        if (transcriptHash.size != HASH_BYTES) {
            throw SecureChannelException("transcript hash must be sha-256 sized")
        }
        val master = hkdfSha256(transcriptHash, sharedSecret, KEYS_INFO, MASTER_SECRET_BYTES)
        val keys = SessionKeys(
            tcpKeyC2S = master.copyOfRange(0, 32),
            tcpKeyS2C = master.copyOfRange(32, 64),
            udpKeyC2S = master.copyOfRange(64, 96),
            udpNoncePrefix = master.copyOfRange(96, 100)
        )
        // Reducing modulo the SAS modulus at every step keeps u64 big-endian semantics
        // without needing unsigned arithmetic.
        val sasRaw = hkdfSha256(transcriptHash, sharedSecret, SAS_INFO, SAS_BYTES)
        var sasValue = 0L
        for (byte in sasRaw) {
            sasValue = ((sasValue shl 8) or (byte.toLong() and 0xff)) % SAS_MODULUS
        }
        return HandshakeSecrets(keys, sasValue)
    }

    fun sasText(sasValue: Long): String = sasValue.toString().padStart(6, '0')

    fun sessionCrypto(keys: SessionKeys): SessionCrypto = SessionCrypto(keys)

    /** The exact 8-byte TCP frame header transmitted on the wire. */
    fun tcpFrameHeader(ciphertextLen: Int): ByteArray {
        val header = ByteArray(TCP_HEADER_BYTES)
        writeI32Be(header, 0, PACKET_MAGIC)
        writeI32Be(header, 4, ciphertextLen)
        return header
    }

    internal fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext)
    } catch (e: Exception) {
        throw SecureChannelException("aead seal failed", e)
    }

    internal fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance(AEAD_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        cipher.doFinal(sealed)
    } catch (e: Exception) {
        throw SecureChannelException("aead open failed", e)
    }

    internal fun putU64Be(destination: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            destination[offset + i] = (value ushr ((7 - i) * 8)).toByte()
        }
    }

    internal fun readU64Be(source: ByteArray, offset: Int): Long? {
        var value = 0L
        for (i in 0 until 8) {
            val byte = source[offset + i].toLong() and 0xff
            if (i == 0 && byte >= 0x80) return null
            value = (value shl 8) or byte
        }
        return value
    }

    internal fun writeI32Be(destination: ByteArray, offset: Int, value: Int) {
        destination[offset] = (value ushr 24).toByte()
        destination[offset + 1] = (value ushr 16).toByte()
        destination[offset + 2] = (value ushr 8).toByte()
        destination[offset + 3] = value.toByte()
    }

    internal fun readI32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    fun base64Encode(data: ByteArray, urlSafe: Boolean = false): String {
        val alphabet = if (urlSafe) BASE64_URL_ALPHABET else BASE64_ALPHABET
        val builder = StringBuilder(((data.size + 2) / 3) * 4)
        var index = 0
        while (index + 2 < data.size) {
            val packed = ((data[index].toInt() and 0xff) shl 16) or
                ((data[index + 1].toInt() and 0xff) shl 8) or
                (data[index + 2].toInt() and 0xff)
            builder.append(alphabet[(packed ushr 18) and 63])
                .append(alphabet[(packed ushr 12) and 63])
                .append(alphabet[(packed ushr 6) and 63])
                .append(alphabet[packed and 63])
            index += 3
        }
        val remaining = data.size - index
        if (remaining == 1) {
            val packed = (data[index].toInt() and 0xff) shl 16
            builder.append(alphabet[(packed ushr 18) and 63])
                .append(alphabet[(packed ushr 12) and 63])
            if (!urlSafe) builder.append("==")
        } else if (remaining == 2) {
            val packed = ((data[index].toInt() and 0xff) shl 16) or
                ((data[index + 1].toInt() and 0xff) shl 8)
            builder.append(alphabet[(packed ushr 18) and 63])
                .append(alphabet[(packed ushr 12) and 63])
                .append(alphabet[(packed ushr 6) and 63])
            if (!urlSafe) builder.append('=')
        }
        return builder.toString()
    }

    /** Accepts standard and URL-safe alphabets, with or without padding. */
    fun base64Decode(text: String): ByteArray {
        val body = text.trim().dropLastWhile { it == '=' }
        if (body.isEmpty()) return ByteArray(0)
        val output = ByteArray(body.length * 3 / 4)
        var accumulator = 0
        var bits = 0
        var position = 0
        for (char in body) {
            val value = base64Value(char) ?: throw SecureChannelException("invalid base64 character")
            accumulator = (accumulator shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output[position++] = ((accumulator ushr bits) and 0xff).toByte()
            }
        }
        return if (position == output.size) output else output.copyOf(position)
    }

    private fun base64Value(char: Char): Int? = when (char) {
        in 'A'..'Z' -> char - 'A'
        in 'a'..'z' -> char - 'a' + 26
        in '0'..'9' -> char - '0' + 52
        '+' -> 62
        '/' -> 63
        '-' -> 62
        '_' -> 63
        else -> null
    }

    /** Loads the persistent Ed25519 identity seed, creating it on first use. */
    fun obtainIdentitySeed(settings: Settings): ByteArray {
        val stored = settings.getString(SECURE_IDENTITY_SEED_KEY, "")
        if (stored.isNotEmpty()) {
            val decoded = runCatching { base64Decode(stored) }.getOrNull()
            if (decoded != null && decoded.size == IDENTITY_SEED_BYTES) return decoded
            Logger.e(LOG_TAG, "Stored secure identity seed is invalid; generating a replacement")
        }
        val seed = generateIdentitySeed()
        settings.putString(SECURE_IDENTITY_SEED_KEY, base64Encode(seed))
        return seed
    }

    fun pairedServerStorageKey(serverPublicKey: ByteArray): String =
        SECURE_PAIRED_SERVER_KEY_PREFIX + base64Encode(serverPublicKey, urlSafe = true)

    fun rememberPairedServer(settings: Settings, serverPublicKey: ByteArray, displayName: String) {
        settings.putString(pairedServerStorageKey(serverPublicKey), displayName)
    }

    fun pairedServerDisplayName(settings: Settings, serverPublicKey: ByteArray): String? =
        settings.getString(pairedServerStorageKey(serverPublicKey), "").ifEmpty { null }

    /**
     * Offline self-check of every primitive against golden values produced by the
     * desktop implementation; kept for diagnostics and cross-version verification.
     */
    fun selfTest(): Boolean = try {
        val chCore = hexToBytes("00112233445566778899aabbccddeeff")
        val shCore = hexToBytes("fedcba98765432100123456789abcdef")
        val shared = ByteArray(SYMMETRIC_KEY_BYTES) { 0x42 }
        val transcript = transcriptHash(chCore, shCore)
        if (!transcript.contentEquals(hexToBytes(GOLDEN_TRANSCRIPT_HASH))) return false
        if (!hkdfSha256(transcript, shared, KEYS_INFO, MASTER_SECRET_BYTES)
            .contentEquals(hexToBytes(GOLDEN_MASTER_SECRET))
        ) return false
        val secrets = deriveHandshakeSecrets(transcript, shared)
        if (sasText(secrets.sasValue) != GOLDEN_SAS) return false

        val clientSession = sessionCrypto(secrets.keys)
        val serverSession = sessionCrypto(secrets.keys)
        val payload = "selftest".toByteArray()
        val header = tcpFrameHeader(payload.size + AEAD_TAG_BYTES)
        val sealed = clientSession.tcpC2S.seal(payload)
        if (!serverSession.tcpC2S.open(header, sealed).contentEquals(payload)) return false
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            serverSession.tcpC2S.open(header, tampered)
            return false
        } catch (_: SecureChannelException) {
        }

        val datagram = clientSession.udp.seal(payload)
        if (!serverSession.udp.open(datagram).contentEquals(payload)) return false
        try {
            serverSession.udp.open(datagram)
            return false
        } catch (_: ReplayDetectedException) {
        }
        true
    } catch (_: Throwable) {
        false
    }

    internal fun hexToBytes(value: String): ByteArray =
        ByteArray(value.length / 2) { i ->
            ((Character.digit(value[i * 2], 16) shl 4) or Character.digit(value[i * 2 + 1], 16)).toByte()
        }

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val BASE64_URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val GOLDEN_TRANSCRIPT_HASH =
        "68a8dc9addad9b69d0369cd4e82f2b13061841442bb293697f1e67e7bfa19a0e"
    private const val GOLDEN_MASTER_SECRET =
        "e17231f9fec9ff7ae501c5a47f19f78a9a5c2bd9a7576f7fb3eac165b612c6ef" +
            "fd0638a0184d349c2545c3d91fb52aa7b1ffa68d57e8dc254e3c43a65968dc87" +
            "0d30fa0271f0432e175f5389c7b2cbfa4f073589bc0ee7dda981db7519edfb94" +
            "952ea86b26331aca"
    private const val GOLDEN_SAS = "406831"
}

/**
 * Directional AES-256-GCM codec for encrypted TCP frames.
 *
 * seal() derives its own AAD from the frame header it will be sent with
 * (PACKET_MAGIC i32 BE || ciphertextLen i32 BE); open() must receive the exact
 * received header bytes. Frames must be opened in transmission order — any
 * failure desynchronizes the counters and requires a fresh session.
 */
class TcpCipher(private val key: ByteArray) {
    init {
        if (key.size != SecureChannel.SYMMETRIC_KEY_BYTES) {
            throw SecureChannelException("tcp cipher key must be ${SecureChannel.SYMMETRIC_KEY_BYTES} bytes")
        }
    }

    private var sendSeq = 0L
    private var recvSeq = 0L

    @Synchronized
    fun seal(payload: ByteArray): ByteArray {
        val header = SecureChannel.tcpFrameHeader(payload.size + SecureChannel.AEAD_TAG_BYTES)
        return SecureChannel.aeadSeal(key, nonce(sendSeq++), header, payload)
    }

    @Synchronized
    fun open(header8: ByteArray, sealed: ByteArray): ByteArray {
        if (header8.size != SecureChannel.TCP_HEADER_BYTES) {
            throw SecureChannelException("tcp frame header must be ${SecureChannel.TCP_HEADER_BYTES} bytes")
        }
        if (sealed.size < SecureChannel.AEAD_TAG_BYTES) {
            throw SecureChannelException("sealed tcp payload too short")
        }
        return SecureChannel.aeadOpen(key, nonce(recvSeq++), header8, sealed)
    }

    private fun nonce(sequence: Long): ByteArray {
        val value = ByteArray(SecureChannel.NONCE_BYTES)
        SecureChannel.putU64Be(value, 4, sequence)
        return value
    }
}

/**
 * AES-256-GCM codec for encrypted UDP audio datagrams:
 * UDP_SECURE_MAGIC (i32 BE) || seq (u64 BE) || ciphertext+tag,
 * nonce = udpNoncePrefix || seq, AAD = the 4 magic bytes.
 */
class UdpCipher(private val key: ByteArray, private val noncePrefix: ByteArray) {
    init {
        if (key.size != SecureChannel.SYMMETRIC_KEY_BYTES) {
            throw SecureChannelException("udp cipher key must be ${SecureChannel.SYMMETRIC_KEY_BYTES} bytes")
        }
        if (noncePrefix.size != UDP_NONCE_PREFIX_BYTES) {
            throw SecureChannelException("udp nonce prefix must be $UDP_NONCE_PREFIX_BYTES bytes")
        }
    }

    private val sendSeq = AtomicLong()
    private val replay = ReplayWindow()

    fun seal(plaintext: ByteArray): ByteArray = sealWithSequence(sendSeq.getAndIncrement(), plaintext)

    internal fun sealWithSequence(sequence: Long, plaintext: ByteArray): ByteArray {
        val magic = ByteArray(MAGIC_BYTES)
        SecureChannel.writeI32Be(magic, 0, UDP_SECURE_MAGIC)
        val nonce = noncePrefix.copyOf(SecureChannel.NONCE_BYTES)
        SecureChannel.putU64Be(nonce, MAGIC_BYTES, sequence)
        val ciphertext = SecureChannel.aeadSeal(key, nonce, magic, plaintext)
        val datagram = ByteArray(MAGIC_BYTES + SEQUENCE_BYTES + ciphertext.size)
        magic.copyInto(datagram, 0)
        SecureChannel.putU64Be(datagram, MAGIC_BYTES, sequence)
        ciphertext.copyInto(datagram, MAGIC_BYTES + SEQUENCE_BYTES)
        return datagram
    }

    fun open(datagram: ByteArray): ByteArray {
        if (datagram.size < SecureChannel.UDP_SECURE_HEADER_BYTES + SecureChannel.AEAD_TAG_BYTES) {
            throw SecureChannelException("secure udp datagram too short")
        }
        val magic = datagram.copyOf(MAGIC_BYTES)
        if (SecureChannel.readI32Be(magic, 0) != UDP_SECURE_MAGIC) {
            throw SecureChannelException("secure udp magic mismatch")
        }
        val sequence = SecureChannel.readU64Be(datagram, MAGIC_BYTES)
            ?: throw SecureChannelException("secure udp sequence number exceeds 63 bits")

        // Authenticate before touching the replay window so forged datagrams cannot
        // evict legitimate sequence numbers.
        val nonce = noncePrefix.copyOf(SecureChannel.NONCE_BYTES)
        SecureChannel.putU64Be(nonce, MAGIC_BYTES, sequence)
        val plaintext = SecureChannel.aeadOpen(key, nonce, magic, datagram.copyOfRange(MAGIC_BYTES + SEQUENCE_BYTES, datagram.size))

        if (!replay.accept(sequence)) throw ReplayDetectedException()
        return plaintext
    }

    private companion object {
        const val MAGIC_BYTES = 4
        const val SEQUENCE_BYTES = 8
        const val UDP_NONCE_PREFIX_BYTES = 4
    }
}

/** RFC 6479-style sliding replay window over the last 2048 sequence numbers. */
private class ReplayWindow {
    private var started = false
    private var highest = 0L
    private val bitmap = LongArray(WINDOW_BITS / 64)

    @Synchronized
    fun accept(sequence: Long): Boolean {
        if (!started) {
            started = true
            highest = sequence
            bitmap[0] = 1L
            return true
        }
        if (sequence > highest) {
            val delta = sequence - highest
            if (delta >= WINDOW_BITS) {
                bitmap.fill(0L)
            } else {
                advanceBitmap(delta.toInt())
            }
            highest = sequence
            bitmap[0] = bitmap[0] or 1L
            return true
        }
        val delta = highest - sequence
        if (delta >= WINDOW_BITS) return false
        val word = (delta / 64).toInt()
        val bit = (delta % 64).toInt()
        if (bitmap[word] and (1L shl bit) != 0L) return false
        bitmap[word] = bitmap[word] or (1L shl bit)
        return true
    }

    // Shifts the bitmap toward newer sequences as one little-endian WINDOW_BITS-bit integer.
    private fun advanceBitmap(delta: Int) {
        val words = delta / 64
        val bits = delta % 64
        if (words >= bitmap.size) {
            bitmap.fill(0L)
            return
        }
        for (i in bitmap.indices.reversed()) {
            var value = if (i >= words) bitmap[i - words] shl bits else 0L
            if (bits > 0 && i > words) value = value or (bitmap[i - words - 1] ushr (64 - bits))
            bitmap[i] = value
        }
    }

    private companion object {
        const val WINDOW_BITS = 2048
    }
}

/** Per-session crypto material: directional TCP codecs plus the UDP codec. */
class SessionCrypto(keys: SessionKeys) {
    val tcpC2S = TcpCipher(keys.tcpKeyC2S)
    val tcpS2C = TcpCipher(keys.tcpKeyS2C)
    val udp = UdpCipher(keys.udpKeyC2S, keys.udpNoncePrefix)
}

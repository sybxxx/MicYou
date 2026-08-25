package com.lanrhyme.micyou.network

import com.lanrhyme.micyou.settings.Settings
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecureChannelTest {
    private class FakeSettings : Settings {
        private val values = mutableMapOf<String, Any>()

        override fun getString(key: String, defaultValue: String): String =
            values[key] as? String ?: defaultValue

        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue

        override fun putLong(key: String, value: Long) {
            values[key] = value
        }

        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key] as? Boolean ?: defaultValue

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
        }

        override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue

        override fun putInt(key: String, value: Int) {
            values[key] = value
        }

        override fun getFloat(key: String, defaultValue: Float): Float = values[key] as? Float ?: defaultValue

        override fun putFloat(key: String, value: Float) {
            values[key] = value
        }
    }

    private fun hex(value: String): ByteArray = SecureChannel.hexToBytes(value)

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Test
    fun crossLanguageVector_matchesRustGoldenValues() {
        val chCore = hex("00112233445566778899aabbccddeeff")
        val shCore = hex("fedcba98765432100123456789abcdef")
        val shared = ByteArray(32) { 0x42 }

        val transcript = SecureChannel.transcriptHash(chCore, shCore)
        assertEquals(
            "68a8dc9addad9b69d0369cd4e82f2b13061841442bb293697f1e67e7bfa19a0e",
            toHex(transcript)
        )

        val master = SecureChannel.hkdfSha256(transcript, shared, "MICYOU-KEYS-V1".toByteArray(), 104)
        assertEquals(
            "e17231f9fec9ff7ae501c5a47f19f78a9a5c2bd9a7576f7fb3eac165b612c6ef" +
                "fd0638a0184d349c2545c3d91fb52aa7b1ffa68d57e8dc254e3c43a65968dc87" +
                "0d30fa0271f0432e175f5389c7b2cbfa4f073589bc0ee7dda981db7519edfb94" +
                "952ea86b26331aca",
            toHex(master)
        )

        val secrets = SecureChannel.deriveHandshakeSecrets(transcript, shared)
        assertContentEquals(master.copyOfRange(0, 32), secrets.keys.tcpKeyC2S)
        assertContentEquals(master.copyOfRange(32, 64), secrets.keys.tcpKeyS2C)
        assertContentEquals(master.copyOfRange(64, 96), secrets.keys.udpKeyC2S)
        assertContentEquals(master.copyOfRange(96, 100), secrets.keys.udpNoncePrefix)
        assertEquals("406831", SecureChannel.sasText(secrets.sasValue))
    }

    @Test
    fun signedTranscript_prefixesTagAndBigEndianLengths() {
        val input = SecureChannel.signedTranscript(hex("aabb"), hex("cc"))
        assertEquals(
            "4d4943594f552d5345435552452d5631" + "00000002aabb" + "00000001cc",
            toHex(input)
        )
    }

    @Test
    fun canonicalCores_dependOnlyOnFieldValues_notOnEncoding() {
        val hello = SecureClientHello(
            suiteMask = SECURE_SUITE_V1,
            ephemeralPubKey = hex("010203"),
            identityPubKey = hex("040506"),
            deviceName = "Pixel"
        )
        // TAG-stripped canon: u32be(len)||part per field, in fixed field order.
        val expectedCore =
            "00000001" + "00000003" + "010203" + "00000003" + "040506" + "00000005" + "506978656c"
        assertEquals(expectedCore, toHex(SecureChannel.clientHelloCore(hello)))

        // The signed core is unaffected by whatever signature the message carries.
        val signed = hello.copy(transcriptSignature = ByteArray(64) { (it + 1).toByte() })
        assertContentEquals(
            SecureChannel.clientHelloCore(hello),
            SecureChannel.clientHelloCore(signed)
        )

        assertEquals(
            "00000001" + "00000000" + "00000000",
            toHex(SecureChannel.serverHelloCore(suite = 1, identityPubKey = ByteArray(0), ephemeralPubKey = ByteArray(0)))
        )
    }

    @Test
    fun messageWrapper_secureFieldNumbers_matchProtoDefinition() {
        val proto = ProtoBuf { }
        assertEquals("5001", toHex(proto.encodeToByteArray(MessageWrapper(secureResult = SecureResult(accepted = true)))))
        assertEquals("4a026162", toHex(proto.encodeToByteArray(MessageWrapper(secureConfirm = SecureConfirm(deviceName = "ab")))))
        assertEquals("42020801", toHex(proto.encodeToByteArray(MessageWrapper(secureServerHello = SecureServerHello(suite = 1)))))

        val decoded = proto.decodeFromByteArray<MessageWrapper>(
            proto.encodeToByteArray(MessageWrapper(secureClientHello = SecureClientHello(deviceName = "phone")))
        )
        assertEquals("phone", decoded.secureClientHello?.deviceName)
        assertNull(decoded.secureResult)
    }

    @Test
    fun ed25519_matchesRfc8032TestVector() {
        val seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val publicKey = SecureChannel.identityPublicKey(seed)
        assertEquals(
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            toHex(publicKey)
        )

        val signature = SecureChannel.sign(seed, ByteArray(0))
        assertEquals(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            toHex(signature)
        )
        assertTrue(SecureChannel.verify(publicKey, ByteArray(0), signature))

        val tampered = signature.copyOf().also { size -> size[size.size - 1] = (size[size.size - 1].toInt() xor 1).toByte() }
        assertFalse(SecureChannel.verify(publicKey, ByteArray(0), tampered))
        assertFalse(SecureChannel.verify(publicKey, byteArrayOf(1), signature))
    }

    @Test
    fun fullLocalHandshake_producesInteroperatingSessions() {
        val clientSeed = SecureChannel.generateIdentitySeed()
        val clientEphPrivate = SecureChannel.generateX25519PrivateKey()

        var hello = SecureClientHello(
            suiteMask = SECURE_SUITE_V1,
            ephemeralPubKey = SecureChannel.x25519PublicKey(clientEphPrivate),
            identityPubKey = SecureChannel.identityPublicKey(clientSeed),
            deviceName = "Pixel 9"
        )
        hello = hello.copy(
            transcriptSignature = SecureChannel.sign(
                clientSeed,
                SecureChannel.signedTranscript(SecureChannel.clientHelloCore(hello))
            )
        )
        val chFull = SecureChannel.clientHelloWireBytes(hello)

        val parsedHello = ProtoBuf { }.decodeFromByteArray<SecureClientHello>(chFull)
        val chCore = SecureChannel.clientHelloCore(parsedHello)
        assertTrue(
            SecureChannel.verify(
                parsedHello.identityPubKey,
                SecureChannel.signedTranscript(chCore),
                parsedHello.transcriptSignature
            )
        )

        val serverSeed = SecureChannel.generateIdentitySeed()
        val serverEphPrivate = SecureChannel.generateX25519PrivateKey()
        val serverCoreValues = Triple(
            SECURE_SUITE_V1,
            SecureChannel.identityPublicKey(serverSeed),
            SecureChannel.x25519PublicKey(serverEphPrivate)
        )
        val shCore = SecureChannel.serverHelloCore(
            serverCoreValues.first,
            serverCoreValues.second,
            serverCoreValues.third
        )
        val serverHello = SecureServerHello(
            suite = serverCoreValues.first,
            identityPubKey = serverCoreValues.second,
            ephemeralPubKey = serverCoreValues.third,
            transcriptSignature = SecureChannel.sign(
                serverSeed,
                SecureChannel.signedTranscript(chCore, shCore)
            )
        )
        val shFull = ProtoBuf { }.encodeToByteArray(serverHello)

        val parsedServerHello = ProtoBuf { }.decodeFromByteArray<SecureServerHello>(shFull)
        val parsedShCore = SecureChannel.serverHelloCore(
            parsedServerHello.suite,
            parsedServerHello.identityPubKey,
            parsedServerHello.ephemeralPubKey
        )
        assertTrue(
            SecureChannel.verify(
                parsedServerHello.identityPubKey,
                SecureChannel.signedTranscript(chCore, parsedShCore),
                parsedServerHello.transcriptSignature
            )
        )

        val sharedClient = SecureChannel.x25519SharedSecret(clientEphPrivate, parsedServerHello.ephemeralPubKey)
        val sharedServer = SecureChannel.x25519SharedSecret(serverEphPrivate, parsedHello.ephemeralPubKey)
        assertContentEquals(sharedClient, sharedServer)

        val transcript = SecureChannel.transcriptHash(chCore, parsedShCore)
        val secretsClient = SecureChannel.deriveHandshakeSecrets(transcript, sharedClient)
        val secretsServer = SecureChannel.deriveHandshakeSecrets(transcript, sharedServer)
        val sasClient = SecureChannel.sasText(secretsClient.sasValue)
        assertEquals(sasClient, SecureChannel.sasText(secretsServer.sasValue))
        assertEquals(6, sasClient.length)
        assertTrue(sasClient.all { it.isDigit() })

        val clientSession = SecureChannel.sessionCrypto(secretsClient.keys)
        val serverSession = SecureChannel.sessionCrypto(secretsServer.keys)

        val payload = "c2s audio frame".toByteArray()
        val header = SecureChannel.tcpFrameHeader(payload.size + SecureChannel.AEAD_TAG_BYTES)
        val sealed = clientSession.tcpC2S.seal(payload)
        assertEquals(payload.size + SecureChannel.AEAD_TAG_BYTES, sealed.size)
        assertContentEquals(payload, serverSession.tcpC2S.open(header, sealed))

        val reply = "s2c ack".toByteArray()
        val replyHeader = SecureChannel.tcpFrameHeader(reply.size + SecureChannel.AEAD_TAG_BYTES)
        val sealedReply = serverSession.tcpS2C.seal(reply)
        assertContentEquals(reply, clientSession.tcpS2C.open(replyHeader, sealedReply))

        val second = clientSession.tcpC2S.seal(payload)
        assertFalse(second.contentEquals(sealed))
        assertContentEquals(payload, serverSession.tcpC2S.open(header, second))

        val datagram = clientSession.udp.seal("udp audio".toByteArray())
        assertEquals(UDP_SECURE_MAGIC, SecureChannel.readI32Be(datagram.copyOf(4), 0))
        assertContentEquals("udp audio".toByteArray(), serverSession.udp.open(datagram))
    }

    @Test
    fun tcpCipher_tamperedPayloadOrHeader_failsToOpen() {
        val key = ByteArray(32) { 0x21 }
        val sender = TcpCipher(key)
        val receiver = TcpCipher(key)

        val payload = "secret-frame".toByteArray()
        val header = SecureChannel.tcpFrameHeader(payload.size + SecureChannel.AEAD_TAG_BYTES)
        val corrupted = sender.seal(payload).copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 1).toByte()
        assertFailsWithSecureChannel { receiver.open(header, corrupted) }

        val sealed = sender.seal(payload)
        val wrongHeader = header.copyOf().also { it[7] = (it[7].toInt() xor 1).toByte() }
        assertFailsWithSecureChannel { receiver.open(wrongHeader, sealed) }
    }

    @Test
    fun tcpCipher_outOfOrderFrames_rejectedThenFreshReceiverAcceptsInOrder() {
        val key = ByteArray(32) { 0x33 }
        val sender = TcpCipher(key)
        val receiver = TcpCipher(key)

        val header = SecureChannel.tcpFrameHeader("frame-x".length + SecureChannel.AEAD_TAG_BYTES)
        val first = sender.seal("frame-0".toByteArray())
        val second = sender.seal("frame-1".toByteArray())

        assertFailsWithSecureChannel { receiver.open(header, second) }
        assertFailsWithSecureChannel { receiver.open(header, first) }

        val freshReceiver = TcpCipher(key)
        assertContentEquals("frame-0".toByteArray(), freshReceiver.open(header, first))
        assertContentEquals("frame-1".toByteArray(), freshReceiver.open(header, second))
    }

    @Test
    fun udpCipher_replayScenarios_matchRustReferenceBehavior() {
        val key = ByteArray(32) { 0x44 }
        val prefix = byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d)
        val sealer = UdpCipher(key, prefix)
        val opener = UdpCipher(key, prefix)

        for (sequence in 0L..2L) {
            opener.open(sealer.sealWithSequence(sequence, byteArrayOf(0x70)))
        }
        assertReplayRejected { opener.open(sealer.sealWithSequence(1, byteArrayOf(0x70))) }

        opener.open(sealer.sealWithSequence(1500, byteArrayOf(0x70)))
        assertReplayRejected { opener.open(sealer.sealWithSequence(1, byteArrayOf(0x70))) }

        opener.open(sealer.sealWithSequence(1000, byteArrayOf(0x70)))

        opener.open(sealer.sealWithSequence(4000, byteArrayOf(0x70)))

        assertReplayRejected { opener.open(sealer.sealWithSequence(1000, byteArrayOf(0x70))) }

        opener.open(sealer.sealWithSequence(3999, byteArrayOf(0x70)))
        assertReplayRejected { opener.open(sealer.sealWithSequence(3999, byteArrayOf(0x70))) }
    }

    @Test
    fun udpCipher_forgedDatagram_failsAuthWithoutAdvancingWindow() {
        val key = ByteArray(32) { 0x55 }
        val prefix = byteArrayOf(1, 2, 3, 4)
        val opener = UdpCipher(key, prefix)

        val forged = ByteArray(4 + 8 + 32)
        SecureChannel.writeI32Be(forged, 0, UDP_SECURE_MAGIC)
        SecureChannel.putU64Be(forged, 4, 7)
        assertFailsWithSecureChannel { opener.open(forged) }

        val genuine = UdpCipher(key, prefix).sealWithSequence(7, "real".toByteArray())
        assertContentEquals("real".toByteArray(), opener.open(genuine))
    }

    @Test
    fun udpCipher_rejectsShortDatagramsAndBadMagic() {
        val cipher = UdpCipher(ByteArray(32) { 1 }, ByteArray(4) { 2 })
        assertFailsWithSecureChannel { cipher.open(ByteArray(20)) }
        val badMagic = ByteArray(28)
        SecureChannel.writeI32Be(badMagic, 0, UDP_PACKET_MAGIC)
        assertFailsWithSecureChannel { cipher.open(badMagic) }
    }

    @Test
    fun base64_rfc4648VectorsAndRoundTrip() {
        val vectors = listOf(
            "" to "",
            "f" to "Zg==",
            "fo" to "Zm8=",
            "foo" to "Zm9v",
            "foob" to "Zm9vYg==",
            "fooba" to "Zm9vYmE=",
            "foobar" to "Zm9vYmFy"
        )
        for ((plain, encoded) in vectors) {
            assertEquals(encoded, SecureChannel.base64Encode(plain.toByteArray()))
            assertEquals(plain, SecureChannel.base64Decode(encoded).toString(Charsets.UTF_8))
            assertEquals(plain.toByteArray().toList(), SecureChannel.base64Decode(encoded.dropLastWhile { it == '=' }).toList())
        }

        val binary = byteArrayOf(0xfb.toByte(), 0xef.toByte(), 0xbe.toByte())
        assertEquals("++++", SecureChannel.base64Encode(binary))
        assertEquals("----", SecureChannel.base64Encode(binary, urlSafe = true))
        assertContentEquals(binary, SecureChannel.base64Decode("----"))

        val random = ByteArray(117).also { java.util.Random(7).nextBytes(it) }
        assertContentEquals(random, SecureChannel.base64Decode(SecureChannel.base64Encode(random)))
        assertContentEquals(random, SecureChannel.base64Decode(SecureChannel.base64Encode(random, urlSafe = true)))
    }

    @Test
    fun identityHelpers_persistSeedAndPairedServers() {
        val settings = FakeSettings()
        val seed = SecureChannel.obtainIdentitySeed(settings)
        assertEquals(32, seed.size)
        assertContentEquals(seed, SecureChannel.obtainIdentitySeed(settings))
        assertFalse(seed.contentEquals(SecureChannel.obtainIdentitySeed(FakeSettings())))

        val publicKey = SecureChannel.identityPublicKey(seed)
        assertEquals(32, publicKey.size)

        val storageKey = SecureChannel.pairedServerStorageKey(publicKey)
        assertTrue(storageKey.startsWith(SECURE_PAIRED_SERVER_KEY_PREFIX))
        assertFalse(storageKey.any { it == '+' || it == '/' || it == '=' })
        assertTrue(storageKey.substring(SECURE_PAIRED_SERVER_KEY_PREFIX.length).all { it.isLetterOrDigit() || it == '-' || it == '_' })

        assertNull(SecureChannel.pairedServerDisplayName(settings, publicKey))
        SecureChannel.rememberPairedServer(settings, publicKey, "Studio PC")
        assertEquals("Studio PC", SecureChannel.pairedServerDisplayName(settings, publicKey))

        val otherSeed = SecureChannel.obtainIdentitySeed(FakeSettings())
        assertNull(SecureChannel.pairedServerDisplayName(settings, SecureChannel.identityPublicKey(otherSeed)))
    }

    @Test
    fun sasText_zeroPadsToSixDigits() {
        assertEquals("000000", SecureChannel.sasText(0))
        assertEquals("000042", SecureChannel.sasText(42))
        assertEquals("254472", SecureChannel.sasText(254472))
        assertEquals("999999", SecureChannel.sasText(999999))
    }

    @Test
    fun selfTest_passesOnThisBuild() {
        assertTrue(SecureChannel.selfTest())
    }

    private inline fun assertFailsWithSecureChannel(block: () -> Unit) {
        val result = runCatching(block)
        val error = result.exceptionOrNull()
        assertTrue(error is SecureChannelException, "expected SecureChannelException but got $error")
        assertFalse(error is ReplayDetectedException, "unexpected replay rejection")
    }

    private inline fun assertReplayRejected(block: () -> Unit) {
        val result = runCatching(block)
        assertTrue(result.exceptionOrNull() is ReplayDetectedException, "expected ReplayDetectedException but got ${result.exceptionOrNull()}")
    }
}

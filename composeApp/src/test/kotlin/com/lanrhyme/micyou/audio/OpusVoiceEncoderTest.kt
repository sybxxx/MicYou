package com.lanrhyme.micyou.audio

import io.github.jaredmdobson.concentus.OpusDecoder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpusVoiceEncoderTest {
    @Test
    fun lowerComplexityStillEncodesContinuousTwentyMillisecondFrames() {
        val sampleRate = 48_000
        val frameSamples = opusSamplesPerFrame(sampleRate)
        val encoder = createOpusVoiceEncoder(sampleRate, channels = 1)
        val decoder = OpusDecoder(sampleRate, 1)
        val packet = ByteArray(4_000)
        val decoded = ShortArray(frameSamples)

        assertEquals(5, encoder.getComplexity())
        assertEquals(960, frameSamples)

        repeat(12) { frame ->
            val pcm = ShortArray(frameSamples) { offset ->
                val sample = frame * frameSamples + offset
                (12_000 * sin(2 * PI * 440 * sample / sampleRate)).toInt().toShort()
            }
            val encodedBytes = encoder.encode(pcm, 0, frameSamples, packet, 0, packet.size)
            assertTrue(encodedBytes in 1..packet.size, "Frame $frame did not encode")
            assertEquals(960, decoder.decode(packet, 0, encodedBytes, decoded, 0, frameSamples, false))
            assertTrue(decoded.any { it.toInt() != 0 }, "Frame $frame decoded to silence")
        }
    }
}

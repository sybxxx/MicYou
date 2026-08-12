package com.lanrhyme.micyou.audio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerAudioHealthPolicyTest {
    @Test
    fun legacyDesktopWithoutHealthSignalIsNotRejected() {
        assertFalse(
            shouldFailForServerAudioHealth(
                supported = false,
                muted = false,
                nowMs = 10_000,
                lastHealthyAudioHealthMs = 0
            )
        )
    }

    @Test
    fun mutedStreamDoesNotTriggerAudioHealthReconnect() {
        assertFalse(
            shouldFailForServerAudioHealth(
                supported = true,
                muted = true,
                nowMs = 10_000,
                lastHealthyAudioHealthMs = 0
            )
        )
    }

    @Test
    fun supportedDesktopTriggersReconnectAfterHealthTimeout() {
        assertFalse(
            shouldFailForServerAudioHealth(
                supported = true,
                muted = false,
                nowMs = SERVER_AUDIO_HEALTH_TIMEOUT_MS - 1,
                lastHealthyAudioHealthMs = 0
            )
        )
        assertTrue(
            shouldFailForServerAudioHealth(
                supported = true,
                muted = false,
                nowMs = SERVER_AUDIO_HEALTH_TIMEOUT_MS,
                lastHealthyAudioHealthMs = 0
            )
        )
    }
}

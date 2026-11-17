package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.viewmodel.StreamState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioSessionStartPolicyTest {
    @Test
    fun failedSessionStillCleaningUpIsNotReused() {
        assertFalse(
            shouldReuseActiveAudioSession(
                desiredRunning = true,
                hasActiveJob = true,
                state = StreamState.Error
            )
        )
    }

    @Test
    fun liveConnectingOrStreamingSessionIsReused() {
        assertTrue(shouldReuseActiveAudioSession(true, true, StreamState.Connecting))
        assertTrue(shouldReuseActiveAudioSession(true, true, StreamState.Streaming))
    }

    @Test
    fun inactiveSessionIsNeverReused() {
        assertFalse(shouldReuseActiveAudioSession(false, true, StreamState.Streaming))
        assertFalse(shouldReuseActiveAudioSession(true, false, StreamState.Streaming))
    }
}

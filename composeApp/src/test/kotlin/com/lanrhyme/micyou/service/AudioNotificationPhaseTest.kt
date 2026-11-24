package com.lanrhyme.micyou.service

import com.lanrhyme.micyou.viewmodel.StreamState
import kotlin.test.Test
import kotlin.test.assertEquals

class AudioNotificationPhaseTest {
    @Test
    fun startupWaitsForStreamingBeforeAnnouncingAudio() {
        val connecting = AudioNotificationPhase.Idle.after(StreamState.Connecting)
        assertEquals(AudioNotificationPhase.Connecting, connecting)
        assertEquals(connecting, connecting.after(StreamState.Connecting))
        assertEquals(AudioNotificationPhase.Streaming, connecting.after(StreamState.Streaming))

        // ACTION_START may be delivered after an already successful handshake.
        assertEquals(AudioNotificationPhase.Streaming, AudioNotificationPhase.Idle.after(StreamState.Streaming))
    }

    @Test
    fun anEstablishedStreamReportsReconnectUntilAudioResumes() {
        val reconnecting = AudioNotificationPhase.Streaming.after(StreamState.Connecting)
        assertEquals(AudioNotificationPhase.Reconnecting, reconnecting)
        assertEquals(reconnecting, reconnecting.after(StreamState.Connecting))
        assertEquals(AudioNotificationPhase.Streaming, reconnecting.after(StreamState.Streaming))
    }

    @Test
    fun stopOrFailureEndsReconnectHistory() {
        for (phase in AudioNotificationPhase.entries) {
            assertEquals(AudioNotificationPhase.Idle, phase.after(StreamState.Idle))
            val failed = phase.after(StreamState.Error)
            assertEquals(AudioNotificationPhase.Idle, failed)
            assertEquals(AudioNotificationPhase.Connecting, failed.after(StreamState.Connecting))
        }
    }
}

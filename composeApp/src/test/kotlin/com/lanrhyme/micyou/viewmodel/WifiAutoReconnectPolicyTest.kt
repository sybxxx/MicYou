package com.lanrhyme.micyou.viewmodel

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WifiAutoReconnectPolicyTest {
    @Test
    fun establishedWifiIntentKeepsRetryingUntilEngineStreams() {
        assertTrue(shouldContinueWifiAutoReconnect(true, true, ConnectionMode.Wifi, StreamState.Error))
        assertTrue(shouldContinueWifiAutoReconnect(true, true, ConnectionMode.Wifi, StreamState.Idle))
        assertTrue(shouldContinueWifiAutoReconnect(true, true, ConnectionMode.Wifi, StreamState.Connecting))
        assertFalse(shouldContinueWifiAutoReconnect(true, true, ConnectionMode.Wifi, StreamState.Streaming))
    }

    @Test
    fun retryStopsWhenIntentSessionOrModeNoLongerMatches() {
        assertFalse(shouldContinueWifiAutoReconnect(false, true, ConnectionMode.Wifi, StreamState.Error))
        assertFalse(shouldContinueWifiAutoReconnect(true, false, ConnectionMode.Wifi, StreamState.Error))
        assertFalse(shouldContinueWifiAutoReconnect(true, true, ConnectionMode.Usb, StreamState.Error))
    }
}

package com.lanrhyme.micyou.network

import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceDiscoveryPolicyTest {
    @Test
    fun updatedServiceAddressReplacesStaleResolution() {
        val old = DiscoveredDevice("MicYou (PC)", "192.168.2.8", 8554)
        val updated = old.copy(hostAddress = "192.168.2.9")

        assertEquals(listOf(updated), replaceDiscoveredDevice(listOf(old), updated))
    }

    @Test
    fun differentServicesRemainAvailable() {
        val first = DiscoveredDevice("MicYou (PC A)", "192.168.2.8", 8554)
        val second = DiscoveredDevice("MicYou (PC B)", "192.168.2.9", 8554)

        assertEquals(listOf(first, second), replaceDiscoveredDevice(listOf(first), second))
    }
}

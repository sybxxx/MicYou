package com.lanrhyme.micyou.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceDiscoveryPolicyTest {
    @Test
    fun updatedServiceAddressReplacesStaleResolution() {
        val old = device("MicYou (PC)", "192.168.2.8")
        val updated = old.copy(hostAddresses = listOf("192.168.2.9"))

        assertEquals(listOf(updated), replaceDiscoveredDevice(listOf(old), updated))
    }

    @Test
    fun laterIpv4IsPreferredAndIpv6OnlyUpdateKeepsIt() {
        val v6 = device("MicYou (PC)", "fd00::1")
        val v4 = device(v6.name, "192.168.2.8")
        val dual = replaceDiscoveredDevice(listOf(v6), v4)
        val result = replaceDiscoveredDevice(dual, device(v6.name, "fd00::2"))
        assertEquals(listOf("192.168.2.8", "fd00::2"), result.single().hostAddresses)
        assertEquals(listOf("192.168.2.8", "fd00::1"), dual.single().hostAddresses)
    }

    @Test
    fun aNewPortDoesNotInheritAddressesFromTheOldResolution() {
        val old = device("MicYou (PC)", "192.168.2.8", "fd00::1")
        val updated = device(old.name, "fd00::2").copy(port = 8556)
        assertEquals(listOf(updated), replaceDiscoveredDevice(listOf(old), updated))
    }

    @Test
    fun differentServicesRemainAvailable() {
        val first = device("MicYou (PC A)", "192.168.2.8")
        val second = device("MicYou (PC B)", "192.168.2.9")

        assertEquals(listOf(first, second), replaceDiscoveredDevice(listOf(first), second))
    }

    @Test
    fun savedRouteCanAttachOnALaterExactMatchWithoutChangingItsAnchor() {
        val anchor = ConnectionEndpoint("192.168.2.8", 8554)
        val selection = SelectedDiscoveredDevice(anchor)
        val v6 = device("MicYou (PC)", "fd00::1")
        selection.refresh(listOf(v6))
        assertNull(selection.serviceName)
        assertEquals(emptyList(), selection.route.endpoints.value)

        selection.refresh(replaceDiscoveredDevice(listOf(v6), device(v6.name, anchor.host)))
        assertEquals(v6.name, selection.serviceName)
        assertEquals(listOf(anchor, ConnectionEndpoint("fd00::1", 8554)), selection.route.endpoints.value)
        assertEquals(anchor, selection.route.anchor)
    }

    @Test
    fun emptyDiscoveryAndAnotherPcDoNotTakeOverSelectedRoute() {
        val anchor = ConnectionEndpoint("192.168.2.8", 8554)
        val selection = SelectedDiscoveredDevice(anchor, "MicYou (PC A)")
        val moved = device("MicYou (PC A)", "192.168.2.10")
        selection.refresh(listOf(moved))
        assertEquals(moved.endpoints, selection.route.endpoints.value)
        selection.refresh(emptyList())
        selection.refresh(listOf(device("MicYou (PC B)", anchor.host)))
        assertEquals(emptyList(), selection.route.endpoints.value)
        assertEquals(anchor, selection.preferredEndpoint)
        assertEquals("MicYou (PC A)", selection.serviceName)
    }

    private fun device(name: String, vararg addresses: String) =
        DiscoveredDevice(name, addresses.toList(), 8554)
}

package com.lanrhyme.micyou.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger

/**
 * Pins the app process to the physical LAN network so its sockets bypass any active
 * VPN tunnel. While a VPN is connected, Android routes every socket through the tunnel
 * and the NSD daemon sends mDNS queries on the default network only, which makes LAN
 * discovery and streaming impossible even though Wi-Fi is still connected. Binding the
 * process to the Wi-Fi/Ethernet network restores direct LAN reachability.
 */
object LanRouteBinder {
    private const val TAG = "LanRouteBinder"

    private val lock = Any()
    private var holders = 0
    private var boundNetwork: Network? = null

    /**
     * Registers a holder and pins the process to the best LAN network (Wi-Fi preferred,
     * Ethernet fallback). Returns the pinned network, or null when none is usable.
     * Every call must be paired with [release], even when null is returned.
     */
    fun acquire(): Network? = synchronized(lock) {
        holders += 1
        val context = ContextHelper.getContext() ?: run {
            Logger.w(TAG, "No application context available")
            return null
        }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run {
            Logger.w(TAG, "ConnectivityManager not available")
            return null
        }

        val lanNetwork = findLanNetwork(manager)
        if (lanNetwork == null) {
            Logger.w(TAG, "No Wi-Fi/Ethernet network available (vpnDefault=${isVpnDefault(manager)})")
            return null
        }

        // Rebind on every acquire: the system silently clears the process binding
        // when the bound network bounces, so a cached check would keep routing new
        // sockets through the default (possibly VPN) network.
        val bound = try {
            manager.bindProcessToNetwork(lanNetwork)
        } catch (e: Exception) {
            Logger.w(TAG, "bindProcessToNetwork failed: ${e.message}")
            false
        }
        if (!bound) {
            Logger.w(TAG, "Failed to bind process to LAN network")
            return null
        }

        boundNetwork = lanNetwork
        Logger.i(TAG, "Process pinned to LAN network $lanNetwork (vpnDefault=${isVpnDefault(manager)})")
        lanNetwork
    }

    /** Removes one holder; restores the system default route when the last holder leaves. */
    fun release() {
        synchronized(lock) {
            if (holders <= 0) return
            holders -= 1
            if (holders > 0 || boundNetwork == null) return
            boundNetwork = null
        }
        val context = ContextHelper.getContext() ?: return
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            manager.bindProcessToNetwork(null)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to restore default network binding: ${e.message}")
        }
    }

    private fun findLanNetwork(manager: ConnectivityManager): Network? {
        var wifi: Network? = null
        var ethernet: Network? = null
        for (network in manager.allNetworks) {
            val caps = manager.getNetworkCapabilities(network) ?: continue
            // VPN tunnels can never reach LAN devices, so they are never candidates.
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (wifi == null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifi = network
            if (ethernet == null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ethernet = network
        }
        return wifi ?: ethernet
    }

    private fun isVpnDefault(manager: ConnectivityManager): Boolean {
        val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}

package com.lanrhyme.micyou.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger

data class DiscoveredDevice(
    val name: String,
    val hostAddresses: List<String>,
    val port: Int
) {
    val hostAddress: String get() = hostAddresses.first()
    val endpoints: List<ConnectionEndpoint> get() = hostAddresses.map { ConnectionEndpoint(it, port) }
}

/** Only an unconfigured target may follow the sole visible server. */
internal fun findDiscoveredDevice(
    devices: List<DiscoveredDevice>,
    serviceName: String?,
    anchor: ConnectionEndpoint?
): DiscoveredDevice? = when {
    serviceName != null -> devices.singleOrNull { it.name == serviceName }
    anchor != null -> devices.singleOrNull { anchor in it.endpoints }
    else -> devices.singleOrNull()
}

/** Candidate addresses for a selected service; a saved literal must first match a discovery. */
internal class SelectedDiscoveredDevice(anchor: ConnectionEndpoint, serviceName: String? = null) {
    var serviceName: String? = serviceName
        private set
    private val endpoints = MutableStateFlow(emptyList<ConnectionEndpoint>())
    val route = DiscoveredRoute(anchor, endpoints.asStateFlow())
    val preferredEndpoint: ConnectionEndpoint get() = endpoints.value.firstOrNull() ?: route.anchor

    fun refresh(devices: List<DiscoveredDevice>) {
        val device = findDiscoveredDevice(devices, serviceName, route.anchor)
        if (device != null) serviceName = device.name
        endpoints.value = device?.endpoints.orEmpty()
    }
}

/** Keep the other address family if a resolution only included one. A new
 * resolution replaces the addresses of the families it actually reported. */
internal fun replaceDiscoveredDevice(
    devices: List<DiscoveredDevice>,
    device: DiscoveredDevice
): List<DiscoveredDevice> {
    // These are resolved address literals, never DNS names.
    fun isIpv6(address: String) = ':' in address
    val previous = devices.firstOrNull { it.name == device.name && it.port == device.port }
    val reportedFamilies = device.hostAddresses.map(::isIpv6).toSet()
    val addresses = (device.hostAddresses +
        previous?.hostAddresses.orEmpty().filter { isIpv6(it) !in reportedFamilies })
        .distinct().sortedWith(compareBy(::isIpv6))
    return devices.filterNot { it.name == device.name } + device.copy(hostAddresses = addresses)
}

class DeviceDiscoveryManager constructor() {
    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile
    private var discoveryGeneration = 0L
    private var discoveryActive = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lanRouteHeld = false
    private val resolutionLock = Any()
    private val pendingResolution = mutableMapOf<String, Any>()

    fun startDiscovery() {
        if (discoveryActive) return
        val generation = synchronized(resolutionLock) {
            pendingResolution.clear()
            _discoveredDevices.value = emptyList()
            ++discoveryGeneration
        }
        val context = ContextHelper.getContext() ?: run {
            Logger.w("DeviceDiscovery", "No application context available")
            return
        }

        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: run {
            Logger.w("DeviceDiscovery", "NsdManager not available")
            return
        }
        acquireMulticastLock(context)
        // Keep discovery traffic on the LAN even when a VPN owns the default route.
        val lanNetwork = LanRouteBinder.acquire()
        lanRouteHeld = true

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (generation != discoveryGeneration) return
                Logger.i("DeviceDiscovery", "Discovery started for $serviceType")
                discoveryActive = true
                _isDiscovering.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                val ticket = Any()
                synchronized(resolutionLock) {
                    if (generation != discoveryGeneration || name in pendingResolution) return
                    pendingResolution[name] = ticket
                }
                try {
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            synchronized(resolutionLock) {
                                if (generation != discoveryGeneration || pendingResolution[name] !== ticket) return
                                pendingResolution.remove(name)
                            }
                            Logger.w("DeviceDiscovery", "Resolve failed: $errorCode for ${info.serviceName}")
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val addresses = resolvedHostAddresses(info)
                            synchronized(resolutionLock) {
                                if (generation != discoveryGeneration || pendingResolution[name] !== ticket) return
                                pendingResolution.remove(name)
                                if (addresses.isNotEmpty() && info.port in 1..65535) {
                                    Logger.i("DeviceDiscovery", "Resolved: $name at $addresses:${info.port}")
                                    _discoveredDevices.value = replaceDiscoveredDevice(
                                        _discoveredDevices.value,
                                        DiscoveredDevice(name, addresses, info.port)
                                    )
                                }
                            }
                        }
                    })
                } catch (e: Exception) {
                    synchronized(resolutionLock) {
                        if (pendingResolution[name] === ticket) pendingResolution.remove(name)
                    }
                    Logger.w("DeviceDiscovery", "Failed to resolve $name: ${e.message}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(resolutionLock) {
                    if (generation != discoveryGeneration) return
                    val name = serviceInfo.serviceName
                    Logger.i("DeviceDiscovery", "Service lost: $name")
                    pendingResolution.remove(name)
                    _discoveredDevices.value = _discoveredDevices.value.filterNot { it.name == name }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (generation != discoveryGeneration) return
                Logger.i("DeviceDiscovery", "Discovery stopped")
                discoveryActive = false
                _isDiscovering.value = false
                releaseMulticastLock()
                releaseLanRoute()
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (generation != discoveryGeneration) return
                Logger.w("DeviceDiscovery", "Discovery start failed: $errorCode")
                discoveryActive = false
                _isDiscovering.value = false
                releaseMulticastLock()
                releaseLanRoute()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                if (generation != discoveryGeneration) return
                Logger.w("DeviceDiscovery", "Discovery stop failed: $errorCode")
            }
        }

        // Mark discovery as active before the asynchronous NSD callback arrives so a
        // connection request made immediately after launch can wait for its result.
        discoveryActive = true
        _isDiscovering.value = true

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && lanNetwork != null) {
                // Without an explicit network the NSD daemon sends its queries over the
                // default route, which vanishes into the VPN tunnel when one is active.
                nsdManager?.discoverServices(
                    "_micyou._tcp.",
                    NsdManager.PROTOCOL_DNS_SD,
                    lanNetwork,
                    ContextCompat.getMainExecutor(context),
                    discoveryListener!!
                )
            } else {
                nsdManager?.discoverServices("_micyou._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            }
        } catch (e: Exception) {
            Logger.e("DeviceDiscovery", "Failed to start discovery", e)
            discoveryActive = false
            _isDiscovering.value = false
            releaseMulticastLock()
            releaseLanRoute()
        }
    }

    fun stopDiscovery() {
        synchronized(resolutionLock) {
            discoveryGeneration++
            pendingResolution.clear()
        }
        if (discoveryActive) {
            try {
                discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
            } catch (e: Exception) {
                Logger.w("DeviceDiscovery", "Error stopping discovery: ${e.message}")
            }
        }
        discoveryListener = null
        discoveryActive = false
        _isDiscovering.value = false
        releaseMulticastLock()
        releaseLanRoute()
        // Don't clear device list here — let restartDiscovery() manage it
    }

    @Suppress("DEPRECATION")
    private fun resolvedHostAddresses(info: NsdServiceInfo): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val addresses = info.hostAddresses.mapNotNull { it.hostAddress }
            if (addresses.isNotEmpty()) return addresses
        }
        return listOfNotNull(info.host?.hostAddress)
    }

    private fun releaseLanRoute() {
        if (!lanRouteHeld) return
        lanRouteHeld = false
        LanRouteBinder.release()
    }

    private fun acquireMulticastLock(context: Context) {
        if (multicastLock?.isHeld == true) return
        try {
            @Suppress("DEPRECATION")
            multicastLock = (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("${context.packageName}:micyou-discovery")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            Logger.w("DeviceDiscovery", "Failed to acquire Wi-Fi multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
}

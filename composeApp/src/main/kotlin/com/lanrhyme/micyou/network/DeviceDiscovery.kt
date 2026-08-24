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
import kotlinx.coroutines.flow.update
import java.util.Collections
import com.lanrhyme.micyou.network.DeviceDiscoveryManager
import com.lanrhyme.micyou.network.DiscoveredDevice
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger

data class DiscoveredDevice(
    val name: String,
    val hostAddress: String,
    val port: Int
)

/** Returns an endpoint that can be selected without user input only when it is unambiguous. */
internal fun selectSingleDiscoveredDevice(devices: List<DiscoveredDevice>): DiscoveredDevice? =
    devices.singleOrNull()

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
    private val pendingResolution: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    fun startDiscovery() {
        if (discoveryActive) return
        _discoveredDevices.value = emptyList()
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

        val generation = ++discoveryGeneration
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (generation != discoveryGeneration) return
                Logger.i("DeviceDiscovery", "Discovery started for $serviceType")
                discoveryActive = true
                _isDiscovering.value = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (generation != discoveryGeneration) return
                val name = serviceInfo.serviceName
                if (name !in pendingResolution) {
                    pendingResolution.add(name)
                    try {
                        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                if (generation != discoveryGeneration) return
                                Logger.w("DeviceDiscovery", "Resolve failed: $errorCode for ${info.serviceName}")
                                pendingResolution.remove(info.serviceName)
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (generation != discoveryGeneration) return
                                pendingResolution.remove(info.serviceName)
                                val host = info.host?.hostAddress ?: return
                                val port = info.port
                                val resolvedName = info.serviceName

                                Logger.i("DeviceDiscovery", "Resolved: $resolvedName at $host:$port")

                                _discoveredDevices.update { current ->
                                    current.filterNot { it.hostAddress == host && it.port == port } +
                                            DiscoveredDevice(name = resolvedName, hostAddress = host, port = port)
                                }
                            }
                        })
                    } catch (e: Exception) {
                        Logger.w("DeviceDiscovery", "Failed to resolve $name: ${e.message}")
                        pendingResolution.remove(name)
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (generation != discoveryGeneration) return
                Logger.i("DeviceDiscovery", "Service lost: ${serviceInfo.serviceName}")
                pendingResolution.remove(serviceInfo.serviceName)
                _discoveredDevices.update { current ->
                    current.filterNot { it.name == serviceInfo.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (generation != discoveryGeneration) return
                Logger.i("DeviceDiscovery", "Discovery stopped")
                discoveryActive = false
                _isDiscovering.value = false
                releaseMulticastLock()
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
        discoveryGeneration++
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
        pendingResolution.clear()
        releaseMulticastLock()
        releaseLanRoute()
        // Don't clear device list here — let restartDiscovery() manage it
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

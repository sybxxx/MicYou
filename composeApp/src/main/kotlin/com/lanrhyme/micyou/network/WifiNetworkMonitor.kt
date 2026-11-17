package com.lanrhyme.micyou.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Emits when Android reports that a Wi-Fi network is available again. */
class WifiNetworkMonitor {
    private val _networkAvailableEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val networkAvailableEvents: SharedFlow<Unit> = _networkAvailableEvents.asSharedFlow()

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var activeNetwork: Network? = null

    fun start() {
        if (networkCallback != null) return

        val context = ContextHelper.getContext() ?: run {
            Logger.w("WifiNetworkMonitor", "No application context available")
            return
        }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: run {
                Logger.w("WifiNetworkMonitor", "ConnectivityManager not available")
                return
            }
        val request = NetworkRequest.Builder()
            // Do not require INTERNET: MicYou only needs the local Wi-Fi route to reach the PC.
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = synchronized(this@WifiNetworkMonitor) {
                    val changed = activeNetwork != network
                    activeNetwork = network
                    changed
                }
                if (changed) {
                    Logger.i("WifiNetworkMonitor", "Wi-Fi network became available")
                    _networkAvailableEvents.tryEmit(Unit)
                }
            }

            override fun onLost(network: Network) {
                synchronized(this@WifiNetworkMonitor) {
                    if (activeNetwork == network) activeNetwork = null
                }
                Logger.i("WifiNetworkMonitor", "Wi-Fi network was lost")
            }
        }

        try {
            manager.registerNetworkCallback(request, callback)
            connectivityManager = manager
            networkCallback = callback
        } catch (e: Exception) {
            Logger.w("WifiNetworkMonitor", "Failed to register Wi-Fi network callback: ${e.message}")
        }
    }

    fun stop() {
        val manager = connectivityManager
        val callback = networkCallback
        connectivityManager = null
        networkCallback = null
        activeNetwork = null
        if (manager != null && callback != null) {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                Logger.w("WifiNetworkMonitor", "Failed to unregister Wi-Fi network callback: ${e.message}")
            }
        }
    }
}

package com.lanrhyme.micyou.network

import kotlinx.coroutines.flow.StateFlow

data class ConnectionEndpoint(val host: String, val port: Int)

/** Addresses of one selected service, in preference order. The anchor never follows discovery. */
class DiscoveredRoute(
    val anchor: ConnectionEndpoint,
    val endpoints: StateFlow<List<ConnectionEndpoint>>
)

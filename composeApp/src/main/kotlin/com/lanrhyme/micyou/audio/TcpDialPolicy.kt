package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.network.ConnectionEndpoint
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

private const val TCP_CONNECT_TIMEOUT_MS = 4_000L
// Give discovery a short chance to provide a fresh address if all current candidates failed.
private const val ADDRESS_UPDATE_GRACE_MS = 4_000L
private const val MAX_DISCOVERED_DIALS = 3

internal fun preferredEndpointChanged(
    selected: ConnectionEndpoint,
    preferredWhenSelected: ConnectionEndpoint?,
    addresses: List<ConnectionEndpoint>,
    attempted: Set<ConnectionEndpoint>
): Boolean = addresses.firstOrNull { it == selected || it !in attempted }
    ?.let { it != selected && it != preferredWhenSelected } == true

/** Own each pending dial before entering a cancellable operation. */
internal class TcpDialAttempt : Closeable {
    val selector = SelectorManager(Dispatchers.IO)
    var socket: Socket? = null
        private set

    suspend fun connect(endpoint: ConnectionEndpoint) {
        socket = aSocket(selector).tcp().connect(endpoint.host, endpoint.port) {
            keepAlive = true
            // This is the read/write limit. TCP establishment has its own deadline below.
            socketTimeout = 10_000L
            sendBufferSize = 8 * 1024
            noDelay = true
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        runCatching { selector.close() }
    }
}

/**
 * Dial only one endpoint at a time. A preferred address can supersede a pending TCP
 * dial, never an established TCP connection or a user's pairing prompt. On success
 * [adopt] transfers the attempt to the session without a cancellable suspension.
 */
internal suspend fun <A : Closeable> dialTcpCandidates(
    fallback: ConnectionEndpoint,
    endpoints: StateFlow<List<ConnectionEndpoint>>?,
    create: () -> A,
    connect: suspend (A, ConnectionEndpoint) -> Unit,
    adopt: (ConnectionEndpoint, A) -> Unit,
    preferFallbackFirst: Boolean = false,
    connectTimeoutMs: Long = TCP_CONNECT_TIMEOUT_MS,
    addressGraceMs: Long = ADDRESS_UPDATE_GRACE_MS
) {
    val attempted = mutableSetOf<ConnectionEndpoint>()
    var lastFailure: IOException? = null
    repeat(if (endpoints == null) 1 else MAX_DISCOVERED_DIALS) {
        val preferred = endpoints?.value?.firstOrNull { it !in attempted }
        val endpoint = fallback.takeIf { preferFallbackFirst && attempted.isEmpty() }
            ?: preferred
            ?: fallback.takeIf { it !in attempted }
            ?: endpoints?.let { addresses ->
                withTimeoutOrNull(addressGraceMs) {
                    addresses.first { list -> list.any { it !in attempted } }
                        .first { it !in attempted }
                }
            }
            ?: throw lastFailure ?: SocketTimeoutException("TCP connection timed out")

        attempted.add(endpoint)
        val attempt = create()
        var adopted = false
        try {
            val connected = withTimeoutOrNull(connectTimeoutMs) {
                coroutineScope {
                    val connecting = async { runCatching { connect(attempt, endpoint) } }
                    val addressChanged = endpoints?.let { addresses ->
                        async {
                            // Discovery may briefly be empty during a restart; only a real
                            // replacement address supersedes this dial.
                            addresses.first { preferredEndpointChanged(endpoint, preferred, it, attempted) }
                        }
                    }
                    select<Boolean> {
                        connecting.onAwait { result ->
                            addressChanged?.cancel()
                            result.getOrThrow()
                            true
                        }
                        addressChanged?.onAwait {
                            connecting.cancel()
                            false
                        }
                    }
                }
            } ?: throw SocketTimeoutException("TCP connection timed out")
            if (connected) {
                currentCoroutineContext().ensureActive()
                adopt(endpoint, attempt)
                adopted = true
                return
            }
        } catch (e: IOException) {
            lastFailure = e
        } finally {
            if (!adopted) attempt.close()
        }
    }
    throw lastFailure ?: SocketTimeoutException("TCP connection timed out; discovered addresses changed during dialing")
}

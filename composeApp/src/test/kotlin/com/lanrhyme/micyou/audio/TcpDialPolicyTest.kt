package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.network.ConnectionEndpoint
import java.io.Closeable
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TcpDialPolicyTest {
    private val v6 = ConnectionEndpoint("fd00::141", 8554)
    private val v4 = ConnectionEndpoint("192.168.0.141", 8554)

    private class Attempt(private val onClose: () -> Unit = {}) : Closeable {
        override fun close() = onClose()
    }

    @Test
    fun rediscoveredIpv4SupersedesAPendingIpv6DialWithoutWaitingForTimeout() = runBlocking<Unit> {
        val candidates = MutableStateFlow(listOf(v6))
        val firstDial = CompletableDeferred<Unit>()
        val attempts = mutableListOf<ConnectionEndpoint>()
        var open = 0
        var adopted: Attempt? = null
        withTimeout(1_500) {
            val dial = async {
                dialTcpCandidates(
                    fallback = v6,
                    endpoints = candidates,
                    create = {
                        assertEquals(0, open, "A new selector must not overlap the old dial")
                        open++
                        Attempt { open-- }
                    },
                    connect = { _, endpoint ->
                        attempts += endpoint
                        if (endpoint == v6) {
                            firstDial.complete(Unit)
                            awaitCancellation()
                        }
                    },
                    adopt = { endpoint, attempt ->
                        assertEquals(v4, endpoint)
                        adopted = attempt
                    },
                    connectTimeoutMs = 10_000
                )
            }
            firstDial.await()
            candidates.value = listOf(v4, v6)
            dial.await()
        }
        assertEquals(listOf(v6, v4), attempts)
        assertEquals(1, open)
        assertNotNull(adopted).close()
        assertEquals(0, open)
    }

    @Test
    fun fastSocketFailureLetsAnAvailableAddressBeTried() = runBlocking<Unit> {
        var failedClosed = false
        val attempts = mutableListOf<ConnectionEndpoint>()
        var adopted: Attempt? = null
        dialTcpCandidates(
            fallback = v6,
            endpoints = MutableStateFlow(listOf(v6, v4)),
            create = { Attempt { failedClosed = true } },
            connect = { _, endpoint ->
                attempts += endpoint
                if (endpoint == v6) throw ConnectException("no route")
                assertTrue(failedClosed)
            },
            adopt = { endpoint, attempt ->
                assertEquals(v4, endpoint)
                adopted = attempt
            }
        )
        assertEquals(listOf(v6, v4), attempts)
        assertNotNull(adopted).close()
    }

    @Test
    fun successfulIpv6IsNotInterruptedByLaterDiscovery() = runBlocking<Unit> {
        val endpoints = MutableStateFlow(listOf(v6))
        val attempts = mutableListOf<ConnectionEndpoint>()
        var closes = 0
        var adopted: Attempt? = null
        dialTcpCandidates(v6, endpoints, { Attempt { closes++ } }, { _, target -> attempts += target }, { target, attempt ->
            assertEquals(v6, target)
            adopted = attempt
        })
        endpoints.value = listOf(v4)
        assertEquals(listOf(v6), attempts)
        assertEquals(0, closes)
        assertNotNull(adopted).close()
    }

    @Test
    fun explicitlySelectedLegacyAddressCanConnectEvenWhenDiscoveryAlreadyPrefersAnother() = runBlocking<Unit> {
        val attempted = mutableListOf<ConnectionEndpoint>()
        dialTcpCandidates(
            fallback = v6,
            endpoints = MutableStateFlow(listOf(v4, v6)),
            create = { Attempt() },
            connect = { _, target -> attempted += target },
            adopt = { target, attempt ->
                assertEquals(v6, target)
                attempt.close()
            },
            preferFallbackFirst = true
        )
        assertEquals(listOf(v6), attempted)
    }

    @Test
    fun cancelledDialClosesOnlyItsOwnPendingAttemptAndDoesNotRetry() = runBlocking<Unit> {
        val started = CompletableDeferred<Unit>()
        val candidates = MutableStateFlow(listOf(v6))
        var created = 0
        var closed = 0
        withTimeout(1_500) {
            val job = launch {
                dialTcpCandidates(v6, candidates, {
                    created++
                    Attempt { closed++ }
                }, { _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                }, { _, _ -> error("Cancelled attempt must not be adopted") })
            }
            started.await()
            job.cancelAndJoin()
            candidates.value = listOf(v4)
        }
        assertEquals(1, created)
        assertEquals(1, closed)
    }

    @Test
    fun manualOrLoopbackDialHasOneAttemptAndTypedConnectTimeout() = runBlocking<Unit> {
        for (endpoint in listOf(v4, v6, ConnectionEndpoint("127.0.0.1", 8554))) {
            val attempts = mutableListOf<ConnectionEndpoint>()
            var closes = 0
            withTimeout(1_500) {
                assertFailsWith<SocketTimeoutException> {
                    dialTcpCandidates(endpoint, null, { Attempt { closes++ } }, { _, target ->
                        attempts += target
                        awaitCancellation()
                    }, { _, _ -> error("Timed out attempt must not be adopted") }, connectTimeoutMs = 30)
                }
            }
            assertEquals(listOf(endpoint), attempts)
            assertEquals(1, closes)
        }
    }
}

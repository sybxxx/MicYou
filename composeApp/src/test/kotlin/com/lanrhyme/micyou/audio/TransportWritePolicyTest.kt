package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.network.MessageWrapper
import com.lanrhyme.micyou.network.PongMessage
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportWritePolicyTest {
    @Test
    fun oldAudioExpiresUsingMonotonicAge() {
        assertFalse(isQueuedAudioExpired(100, 300))
        assertTrue(isQueuedAudioExpired(100, 301))
        assertFalse(isQueuedAudioExpired(100, 99))
    }

    @Test
    fun pongBypassesQueuedAudio() = runBlocking {
        val control = Channel<MessageWrapper>(2)
        val audio = Channel<QueuedAudioFrame>(2)
        val pong = MessageWrapper(pong = PongMessage(123))
        audio.send(QueuedAudioFrame(MessageWrapper(), 10))
        control.send(pong)
        assertEquals(pong, receiveNextOutgoingFrame(control, audio).message)
        assertEquals(10, receiveNextOutgoingFrame(control, audio).enqueuedAtMs)
        control.close()
        audio.close()
    }

    @Test
    fun aStalledWriteClosesItsSocketAndUnblocks() = runBlocking {
        val socketClosed = CompletableDeferred<Unit>()
        var closes = 0
        withTimeout(1_000) {
            assertFailsWith<SocketTimeoutException> {
                withTransportWriteDeadline(
                    timeoutMs = 30,
                    closeTransport = { closes++; socketClosed.complete(Unit) }
                ) { socketClosed.await() }
            }
        }
        assertEquals(1, closes)
    }

    @Test
    fun completedWriteCancelsTheCloseGuard() = runBlocking {
        var closed = false
        assertEquals(42, withTransportWriteDeadline(timeoutMs = 30, closeTransport = { closed = true }) { 42 })
        delay(60)
        assertFalse(closed)
    }

    @Test
    fun cancelledWriteCannotCloseALaterTransport() = runBlocking {
        var closed = false
        val entered = CompletableDeferred<Unit>()
        val writer = launch {
            withTransportWriteDeadline(timeoutMs = 50, closeTransport = { closed = true }) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        writer.cancelAndJoin()
        delay(70)
        assertFalse(closed)
    }
}

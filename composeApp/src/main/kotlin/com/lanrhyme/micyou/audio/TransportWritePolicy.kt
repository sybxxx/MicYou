package com.lanrhyme.micyou.audio

import com.lanrhyme.micyou.network.MessageWrapper
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select

internal const val MAX_QUEUED_AUDIO_AGE_MS = 200L
internal const val TRANSPORT_WRITE_DEADLINE_MS = 500L

internal data class QueuedAudioFrame(val message: MessageWrapper, val enqueuedAtMs: Long)

internal fun isQueuedAudioExpired(enqueuedAtMs: Long, nowMs: Long): Boolean =
    nowMs >= enqueuedAtMs && nowMs - enqueuedAtMs > MAX_QUEUED_AUDIO_AGE_MS

internal suspend fun receiveNextOutgoingFrame(
    control: Channel<MessageWrapper>,
    audio: Channel<QueuedAudioFrame>
): QueuedAudioFrame = control.tryReceive().getOrNull()?.let { QueuedAudioFrame(it, 0L) }
    ?: select {
        control.onReceive { QueuedAudioFrame(it, 0L) }
        audio.onReceive { it }
    }

/** A separate IO guard closes the actual socket; cancellation alone cannot stop a blocked write. */
internal suspend fun <T> withTransportWriteDeadline(
    closeTransport: () -> Unit,
    timeoutMs: Long = TRANSPORT_WRITE_DEADLINE_MS,
    write: suspend () -> T
): T = coroutineScope {
    val expired = AtomicBoolean(false)
    val deadline = launch(Dispatchers.IO) {
        delay(timeoutMs)
        expired.set(true)
        runCatching(closeTransport)
    }
    try {
        val result = write()
        if (expired.get()) throw SocketTimeoutException("Audio transport write stalled for $timeoutMs ms")
        result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (expired.get()) {
            throw SocketTimeoutException("Audio transport write stalled for $timeoutMs ms").apply { initCause(e) }
        }
        throw e
    } finally {
        deadline.cancel()
    }
}

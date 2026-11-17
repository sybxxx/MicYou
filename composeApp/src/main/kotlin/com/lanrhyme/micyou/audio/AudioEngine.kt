/*
 * MicYou — Turns your Android device into a high-quality PC microphone.
 * Copyright (C) 2026 LanRhyme <https://github.com/MicYou-Dev/MicYou>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version, with the MicYou Plugin Exception.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */

package com.lanrhyme.micyou.audio
import com.lanrhyme.micyou.R

import android.content.Intent
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import com.lanrhyme.micyou.network.SECURE_SUITE_V1
import com.lanrhyme.micyou.network.SessionCrypto
import com.lanrhyme.micyou.network.UdpCipher
import com.lanrhyme.micyou.network.SecureChannel
import com.lanrhyme.micyou.network.SecureChannelException
import com.lanrhyme.micyou.network.LanRouteBinder
import com.lanrhyme.micyou.network.SecureClientHello
import com.lanrhyme.micyou.network.SecureConfirm
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.EOFException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import com.lanrhyme.micyou.audio.AndroidAudioSource
import com.lanrhyme.micyou.audio.AudioLevelData
import com.lanrhyme.micyou.network.AudioPacketMessage
import com.lanrhyme.micyou.network.AudioPacketMessageOrdered
import com.lanrhyme.micyou.network.CLIENT_PLATFORM
import com.lanrhyme.micyou.network.CODEC_OPUS
import com.lanrhyme.micyou.network.ConnectMessage
import com.lanrhyme.micyou.network.HANDSHAKE_CLIENT
import com.lanrhyme.micyou.network.HANDSHAKE_SERVER
import com.lanrhyme.micyou.network.calculateUdpPort
import com.lanrhyme.micyou.network.MessageWrapper
import com.lanrhyme.micyou.network.MAX_CONTROL_PAYLOAD_SIZE
import com.lanrhyme.micyou.network.PACKET_MAGIC
import com.lanrhyme.micyou.network.UDP_CUSTOM_HEADER_SIZE
import com.lanrhyme.micyou.network.UDP_MAX_DATAGRAM_SIZE
import com.lanrhyme.micyou.network.UDP_PACKET_MAGIC
import com.lanrhyme.micyou.network.UDP_PCM_PAYLOAD_SIZE
import com.lanrhyme.micyou.service.AudioService
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.getString
import com.lanrhyme.micyou.util.Logger
import com.lanrhyme.micyou.viewmodel.ConnectionMode
import com.lanrhyme.micyou.viewmodel.StreamState
import com.lanrhyme.micyou.viewmodel.TransportProtocol
import com.lanrhyme.micyou.network.hasControlMessage
import com.lanrhyme.micyou.network.MuteMessage
import com.lanrhyme.micyou.network.PluginMessage
import com.lanrhyme.micyou.network.PongMessage
internal suspend fun awaitJobWithin(job: Job, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) {
        job.join()
        true
    } == true

internal enum class AudioReadStatus { Data, Waiting, Failed, Stalled }

/** libopus 支持的采样率；44.1kHz 不在其中，捕获时会映射到 48kHz。 */
private val OPUS_SAMPLE_RATES = intArrayOf(8000, 12000, 16000, 24000, 48000)

internal fun classifyAudioRead(
    readCount: Int,
    lastSuccessfulReadMs: Long,
    nowMs: Long,
    stallTimeoutMs: Long
): AudioReadStatus = when {
    readCount < 0 -> AudioReadStatus.Failed
    readCount > 0 -> AudioReadStatus.Data
    nowMs - lastSuccessfulReadMs >= stallTimeoutMs -> AudioReadStatus.Stalled
    else -> AudioReadStatus.Waiting
}

internal const val SERVER_AUDIO_HEALTH_TIMEOUT_MS = 4_000L

internal fun shouldFailForServerAudioHealth(
    supported: Boolean,
    muted: Boolean,
    nowMs: Long,
    lastHealthyAudioHealthMs: Long
): Boolean = supported &&
    !muted &&
    nowMs >= lastHealthyAudioHealthMs &&
    nowMs - lastHealthyAudioHealthMs >= SERVER_AUDIO_HEALTH_TIMEOUT_MS

internal fun shouldReuseActiveAudioSession(
    desiredRunning: Boolean,
    hasActiveJob: Boolean,
    state: StreamState
): Boolean = desiredRunning &&
    hasActiveJob &&
    (state == StreamState.Connecting || state == StreamState.Streaming)

/** Process-wide single-owner state machine for a native resource. */
internal class RecorderOwnerGate<T : Any> {
    internal sealed interface State<T : Any> {
        data class Creating<T : Any>(
            val token: Any,
            val completion: CompletableDeferred<Unit>
        ) : State<T>
        data class Active<T : Any>(val resource: T) : State<T>
        data class Teardown<T : Any>(
            val resource: T,
            val completion: CompletableDeferred<Unit>
        ) : State<T>
    }

    internal sealed interface TeardownResult {
        data class Started(val completion: CompletableDeferred<Unit>) : TeardownResult
        data class Existing(val completion: CompletableDeferred<Unit>) : TeardownResult
        data class Rejected(val ownerIsActive: Boolean) : TeardownResult
    }

    private val lock = Any()
    private var owner: State<T>? = null

    fun create(
        blockedMessage: () -> String,
        discard: (T) -> Unit = {},
        factory: () -> T
    ): T {
        val token = Any()
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            check(owner == null, blockedMessage)
            owner = State.Creating(token, completion)
        }

        val resource = try {
            factory()
        } catch (failure: Throwable) {
            synchronized(lock) {
                val current = owner
                if (current is State.Creating && current.token === token) {
                    owner = null
                    current.completion.completeExceptionally(failure)
                }
            }
            throw failure
        }

        val installed = synchronized(lock) {
            val current = owner
            if (current is State.Creating && current.token === token) {
                owner = State.Active(resource)
                current.completion.complete(Unit)
                true
            } else {
                false
            }
        }
        if (!installed) {
            discard(resource)
            throw IllegalStateException("Recorder owner changed while the resource was being created")
        }
        return resource
    }

    fun beginTeardown(resource: T): TeardownResult = synchronized(lock) {
        when (val current = owner) {
            is State.Creating -> TeardownResult.Rejected(ownerIsActive = false)
            is State.Active -> {
                if (current.resource !== resource) {
                    TeardownResult.Rejected(ownerIsActive = true)
                } else {
                    val completion = CompletableDeferred<Unit>()
                    owner = State.Teardown(resource, completion)
                    TeardownResult.Started(completion)
                }
            }
            is State.Teardown -> {
                if (current.resource === resource) {
                    TeardownResult.Existing(current.completion)
                } else {
                    TeardownResult.Rejected(ownerIsActive = false)
                }
            }
            null -> TeardownResult.Rejected(ownerIsActive = false)
        }
    }

    fun completeTeardown(resource: T, completion: CompletableDeferred<Unit>) {
        synchronized(lock) {
            val current = owner
            if (current is State.Teardown &&
                current.resource === resource && current.completion === completion
            ) {
                owner = null
                completion.complete(Unit)
            }
        }
    }
}

/** Associates the globally visible engine with the exact session and recorder it owns. */
internal class ActiveEngineOwner<E : Any, R : Any> {
    private data class Entry<E : Any, R : Any>(
        val engine: WeakReference<E>,
        val session: Any,
        val owner: R
    )

    private val lock = Any()
    private var entry: Entry<E, R>? = null

    fun publish(engine: E, session: Any, owner: R) = synchronized(lock) {
        entry = Entry(WeakReference(engine), session, owner)
    }

    fun get(): E? = synchronized(lock) { entry?.engine?.get() }

    fun isCurrent(engine: E, session: Any, owner: R): Boolean = synchronized(lock) {
        val current = entry
        current?.engine?.get() === engine && current.session === session && current.owner === owner
    }

    fun clearIfCurrent(engine: E, session: Any, owner: R): Boolean = synchronized(lock) {
        val current = entry
        if (current?.engine?.get() === engine && current.session === session && current.owner === owner) {
            entry = null
            true
        } else {
            false
        }
    }
}

/**
 * Single owner of the recorder and its cancellable streaming session. Network recovery replaces
 * transports without releasing AudioRecord; explicit stop and configuration changes tear down
 * that same owner before another generation can start. Acquisition, publication and teardown stay
 * together here to preserve this ordering while following the upstream capture/Opus pipeline.
 * Cryptographic primitives live in SecureChannel; bounded writes and control priority live in
 * TransportWritePolicy, independent of recorder ownership.
 */
class AudioEngine constructor() {
    companion object {
        private const val MAX_UDP_CONSECUTIVE_FAILURES = 500
        private const val HEARTBEAT_TIMEOUT_MS = 5000L
        private const val AUDIO_READ_STALL_TIMEOUT_MS = 5000L
        private const val AUDIO_READ_IDLE_DELAY_MS = 5L
        private const val STOP_TIMEOUT_MS = 5000L
        private const val CLOSE_FINAL_WAIT_MS = 15000L
        private const val UI_AUDIO_LEVEL_UPDATE_INTERVAL_MS = 75L
        private const val FEC_GROUP_SIZE = 12 // 每 12 个包生成一个 FEC 包（约 87ms @44100Hz）
        private const val RECORDER_TEARDOWN_BLOCKED_ERROR =
            "Native audio recorder teardown is still in progress; restart the app process to recover"
        private val sessionCounter = AtomicLong(System.currentTimeMillis().coerceAtLeast(1L))
        private val recorderOwnerGate = RecorderOwnerGate<AudioRecord>()

        /**
         * AudioRecord.stop() can remain blocked in native code indefinitely. The process gate keeps
         * a hard upper bound of one detached worker and one retained recorder across AudioEngine
         * instances. The worker closure contains only the recorder snapshot and completion signal.
         */
        private fun stopAndReleaseRecorderAsync(recorder: AudioRecord?): CompletableDeferred<Unit>? {
            if (recorder == null) return null
            return when (val teardown = recorderOwnerGate.beginTeardown(recorder)) {
                is RecorderOwnerGate.TeardownResult.Existing -> teardown.completion
                is RecorderOwnerGate.TeardownResult.Rejected -> {
                    val failure = IllegalStateException(
                        "Recorder teardown rejected because the process owner does not match"
                    )
                    val isStarted = try {
                        recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING
                    } catch (e: Exception) {
                        Logger.e("AudioEngine", "Fatal recorder owner mismatch with unknown recorder state", e)
                        true
                    }
                    if (!isStarted) {
                        try {
                            recorder.release()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to release rejected unstarted recorder: ${e.message}")
                        }
                    } else {
                        Logger.e(
                            "AudioEngine",
                            "Fatal recorder owner mismatch: refusing to start a second stop thread",
                            failure
                        )
                    }
                    CompletableDeferred<Unit>().also { it.completeExceptionally(failure) }
                }
                is RecorderOwnerGate.TeardownResult.Started -> {
                    val completion = teardown.completion
                    Thread({
                        try {
                            recorder.stop()
                        } catch (_: Exception) {
                        }
                        try {
                            recorder.release()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to release recorder: ${e.message}")
                        } finally {
                            recorderOwnerGate.completeTeardown(recorder, completion)
                        }
                    }, "AudioRecord-stop").apply {
                        isDaemon = true
                        start()
                    }
                    completion
                }
            }
        }

        private val activeEngineOwner = ActiveEngineOwner<AudioEngine, AudioRecord>()

        private fun getActiveEngine(): AudioEngine? = activeEngineOwner.get()

        fun requestDisconnectFromNotification() {
            getActiveEngine()?.stop()
        }

        fun isStreaming(): Boolean {
            val state = getActiveEngine()?.currentStreamState()
            return state == StreamState.Streaming || state == StreamState.Connecting
        }
    }
    private val _state = MutableStateFlow(StreamState.Idle)
    val streamState: Flow<StreamState> = _state

    fun currentStreamState(): StreamState = _state.value
    private val _audioLevels = MutableStateFlow(0f)
    val audioLevels: Flow<Float> = _audioLevels

    private val _audioLevelData = MutableStateFlow(AudioLevelData.SILENT)
    val audioLevelData: Flow<AudioLevelData> = _audioLevelData
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: Flow<String?> = _lastError

    private val _isMuted = MutableStateFlow(false)
    val isMuted: Flow<Boolean> = _isMuted

    // 跨设备插件消息（MessageWrapper 字段 7）：reader 循环收到的桌面端插件
    // 消息在此广播；SoundPad 等控制器自行按 source 过滤。DROP_OLDEST 保证
    // reader 循环永不因消费者慢而阻塞。
    private val _pluginMessages = MutableSharedFlow<PluginMessage>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val pluginMessages: SharedFlow<PluginMessage> = _pluginMessages.asSharedFlow()

    private val _sessionEncrypted = MutableStateFlow(false)
    val sessionEncrypted: Flow<Boolean> = _sessionEncrypted

    data class PairingPrompt(val sas: String, val peer: String)
    private val _pairingPrompt = MutableStateFlow<PairingPrompt?>(null)
    val pairingPrompt: Flow<PairingPrompt?> = _pairingPrompt
    private var pairingDeferred: CompletableDeferred<Boolean>? = null

    /** Called by the UI once the user compared the SAS codes and decided. */
    fun answerPairingPrompt(accepted: Boolean) {
        pairingDeferred?.complete(accepted)
        _pairingPrompt.value = null
    }

    // Peers that answered a secure hello without encryption support; retried
    // as plaintext for the rest of the process lifetime.
    private val plainOnlyPeers = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // Peers that completed at least one encrypted handshake on this engine instance.
    // A missing secure reply from such a peer is a transient network failure, never
    // evidence of a legacy plaintext-only server.
    private val securedPeers = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Volatile
    private var activeCrypto: SessionCrypto? = null

    private suspend fun sendSealedFrame(output: ByteWriteChannel, session: SessionCrypto, plain: ByteArray) {
        val header = SecureChannel.tcpFrameHeader(plain.size + SecureChannel.AEAD_TAG_BYTES)
        val sealed = session.tcpC2S.seal(plain)
        output.writeFully(header)
        output.writeFully(sealed)
        output.flush()
    }

    private val secureSettings by lazy { com.lanrhyme.micyou.settings.SettingsFactory.getSettings() }


    private data class StopResources(
        val sessionJob: Job?,
        val recorder: AudioRecord?,
        val tcpSocket: Socket?,
        val input: ByteReadChannel?,
        val output: ByteWriteChannel?,
        val udpSocket: DatagramSocket?,
        val channel: Channel<MessageWrapper>?
    )

    private var job: Job? = null
    private var stopTimedOutJob: Job? = null
    private var stopTimedOutResources: StopResources? = null
    private var configRestartJob: Job? = null
    private var configRestartRequest: Long = 0
    private val startStopMutex = Mutex()
    private val stopOperationMutex = Mutex()
    private val lifecycleJob = SupervisorJob()
    private val lifecycleScope = CoroutineScope(lifecycleJob + Dispatchers.IO)
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(cleanupJob + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val closeLock = Any()
    private var closeJob: Job? = null
    private val proto = ProtoBuf { }

    @Volatile
    private var sendChannel: Channel<MessageWrapper>? = null
    @Volatile
    private var activeRecorder: AudioRecord? = null
    @Volatile
    private var activeTcpSocket: Socket? = null
    @Volatile
    private var activeInput: ByteReadChannel? = null
    @Volatile
    private var activeOutput: ByteWriteChannel? = null

    @Volatile
    private var udpSocket: DatagramSocket? = null
    @Volatile
    private var udpServerAddress: InetSocketAddress? = null

    @Volatile
    private var enableNS: Boolean = false
    @Volatile
    private var enableAGC: Boolean = false
    @Volatile
    private var audioSource: AndroidAudioSource = AndroidAudioSource.Mic

    private val reconnectWakeup = Channel<Unit>(Channel.CONFLATED)

    fun wakeupReconnect() { reconnectWakeup.trySend(Unit) }

    fun updateTarget(ip: String, port: Int) {
        savedIp = ip
        savedPort = port
        wakeupReconnect()
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    private var savedIp: String = ""
    private var savedPort: Int = 0
    private var savedMode: ConnectionMode = ConnectionMode.Wifi
    private var savedSampleRate: SampleRate = SampleRate.Rate44100
    private var savedChannelCount: ChannelCount = ChannelCount.Mono
    private var savedAudioFormat: AudioFormat = AudioFormat.PCM_16BIT
    private var savedTransportProtocol: TransportProtocol = TransportProtocol.Both
    @Volatile
    private var desiredRunning: Boolean = false
    @Volatile
    private var retryLastFailure: Boolean = true
    fun canRetryLastFailure(): Boolean = retryLastFailure
    @Volatile
    private var lifecycleGeneration: Long = 0
    private var startRequestGeneration: Long = 0


    suspend fun start(
        ip: String,
        port: Int,
        mode: ConnectionMode,
        isClient: Boolean,
        sampleRate: SampleRate,
        channelCount: ChannelCount,
        audioFormat: AudioFormat,
        transportProtocol: TransportProtocol
    ) {
        if (!isClient) return
        check(!closed.get()) { "AudioEngine is closed" }
        Logger.i("AudioEngine", "Starting Android AudioEngine: mode=$mode, protocol=$transportProtocol, ip=$ip, port=$port, sampleRate=${sampleRate.value}, channels=${channelCount.label}, format=${audioFormat.label}")
        _lastError.value = null
        retryLastFailure = true

        savedIp = ip
        savedPort = port
        savedMode = mode
        savedSampleRate = sampleRate
        savedChannelCount = channelCount
        savedAudioFormat = audioFormat
        savedTransportProtocol = transportProtocol

        val connectionComplete = CompletableDeferred<Unit>()
        var launchedJob: Job? = null
        var stoppingJob: Job? = null
        var requestToken = 0L
        var firstAttempt = true
        var startIgnored = false
        var sessionId = sessionCounter.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }
        while (launchedJob == null && !startIgnored) {
            stoppingJob = null
            startStopMutex.withLock {
                if (firstAttempt) {
                    if (closed.get()) throw IllegalStateException("AudioEngine is closed")
                    configRestartJob?.takeIf { it !== currentCoroutineContext()[Job] }?.cancel()
                    val wasDesiredRunning = desiredRunning
                    val timedOutJob = stopTimedOutJob
                    if (timedOutJob != null && !timedOutJob.isCompleted) {
                        throw IllegalStateException("Previous audio session did not stop within $STOP_TIMEOUT_MS ms")
                    }
                    if (timedOutJob?.isCompleted == true) {
                        stopTimedOutJob = null
                        stopTimedOutResources = null
                    }
                    if (shouldReuseActiveAudioSession(wasDesiredRunning, job?.isCompleted == false, _state.value)) {
                        Logger.w("AudioEngine", "AudioEngine already running, ignoring start request")
                        connectionComplete.complete(Unit)
                        startIgnored = true
                        return@withLock
                    }
                    desiredRunning = true
                    startRequestGeneration++
                    requestToken = startRequestGeneration
                } else if (!desiredRunning || requestToken != startRequestGeneration) {
                    throw CancellationException("Audio start request superseded while waiting for previous session")
                }

                val currentJob = job
                if (currentJob != null && !currentJob.isCompleted) {
                    Logger.i("AudioEngine", "Waiting for the previous stopped generation before starting")
                    stoppingJob = currentJob
                } else {
                    lifecycleGeneration++
                    val sessionGeneration = lifecycleGeneration
                    _state.value = StreamState.Connecting
                    val sessionJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
                    var recorder: AudioRecord? = null
                    var sessionUdpSocket: DatagramSocket? = null
                    var sessionUdpAddress: InetSocketAddress? = null
                    var sessionNoiseSuppressor: NoiseSuppressor? = null
                    var sessionAutomaticGainControl: AutomaticGainControl? = null
                    var sessionUdpConsecutiveFailures = 0
                    // Monotonic clock: a wall-clock jump must not fake a heartbeat timeout.
                    // Written by the reader coroutine, read by the capture loop.
                    val sessionLastPingReceivedTime = java.util.concurrent.atomic.AtomicLong(SystemClock.elapsedRealtime())
                    val channel = Channel<MessageWrapper>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    startStopMutex.withLock {
                        if (lifecycleGeneration == sessionGeneration && desiredRunning) {
                            sendChannel = channel
                        }
                    }

                    var tcpSocket: Socket? = null
                    var input: ByteReadChannel? = null
                    var output: ByteWriteChannel? = null
                    var selectorManager: SelectorManager? = null
                    var closeConnection: () -> Unit = {}
                    var writerJob: Job? = null
                    var readerJob: Job? = null
                    val serverAudioHealthSupported = AtomicBoolean(false)
                    val lastHealthyAudioHealthTime = AtomicLong(SystemClock.elapsedRealtime())
                    val audioChannel = Channel<QueuedAudioFrame>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                    var lanRouteHeld = false
                    val sessionJobIdentity = requireNotNull(currentCoroutineContext()[Job])

                    try {
                        if (mode == ConnectionMode.Wifi) {
                            LanRouteBinder.acquire()
                            lanRouteHeld = true
                        }
                        if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                            throw CancellationException("Audio session superseded before initialization")
                        }
                        val androidSampleRate = sampleRate.value
                        // Opus 编码器仅支持 8/12/16/24/48kHz；把 44.1kHz 采集映射到 48kHz，
                        // 保证线上 codec 始终合法（44.1kHz 单独配置的用户极少）。
                        val opusSampleRate = if (androidSampleRate in OPUS_SAMPLE_RATES) androidSampleRate else 48000
                        val androidChannelConfig = if (channelCount == ChannelCount.Stereo)
                            android.media.AudioFormat.CHANNEL_IN_STEREO
                        else
                            android.media.AudioFormat.CHANNEL_IN_MONO
                        val resolvedAudioFormat = resolveAudioFormat(audioFormat)
                        if (resolvedAudioFormat.captureFormat != audioFormat) {
                            Logger.w(
                                "AudioEngine",
                                "Requested ${audioFormat.label} is not supported for capture; " +
                                    "falling back to ${resolvedAudioFormat.captureFormat.label} for capture and wire format"
                            )
                        }
                        val androidAudioFormat = resolvedAudioFormat.androidEncoding
                        val wireAudioFormat = resolvedAudioFormat.wireFormat
                        val minBufSize = AudioRecord.getMinBufferSize(opusSampleRate, androidChannelConfig, androidAudioFormat)

                        if (minBufSize <= 0 || minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE) {
                            val msg = String.format(getString(R.string.errorAudioFormatNotSupported), audioFormat.label, androidAudioFormat.toString(), androidSampleRate)
                            Logger.e("AudioEngine", msg + ", minBufSize=$minBufSize")
                            throw IllegalStateException(msg)
                        }

                        try {
                            recorder = recorderOwnerGate.create(
                                blockedMessage = { RECORDER_TEARDOWN_BLOCKED_ERROR },
                                discard = { unstarted ->
                                    try {
                                        unstarted.release()
                                    } catch (e: Exception) {
                                        Logger.w("AudioEngine", "Failed to release discarded recorder: ${e.message}")
                                    }
                                }
                            ) {
                                val sourceId = audioSource.sourceId
                                Logger.d("AudioEngine", "Initializing AudioRecord with source ${audioSource.name} (id=$sourceId)")
                                try {
                                    AudioRecord(
                                        sourceId,
                                        opusSampleRate,
                                        androidChannelConfig,
                                        androidAudioFormat,
                                        minBufSize * 3
                                    )
                                } catch (e: Exception) {
                                    Logger.w("AudioEngine", "${audioSource.name} failed, falling back to MIC: ${e.message}")
                                    AudioRecord(
                                        MediaRecorder.AudioSource.MIC,
                                        opusSampleRate,
                                        androidChannelConfig,
                                        androidAudioFormat,
                                        minBufSize * 3
                                    )
                                }
                            }
                        } catch (e: SecurityException) {
                            Logger.e("AudioEngine", "Record permission denied", e)
                            throw IllegalStateException(getString(R.string.errorRecordingPermissionDenied), e)
                        }

                        val sessionRecorder = requireNotNull(recorder)
                        startStopMutex.withLock {
                            if (lifecycleGeneration != sessionGeneration || !desiredRunning || job !== sessionJobIdentity) {
                                throw CancellationException("Audio session superseded during recorder initialization")
                            }
                            activeRecorder = sessionRecorder
                            activeEngineOwner.publish(this@AudioEngine, sessionJobIdentity, sessionRecorder)
                        }
                        if (sessionRecorder.state != AudioRecord.STATE_INITIALIZED) {
                            val msg = getString(R.string.errorAudioRecordInitFailed)
                            Logger.e("AudioEngine", msg)
                            throw IllegalStateException(msg)
                        }

                        try {
                            if (NoiseSuppressor.isAvailable()) {
                                sessionNoiseSuppressor = NoiseSuppressor.create(sessionRecorder.audioSessionId)
                                sessionNoiseSuppressor?.enabled = enableNS
                                noiseSuppressor = sessionNoiseSuppressor
                                Logger.d("AudioEngine", "NoiseSuppressor initialized, enabled=$enableNS")
                            } else {
                                Logger.d("AudioEngine", "NoiseSuppressor not available")
                            }

                            if (AutomaticGainControl.isAvailable()) {
                                sessionAutomaticGainControl = AutomaticGainControl.create(sessionRecorder.audioSessionId)
                                sessionAutomaticGainControl?.enabled = enableAGC
                                automaticGainControl = sessionAutomaticGainControl
                                Logger.d("AudioEngine", "AutomaticGainControl initialized, enabled=$enableAGC")
                            } else {
                                Logger.d("AudioEngine", "AutomaticGainControl not available")
                            }
                        } catch (e: Exception) {
                             Logger.w("AudioEngine", "Failed to initialize audio effects: ${e.message}")
                        }

                        if (activeEngineOwner.isCurrent(this@AudioEngine, sessionJobIdentity, sessionRecorder)) {
                            val context = ContextHelper.getContext()
                            if (context != null) {
                                val intent = Intent(context, AudioService::class.java).apply {
                                    action = AudioService.ACTION_START
                                    putExtra(AudioService.EXTRA_USE_WIFI_LOCK, mode == ConnectionMode.Wifi)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                                else context.startService(intent)
                            }
                        }
                        sessionRecorder.startRecording()
                        check(sessionRecorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            "Audio recorder failed to enter recording state"
                        }
                        if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                            throw CancellationException("Audio session superseded while recording started")
                        }

                        // Size raw PCM so the custom header plus worst-case protobuf/FEC metadata stays <= 1472 bytes.
                        val udpSafePayloadSize = UDP_PCM_PAYLOAD_SIZE
                        val bytesPerSample = resolvedAudioFormat.bytesPerSample
                        val frameAlignBytes = bytesPerSample * channelCount.value
                        val alignedPayloadSize = (udpSafePayloadSize / frameAlignBytes) * frameAlignBytes
                        val readBufSize = (minOf(minBufSize, alignedPayloadSize, opusSampleRate / 50 * frameAlignBytes) / frameAlignBytes * frameAlignBytes).coerceAtLeast(frameAlignBytes)
                        val buffer = ByteArray(readBufSize)
                        val floatBuffer = if (androidAudioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT) FloatArray(readBufSize / 4) else null
                        // API < 23 reads float PCM through a direct buffer; reuse one per session.
                        val legacyFloatByteBuf = if (floatBuffer != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                            ByteBuffer.allocateDirect(floatBuffer.size * 4).order(ByteOrder.nativeOrder())
                        } else {
                            null
                        }

                        // Opus 编码：固定 20ms 帧。AudioRecord 返回的块大小任意，
                        // 先把 PCM 转成 Short 累积到 opusPcmAccumulator，凑满一帧再编码发送。
                        val opusChannels = if (channelCount == ChannelCount.Stereo) 2 else 1
                        val opusFrameSamples = (opusSampleRate * 20) / 1000
                        val opusEncoder = OpusEncoder(opusSampleRate, opusChannels, OpusApplication.OPUS_APPLICATION_VOIP)
                        val opusEncodedBuf = ByteArray(4000) // Concentus 要求输出缓冲区至少 4000 字节
                        val pcmShortBuf = ShortArray((readBufSize / bytesPerSample).coerceAtLeast(1))
                        val opusPcmAccumulator = ShortArray(opusFrameSamples * opusChannels * 2)
                        var opusPendingSamples = 0

                        var sequenceNumber = 0
                        var fecGroupBuffer = mutableListOf<ByteArray>()
                        var fecGroupStartSeq = 0
                        sessionLastPingReceivedTime.set(SystemClock.elapsedRealtime())
                        var lastSuccessfulAudioRead = SystemClock.elapsedRealtime()

                        // Helper function to establish network connection
                        suspend fun connectTransport(targetIp: String, targetPort: Int) {
                            if (mode == ConnectionMode.Wifi) LanRouteBinder.refresh()
                            writerJob?.cancel()
                            readerJob?.cancel()
                            try { tcpSocket?.close() } catch (_: Exception) {}
                            try { sessionUdpSocket?.close() } catch (_: Exception) {}
                            try { selectorManager?.close() } catch (_: Exception) {}
                            writerJob?.join()
                            readerJob?.join()
                            while (channel.tryReceive().isSuccess) { }
                            while (audioChannel.tryReceive().isSuccess) { }
                            // A recovered transport is a new wire session, while AudioRecord stays owned here.
                            // Old plaintext UDP datagrams cannot enter the replacement session.
                            sessionId = sessionCounter.updateAndGet { current ->
                                if (current == Long.MAX_VALUE) 1L else current + 1L
                            }
                            sequenceNumber = 0
                            fecGroupBuffer.clear()
                            fecGroupStartSeq = 0
                            opusPendingSamples = 0

                            tcpSocket = null
                            input = null
                            output = null
                            sessionUdpSocket = null
                            sessionUdpAddress = null

                            // The previous transport is gone; drop its crypto so a reconnect
                            // never seals UDP audio with keys from the dead session.
                            activeCrypto = null
                            _sessionEncrypted.value = false

                            var newSelector: SelectorManager? = null
                            var newTcpSocket: Socket? = null
                            var newInput: ByteReadChannel? = null
                            var newOutput: ByteWriteChannel? = null
                            var secureSession: SessionCrypto? = null

                            if (transportProtocol == TransportProtocol.Tcp || transportProtocol == TransportProtocol.Both) {
                                Logger.i("AudioEngine", "Connecting via TCP to $targetIp:$targetPort")
                                // An old server silently ignores the secure hello, so a
                                // fallback re-opens a fresh socket and continues plaintext.
                                var attemptSecure = mode == ConnectionMode.Wifi &&
                                    (targetIp !in plainOnlyPeers || SecureChannel.peerRequiresEncryption(secureSettings, targetIp))
                                while (true) {
                                    newSelector = SelectorManager(Dispatchers.IO)
                                    selectorManager = newSelector
                                    val socketBuilder = aSocket(newSelector!!)
                                    try {
                                        newTcpSocket = socketBuilder.tcp().connect(targetIp, targetPort) {
                                            keepAlive = true
                                            socketTimeout = 10000L
                                            sendBufferSize = 8 * 1024
                                            noDelay = true
                                        }
                                    } catch (e: Exception) {
                                        Logger.e("AudioEngine", "TCP connect to $targetIp:$targetPort failed: ${e.message}")
                                        throw e
                                    }
                                    newInput = newTcpSocket.openReadChannel()
                                    newOutput = newTcpSocket.openWriteChannel(autoFlush = true)
                                    // Publish before handshake/pairing: stop must close a partially started socket too.
                                    tcpSocket = newTcpSocket
                                    input = newInput
                                    output = newOutput
                                    closeConnection = {
                                        newTcpSocket?.close()
                                        sessionUdpSocket?.close()
                                    }
                                    startStopMutex.withLock {
                                        if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                                            newTcpSocket.close()
                                            throw CancellationException("Audio session superseded during TCP connection")
                                        }
                                        activeTcpSocket = newTcpSocket
                                        activeInput = newInput
                                        activeOutput = newOutput
                                    }

                                    Logger.d("AudioEngine", "Starting handshake")
                                    newOutput.writeFully(HANDSHAKE_CLIENT.encodeToByteArray())
                                    newOutput.flush()
                                    val responseBuffer = ByteArray(HANDSHAKE_SERVER.length)
                                    newInput.readFully(responseBuffer, 0, responseBuffer.size)

                                    if (!responseBuffer.decodeToString().equals(HANDSHAKE_SERVER)) {
                                        newTcpSocket.close()
                                        newSelector.close()
                                        val msg = getString(R.string.errorHandshakeFailedDetailed)
                                        Logger.e("AudioEngine", "Handshake failed: received ${responseBuffer.decodeToString()}")
                                        throw IllegalStateException(msg)
                                    }
                                    Logger.i("AudioEngine", "Handshake successful")

                                    val inChannel = newInput
                                    val outChannel = newOutput
                                    if (attemptSecure && inChannel != null && outChannel != null) {
                                        when (val outcome = negotiateSecureTransport(inChannel, outChannel, targetIp, targetIp in securedPeers || SecureChannel.peerRequiresEncryption(secureSettings, targetIp))) {
                                            is SecureNegotiation.Session -> {
                                                secureSession = outcome.crypto
                                                activeCrypto = outcome.crypto
                                                securedPeers.add(targetIp)
                                                SecureChannel.pinSecurePeer(secureSettings, targetIp)
                                                _sessionEncrypted.value = true
                                                Logger.i("AudioEngine", "Secure transport established with $targetIp")
                                            }
                                            is SecureNegotiation.FallBackToPlain -> {
                                                plainOnlyPeers.add(targetIp)
                                                attemptSecure = false
                                                runCatching { newTcpSocket?.close() }
                                                newSelector.close()
                                                Logger.i("AudioEngine", "Peer $targetIp lacks encryption support; reconnecting in plaintext")
                                                continue
                                            }
                                            is SecureNegotiation.Failure -> {
                                                runCatching { newTcpSocket?.close() }
                                                newSelector.close()
                                                if (outcome.retryable) throw java.io.IOException(outcome.message)
                                                throw SecureChannelException(outcome.message)
                                            }
                                        }
                                    }
                                    break
                                }

                                val connectBytes = proto.encodeToByteArray(
                                    MessageWrapper.serializer(),
                                    MessageWrapper(connect = ConnectMessage(sessionId, CLIENT_PLATFORM, if (transportProtocol == TransportProtocol.Tcp) "tcp" else "both"))
                                )
                                val outChannel = newOutput
                                if (secureSession != null && outChannel != null) {
                                    sendSealedFrame(outChannel, secureSession, connectBytes)
                                } else {
                                    outChannel?.writeInt(PACKET_MAGIC)
                                    outChannel?.writeInt(connectBytes.size)
                                    outChannel?.writeFully(connectBytes)
                                    outChannel?.flush()
                                }
                            }

                            var newUdpSocket: DatagramSocket? = null
                            var newUdpAddress: InetSocketAddress? = null
                            if (mode == ConnectionMode.Wifi && transportProtocol == TransportProtocol.Both) {
                                val udpPort = calculateUdpPort(targetPort)
                                Logger.i("AudioEngine", "Connecting via UDP to $targetIp:$udpPort")
                                newUdpSocket = try {
                                    DatagramSocket().also {
                                        it.sendBufferSize = 256 * 1024
                                    }
                                } catch (e: Exception) {
                                    Logger.e("AudioEngine", "UDP socket setup for $targetIp:$udpPort failed: ${e.message}")
                                    throw e
                                }
                                sessionUdpSocket = newUdpSocket
                                newUdpAddress = InetSocketAddress(targetIp, udpPort)
                            }

                            startStopMutex.withLock {
                                if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                                    try { newTcpSocket?.close() } catch (_: Exception) {}
                                    try { newUdpSocket?.close() } catch (_: Exception) {}
                                    try { newSelector?.close() } catch (_: Exception) {}
                                    throw CancellationException("Audio session superseded during transport setup")
                                }
                                tcpSocket = newTcpSocket
                                input = newInput
                                output = newOutput
                                sessionUdpSocket = newUdpSocket
                                sessionUdpAddress = newUdpAddress
                                activeTcpSocket = newTcpSocket
                                activeInput = newInput
                                activeOutput = newOutput
                                udpSocket = newUdpSocket
                                udpServerAddress = newUdpAddress
                            }

                            closeConnection = {
                                newTcpSocket?.close()
                                newUdpSocket?.close()
                            }

                            // Control messages have their own queue and are never stuck behind old audio.
                            // Start Writer Job
                            writerJob = launch {
                                Logger.d("AudioEngine", "Writer loop started")
                                while (isActive) {
                                    try {
                                        val queued = receiveNextOutgoingFrame(channel, audioChannel)
                                        if (queued.message.audioPacket != null &&
                                            isQueuedAudioExpired(queued.enqueuedAtMs, SystemClock.elapsedRealtime())) continue
                                        val msg = queued.message
                                        val shouldUseUdp = when (transportProtocol) {
                                            TransportProtocol.Tcp -> false
                                            TransportProtocol.Both -> mode == ConnectionMode.Wifi && !msg.hasControlMessage()
                                        }

                                        val localUdpSocket = sessionUdpSocket
                                        val localUdpAddress = sessionUdpAddress
                                        if (shouldUseUdp && localUdpSocket != null && localUdpAddress != null) {
                                            sessionUdpConsecutiveFailures = sendAudioPacketViaUdp(msg, localUdpSocket, localUdpAddress, sessionUdpConsecutiveFailures, secureSession?.udp)
                                        } else {
                                            val out = output
                                            if (out != null && !out.isClosedForWrite) {
                                                val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), msg)
                                                withTransportWriteDeadline(closeTransport = { newTcpSocket?.close() }) {
                                                val cipher = secureSession?.tcpC2S
                                                if (cipher != null) {
                                                    val sealed = cipher.seal(packetBytes)
                                                    out.writeFully(SecureChannel.tcpFrameHeader(sealed.size))
                                                    out.writeFully(sealed)
                                                } else {
                                                    out.writeInt(PACKET_MAGIC)
                                                    out.writeInt(packetBytes.size)
                                                    out.writeFully(packetBytes)
                                                }
                                                out.flush()
                                                }
                                            }
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Logger.e("AudioEngine", "Error writing to socket", e)
                                        break
                                    }
                                }
                                Logger.d("AudioEngine", "Writer loop stopped")
                            }

                            // Start Reader Job if TCP connected
                            readerJob = if (newTcpSocket != null) {
                                launch {
                                    val inChannel = input ?: return@launch
                                    Logger.d("AudioEngine", "Reader loop started")
                                    try {
                                    while (isActive) {
                                            val header = ByteArray(8)
                                            val magic: Int
                                            val length: Int
                                            try {
                                                if (secureSession != null) {
                                                    inChannel.readFully(header)
                                                    magic = SecureChannel.readI32Be(header, 0)
                                                    length = SecureChannel.readI32Be(header, 4)
                                                } else {
                                                    magic = inChannel.readInt()
                                                    length = inChannel.readInt()
                                                }
                                            } catch (e: Exception) {
                                                if (isActive && _state.value == StreamState.Streaming && !isNormalDisconnect(e)) {
                                                    Logger.d("AudioEngine", "Reader loop: socket closed or EOF: ${e.message}")
                                                }
                                                break
                                            }

                                            if (magic != PACKET_MAGIC) {
                                                Logger.w("AudioEngine", "Invalid Magic: ${magic.toString(16)}")
                                                throw java.io.IOException("Invalid Packet Magic")
                                            }

                                            val maxFrameLength = MAX_CONTROL_PAYLOAD_SIZE +
                                                (if (secureSession != null) SecureChannel.AEAD_TAG_BYTES else 0)
                                            if (length < 0 || length > maxFrameLength) {
                                                throw java.io.IOException("Invalid control frame length: $length")
                                            }
                                            if (length > 0) {
                                                val packetBytes = ByteArray(length)
                                                inChannel.readFully(packetBytes)
                                                try {
                                                    val plainBytes = secureSession?.tcpS2C?.open(header, packetBytes) ?: packetBytes
                                                    val wrapper = proto.decodeFromByteArray(MessageWrapper.serializer(), plainBytes)
                                                    if (wrapper.mute != null) {
                                                        _isMuted.value = wrapper.mute.isMuted
                                                        Logger.i("AudioEngine", "Received Mute Command: ${wrapper.mute.isMuted}")
                                                    }

                                                    if (wrapper.ping != null) {
                                                        sessionLastPingReceivedTime.set(SystemClock.elapsedRealtime())
                                                        if (wrapper.ping.audioHealthSupported) {
                                                            val healthNow = SystemClock.elapsedRealtime()
                                                            if (serverAudioHealthSupported.compareAndSet(false, true)) {
                                                                lastHealthyAudioHealthTime.set(healthNow)
                                                            }
                                                            if (wrapper.ping.audioHealthy || _isMuted.value) {
                                                                lastHealthyAudioHealthTime.set(healthNow)
                                                            }
                                                        }
                                                        channel.trySend(MessageWrapper(pong = PongMessage(wrapper.ping.timestamp)))
                                                    }
                                                    wrapper.pluginMessage?.let { _pluginMessages.tryEmit(it) }
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    Logger.e("AudioEngine", "Error decoding incoming message", e)
                                                    if (secureSession != null) throw e
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        if (isActive && _state.value == StreamState.Streaming && !isNormalDisconnect(e)) {
                                            Logger.e("AudioEngine", "Error reading from socket", e)
                                        }
                                    }
                                    Logger.d("AudioEngine", "Reader loop stopped")
                                }
                            } else null

                            channel.send(MessageWrapper(mute = MuteMessage(_isMuted.value)))
                        }

                        fun drainAudioRecord() {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
                            while (isActive && desiredRunning) {
                                val read = if (floatBuffer != null) {
                                    sessionRecorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_NON_BLOCKING)
                                } else {
                                    sessionRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                                }
                                if (read <= 0) break
                                lastSuccessfulAudioRead = SystemClock.elapsedRealtime()
                            }
                        }

                        val targetIp = if (mode == ConnectionMode.Usb) "127.0.0.1" else ip
                        drainAudioRecord()
                        connectTransport(targetIp, port)
                        drainAudioRecord()
                        _state.value = StreamState.Streaming
                        _lastError.value = null
                        connectionComplete.complete(Unit)
                        var lastUiLevelUpdateAt = 0L

                        while (isActive && desiredRunning && lifecycleGeneration == sessionGeneration) {
                            val currentWriter = writerJob
                            val currentReader = readerJob

                            val isWriterFailed = currentWriter?.isCompleted == true || currentWriter?.isCancelled == true
                            val isReaderFailed = currentReader != null && (currentReader.isCompleted || currentReader.isCancelled)
                            val isHeartbeatTimeout = currentReader != null && (SystemClock.elapsedRealtime() - sessionLastPingReceivedTime.get() > HEARTBEAT_TIMEOUT_MS)
                            val isAudioHealthTimeout = currentReader != null && shouldFailForServerAudioHealth(
                                supported = serverAudioHealthSupported.get(),
                                muted = _isMuted.value,
                                nowMs = SystemClock.elapsedRealtime(),
                                lastHealthyAudioHealthMs = lastHealthyAudioHealthTime.get()
                            )

                            if (isWriterFailed || isReaderFailed || isHeartbeatTimeout || isAudioHealthTimeout) {
                                if (desiredRunning) {
                                    _state.value = StreamState.Connecting
                                    Logger.w(
                                        "AudioEngine",
                                        "Network connection dropped (writer=$isWriterFailed, reader=$isReaderFailed, pingTimeout=$isHeartbeatTimeout, healthTimeout=$isAudioHealthTimeout); entering background reconnect loop"
                                    )

                                    // Clear existing transport channels
                                    writerJob?.cancel()
                                    readerJob?.cancel()
                                    try { tcpSocket?.close() } catch (_: Exception) {}
                                    try { sessionUdpSocket?.close() } catch (_: Exception) {}
                                    try { selectorManager?.close() } catch (_: Exception) {}
                                    tcpSocket = null
                                    input = null
                                    output = null
                                    sessionUdpSocket = null
                                    sessionUdpAddress = null
                                    startStopMutex.withLock {
                                        activeTcpSocket = null
                                        activeInput = null
                                        activeOutput = null
                                        udpSocket = null
                                        udpServerAddress = null
                                    }

                                    var retryDelayMs = 1000L
                                    val maxRetryDelayMs = 10000L
                                    var reconnected = false

                                    while (isActive && desiredRunning && lifecycleGeneration == sessionGeneration && !reconnected) {
                                        drainAudioRecord()
                                        withTimeoutOrNull(retryDelayMs) {
                                            reconnectWakeup.receive()
                                        }
                                        if (!isActive || !desiredRunning || lifecycleGeneration != sessionGeneration) break
                                        drainAudioRecord()

                                        try {
                                            val currentTargetIp = if (savedMode == ConnectionMode.Usb) "127.0.0.1" else savedIp
                                            val currentTargetPort = savedPort
                                            Logger.i("AudioEngine", "Retrying connection to $currentTargetIp:$currentTargetPort")
                                            connectTransport(currentTargetIp, currentTargetPort)

                                            drainAudioRecord()
                                            opusPendingSamples = 0
                                            fecGroupBuffer.clear()
                                            fecGroupStartSeq = sequenceNumber

                                            sessionLastPingReceivedTime.set(SystemClock.elapsedRealtime())
                                            serverAudioHealthSupported.set(false)
                                            lastHealthyAudioHealthTime.set(SystemClock.elapsedRealtime())
                                            sessionUdpConsecutiveFailures = 0

                                            _state.value = StreamState.Streaming
                                            _lastError.value = null
                                            Logger.i("AudioEngine", "Successfully reconnected to desktop")
                                            reconnected = true
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            if (e is SecureChannelException) throw e
                                            Logger.w("AudioEngine", "Reconnect attempt failed: ${e.message}")
                                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(maxRetryDelayMs)
                                        }
                                    }

                                    if (!reconnected) {
                                        break
                                    }
                                } else {
                                    throw Exception("Connection lost before streaming established")
                                }
                            }

                            var readBytes = 0
                            val audioData: ByteArray

                            // [API 21+ 兼容] AudioRecord.read(float[], int, int, int) 是 API 23+ 才有的，
                            // 低版本用 ByteBuffer 间接读取 float PCM 数据。
                            if (androidAudioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT && floatBuffer != null) {
                                val readFloats = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    recorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)
                                } else {
                                    // API < 23: 用 ByteBuffer 间接读取 float PCM
                                    val byteBuf = legacyFloatByteBuf!!
                                    byteBuf.clear()
                                    val readBytes = recorder.read(byteBuf, floatBuffer.size * 4)
                                    if (readBytes > 0) {
                                        byteBuf.rewind()
                                        byteBuf.asFloatBuffer().get(floatBuffer, 0, readBytes / 4)
                                        readBytes / 4
                                    } else {
                                        readBytes // 负值或 0 直接返回
                                    }
                                }
                                if (readFloats > 0) {
                                    readBytes = readFloats * 4
                                    audioData = ByteArray(readBytes)
                                    ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatBuffer, 0, readFloats)
                                } else {
                                    audioData = ByteArray(0)
                                }
                            } else {
                                // [API 21+ 兼容] 带 readMode 参数的 AudioRecord.read 是 API 23+ 才有的，
                                // 低版本用 3 参数的阻塞版本（无 readMode 参数）。
                                readBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                                } else {
                                    recorder.read(buffer, 0, buffer.size)
                                }
                                audioData = if (readBytes > 0) buffer.copyOfRange(0, readBytes) else ByteArray(0)
                            }

                            val readNow = SystemClock.elapsedRealtime()
                            when (classifyAudioRead(readBytes, lastSuccessfulAudioRead, readNow, AUDIO_READ_STALL_TIMEOUT_MS)) {
                                AudioReadStatus.Failed -> throw IllegalStateException("Audio recorder read failed with code $readBytes")
                                AudioReadStatus.Stalled -> throw IllegalStateException(
                                    "Audio recorder produced no data for $AUDIO_READ_STALL_TIMEOUT_MS ms"
                                )
                                AudioReadStatus.Waiting -> {
                                    delay(AUDIO_READ_IDLE_DELAY_MS)
                                    continue
                                }
                                AudioReadStatus.Data -> lastSuccessfulAudioRead = readNow
                            }

                            if (readBytes > 0) {
                                val levelNow = SystemClock.elapsedRealtime()
                                if (levelNow - lastUiLevelUpdateAt >= UI_AUDIO_LEVEL_UPDATE_INTERVAL_MS) {
                                    val levelData = calculateAudioLevelData(audioData, resolvedAudioFormat.captureFormat)
                                    _audioLevels.value = levelData.rms
                                    _audioLevelData.value = levelData
                                    lastUiLevelUpdateAt = levelNow
                                }

                                if (!_isMuted.value) {
                                    // 把采集到的 PCM（8bit/16bit/float）统一转成 16-bit Short 并累积。
                                    val shortsIn = convertPcmToShort(audioData, resolvedAudioFormat.captureFormat, pcmShortBuf)
                                    var idx = 0
                                    while (idx < shortsIn && opusPendingSamples < opusPcmAccumulator.size) {
                                        opusPcmAccumulator[opusPendingSamples++] = pcmShortBuf[idx++]
                                    }

                                    // 每凑满 20ms 帧就编码并发送一个 Opus 包。
                                    val frameSizePerChannel = opusFrameSamples * opusChannels
                                    while (opusPendingSamples >= frameSizePerChannel) {
                                        val encodedLen = opusEncoder.encode(opusPcmAccumulator, 0, opusFrameSamples, opusEncodedBuf, 0, opusEncodedBuf.size)
                                        val encoded = opusEncodedBuf.copyOf(encodedLen)

                                        // 紧凑剩余未编码的 PCM，为下一帧腾出头部空间。
                                        val remaining = opusPendingSamples - frameSizePerChannel
                                        if (remaining > 0) {
                                            System.arraycopy(opusPcmAccumulator, frameSizePerChannel, opusPcmAccumulator, 0, remaining)
                                        }
                                        opusPendingSamples = remaining

                                        val packet = AudioPacketMessage(
                                            buffer = encoded,
                                            sampleRate = opusSampleRate,
                                            channelCount = opusChannels,
                                            audioFormat = wireAudioFormat.value,
                                            codec = CODEC_OPUS
                                        )
                                        val wrapper = MessageWrapper(
                                            audioPacket = AudioPacketMessageOrdered(
                                                sequenceNumber = sequenceNumber++,
                                                audioPacket = packet,
                                                timestamp = System.currentTimeMillis(),
                                                sessionId = sessionId
                                            )
                                        )

                                        val localUdpSocket = sessionUdpSocket
                                        val localUdpAddress = sessionUdpAddress
                                        if (localUdpSocket != null && localUdpAddress != null) {
                                            sessionUdpConsecutiveFailures = sendAudioPacketViaUdp(wrapper, localUdpSocket, localUdpAddress, sessionUdpConsecutiveFailures, activeCrypto?.udp)

                                            // FEC: 收集 Opus 编码包，满一组后生成 FEC 包。XOR 与编码无关，
                                            // 且 fecPacketLengths 保留每个包的原始长度以处理变长 Opus 包。
                                            fecGroupBuffer.add(encoded)
                                            if (fecGroupBuffer.size >= FEC_GROUP_SIZE) {
                                                val xorResult = xorBuffers(fecGroupBuffer)
                                                val fecPacket = AudioPacketMessage(
                                                    buffer = xorResult,
                                                    sampleRate = opusSampleRate,
                                                    channelCount = opusChannels,
                                                    audioFormat = wireAudioFormat.value,
                                                    codec = CODEC_OPUS
                                                )
                                                val fecWrapper = MessageWrapper(
                                                    audioPacket = AudioPacketMessageOrdered(
                                                        // FEC is out-of-band: it may share the next regular
                                                        // sequence number, but must not create a gap in audio.
                                                        sequenceNumber = sequenceNumber,
                                                        audioPacket = fecPacket,
                                                        timestamp = System.currentTimeMillis(),
                                                        // Non-empty marker disambiguates group zero from proto3's
                                                        // omitted/default fecSequenceNumber on regular packets.
                                                        fecBuffer = byteArrayOf(1),
                                                        fecSequenceNumber = fecGroupStartSeq,
                                                        sessionId = sessionId,
                                                        // Opus 包长度可变；保留每个原始长度以便 XOR 恢复时去除零填充。
                                                        fecPacketLengths = fecGroupBuffer.map { it.size }
                                                    )
                                                )
                                                sessionUdpConsecutiveFailures = sendAudioPacketViaUdp(fecWrapper, localUdpSocket, localUdpAddress, sessionUdpConsecutiveFailures, activeCrypto?.udp)
                                                fecGroupBuffer = mutableListOf()
                                                fecGroupStartSeq = sequenceNumber
                                            }
                                        } else {
                                            audioChannel.trySend(QueuedAudioFrame(wrapper, SystemClock.elapsedRealtime()))
                                        }
                                    }
                                } else {
                                    // 静音时丢弃累积的 PCM，避免恢复后把静音前的残留音频发出去。
                                    opusPendingSamples = 0
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        connectionComplete.completeExceptionally(e)
                        throw e
                    } catch (e: Exception) {
                        // Closing the session channel is part of the normal stop path. A send
                        // racing with that close must not surface "Channel is already closed"
                        // as a connection error or prevent a subsequent reconnect.
                        if (!desiredRunning || isChannelClosed(e)) {
                            val cancellation = CancellationException("Audio session stopped")
                            connectionComplete.completeExceptionally(cancellation)
                            throw cancellation
                        }
                        val errorMsg = when {
                            e is UdpCircuitBreakerException -> e.message ?: getString(R.string.connectionDisconnected)
                            e is java.net.ConnectException && e.message?.contains("Connection refused", ignoreCase = true) == true ->
                                String.format(getString(R.string.connectionRejected), port)
                            e is java.net.SocketTimeoutException ->
                                getString(R.string.connectionTimeout)
                            e is java.net.NoRouteToHostException ->
                                getString(R.string.connectionUnreachable)
                            e.message?.contains("Heartbeat timeout", ignoreCase = true) == true ->
                                e.message ?: getString(R.string.connectionUnreachable)
                            e.message?.contains("Reader job failed", ignoreCase = true) == true ->
                                getString(R.string.connectionDisconnected)
                            else -> e.message ?: getString(R.string.connectionDisconnected)
                        }
                        Logger.e("AudioEngine", "Connection lost", e)
                        if (lifecycleGeneration == sessionGeneration) {
                            retryLastFailure = e !is SecureChannelException
                            _state.value = StreamState.Error
                            _lastError.value = errorMsg
                        }
                        connectionComplete.completeExceptionally(Exception(errorMsg, e))
                    } finally {
                        connectionComplete.completeExceptionally(CancellationException("Audio session ended before startup completed"))
                        Logger.d("AudioEngine", "Cleaning up resources for generation $sessionGeneration")
                        channel.close()
                        if (lanRouteHeld) LanRouteBinder.release()
                        audioChannel.close()
                        writerJob?.cancel()
                        readerJob?.cancel()
                        activeCrypto = null
                        _sessionEncrypted.value = false
                        pairingDeferred?.cancel()
                        _pairingPrompt.value = null
                        val recorderToRelease = recorder
                        val recorderStopCompletion = stopAndReleaseRecorderAsync(recorderToRelease)
                        // Do not retain the recorder in this session coroutine while native stop runs.
                        recorder = null
                        // A new session must not race the native AudioRecord teardown. The stop
                        // worker still owns the recorder; waiting here only keeps the session
                        // lifecycle alive until that ownership is released. NonCancellable is
                        // required because this finally block normally runs after cancellation.
                        try {
                            withContext(NonCancellable) {
                                recorderStopCompletion?.await()
                            }
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "AudioRecord teardown did not complete cleanly: ${e.message}")
                        }
                        try {
                            closeConnection()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to close connection: ${e.message}")
                        }
                        try {
                            selectorManager?.close()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to close selector manager: ${e.message}")
                        }
                        try {
                            sessionNoiseSuppressor?.release()
                            sessionAutomaticGainControl?.release()
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "Failed to release audio effects: ${e.message}")
                        }

                        startStopMutex.withLock {
                            if (sendChannel === channel) sendChannel = null
                            if (activeRecorder === recorderToRelease) activeRecorder = null
                            if (activeTcpSocket === tcpSocket) activeTcpSocket = null
                            if (activeInput === input) activeInput = null
                            if (activeOutput === output) activeOutput = null
                            if (udpSocket === sessionUdpSocket) {
                                udpSocket = null
                                udpServerAddress = null
                            }
                            if (noiseSuppressor === sessionNoiseSuppressor) noiseSuppressor = null
                            if (automaticGainControl === sessionAutomaticGainControl) automaticGainControl = null
                            if (job === sessionJobIdentity) job = null
                            if (lifecycleGeneration == sessionGeneration) {
                                if (_state.value != StreamState.Error) _state.value = StreamState.Idle
                            }
                        }

                        if (recorderToRelease != null &&
                            activeEngineOwner.clearIfCurrent(this@AudioEngine, sessionJobIdentity, recorderToRelease)
                        ) {
                            stopStreamingNotification()
                            Logger.i("AudioEngine", "AudioEngine stopped")
                        }
                    }
                }
                job = sessionJob
                launchedJob = sessionJob
                sessionJob.start()
                }
            }

            val previousJob = stoppingJob
            if (previousJob != null) {
                val previousStopped = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                    previousJob.join()
                    true
                } == true
                if (!previousStopped) {
                    val error = IllegalStateException("Previous audio session did not stop within $STOP_TIMEOUT_MS ms")
                    startStopMutex.withLock {
                        if (requestToken == startRequestGeneration) {
                            desiredRunning = false
                            startRequestGeneration++
                            stopTimedOutJob = previousJob
                            stopTimedOutResources = StopResources(
                                previousJob,
                                activeRecorder,
                                activeTcpSocket,
                                activeInput,
                                activeOutput,
                                udpSocket,
                                sendChannel
                            )
                            _state.value = StreamState.Error
                            _lastError.value = error.message
                        }
                    }
                    throw error
                }
                firstAttempt = false
            }
        }

        try {
            connectionComplete.await()
        } catch (e: Exception) {
            launchedJob?.cancel()
            if (launchedJob != null) {
                withTimeoutOrNull(STOP_TIMEOUT_MS) { launchedJob?.join() }
            }
            throw e
        }
    }

    /**
     * 把任意采集格式的 PCM 字节转成 16-bit LE Short（Opus 编码输入格式）。
     * @return 写入 out 的 sample 数量
     */
    private fun convertPcmToShort(data: ByteArray, format: AudioFormat, out: ShortArray): Int {
        return when (format) {
            AudioFormat.PCM_FLOAT -> {
                val fb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                var n = 0
                while (n < fb.limit() && n < out.size) {
                    // roundToInt() throws on NaN, which a faulty HAL can deliver
                    val sample = fb.get(n).takeUnless { it.isNaN() } ?: 0f
                    out[n] = (sample * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
                    n++
                }
                n
            }
            AudioFormat.PCM_8BIT -> {
                var n = 0
                while (n < data.size && n < out.size) {
                    val v = (data[n].toInt() and 0xff) - 128
                    out[n] = (v * 256).toShort()
                    n++
                }
                n
            }
            AudioFormat.PCM_16BIT -> {
                val n = minOf(data.size / 2, out.size)
                var i = 0
                while (i < n) {
                    out[i] = ((data[i * 2 + 1].toInt() shl 8) or (data[i * 2].toInt() and 0xff)).toShort()
                    i++
                }
                n
            }
            AudioFormat.PCM_24BIT -> 0 // 采集时已回退为 PCM16，实际不可达
        }
    }

    /**
     * XOR 多个 buffer（处理不同长度：以最长的为准，短的用 0 填充）
     */
    private fun xorBuffers(buffers: List<ByteArray>): ByteArray {
        val maxLen = buffers.maxOf { it.size }
        val result = ByteArray(maxLen)
        for (buf in buffers) {
            for (i in buf.indices) {
                result[i] = (result[i].toInt() xor buf[i].toInt()).toByte()
            }
        }
        return result
    }

    private sealed class SecureNegotiation {
        data class Session(val crypto: SessionCrypto) : SecureNegotiation()
        object FallBackToPlain : SecureNegotiation()
        data class Failure(val message: String, val retryable: Boolean = false) : SecureNegotiation()
    }

    /**
     * Runs the client half of the encrypted handshake over an open control
     * channel. Returns [SecureNegotiation.FallBackToPlain] when the peer turns
     * out to be a legacy server, and [SecureNegotiation.Failure] for anything
     * that must not silently downgrade (bad signature, user refusal).
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun negotiateSecureTransport(
        input: ByteReadChannel,
        output: ByteWriteChannel,
        peer: String,
        peerPreviouslySecured: Boolean
    ): SecureNegotiation {
        return try {
            val seed = SecureChannel.obtainIdentitySeed(secureSettings)
            val identityPub = SecureChannel.identityPublicKey(seed)
            val ephPrivate = SecureChannel.generateX25519PrivateKey()
            val ephPub = SecureChannel.x25519PublicKey(ephPrivate)

            var hello = SecureClientHello(
                suiteMask = SECURE_SUITE_V1,
                ephemeralPubKey = ephPub,
                identityPubKey = identityPub,
                deviceName = android.os.Build.MODEL ?: "Android"
            )
            hello = hello.copy(
                transcriptSignature = SecureChannel.sign(
                    seed,
                    SecureChannel.signedTranscript(SecureChannel.clientHelloCore(hello))
                )
            )
            val helloBody = proto.encodeToByteArray(
                MessageWrapper.serializer(),
                MessageWrapper(secureClientHello = hello)
            )
            output.writeFully(SecureChannel.tcpFrameHeader(helloBody.size))
            output.writeFully(helloBody)
            output.flush()

            // Only this read may legitimately hit a legacy server: it either never
            // answers the unknown message or closes the socket. Every later error is
            // a real failure and must NOT permanently downgrade the peer.
            val header = try {
                val header = ByteArray(8)
                withTimeout(3_000L) { input.readFully(header) }
                header
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (peerPreviouslySecured) {
                    // This peer completed an encrypted handshake before, so it is not a
                    // legacy server. A missing reply is a transient network failure that
                    // must be retried, never a reason to silently downgrade to plaintext.
                    return SecureNegotiation.Failure("secure reply from $peer failed: ${e.message}", retryable = true)
                }
                Logger.w("AudioEngine", "No secure reply from $peer (${e.message}); treating as legacy server")
                return SecureNegotiation.FallBackToPlain
            }
            if (SecureChannel.readI32Be(header, 0) != PACKET_MAGIC) {
                return SecureNegotiation.Failure("invalid secure reply magic")
            }
            val replyLength = SecureChannel.readI32Be(header, 4)
            if (replyLength <= 0 || replyLength > 1 shl 20) {
                return SecureNegotiation.Failure("invalid secure reply length")
            }
            val replyBytes = ByteArray(replyLength)
            withTimeout(3_000L) { input.readFully(replyBytes) }
            val serverHello = proto.decodeFromByteArray(MessageWrapper.serializer(), replyBytes)
                .secureServerHello
                ?: return SecureNegotiation.Failure("secure reply omitted server hello")

            // A legacy-capable server signals "plaintext only" with suite 0.
            if (serverHello.suite == 0) {
                return if (peerPreviouslySecured) SecureNegotiation.Failure("secure peer refused encryption")
                    else SecureNegotiation.FallBackToPlain
            }
            if (serverHello.suite != SECURE_SUITE_V1) return SecureNegotiation.Failure("unsupported secure suite")

            val chCore = SecureChannel.clientHelloCore(hello)
            val shCore = SecureChannel.serverHelloCore(
                serverHello.suite,
                serverHello.identityPubKey,
                serverHello.ephemeralPubKey
            )
            if (!SecureChannel.verify(
                    serverHello.identityPubKey,
                    SecureChannel.signedTranscript(chCore, shCore),
                    serverHello.transcriptSignature
                )
            ) {
                return SecureNegotiation.Failure("server transcript signature invalid")
            }

            val shared = SecureChannel.x25519SharedSecret(ephPrivate, serverHello.ephemeralPubKey)
            val transcript = SecureChannel.transcriptHash(chCore, shCore)
            val secrets = SecureChannel.deriveHandshakeSecrets(transcript, shared)

            val knownServer = SecureChannel.pairedServerDisplayName(secureSettings, serverHello.identityPubKey) != null
            if (!knownServer) {
                val sas = SecureChannel.sasText(secrets.sasValue)
                Logger.i("AudioEngine", "First secure contact with $peer; SAS $sas")
                pairingDeferred = CompletableDeferred()
                _pairingPrompt.value = PairingPrompt(sas = sas, peer = peer)
                val accepted = try {
                    withTimeout(180_000L) { pairingDeferred?.await() ?: false }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                } finally {
                    _pairingPrompt.value = null
                    pairingDeferred = null
                }
                if (!accepted) {
                    return SecureNegotiation.Failure("pairing rejected by user")
                }
            }

            val session = SecureChannel.sessionCrypto(secrets.keys)

            val confirm = MessageWrapper(
                secureConfirm = SecureConfirm(deviceName = hello.deviceName)
            )
            val confirmBytes = proto.encodeToByteArray(MessageWrapper.serializer(), confirm)
            val confirmHeader = SecureChannel.tcpFrameHeader(confirmBytes.size + SecureChannel.AEAD_TAG_BYTES)
            val sealedConfirm = session.tcpC2S.seal(confirmBytes)
            output.writeFully(confirmHeader)
            output.writeFully(sealedConfirm)
            output.flush()

            val verdictHeader = ByteArray(8)
            input.readFully(verdictHeader)
            if (SecureChannel.readI32Be(verdictHeader, 0) != PACKET_MAGIC) {
                return SecureNegotiation.Failure("invalid verdict frame magic")
            }
            val verdictLength = SecureChannel.readI32Be(verdictHeader, 4)
            if (verdictLength < SecureChannel.AEAD_TAG_BYTES || verdictLength > MAX_CONTROL_PAYLOAD_SIZE) {
                return SecureNegotiation.Failure("invalid secure verdict length")
            }
            val verdictBytes = ByteArray(verdictLength)
            input.readFully(verdictBytes)
            val opened = session.tcpS2C.open(verdictHeader, verdictBytes)
            val accepted = proto.decodeFromByteArray(MessageWrapper.serializer(), opened)
                .secureResult?.accepted ?: false
            if (!accepted) {
                return SecureNegotiation.Failure("server rejected the secure session")
            }
            SecureChannel.rememberPairedServer(secureSettings, serverHello.identityPubKey, peer)
            SecureChannel.pinSecurePeer(secureSettings, peer)
            SecureNegotiation.Session(session)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Past the hello phase the peer has proven it speaks the secure
            // protocol, so transport errors here must surface instead of
            // permanently downgrading the peer to plaintext.
            Logger.e("AudioEngine", "Secure negotiation failed: ${e.message}")
            SecureNegotiation.Failure(
                e.message ?: "secure handshake failed",
                retryable = e is java.io.IOException || e is kotlinx.coroutines.TimeoutCancellationException
            )
        }
    }


    @OptIn(ExperimentalSerializationApi::class)
    private fun sendAudioPacketViaUdp(
        wrapper: MessageWrapper,
        socket: DatagramSocket,
        serverAddress: InetSocketAddress,
        consecutiveFailures: Int,
        udpCipher: UdpCipher? = null
    ): Int {
        return try {
            val packetBytes = proto.encodeToByteArray(MessageWrapper.serializer(), wrapper)
            val datagramBytes = if (udpCipher != null) {
                // Sealed datagrams carry their own magic + sequence prefix.
                udpCipher.seal(packetBytes)
            } else {
                require(UDP_CUSTOM_HEADER_SIZE + packetBytes.size <= UDP_MAX_DATAGRAM_SIZE) {
                    "UDP datagram exceeds $UDP_MAX_DATAGRAM_SIZE bytes: ${UDP_CUSTOM_HEADER_SIZE + packetBytes.size}"
                }
                val header = ByteArray(UDP_CUSTOM_HEADER_SIZE).apply {
                    this[0] = (UDP_PACKET_MAGIC shr 24).toByte()
                    this[1] = (UDP_PACKET_MAGIC shr 16).toByte()
                    this[2] = (UDP_PACKET_MAGIC shr 8).toByte()
                    this[3] = UDP_PACKET_MAGIC.toByte()
                    this[4] = (packetBytes.size shr 24).toByte()
                    this[5] = (packetBytes.size shr 16).toByte()
                    this[6] = (packetBytes.size shr 8).toByte()
                    this[7] = packetBytes.size.toByte()
                }
                header + packetBytes
            }
            require(datagramBytes.size <= UDP_MAX_DATAGRAM_SIZE) {
                "UDP datagram exceeds $UDP_MAX_DATAGRAM_SIZE bytes: ${datagramBytes.size}"
            }
            val udpPacket = DatagramPacket(
                datagramBytes,
                datagramBytes.size,
                serverAddress
            )
            socket.send(udpPacket)
            0
        } catch (e: Exception) {
            Logger.w("AudioEngine", "UDP send failed: ${e.message}")
            val updatedFailures = consecutiveFailures + 1
            if (updatedFailures >= MAX_UDP_CONSECUTIVE_FAILURES) {
                val err = UdpCircuitBreakerException("UDP send failed $updatedFailures consecutive times, triggering disconnect")
                Logger.e("AudioEngine", err.message!!)
                throw err
            }
            updatedFailures
        }
    }

    fun stop() {
        lifecycleScope.launch {
            try {
                stopAndWait()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("AudioEngine", "Failed to stop audio stream", e)
            }
        }
    }

    fun close(): Job = synchronized(closeLock) {
        closeJob?.let { return@synchronized it }
        closed.set(true)
        val closeOperation = cleanupScope.launch {
            try {
                stopAndWait()
            } catch (e: IllegalStateException) {
                Logger.w("AudioEngine", "Stop timed out while closing; waiting up to $CLOSE_FINAL_WAIT_MS ms for final completion")
                var pendingResources: StopResources? = null
                val pendingCompletion = startStopMutex.withLock {
                    pendingResources = stopTimedOutResources
                    stopTimedOutJob
                }
                if (pendingCompletion == null) throw e
                // close has a total upper bound of STOP_TIMEOUT_MS + CLOSE_FINAL_WAIT_MS (20 s).
                if (awaitJobWithin(pendingCompletion, CLOSE_FINAL_WAIT_MS)) {
                    finalizeStoppedSession(pendingCompletion, pendingResources, userInitiated = true)
                } else {
                    forceAbandonStoppedSession(pendingCompletion, pendingResources)
                }
            } finally {
                lifecycleJob.cancel()
                // The close coroutine is itself a cleanupJob child, so cancel (rather than join)
                // here; it completes immediately after this finally block.
                cleanupJob.cancel()
            }
        }
        closeJob = closeOperation
        closeOperation
    }

    suspend fun stopAndWait(userInitiated: Boolean = true): Boolean = stopOperationMutex.withLock {
        stopAndWaitLocked(userInitiated)
    }

    private suspend fun stopAndWaitLocked(userInitiated: Boolean): Boolean {
        var pendingResources: StopResources? = null
        val pendingCompletion = startStopMutex.withLock {
            stopTimedOutJob?.also {
                pendingResources = stopTimedOutResources
                if (userInitiated) {
                    desiredRunning = false
                    lifecycleGeneration++
                    startRequestGeneration++
                    configRestartRequest++
                    configRestartJob?.cancel()
                    configRestartJob = null
                }
            }
        }
        if (pendingCompletion != null) {
            val completed = withTimeoutOrNull(STOP_TIMEOUT_MS) {
                pendingCompletion.join()
                true
            } == true
            if (!completed) {
                throw IllegalStateException("AudioEngine stop timed out after $STOP_TIMEOUT_MS ms")
            }
            finalizeStoppedSession(pendingCompletion, pendingResources, userInitiated)
            return true
        }

        var currentJob: Job? = null
        var currentRecorder: AudioRecord? = null
        var currentSocket: Socket? = null
        var currentInput: ByteReadChannel? = null
        var currentOutput: ByteWriteChannel? = null
        var currentUdpSocket: DatagramSocket? = null
        var currentChannel: Channel<MessageWrapper>? = null
        var stopGeneration = 0L

        startStopMutex.withLock {
            if (userInitiated) {
                desiredRunning = false
                lifecycleGeneration++
                startRequestGeneration++
                configRestartRequest++
                configRestartJob?.cancel()
                configRestartJob = null
            }
            stopGeneration = lifecycleGeneration
            currentJob = job
            currentRecorder = activeRecorder
            currentSocket = activeTcpSocket
            currentInput = activeInput
            currentOutput = activeOutput
            currentUdpSocket = udpSocket
            currentChannel = sendChannel
            currentJob?.cancel(CancellationException("AudioEngine stopping"))
        }

        // Interrupt cancellable resources in a lifecycle child. Native AudioRecord.stop() is
        // deliberately detached below so it cannot keep lifecycleJob or completion waiting.
        val interruptJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                currentInput?.cancel(CancellationException("AudioEngine stopping"))
            } catch (_: Exception) {
            }
            try {
                currentChannel?.close()
            } catch (_: Exception) {
            }
            try {
                currentUdpSocket?.close()
            } catch (_: Exception) {
            }
            try {
                currentSocket?.close()
            } catch (_: Exception) {
            }
        }
        val recorderStopCompletion = stopAndReleaseRecorderAsync(currentRecorder)

        val stopCompletionJob = lifecycleScope.launch {
            interruptJob.join()
            recorderStopCompletion?.await()
            currentJob?.join()
        }
        val stopped = awaitJobWithin(stopCompletionJob, STOP_TIMEOUT_MS)

        val resources = StopResources(
            currentJob,
            currentRecorder,
            currentSocket,
            currentInput,
            currentOutput,
            currentUdpSocket,
            currentChannel
        )
        if (stopped) {
            finalizeStoppedSession(stopCompletionJob, resources, userInitiated)
        } else {
            startStopMutex.withLock {
                stopTimedOutJob = stopCompletionJob
                stopTimedOutResources = resources
                if (lifecycleGeneration == stopGeneration && (userInitiated || !desiredRunning)) {
                    _state.value = StreamState.Error
                    _lastError.value = "AudioEngine stop timed out after $STOP_TIMEOUT_MS ms"
                }
            }
            val error = IllegalStateException("AudioEngine stop timed out after $STOP_TIMEOUT_MS ms")
            Logger.e("AudioEngine", "Timed out waiting $STOP_TIMEOUT_MS ms for audio session to stop")
            throw error
        }
        return true
    }

    private suspend fun forceAbandonStoppedSession(
        completionJob: Job,
        resources: StopResources?
    ) {
        val timeoutMessage = "AudioEngine close abandoned stop after ${STOP_TIMEOUT_MS + CLOSE_FINAL_WAIT_MS} ms total"
        resources?.sessionJob?.cancel(CancellationException(timeoutMessage))
        completionJob.cancel(CancellationException(timeoutMessage))
        configRestartJob?.cancel(CancellationException(timeoutMessage))

        startStopMutex.withLock {
            desiredRunning = false
            lifecycleGeneration++
            startRequestGeneration++
            configRestartRequest++
            configRestartJob = null
            if (stopTimedOutJob === completionJob || stopTimedOutJob === resources?.sessionJob) {
                stopTimedOutJob = null
                stopTimedOutResources = null
            }
            if (resources != null) {
                if (job === resources.sessionJob) job = null
                if (activeRecorder === resources.recorder) activeRecorder = null
                if (activeTcpSocket === resources.tcpSocket) activeTcpSocket = null
                if (activeInput === resources.input) activeInput = null
                if (activeOutput === resources.output) activeOutput = null
                if (udpSocket === resources.udpSocket) {
                    udpSocket = null
                    udpServerAddress = null
                }
                if (sendChannel === resources.channel) sendChannel = null
            }
            _state.value = StreamState.Error
            _lastError.value = timeoutMessage
        }
        if (resources?.sessionJob != null && resources.recorder != null &&
            activeEngineOwner.clearIfCurrent(this, resources.sessionJob, resources.recorder)
        ) {
            stopStreamingNotification()
        }
        Logger.e("AudioEngine", timeoutMessage)
    }

    private suspend fun finalizeStoppedSession(
        completionJob: Job,
        resources: StopResources?,
        userInitiated: Boolean
    ) {
        startStopMutex.withLock {
            if (stopTimedOutJob === completionJob || stopTimedOutJob === resources?.sessionJob) {
                stopTimedOutJob = null
                stopTimedOutResources = null
            }
            if (resources != null) {
                if (job === resources.sessionJob) job = null
                if (activeRecorder === resources.recorder) activeRecorder = null
                if (activeTcpSocket === resources.tcpSocket) activeTcpSocket = null
                if (activeInput === resources.input) activeInput = null
                if (activeOutput === resources.output) activeOutput = null
                if (udpSocket === resources.udpSocket) {
                    udpSocket = null
                    udpServerAddress = null
                }
                if (sendChannel === resources.channel) sendChannel = null
            }
            if (userInitiated || !desiredRunning) {
                _state.value = StreamState.Idle
            }
        }
        if (userInitiated && resources?.sessionJob != null && resources.recorder != null &&
            activeEngineOwner.clearIfCurrent(this, resources.sessionJob, resources.recorder)
        ) {
            stopStreamingNotification()
        }
    }

    private fun stopStreamingNotification() {
        val context = ContextHelper.getContext() ?: return
        try {
            val intent = Intent(context, AudioService::class.java).apply { action = AudioService.ACTION_STOP }
            context.startService(intent)
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Failed to stop streaming notification: ${e.message}")
        }
    }

    /**
     * Send a cross-device plugin message over the TCP control channel
     * (MessageWrapper field 7). Non-blocking; returns false when no session
     * is active or the send buffer is full.
     */
    fun sendPluginMessage(message: PluginMessage): Boolean {
        if (_state.value != StreamState.Streaming && _state.value != StreamState.Connecting) {
            return false
        }
        val ch = sendChannel ?: return false
        return try {
            ch.trySend(MessageWrapper(pluginMessage = message)).isSuccess
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Failed to enqueue plugin message: ${e.message}")
            false
        }
    }

    suspend fun setMute(muted: Boolean) {
        _isMuted.value = muted
        if (_state.value == StreamState.Streaming || _state.value == StreamState.Connecting) {
             try {
                 sendChannel?.send(MessageWrapper(mute = MuteMessage(muted)))
             } catch (e: Exception) {
                 Logger.e("AudioEngine", "Failed to send mute message: ${e.message}")
             }
        }
    }

    fun setAudioSource(sourceName: String) {
        val source = try {
            AndroidAudioSource.valueOf(sourceName)
        } catch (e: Exception) {
            AndroidAudioSource.Mic
        }

        if (this.audioSource != source) {
            this.audioSource = source
            Logger.d("AudioEngine", "Audio source changed to: ${source.name}")

            if (desiredRunning && _state.value == StreamState.Streaming) {
                Logger.i("AudioEngine", "Restarting audio stream with new source...")
                scheduleConfigRestart()
            }
        }
    }

    private fun scheduleConfigRestart() {
        val request = ++configRestartRequest
        configRestartJob?.cancel()
        configRestartJob = lifecycleScope.launch {
            val capturedGeneration = startStopMutex.withLock {
                if (!desiredRunning || request != configRestartRequest) return@launch
                lifecycleGeneration
            }

            try {
                stopAndWait(userInitiated = false)

                val shouldRestart = startStopMutex.withLock {
                    desiredRunning &&
                        lifecycleGeneration == capturedGeneration &&
                        request == configRestartRequest
                }
                if (!shouldRestart) return@launch

                start(savedIp, savedPort, savedMode, true, savedSampleRate, savedChannelCount, savedAudioFormat, savedTransportProtocol)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("AudioEngine", "Failed to restart audio stream after config change", e)
            }
        }
    }

    private fun isNormalDisconnect(e: Throwable): Boolean {
        if (e is kotlinx.coroutines.CancellationException) return true
        if (e is EOFException) return true
        if (e is java.io.IOException) {
            val msg = e.message ?: ""
            if (msg.contains("Socket closed", ignoreCase = true)) return true
            if (msg.contains("Connection reset", ignoreCase = true)) return true
            if (msg.contains("Broken pipe", ignoreCase = true)) return true
        }
        return false
    }

    private fun isChannelClosed(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("Channel is already closed", ignoreCase = true) ||
            message.contains("Channel was closed", ignoreCase = true)
    }

    private fun calculateAudioLevelData(buffer: ByteArray, format: AudioFormat): AudioLevelData {
        if (buffer.isEmpty()) return AudioLevelData.SILENT
        var sum = 0.0
        var maxSample = 0.0
        var sampleCount = 0
        when (format) {
            AudioFormat.PCM_FLOAT -> {
                sampleCount = buffer.size / 4
                for (i in 0 until sampleCount) {
                    val byteIndex = i * 4
                    val bits = (buffer[byteIndex].toInt() and 0xFF) or
                               ((buffer[byteIndex + 1].toInt() and 0xFF) shl 8) or
                               ((buffer[byteIndex + 2].toInt() and 0xFF) shl 16) or
                               ((buffer[byteIndex + 3].toInt() and 0xFF) shl 24)
                    val sample = Float.fromBits(bits)
                    sum += sample * sample
                    maxSample = maxOf(maxSample, kotlin.math.abs(sample.toDouble()))
                }
            }
            AudioFormat.PCM_8BIT -> {
                sampleCount = buffer.size
                for (i in 0 until sampleCount) {
                    val sample = (buffer[i].toInt() and 0xFF) - 128
                    val normalized = sample / 128.0
                    sum += normalized * normalized
                    maxSample = maxOf(maxSample, kotlin.math.abs(normalized))
                }
            }
            else -> {
                sampleCount = buffer.size / 2
                for (i in 0 until sampleCount) {
                    val byteIndex = i * 2
                    val sample = (buffer[byteIndex].toInt() and 0xFF) or
                                 ((buffer[byteIndex + 1].toInt()) shl 8)
                    val normalized = sample / 32768.0
                    sum += normalized * normalized
                    maxSample = maxOf(maxSample, kotlin.math.abs(normalized))
                }
            }
        }
        if (sampleCount == 0) return AudioLevelData.SILENT
        val rms = kotlin.math.sqrt(sum / sampleCount).toFloat().coerceIn(0f, 1f)
        val peak = maxSample.toFloat().coerceIn(0f, 1f)
        return AudioLevelData.fromRmsAndPeak(rms, peak)
    }
}

private class UdpCircuitBreakerException(message: String) : Exception(message)

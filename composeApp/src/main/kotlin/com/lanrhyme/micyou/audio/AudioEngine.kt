package com.lanrhyme.micyou.audio
import com.lanrhyme.micyou.R

import android.content.Intent
import android.media.AudioRecord
import android.media.MediaRecorder
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
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.reader
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import com.lanrhyme.micyou.settings.SettingsFactory
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.EOFException
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import com.lanrhyme.micyou.audio.AndroidAudioSource
import com.lanrhyme.micyou.audio.AudioLevelData
import com.lanrhyme.micyou.audio.AudioMetrics
import com.lanrhyme.micyou.network.AudioPacketMessage
import com.lanrhyme.micyou.network.AudioPacketMessageOrdered
import com.lanrhyme.micyou.network.ConnectMessage
import com.lanrhyme.micyou.network.calculateUdpPort
import com.lanrhyme.micyou.network.MessageWrapper
import com.lanrhyme.micyou.network.PACKET_MAGIC
import com.lanrhyme.micyou.network.SECURE_SUITE_V1
import com.lanrhyme.micyou.network.SessionCrypto
import com.lanrhyme.micyou.network.UdpCipher
import com.lanrhyme.micyou.network.SecureChannel
import com.lanrhyme.micyou.network.SecureClientHello
import com.lanrhyme.micyou.network.SecureConfirm
import com.lanrhyme.micyou.network.SecureResult
import com.lanrhyme.micyou.network.UDP_CUSTOM_HEADER_SIZE
import com.lanrhyme.micyou.network.UDP_MAX_DATAGRAM_SIZE
import com.lanrhyme.micyou.network.UDP_PACKET_MAGIC
import com.lanrhyme.micyou.network.UDP_PCM_PAYLOAD_SIZE
import com.lanrhyme.micyou.service.AudioService
import com.lanrhyme.micyou.util.ContextHelper
import com.lanrhyme.micyou.util.getString
import com.lanrhyme.micyou.util.Logger
import com.lanrhyme.micyou.util.PerformanceConfig
import com.lanrhyme.micyou.viewmodel.ConnectionMode
import com.lanrhyme.micyou.viewmodel.NoiseReductionType
import com.lanrhyme.micyou.viewmodel.StreamState
import com.lanrhyme.micyou.viewmodel.TransportProtocol
import com.lanrhyme.micyou.network.hasControlMessage
import com.lanrhyme.micyou.network.MuteMessage
import com.lanrhyme.micyou.network.PongMessage
/**
 * Converts OutputStream to ByteWriteChannel using the current coroutine context.
 */
internal suspend fun awaitJobWithin(job: Job, timeoutMs: Long): Boolean =
    withTimeoutOrNull(timeoutMs) {
        job.join()
        true
    } == true

internal enum class AudioReadStatus { Data, Waiting, Failed, Stalled }

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

suspend fun OutputStream.toByteWriteChannelSuspend(): ByteWriteChannel {
    val scope = CoroutineScope(coroutineContext)
    val outputStream = this
    return scope.reader(Dispatchers.IO, autoFlush = true) {
        val buffer = ByteArray(4096)
        try {
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer)
                if (count == -1) break
                try {
                    outputStream.write(buffer, 0, count)
                    outputStream.flush()
                } catch (e: java.io.IOException) {
                    Logger.e("ByteWriteChannel", "I/O error writing to stream: ${e.message}", e)
                    break
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("ByteWriteChannel", "Unexpected error in write channel: ${e.message}", e)
        }
    }.channel
}

class AudioEngine constructor() {
    companion object {
        private const val MAX_UDP_CONSECUTIVE_FAILURES = 500
        private const val HEARTBEAT_TIMEOUT_MS = 5000L
        private const val AUDIO_READ_STALL_TIMEOUT_MS = 5000L
        private const val AUDIO_READ_IDLE_DELAY_MS = 5L
        private const val UI_AUDIO_LEVEL_UPDATE_INTERVAL_MS = 75L
        private const val STOP_TIMEOUT_MS = 5000L
        private const val CLOSE_FINAL_WAIT_MS = 15000L
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
            val engine = getActiveEngine() ?: return false
            val state = engine.currentStreamState()
            return state == StreamState.Streaming || state == StreamState.Connecting
        }

        fun isWifiStreaming(): Boolean {
            val engine = getActiveEngine() ?: return false
            val state = engine.currentStreamState()
            return engine.savedMode == ConnectionMode.Wifi &&
                (state == StreamState.Streaming || state == StreamState.Connecting)
        }
    }
    private val _state = MutableStateFlow(StreamState.Idle)
    val streamState: Flow<StreamState> = _state

    fun currentStreamState(): StreamState = _state.value
    private val _audioLevels = MutableStateFlow(0f)
    val audioLevels: Flow<Float> = _audioLevels

    private val _rawSpectrum = MutableStateFlow(FloatArray(0))
    val rawSpectrum: Flow<FloatArray> = _rawSpectrum

    private val _processedSpectrum = MutableStateFlow(FloatArray(0))
    val processedSpectrum: Flow<FloatArray> = _processedSpectrum
    private val _audioLevelData = MutableStateFlow(AudioLevelData.SILENT)
    val audioLevelData: Flow<AudioLevelData> = _audioLevelData
    private val _audioMetrics = MutableStateFlow<AudioMetrics?>(null)
    val audioMetrics: Flow<AudioMetrics?> = _audioMetrics
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: Flow<String?> = _lastError

    private val _isMuted = MutableStateFlow(false)
    val isMuted: Flow<Boolean> = _isMuted

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
    private var enableStreamingNotification: Boolean = true

    @Volatile
    private var enableNS: Boolean = false
    @Volatile
    private var enableAGC: Boolean = false
    @Volatile
    private var audioSource: AndroidAudioSource = AndroidAudioSource.Mic
    private val reconnectWakeup = Channel<Unit>(Channel.CONFLATED)

    fun wakeupReconnect() {
        reconnectWakeup.trySend(Unit)
    }

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
    private var hasEstablishedStream: Boolean = false
    @Volatile
    private var lifecycleGeneration: Long = 0
    private var startRequestGeneration: Long = 0

    private val CHECK_1 = "MicYouCheck1"
    private val CHECK_2 = "MicYouCheck2"

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
        val sessionId = sessionCounter.updateAndGet { current ->
            if (current == Long.MAX_VALUE) 1L else current + 1L
        }
        while (launchedJob == null && !startIgnored) {
            stoppingJob = null
            startStopMutex.withLock {
                if (firstAttempt) {
                    if (closed.get()) throw IllegalStateException("AudioEngine is closed")
                    configRestartJob?.takeIf { it !== coroutineContext[Job] }?.cancel()
                    val wasDesiredRunning = desiredRunning
                    val timedOutJob = stopTimedOutJob
                    if (timedOutJob != null && !timedOutJob.isCompleted) {
                        throw IllegalStateException("Previous audio session did not stop within $STOP_TIMEOUT_MS ms")
                    }
                    if (timedOutJob?.isCompleted == true) {
                        stopTimedOutJob = null
                        stopTimedOutResources = null
                    }
                    if (shouldReuseActiveAudioSession(
                            desiredRunning = wasDesiredRunning,
                            hasActiveJob = job?.isCompleted == false,
                            state = _state.value
                        )
                    ) {
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
                    var sessionLastPingReceivedTime = System.currentTimeMillis()
                    val serverAudioHealthSupported = AtomicBoolean(false)
                    val lastHealthyAudioHealthTime = AtomicLong(SystemClock.elapsedRealtime())
                    val serverAudioUnhealthyNotified = AtomicBoolean(false)
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
                    var writerJob: Job? = null
                    var readerJob: Job? = null
                    val sessionJobIdentity = requireNotNull(coroutineContext[Job])
                    
                    try {
                        if (lifecycleGeneration != sessionGeneration || !desiredRunning) {
                            throw CancellationException("Audio session superseded before initialization")
                        }
                        val androidSampleRate = sampleRate.value
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
                        val minBufSize = AudioRecord.getMinBufferSize(androidSampleRate, androidChannelConfig, androidAudioFormat)

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
                                        androidSampleRate,
                                        androidChannelConfig,
                                        androidAudioFormat,
                                        minBufSize * 3
                                    )
                                } catch (e: Exception) {
                                    Logger.w("AudioEngine", "${audioSource.name} failed, falling back to MIC: ${e.message}")
                                    AudioRecord(
                                        MediaRecorder.AudioSource.MIC,
                                        androidSampleRate,
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

                        // Promote the process before any network handshake so Android can keep
                        // the recorder and sockets alive if the user backgrounds the activity.
                        startStreamingNotification(mode)

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

                        recorder.startRecording()
                        if (sessionRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                            throw IllegalStateException("Audio recorder failed to enter recording state")
                        }

                        // Size raw PCM so the custom header plus worst-case protobuf/FEC metadata stays <= 1472 bytes.
                        val udpSafePayloadSize = UDP_PCM_PAYLOAD_SIZE
                        val bytesPerSample = resolvedAudioFormat.bytesPerSample
                        val frameAlignBytes = bytesPerSample * channelCount.value
                        val alignedPayloadSize = (udpSafePayloadSize / frameAlignBytes) * frameAlignBytes
                        val readBufSize = minOf(minBufSize, alignedPayloadSize).coerceAtLeast(frameAlignBytes)
                        val buffer = ByteArray(readBufSize)
                        val floatBuffer = if (androidAudioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT) FloatArray(readBufSize / 4) else null
                        var sequenceNumber = 0
                        var fecGroupBuffer = mutableListOf<ByteArray>()
                        var fecGroupStartSeq = 0
                        var lastSuccessfulAudioRead = SystemClock.elapsedRealtime()
                        var lastUiLevelUpdateAt = 0L

                        // Helper function to establish network connection
                        suspend fun connectTransport(targetIp: String, targetPort: Int) {
                            writerJob?.cancel()
                            readerJob?.cancel()
                            writerJob?.join()
                            readerJob?.join()
                            try { tcpSocket?.close() } catch (_: Exception) {}
                            try { sessionUdpSocket?.close() } catch (_: Exception) {}
                            try { selectorManager?.close() } catch (_: Exception) {}

                            tcpSocket = null
                            input = null
                            output = null
                            sessionUdpSocket = null
                            sessionUdpAddress = null

                            var newSelector: SelectorManager? = null
                            var newTcpSocket: Socket? = null
                            var newInput: ByteReadChannel? = null
                            var newOutput: ByteWriteChannel? = null
                            var secureSession: SessionCrypto? = null

                            if (transportProtocol == TransportProtocol.Tcp || transportProtocol == TransportProtocol.Both) {
                                Logger.i("AudioEngine", "Connecting via TCP to $targetIp:$targetPort")
                                // An old server silently ignores the secure hello, so a
                                // fallback re-opens a fresh socket and continues plaintext.
                                var attemptSecure = mode == ConnectionMode.Wifi && targetIp !in plainOnlyPeers
                                while (true) {
                                    newSelector = SelectorManager(Dispatchers.IO)
                                    selectorManager = newSelector
                                    val socketBuilder = aSocket(newSelector!!)
                                    try {
                                        newTcpSocket = socketBuilder.tcp().connect(targetIp, targetPort) {
                                            keepAlive = true
                                            socketTimeout = 10000L
                                            noDelay = true
                                        }
                                    } catch (e: Exception) {
                                        Logger.e("AudioEngine", "TCP connect to $targetIp:$targetPort failed: ${e.message}")
                                        throw e
                                    }
                                    newInput = newTcpSocket.openReadChannel()
                                    newOutput = newTcpSocket.openWriteChannel(autoFlush = true)

                                    Logger.d("AudioEngine", "Starting handshake")
                                    newOutput.writeFully(CHECK_1.encodeToByteArray())
                                    newOutput.flush()
                                    val responseBuffer = ByteArray(CHECK_2.length)
                                    newInput.readFully(responseBuffer, 0, responseBuffer.size)

                                    if (!responseBuffer.decodeToString().equals(CHECK_2)) {
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
                                        when (val outcome = negotiateSecureTransport(inChannel, outChannel, targetIp)) {
                                            is SecureNegotiation.Session -> {
                                                secureSession = outcome.crypto
                                                activeCrypto = outcome.crypto
                                                _sessionEncrypted.value = true
                                                Logger.i("AudioEngine", "Secure transport established with $targetIp")
                                            }
                                            is SecureNegotiation.FallBackToPlain -> {
                                                plainOnlyPeers.add(targetIp)
                                                runCatching { newTcpSocket?.close() }
                                                newSelector.close()
                                                Logger.i("AudioEngine", "Peer $targetIp lacks encryption support; reconnecting in plaintext")
                                                continue
                                            }
                                            is SecureNegotiation.Failure -> {
                                                runCatching { newTcpSocket?.close() }
                                                newSelector.close()
                                                throw java.io.IOException(outcome.message)
                                            }
                                        }
                                    }
                                    break
                                }

                                val connectBytes = proto.encodeToByteArray(
                                    MessageWrapper.serializer(),
                                    MessageWrapper(connect = ConnectMessage(sessionId))
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

                            // Start Writer Job
                            writerJob = launch {
                                Logger.d("AudioEngine", "Writer loop started")
                                for (msg in channel) {
                                    try {
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
                                                        sessionLastPingReceivedTime = System.currentTimeMillis()
                                                        if (wrapper.ping.audioHealthSupported) {
                                                            val healthNow = SystemClock.elapsedRealtime()
                                                            if (serverAudioHealthSupported.compareAndSet(false, true)) {
                                                                lastHealthyAudioHealthTime.set(healthNow)
                                                            }
                                                            if (wrapper.ping.audioHealthy || _isMuted.value) {
                                                                lastHealthyAudioHealthTime.set(healthNow)
                                                                if (serverAudioUnhealthyNotified.compareAndSet(true, false)) {
                                                                    Logger.i("AudioEngine", "Desktop audio stream recovered")
                                                                    if (desiredRunning && hasEstablishedStream) {
                                                                        updateStreamingNotification(AudioService.STATUS_STREAMING)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        channel.send(MessageWrapper(pong = PongMessage(wrapper.ping.timestamp)))
                                                    }
                                                } catch (e: Exception) {
                                                    Logger.e("AudioEngine", "Error decoding incoming message", e)
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
                            while (true) {
                                val readBytes = if (androidAudioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT && floatBuffer != null) {
                                    val readFloats = sessionRecorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_NON_BLOCKING)
                                    if (readFloats > 0) readFloats * 4 else 0
                                } else {
                                    sessionRecorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                                }
                                if (readBytes > 0) {
                                    lastSuccessfulAudioRead = SystemClock.elapsedRealtime()
                                } else {
                                    break
                                }
                            }
                        }

                        // Initial connection attempt
                        val targetIp = if (mode == ConnectionMode.Usb) "127.0.0.1" else ip
                        Logger.i("AudioEngine", "Connecting with protocol $transportProtocol to $targetIp:$port")
                        drainAudioRecord()
                        connectTransport(targetIp, port)
                        drainAudioRecord()
                        fecGroupBuffer.clear()
                        fecGroupStartSeq = sequenceNumber

                        sessionLastPingReceivedTime = System.currentTimeMillis()
                        lastHealthyAudioHealthTime.set(SystemClock.elapsedRealtime())
                        _state.value = StreamState.Streaming
                        hasEstablishedStream = true
                        _lastError.value = null
                        updateStreamingNotification(AudioService.STATUS_STREAMING)
                        connectionComplete.complete(Unit)

                        // Main Audio Recording & Network Monitoring Loop
                        while (isActive && desiredRunning && lifecycleGeneration == sessionGeneration) {
                            val currentWriter = writerJob
                            val currentReader = readerJob

                            val isWriterFailed = currentWriter?.isCompleted == true || currentWriter?.isCancelled == true
                            val isReaderFailed = currentReader != null && (currentReader.isCompleted || currentReader.isCancelled)
                            val isHeartbeatTimeout = currentReader != null && (System.currentTimeMillis() - sessionLastPingReceivedTime > HEARTBEAT_TIMEOUT_MS)
                            val isAudioHealthTimeout = currentReader != null && shouldFailForServerAudioHealth(
                                supported = serverAudioHealthSupported.get(),
                                muted = _isMuted.value,
                                nowMs = SystemClock.elapsedRealtime(),
                                lastHealthyAudioHealthMs = lastHealthyAudioHealthTime.get()
                            )

                            if (isWriterFailed || isReaderFailed || isHeartbeatTimeout || isAudioHealthTimeout) {
                                if (hasEstablishedStream && desiredRunning) {
                                    _state.value = StreamState.Connecting
                                    updateStreamingNotification(AudioService.STATUS_RECONNECTING)
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
                                            fecGroupBuffer.clear()
                                            fecGroupStartSeq = sequenceNumber

                                            sessionLastPingReceivedTime = System.currentTimeMillis()
                                            serverAudioHealthSupported.set(false)
                                            lastHealthyAudioHealthTime.set(SystemClock.elapsedRealtime())
                                            serverAudioUnhealthyNotified.set(false)
                                            sessionUdpConsecutiveFailures = 0

                                            _state.value = StreamState.Streaming
                                            _lastError.value = null
                                            updateStreamingNotification(AudioService.STATUS_STREAMING)
                                            Logger.i("AudioEngine", "Successfully reconnected to desktop")
                                            reconnected = true
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
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

                            if (androidAudioFormat == android.media.AudioFormat.ENCODING_PCM_FLOAT && floatBuffer != null) {
                                val readFloats = recorder.read(floatBuffer, 0, floatBuffer.size, AudioRecord.READ_BLOCKING)
                                if (readFloats > 0) {
                                    readBytes = readFloats * 4
                                    audioData = ByteArray(readBytes)
                                    ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(floatBuffer, 0, readFloats)
                                } else {
                                    audioData = ByteArray(0)
                                }
                            } else {
                                readBytes = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
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
                                if (lastUiLevelUpdateAt == 0L ||
                                    levelNow - lastUiLevelUpdateAt >= UI_AUDIO_LEVEL_UPDATE_INTERVAL_MS
                                ) {
                                    val levelData = calculateAudioLevelData(audioData, resolvedAudioFormat.captureFormat)
                                    _audioLevels.value = levelData.rms
                                    _audioLevelData.value = levelData
                                    lastUiLevelUpdateAt = levelNow
                                }

                                if (!_isMuted.value && _state.value == StreamState.Streaming) {
                                    val packet = AudioPacketMessage(
                                        buffer = audioData,
                                        sampleRate = androidSampleRate,
                                        channelCount = if (channelCount == ChannelCount.Stereo) 2 else 1,
                                        audioFormat = wireAudioFormat.value
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

                                        // FEC: 收集音频 buffer，满一组后生成 FEC 包
                                        fecGroupBuffer.add(audioData)
                                        if (fecGroupBuffer.size >= FEC_GROUP_SIZE) {
                                            val xorResult = xorBuffers(fecGroupBuffer)
                                            val fecPacket = AudioPacketMessage(
                                                buffer = xorResult,
                                                sampleRate = androidSampleRate,
                                                channelCount = if (channelCount == ChannelCount.Stereo) 2 else 1,
                                                audioFormat = wireAudioFormat.value
                                            )
                                            val fecWrapper = MessageWrapper(
                                                audioPacket = AudioPacketMessageOrdered(
                                                    sequenceNumber = sequenceNumber,
                                                    audioPacket = fecPacket,
                                                    timestamp = System.currentTimeMillis(),
                                                    fecBuffer = byteArrayOf(1),
                                                    fecSequenceNumber = fecGroupStartSeq,
                                                    sessionId = sessionId,
                                                    fecPacketLengths = fecGroupBuffer.map { it.size }
                                                )
                                            )
                                            sessionUdpConsecutiveFailures = sendAudioPacketViaUdp(fecWrapper, localUdpSocket, localUdpAddress, sessionUdpConsecutiveFailures, activeCrypto?.udp)
                                            fecGroupBuffer = mutableListOf()
                                            fecGroupStartSeq = sequenceNumber
                                        }
                                    } else {
                                        channel.trySend(wrapper)
                                    }
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        connectionComplete.completeExceptionally(e)
                        throw e
                    } catch (e: Exception) {
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
                        Logger.e("AudioEngine", "Connection lost: $errorMsg", e)
                        if (lifecycleGeneration == sessionGeneration) {
                            _state.value = StreamState.Error
                            _lastError.value = errorMsg
                        }
                        connectionComplete.completeExceptionally(Exception(errorMsg, e))
                    } finally {
                        connectionComplete.completeExceptionally(CancellationException("Audio session ended before startup completed"))
                        Logger.d("AudioEngine", "Cleaning up resources for generation $sessionGeneration")
                        writerJob?.cancel()
                        readerJob?.cancel()
                        channel.close()
                        val recorderToRelease = recorder
                        val recorderStopCompletion = stopAndReleaseRecorderAsync(recorderToRelease)
                        recorder = null
                        try {
                            withContext(NonCancellable) {
                                recorderStopCompletion?.await()
                            }
                        } catch (e: Exception) {
                            Logger.w("AudioEngine", "AudioRecord teardown did not complete cleanly: ${e.message}")
                        }
                        try {
                            tcpSocket?.close()
                            sessionUdpSocket?.close()
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
                                if (_state.value != StreamState.Error) { _state.value = StreamState.Idle; _sessionEncrypted.value = false; activeCrypto = null }
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
        data class Failure(val message: String) : SecureNegotiation()
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
        peer: String
    ): SecureNegotiation = try {
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

        val header = ByteArray(8)
        input.readFully(header)
        if (SecureChannel.readI32Be(header, 0) != PACKET_MAGIC) {
            return SecureNegotiation.FallBackToPlain
        }
        val replyLength = SecureChannel.readI32Be(header, 4)
        if (replyLength <= 0 || replyLength > 1 shl 20) {
            return SecureNegotiation.FallBackToPlain
        }
        val replyBytes = ByteArray(replyLength)
        input.readFully(replyBytes)
        val serverHello = proto.decodeFromByteArray(MessageWrapper.serializer(), replyBytes)
            .secureServerHello
            ?: return SecureNegotiation.FallBackToPlain

        // A legacy-capable server signals "plaintext only" with suite 0.
        if (serverHello.suite == 0) return SecureNegotiation.FallBackToPlain

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
            _pairingPrompt.value = PairingPrompt(sas = sas, peer = peer)
            pairingDeferred = CompletableDeferred()
            val accepted = try {
                withTimeout(120_000L) { pairingDeferred?.await() ?: false }
            } catch (_: Exception) {
                false
            } finally {
                _pairingPrompt.value = null
                pairingDeferred = null
            }
            if (!accepted) {
                return SecureNegotiation.Failure("pairing rejected by user")
            }
            SecureChannel.rememberPairedServer(secureSettings, serverHello.identityPubKey, peer)
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
        val verdictBytes = ByteArray(verdictLength)
        input.readFully(verdictBytes)
        val opened = session.tcpS2C.open(verdictHeader, verdictBytes)
        val accepted = proto.decodeFromByteArray(MessageWrapper.serializer(), opened)
            .secureResult?.accepted ?: false
        if (!accepted) {
            return SecureNegotiation.Failure("server rejected the secure session")
        }
        SecureNegotiation.Session(session)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // Anything else (EOF/garbage/timeout) means the peer predates the
        // encrypted transport; retrying in plaintext is safe because the
        // fallback only happens before any audio or identity material flowed.
        Logger.w("AudioEngine", "Secure negotiation unavailable (${e.message}); falling back to plaintext")
        SecureNegotiation.FallBackToPlain
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun sendAudioPacketViaUdp(
        wrapper: MessageWrapper,
        socket: DatagramSocket,
        serverAddress: InetSocketAddress,
        consecutiveFailures: Int,
        udpCipher: UdpCipher?
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
                    hasEstablishedStream = false
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
                hasEstablishedStream = false
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
            hasEstablishedStream = false
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
                _sessionEncrypted.value = false
                activeCrypto = null
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

    private fun startStreamingNotification(mode: ConnectionMode) {
        val context = ContextHelper.getContext()
        if (context == null) {
            Logger.w("AudioEngine", "Audio context is unavailable; foreground service was not started")
            return
        }
        if (AudioService.isRunning()) {
            if (AudioService.updateStatusIfRunning(
                    AudioService.STATUS_CONNECTING,
                    useWifiLock = mode == ConnectionMode.Wifi
                )) {
                return
            }
        }
        val intent = Intent(context, AudioService::class.java).apply {
            action = AudioService.ACTION_START
            putExtra(AudioService.EXTRA_USE_WIFI_LOCK, mode == ConnectionMode.Wifi)
            putExtra(AudioService.EXTRA_STATUS, AudioService.STATUS_CONNECTING)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun updateStreamingNotification(status: String) {
        val context = ContextHelper.getContext() ?: return
        if (AudioService.updateStatusIfRunning(status)) return
        try {
            val intent = Intent(context, AudioService::class.java).apply {
                action = AudioService.ACTION_UPDATE_STATUS
                putExtra(AudioService.EXTRA_STATUS, status)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Logger.w("AudioEngine", "Failed to update streaming notification: ${e.message}")
        }
    }
    
    fun setMonitoring(enabled: Boolean) { }

    val installProgress: Flow<String?> = MutableStateFlow(null)
    
    suspend fun installDriver() { }

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

    fun updateConfig(
        enableNS: Boolean,
        nsType: NoiseReductionType,
        nsIntensity: Float,
        enableAGC: Boolean,
        agcTargetLevel: Int,
        agcAttackRate: Float,
        agcDecayRate: Float,
        enableVAD: Boolean,
        vadThreshold: Int,
        enableDereverb: Boolean,
        dereverbLevel: Float,
        amplification: Float,
        processingChain: List<AudioEffectType>,
        equalizerConfig: EqualizerConfig
    ) {
        val nsChanged = this.enableNS != enableNS
        val agcChanged = this.enableAGC != enableAGC

        this.enableNS = enableNS
        this.enableAGC = enableAGC
        // Note: nsIntensity, agcAttackRate, agcDecayRate, dereverbLevel, amplification,
        // processingChain, and equalizerConfig are currently ignored on Android
        // as it uses hardware-based processing.

        try {
            noiseSuppressor?.enabled = enableNS
            automaticGainControl?.enabled = enableAGC
        } catch (e: Exception) {
            Logger.e("AudioEngine", "Error updating audio effects: ${e.message}")
        }

        if ((nsChanged || agcChanged) && desiredRunning && _state.value == StreamState.Streaming) {
            Logger.i("AudioEngine", "Hardware processing changed, restarting audio stream...")
            scheduleConfigRestart()
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

    fun setStreamingNotificationEnabled(enabled: Boolean) {
        enableStreamingNotification = enabled
        if (_state.value == StreamState.Streaming && !enabled) {
            Logger.w(
                "AudioEngine",
                "Streaming notification cannot be hidden while Android requires a microphone foreground service"
            )
        }
    }

    private fun isNormalDisconnect(e: Throwable): Boolean {
        if (e is kotlinx.coroutines.CancellationException) return true
        if (e is EOFException) return true
        if (e is io.ktor.utils.io.errors.EOFException) return true
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
        val rms = Math.sqrt(sum / sampleCount).toFloat().coerceIn(0f, 1f)
    val peak = maxSample.toFloat().coerceIn(0f, 1f)
        return AudioLevelData.fromRmsAndPeak(rms, peak)
    }

    fun updatePerformanceConfig(config: PerformanceConfig) {
        Logger.d("AudioEngine", "Android does not support dynamic performance config adjustment")
    }
}

private class UdpCircuitBreakerException(message: String) : Exception(message)

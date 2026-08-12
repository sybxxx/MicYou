package com.lanrhyme.micyou.service
import com.lanrhyme.micyou.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.app.PendingIntent
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.runBlocking
import java.lang.ref.WeakReference
import com.lanrhyme.micyou.MainActivity
import com.lanrhyme.micyou.audio.AudioEngine
import com.lanrhyme.micyou.util.AppLanguage
import com.lanrhyme.micyou.util.getString
class AudioService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var useWifiLock = false

    companion object {
        private const val CHANNEL_ID = "AudioServiceChannel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "ACTION_START"
        const val ACTION_START_IDLE = "ACTION_START_IDLE"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val ACTION_UPDATE_STATUS = "ACTION_UPDATE_STATUS"
        const val EXTRA_USE_WIFI_LOCK = "EXTRA_USE_WIFI_LOCK"
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val PREFS_NAME = "android_mic_prefs"
        const val KEY_WIFI_LOCK = "audio_wifi_lock"
        const val KEY_STREAMING = "audio_streaming"
        private const val RESTART_DELAY_MS = 5000L
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_STREAMING = "streaming"
        const val STATUS_RECONNECTING = "reconnecting"
        private const val STATUS_IDLE = "idle"

        @Volatile
        private var serviceReference: WeakReference<AudioService>? = null

        fun isRunning(): Boolean = serviceReference?.get() != null

        /** Updates or promotes the service without starting a duplicate session. */
        fun updateStatusIfRunning(status: String, useWifiLock: Boolean? = null): Boolean {
            val service = serviceReference?.get() ?: return false
            return try {
                service.updateStreamingStatus(status, useWifiLock)
                true
            } catch (e: Exception) {
                Log.w("AudioService", "Failed to promote streaming foreground service", e)
                false
            }
        }
    }

    @Volatile
    private var streamingForeground = false

    override fun onCreate() {
        super.onCreate()
        serviceReference = WeakReference(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startStreamingForeground(
                    intent.getBooleanExtra(EXTRA_USE_WIFI_LOCK, readWifiLockFlag()),
                    intent.getStringExtra(EXTRA_STATUS) ?: STATUS_CONNECTING
                )
            }
            ACTION_START_IDLE -> startIdleForeground()
            ACTION_UPDATE_STATUS -> {
                updateStreamingStatus(intent.getStringExtra(EXTRA_STATUS) ?: STATUS_CONNECTING)
            }
            ACTION_STOP -> startIdleForeground()
            ACTION_DISCONNECT -> {
                AudioEngine.requestDisconnectFromNotification()
                startIdleForeground()
            }
            null -> {
                // START_STICKY recreates the service with a null intent after a system
                // restart. Restore the last foreground state and let the existing
                // AudioEngine/session lifecycle decide whether streaming resumes.
                if (readStreamingFlag()) {
                    startStreamingForeground(readWifiLockFlag(), STATUS_STREAMING)
                } else {
                    startIdleForeground()
                }
            }
            else -> if (AudioEngine.isStreaming()) {
                startStreamingForeground(AudioEngine.isWifiStreaming(), STATUS_STREAMING)
            }
        }
        return START_STICKY
    }

    private fun startStreamingForeground(useWifiLock: Boolean, status: String) {
        this.useWifiLock = useWifiLock
        writeWifiLockFlag(useWifiLock)
        writeStreamingFlag(true)
        cancelRestartAlarm()
        val notification = createNotification(status)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        streamingForeground = true
        acquireSessionLocks(useWifiLock)
    }

    private fun startIdleForeground() {
        useWifiLock = false
        writeWifiLockFlag(false)
        writeStreamingFlag(false)
        releaseSessionLocks()
        streamingForeground = false
        val notification = createNotification(STATUS_IDLE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireSessionLocks(useWifiLock: Boolean) {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:audio-stream"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (useWifiLock && wifiLock?.isHeld != true) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "$packageName:audio-stream"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!useWifiLock) {
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
        }

        if (useWifiLock && multicastLock?.isHeld != true) {
            try {
                @Suppress("DEPRECATION")
                multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createMulticastLock("$packageName:micyou-discovery")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            } catch (e: Exception) {
                Log.w("AudioService", "Failed to acquire Wi-Fi multicast lock", e)
            }
        } else if (!useWifiLock) {
            multicastLock?.let { if (it.isHeld) it.release() }
            multicastLock = null
        }
    }

    private fun releaseSessionLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestart()
    }

    private fun scheduleRestart() {
        val alarm = getSystemService(AlarmManager::class.java)
        val triggerAt = System.currentTimeMillis() + RESTART_DELAY_MS
        try {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, restartPendingIntent())
        } catch (e: SecurityException) {
            // Some Android builds require the exact-alarm permission. A bounded
            // inexact alarm still restores the foreground service when allowed.
            Log.w("AudioService", "Exact restart alarm unavailable; using inexact alarm", e)
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, restartPendingIntent())
        }
    }

    private fun cancelRestartAlarm() {
        val alarm = getSystemService(AlarmManager::class.java)
        alarm.cancel(restartPendingIntent())
    }

    private fun restartPendingIntent(): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            0,
            Intent(this, RestartReceiver::class.java).apply { action = ACTION_START },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun writeWifiLockFlag(useWifiLock: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WIFI_LOCK, useWifiLock)
            .apply()
    }

    private fun readWifiLockFlag(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_WIFI_LOCK, false)

    private fun writeStreamingFlag(streaming: Boolean) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STREAMING, streaming)
            .apply()
    }

    private fun readStreamingFlag(): Boolean =
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_STREAMING, false)

    override fun onDestroy() {
        releaseSessionLocks()
        if (serviceReference?.get() === this) {
            serviceReference = null
        }
        streamingForeground = false
        super.onDestroy()
    }

    private fun updateNotification(status: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, createNotification(status))
        } catch (e: Exception) {
            Log.w("AudioService", "Failed to update streaming notification", e)
        }
    }

    private fun createNotification(status: String): Notification {
        val (title, text, contentIntent) = if (status == STATUS_IDLE) {
            val openApp = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val (idleTitle, idleText) = resolveIdleNotificationText()
            Triple(
                idleTitle,
                idleText,
                PendingIntent.getActivity(
                    this,
                    1,
                    openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            val disconnectIntent = Intent(this, AudioService::class.java).apply { action = ACTION_DISCONNECT }
            val (streamingTitle, streamingText) = resolveNotificationText(status)
            Triple(
                streamingTitle,
                streamingText,
                PendingIntent.getService(
                    this,
                    0,
                    disconnectIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateStreamingStatus(status: String, requestedWifiLock: Boolean? = null) {
        if (!streamingForeground) {
            startStreamingForeground(requestedWifiLock ?: readWifiLockFlag(), status)
        } else {
            requestedWifiLock?.let {
                useWifiLock = it
                writeWifiLockFlag(it)
                writeStreamingFlag(true)
                acquireSessionLocks(it)
            }
            updateNotification(status)
        }
    }

    private fun resolveNotificationText(status: String): Pair<String, String> {
        val selectedLanguage = readSelectedLanguage()
        if (status != STATUS_STREAMING) {
            val text = when (status) {
                STATUS_RECONNECTING -> when (selectedLanguage) {
                    AppLanguage.English -> "Reconnecting to desktop..."
                    AppLanguage.Chinese -> "正在重新连接电脑端..."
                    AppLanguage.ChineseTraditional -> "正在重新連線至電腦端..."
                    AppLanguage.Cantonese -> "重新連緊電腦..."
                    else -> getString(R.string.streaming_notification_reconnecting)
                }
                else -> when (selectedLanguage) {
                    AppLanguage.English -> "Connecting to desktop..."
                    AppLanguage.Chinese -> "正在连接电脑端..."
                    AppLanguage.ChineseTraditional -> "正在連線至電腦端..."
                    AppLanguage.Cantonese -> "連緊電腦..."
                    else -> getString(R.string.streaming_notification_connecting)
                }
            }
            return getString(R.string.app_name) to text
        }
        return when (selectedLanguage) {
            AppLanguage.English -> "MicYou Streaming" to "Tap to disconnect"
            AppLanguage.Chinese -> "MicYou 正在传输" to "点击断开连接"
            AppLanguage.ChineseTraditional -> "MicYou 正在傳輸" to "點擊中斷連線"
            AppLanguage.Cantonese -> "MicYou 傳輸緊" to "撳掣斷開連線"
            else -> getString(R.string.streaming_notification_title) to getString(R.string.streaming_notification_text)
        }
    }

    private fun resolveIdleNotificationText(): Pair<String, String> {
        return when (readSelectedLanguage()) {
            AppLanguage.English -> "MicYou is on" to "Tap to manage"
            AppLanguage.Chinese -> "MicYou 已开启" to "点击管理"
            AppLanguage.ChineseTraditional -> "MicYou 已開啟" to "點擊管理"
            AppLanguage.Cantonese -> "MicYou 開咗" to "撳掣管理"
            else -> getString(R.string.notification_idle_title) to getString(R.string.notification_idle_text)
        }
    }

    private fun readSelectedLanguage(): AppLanguage {
        val prefs = getSharedPreferences("android_mic_prefs", Context.MODE_PRIVATE)
    val saved = prefs.getString("language", AppLanguage.System.name)
        return try {
            AppLanguage.valueOf(saved ?: AppLanguage.System.name)
        } catch (_: Exception) {
            AppLanguage.System
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = runBlocking { getString(R.string.audioStreamingService) }
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}

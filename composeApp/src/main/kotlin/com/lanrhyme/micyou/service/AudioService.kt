package com.lanrhyme.micyou.service
import com.lanrhyme.micyou.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import com.lanrhyme.micyou.audio.AudioEngine
import com.lanrhyme.micyou.service.AudioService
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
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_DISCONNECT = "ACTION_DISCONNECT"
        const val ACTION_UPDATE_STATUS = "ACTION_UPDATE_STATUS"
        const val EXTRA_USE_WIFI_LOCK = "EXTRA_USE_WIFI_LOCK"
        const val EXTRA_STATUS = "EXTRA_STATUS"
        const val STATUS_CONNECTING = "connecting"
        const val STATUS_STREAMING = "streaming"
        const val STATUS_RECONNECTING = "reconnecting"

        @Volatile
        private var serviceReference: WeakReference<AudioService>? = null

        fun isRunning(): Boolean = serviceReference?.get() != null

        /** Updates an existing foreground service without starting a new service from the background. */
        fun updateStatusIfRunning(status: String): Boolean {
            val service = serviceReference?.get() ?: return false
            service.updateNotification(status)
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceReference = WeakReference(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keepAlive = when (intent?.action) {
            ACTION_START -> {
                useWifiLock = intent.getBooleanExtra(EXTRA_USE_WIFI_LOCK, false)
                startForegroundService(
                    useWifiLock,
                    intent.getStringExtra(EXTRA_STATUS) ?: STATUS_CONNECTING
                )
                true
            }
            ACTION_UPDATE_STATUS -> {
                updateNotification(intent.getStringExtra(EXTRA_STATUS) ?: STATUS_CONNECTING)
                true
            }
            ACTION_STOP -> {
                stopForegroundService()
                false
            }
            ACTION_DISCONNECT -> {
                AudioEngine.requestDisconnectFromNotification()
                stopForegroundService()
                false
            }
            null -> {
                // START_STICKY recreates the service with a null intent after a system
                // restart. Reassert the foreground state only for a live audio session.
                if (AudioEngine.isStreaming()) {
                    useWifiLock = AudioEngine.isWifiStreaming()
                    startForegroundService(useWifiLock, STATUS_STREAMING)
                    true
                } else {
                    false
                }
            }
            else -> AudioEngine.isStreaming()
        }
        return if (keepAlive) START_STICKY else START_NOT_STICKY
    }

    private fun startForegroundService(useWifiLock: Boolean, status: String) {
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
        acquireSessionLocks(useWifiLock)
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

    private fun stopForegroundService() {
        releaseSessionLocks()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        releaseSessionLocks()
        if (serviceReference?.get() === this) {
            serviceReference = null
        }
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
        val disconnectIntent = Intent(this, AudioService::class.java).apply { action = ACTION_DISCONNECT }
    val disconnectPendingIntent = PendingIntent.getService(
            this,
            0,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    val (title, text) = resolveNotificationText(status)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(disconnectPendingIntent)
            .build()
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

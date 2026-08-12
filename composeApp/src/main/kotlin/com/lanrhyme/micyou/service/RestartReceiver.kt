package com.lanrhyme.micyou.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Restores the persistent foreground service after Android removes its task. */
class RestartReceiver : BroadcastReceiver() {
    @Suppress("UNUSED_PARAMETER")
    override fun onReceive(context: Context, intent: Intent?) {
        val prefs = context.getSharedPreferences(AudioService.PREFS_NAME, Context.MODE_PRIVATE)
        val useWifiLock = prefs.getBoolean(AudioService.KEY_WIFI_LOCK, false)
        val streaming = prefs.getBoolean(AudioService.KEY_STREAMING, false)
        val serviceIntent = Intent(context, AudioService::class.java).apply {
            action = if (streaming) AudioService.ACTION_START else AudioService.ACTION_START_IDLE
            putExtra(AudioService.EXTRA_USE_WIFI_LOCK, useWifiLock)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}

package com.dreyev.vlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.dreyev.vlink.MainActivity
import com.dreyev.vlink.R
import com.dreyev.vlink.VLinkApp
import com.dreyev.vlink.obd.LinkState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Foreground service that keeps the Bluetooth link and GPS running with the screen off. */
class ObdService : LifecycleService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        createChannel()
        val types = if (Build.VERSION.SDK_INT >= 29)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, 1, notification("Connecting to adapter…"), types)
        } catch (e: Exception) {
            // Location permission missing → run as connected-device only.
            if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, 1, notification("Connecting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
        val app = application as VLinkApp
        lifecycleScope.launch {
            app.store.state.map { it.link.state to it.link.deviceName }.distinctUntilChanged().collect { (st, name) ->
                val text = when (st) {
                    LinkState.CONNECTED -> "Connected to ${name ?: "adapter"}"
                    LinkState.CONNECTING, LinkState.INITIALIZING -> "Connecting to ${name ?: "adapter"}…"
                    LinkState.ERROR -> "Reconnecting to ${name ?: "adapter"}…"
                    LinkState.DISCONNECTED -> "Disconnected"
                }
                getSystemService(NotificationManager::class.java).notify(1, notification(text))
            }
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Vehicle connection", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle("dreyev VLink")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    companion object { const val CHANNEL = "obd_link" }
}

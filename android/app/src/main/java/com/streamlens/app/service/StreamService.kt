package com.streamlens.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.streamlens.app.R

/**
 * "Service mode" while live: a foreground service so Android doesn't kill or throttle the app,
 * a wake lock, a Wi-Fi lock that stops the radio napping (a common cause of random stutters),
 * and optionally Do Not Disturb so calls and notifications can't interrupt the camera.
 */
class StreamService : Service() {

    companion object {
        private const val CHANNEL = "live"
        private const val EXTRA_SILENCE = "silence"

        fun start(context: Context, silence: Boolean) {
            val intent = Intent(context, StreamService::class.java).putExtra(EXTRA_SILENCE, silence)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var previousFilter: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live streaming", NotificationManager.IMPORTANCE_LOW))
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("StreamLens is live")
            .setContentText("Sending camera to OBS")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
        )

        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamLens:live").apply { acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(mode, "StreamLens:live").apply { acquire() }
        }
        if (intent?.getBooleanExtra(EXTRA_SILENCE, false) == true && nm.isNotificationPolicyAccessGranted) {
            if (previousFilter == null) previousFilter = nm.currentInterruptionFilter
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        } else if (previousFilter != null) {
            // Silence was switched off while live: give the phone its calls back now.
            nm.setInterruptionFilter(previousFilter!!)
            previousFilter = null
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        previousFilter?.let { getSystemService(NotificationManager::class.java).setInterruptionFilter(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

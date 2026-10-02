package com.vits.app.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vits.app.MainActivity
import com.vits.app.R

/**
 * Foreground service hosting an export, so Android doesn't kill it when the user switches apps.
 * Shows progress with a Cancel action. The export needs a Looper thread (see Exporter.run).
 */
class ExportService : Service() {
    private var thread: HandlerThread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ExportManager.cancel()
            return START_NOT_STICKY
        }
        createChannel()
        // Platform call, not ServiceCompat: the compat helper drops service types newer than
        // itself (mediaProcessing, API 35), and Android 14+ rejects a typeless foreground service.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification(0), foregroundType())
        } else {
            startForeground(NOTIFICATION_ID, notification(0))
        }
        val t = HandlerThread("vits-export").apply { start() }
        thread = t
        Handler(t.looper).post {
            var lastPercent = -1
            ExportManager.runPending(applicationContext) { p ->
                val percent = (p * 100).toInt()
                if (percent != lastPercent) {
                    lastPercent = percent
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(percent))
                }
            }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        thread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(percent: Int) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_monochrome)
        .setContentTitle(getString(R.string.export_running))
        .setContentText(getString(R.string.export_percent, percent))
        .setProgress(100, percent, false)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        .addAction(
            0, getString(R.string.action_cancel),
            PendingIntent.getService(
                this, 1, Intent(this, ExportService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.export_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    private companion object {
        const val CHANNEL_ID = "export"
        const val NOTIFICATION_ID = 1
        const val ACTION_CANCEL = "com.vits.app.export.CANCEL"
    }
}

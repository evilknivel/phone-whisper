package com.kafkasl.phonewhisper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast

/**
 * Runs model downloads as a foreground service, so a several-hundred-MB download keeps going when
 * the screen turns off or the user leaves the app. The work itself is done by [ModelDownloader];
 * this service only keeps the process alive, holds a wake lock and shows progress.
 */
class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_ARCHIVE = "archive"

        /** Start downloading [model]. Returns false if it is already downloading. */
        fun start(ctx: Context, model: Model): Boolean {
            if (ModelDownloader.state(model) != null) return false
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra(EXTRA_ARCHIVE, model.archive))
            return true
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val listener: (Model, DownloadState) -> Unit = { model, state -> onState(model, state) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Model downloads", NotificationManager.IMPORTANCE_LOW)
        )
        ModelDownloader.addListener(listener)
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneWhisper:download")
            .apply { setReferenceCounted(false); acquire(60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val model = MODEL_CATALOG.firstOrNull { it.archive == intent?.getStringExtra(EXTRA_ARCHIVE) }
        startForeground(
            NOTIFICATION_ID,
            notification(model?.let { "Downloading ${it.name}" } ?: "Downloading model", 0, indeterminate = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (model != null) ModelDownloader.download(this, model)
        if (ModelDownloader.activeCount() == 0) stopSelf()
        // A killed download is not resumed automatically; the user can tap it again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ModelDownloader.removeListener(listener)
        wakeLock?.release()
        super.onDestroy()
    }

    private fun onState(model: Model, state: DownloadState) {
        val nm = getSystemService(NotificationManager::class.java)
        when (state) {
            is DownloadState.Downloading ->
                nm.notify(NOTIFICATION_ID, notification("Downloading ${model.name}", (state.progress * 100).toInt(), false))
            is DownloadState.Extracting ->
                nm.notify(NOTIFICATION_ID, notification("Installing ${model.name}", 0, true))
            is DownloadState.Done, is DownloadState.Error -> {
                val message = if (state is DownloadState.Error) "${model.name}: ${state.message}" else "${model.name} ready"
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    if (ModelDownloader.activeCount() == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun notification(title: String, percent: Int, indeterminate: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (indeterminate) "" else "$percent%")
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }
}

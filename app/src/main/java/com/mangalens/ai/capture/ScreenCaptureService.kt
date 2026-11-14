package com.mangalens.ai.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import com.mangalens.ai.MainActivity
import com.mangalens.ai.MangaLensApp
import com.mangalens.ai.R
import com.mangalens.ai.overlay.ControlBubble
import com.mangalens.ai.overlay.OverlayManager
import com.mangalens.ai.pipeline.TranslationPipeline

/** Foreground service (type mediaProjection) that owns the whole translation pipeline while translation mode is on. */
class ScreenCaptureService : Service() {
    companion object {
        const val ACTION_START = "mangalens.START"
        const val ACTION_STOP = "mangalens.STOP"
        const val ACTION_TOGGLE = "mangalens.TOGGLE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "mangalens"
        private const val NOTIF_ID = 42
    }

    private var pipeline: TranslationPipeline? = null
    private var overlay: OverlayManager? = null
    private var bubble: ControlBubble? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startEngine(intent)
            ACTION_TOGGLE -> pipeline?.togglePause()
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun startEngine(intent: Intent) {
        if (pipeline != null) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen translation", NotificationManager.IMPORTANCE_LOW))
        // Must be foreground BEFORE obtaining the MediaProjection.
        ServiceCompat.startForeground(this, NOTIF_ID, notification(false), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) { stopAll(); return }
        val projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)

        val app = application as MangaLensApp
        val ov = OverlayManager(this).also { it.attach() }
        overlay = ov
        val b = if (app.settings.settings.value.bubbleEnabled)
            ControlBubble(this, { pipeline?.togglePause() }, { stopAll() }).also { it.show() } else null
        bubble = b

        pipeline = TranslationPipeline(
            this, projection, app.settings, app.db, ov,
            excluded = { listOfNotNull(b?.bounds()) },
            onPauseChanged = { p -> b?.setPaused(p); getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(p)) },
            onProjectionStopped = { stopAll() }
        ).also { it.start() }
    }

    private fun notification(paused: Boolean): Notification {
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, ScreenCaptureService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("MangaLens AI")
            .setContentText(if (paused) "Paused" else "Translating your screen")
            .setContentIntent(open).setOngoing(true)
            .addAction(0, if (paused) "Resume" else "Pause", pi(ACTION_TOGGLE, 1))
            .addAction(0, "Stop", pi(ACTION_STOP, 2))
            .build()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pipeline?.onConfigChanged()
    }

    private fun stopAll() {
        if (stopping) return
        stopping = true
        pipeline?.stop(); pipeline = null
        bubble?.destroy(); bubble = null
        overlay?.destroy(); overlay = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }
}

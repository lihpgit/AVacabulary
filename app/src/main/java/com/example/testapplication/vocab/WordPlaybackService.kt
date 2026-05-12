package com.example.testapplication.vocab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务：防止系统在后台杀死进程，使 FlashCardActivity 中的
 * MediaPlayer 自动朗读可以持续运行。
 *
 * 服务本身不持有播放逻辑，仅保活 + 显示通知。
 */
class WordPlaybackService : Service() {

    private val CHANNEL_ID = "WordPlaybackChannel"
    private val NOTIFICATION_ID = 2

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WordPlayback:WakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        createChannel()

        val openIntent = Intent(this, FlashCardActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, WordPlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("背单词朗读中")
            .setContentText("自动朗读正在后台运行")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopPending)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            startForeground(NOTIFICATION_ID, notification)
        }

        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours safety timeout

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "单词朗读",
                NotificationManager.IMPORTANCE_LOW // 低优先级，不发声
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.testapplication.vocab.STOP_PLAYBACK"
        const val ACTION_SERVICE_STOPPED = "com.example.testapplication.vocab.SERVICE_STOPPED"

        fun start(context: Context) {
            val intent = Intent(context, WordPlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WordPlaybackService::class.java))
        }
    }
}

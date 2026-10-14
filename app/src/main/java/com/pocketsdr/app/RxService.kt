package com.pocketsdr.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * 前台服务：后台听收音时保持 DSP 线程存活（+ 常驻通知显示当前频率）。
 */
class RxService : Service() {

    companion object {
        const val CHANNEL_ID = "pocketsdr_rx"
        const val NOTIF_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "收音中", NotificationManager.IMPORTANCE_LOW).apply {
                description = "PocketSDR 后台收音"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val freq = intent?.getLongExtra("freq", 0L) ?: 0L
        val mode = intent?.getStringExtra("mode") ?: "WFM"
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (freq > 0) "${"%.4f".format(freq / 1e6)} MHz · $mode" else "PocketSDR"
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketSDR")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package io.aigw

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

/**
 * 前台服务：把网关跑在通知栏常驻的进程里，避免被系统回收。
 * 持有 PARTIAL_WAKE_LOCK，防止息屏后上游长连接被掐。
 */
class GatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "aigw_gateway"
        const val NOTIF_ID = 1001
        const val ACTION_START = "io.aigw.START"
        const val ACTION_STOP = "io.aigw.STOP"

        @Volatile
        var server: GatewayServer? = null

        @Volatile
        var lastError: String? = null
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification("启动中…"))

        val confFile = File(filesDir, "config.json")
        if (!confFile.exists()) GatewayConfig.writeDefault(confFile)

        val cfg = try {
            GatewayConfig.load(confFile)
        } catch (e: Exception) {
            lastError = e.message
            updateNotification("配置错误：${e.message}")
            return START_NOT_STICKY
        }

        // 安全护栏：本 App 只允许回环监听。要对外请在前置反代/隧道里做。
        if (cfg.host != "127.0.0.1" && !cfg.allowPublicListen) {
            lastError = "host=${cfg.host} 被拒绝：未显式 allow_public_listen"
            updateNotification(lastError!!)
            return START_NOT_STICKY
        }

        if (server != null) {
            updateNotification("已在运行 :${cfg.port}")
            return START_STICKY
        }

        server = try {
            GatewayServer(applicationContext, cfg).also { it.start() }
        } catch (e: Exception) {
            lastError = e.message
            updateNotification("启动失败：${e.message}")
            return START_NOT_STICKY
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aigw:gateway").apply {
            setReferenceCounted(false)
            acquire()
        }

        updateNotification("监听 127.0.0.1:${cfg.port}")
        return START_STICKY
    }

    override fun onDestroy() {
        try { server?.stop() } catch (e: Exception) {}
        server = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
        }
    }
}
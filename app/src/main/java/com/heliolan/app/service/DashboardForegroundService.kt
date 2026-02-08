package com.heliolan.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class DashboardForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "dashboard_service"
        private const val NOTIFICATION_CHANNEL_NAME = "Dashboard Service"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // TODO: Initialize HTTP server
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        // TODO: Start HTTP server
        return START_STICKY
    }

    override fun onDestroy() {
        // TODO: Stop HTTP server, release resources
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when HelioLAN dashboard is running"
                    setShowBadge(false)
                }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("HelioLAN Dashboard Running")
            .setContentText("Dashboard available at http://...")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // TODO: Replace with app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}

package com.heliolan.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.heliolan.app.R
import com.heliolan.server.DashboardServerController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class DashboardForegroundService : Service() {
    companion object {
        private const val TAG = "DashboardFgService"
        private const val NOTIFICATION_CHANNEL_ID = "dashboard_service"
        private const val NOTIFICATION_CHANNEL_NAME = "Dashboard Service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.heliolan.app.service.action.START_DASHBOARD"
        const val ACTION_STOP = "com.heliolan.app.service.action.STOP_DASHBOARD"
        const val ACTION_RESTART = "com.heliolan.app.service.action.RESTART_DASHBOARD"
        const val EXTRA_PORT = "extra_port"
    }

    @Inject
    lateinit var dashboardServerController: DashboardServerController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var latestDashboardQrCode: Bitmap? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_STOP -> {
                serviceScope.launch {
                    dashboardServerController.stop()
                    latestDashboardQrCode = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                startForeground(NOTIFICATION_ID, createNotification(url = null, isStarting = true))
                serviceScope.launch {
                    val preferredPort = intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 }
                    runCatching {
                        dashboardServerController.restart(preferredPort)
                    }.onSuccess { runtimeInfo ->
                        latestDashboardQrCode = generateQrCode(runtimeInfo.dashboardUrl)
                        updateNotification(runtimeInfo.dashboardUrl)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to restart dashboard server.", error)
                        latestDashboardQrCode = null
                        updateNotification(url = null)
                    }
                }
                return START_STICKY
            }

            else -> {
                startForeground(NOTIFICATION_ID, createNotification(url = null, isStarting = true))
                serviceScope.launch {
                    runCatching {
                        dashboardServerController.start()
                    }.onSuccess { runtimeInfo ->
                        latestDashboardQrCode = generateQrCode(runtimeInfo.dashboardUrl)
                        updateNotification(runtimeInfo.dashboardUrl)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to start dashboard server.", error)
                        latestDashboardQrCode = null
                        updateNotification(url = null)
                    }
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        runBlocking {
            dashboardServerController.stop()
        }
        latestDashboardQrCode = null
        serviceScope.cancel()
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

    private fun updateNotification(url: String?) {
        startForeground(NOTIFICATION_ID, createNotification(url = url))
    }

    private fun createNotification(
        url: String?,
        isStarting: Boolean = false,
    ): Notification {
        val stopIntent =
            Intent(this, DashboardForegroundService::class.java).apply {
                action = ACTION_STOP
            }
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                101,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val openPendingIntent =
            url?.let { dashboardUrl ->
                val openIntent =
                    Intent(Intent.ACTION_VIEW, Uri.parse(dashboardUrl)).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                PendingIntent.getActivity(
                    this,
                    102,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val contentText =
            when {
                isStarting -> getString(R.string.dashboard_service_starting)
                !url.isNullOrBlank() -> getString(R.string.dashboard_service_url, url)
                else -> getString(R.string.dashboard_service_error_start_failed)
            }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.dashboard_service_running))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.action_stop),
                stopPendingIntent,
            )
            .build()
    }

    private fun generateQrCode(url: String): Bitmap? {
        return runCatching {
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512)
            Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until matrix.width) {
                    for (y in 0 until matrix.height) {
                        setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
                    }
                }
            }
        }.getOrNull()
    }
}

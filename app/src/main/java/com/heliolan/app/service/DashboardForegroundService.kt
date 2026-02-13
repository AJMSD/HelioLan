package com.heliolan.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.heliolan.app.R
import com.heliolan.app.ui.MainActivity
import com.heliolan.server.DashboardServerController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var lastKnownDashboardUrl: String? = null
    private var runtimeMonitorJob: Job? = null
    private var pausedForWifiLoss: Boolean = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
                stopServerAndService()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                startForeground(NOTIFICATION_ID, createNotification(url = null, isStarting = true))
                serviceScope.launch {
                    val preferredPort = intent?.getIntExtra(EXTRA_PORT, -1)?.takeIf { it > 0 }
                    runCatching {
                        dashboardServerController.restart(preferredPort)
                    }.onSuccess { runtimeInfo ->
                        onServerRunning(runtimeInfo.dashboardUrl)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to restart dashboard server.", error)
                        latestDashboardQrCode = null
                        updateNotification(
                            url = null,
                            warningMessage = getString(R.string.dashboard_service_error_start_failed),
                        )
                    }
                }
                return START_NOT_STICKY
            }

            else -> {
                startForeground(NOTIFICATION_ID, createNotification(url = null, isStarting = true))
                serviceScope.launch {
                    runCatching {
                        dashboardServerController.start()
                    }.onSuccess { runtimeInfo ->
                        onServerRunning(runtimeInfo.dashboardUrl)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to start dashboard server.", error)
                        latestDashboardQrCode = null
                        updateNotification(
                            url = null,
                            warningMessage = getString(R.string.dashboard_service_error_start_failed),
                        )
                    }
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopServerAndService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        runBlocking {
            dashboardServerController.stop()
        }
        stopRuntimeMonitors()
        latestDashboardQrCode = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopServerAndService() {
        serviceScope.launch {
            dashboardServerController.stop()
            stopRuntimeMonitors()
            latestDashboardQrCode = null
            lastKnownDashboardUrl = null
            pausedForWifiLoss = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onServerRunning(url: String) {
        pausedForWifiLoss = false
        lastKnownDashboardUrl = url
        latestDashboardQrCode = generateQrCode(url)
        startRuntimeMonitors()
        updateNotification(
            url = url,
            warningMessage = batterySaverWarningOrNull(),
        )
    }

    private fun startRuntimeMonitors() {
        if (runtimeMonitorJob == null) {
            runtimeMonitorJob =
                serviceScope.launch {
                    while (isActive) {
                        monitorRuntimeState()
                        delay(5_000)
                    }
                }
        }
        registerNetworkCallbackIfNeeded()
    }

    private suspend fun monitorRuntimeState() {
        if (!isWifiConnected()) {
            pauseDueToWifiLoss()
            return
        }

        val runtimeInfo = dashboardServerController.getRuntimeInfo() ?: return
        if (runtimeInfo.dashboardUrl != lastKnownDashboardUrl) {
            lastKnownDashboardUrl = runtimeInfo.dashboardUrl
            latestDashboardQrCode = generateQrCode(runtimeInfo.dashboardUrl)
            updateNotification(
                url = runtimeInfo.dashboardUrl,
                warningMessage = batterySaverWarningOrNull(),
            )
            return
        }

        updateNotification(
            url = runtimeInfo.dashboardUrl,
            warningMessage = batterySaverWarningOrNull(),
        )
    }

    private suspend fun pauseDueToWifiLoss() {
        if (pausedForWifiLoss) return
        pausedForWifiLoss = true
        dashboardServerController.stop()
        latestDashboardQrCode = null
        lastKnownDashboardUrl = null
        updateNotification(
            url = null,
            warningMessage = getString(R.string.dashboard_service_wifi_paused),
        )
    }

    private fun stopRuntimeMonitors() {
        runtimeMonitorJob?.cancel()
        runtimeMonitorJob = null

        networkCallback?.let { callback ->
            val connectivityManager = getSystemService(ConnectivityManager::class.java)
            runCatching {
                connectivityManager?.unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (networkCallback != null) return

        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    serviceScope.launch {
                        pauseDueToWifiLoss()
                    }
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        serviceScope.launch {
                            pauseDueToWifiLoss()
                        }
                    }
                }
            }

        val request =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

        runCatching {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        }.onFailure {
            Log.w(TAG, "Unable to register network callback", it)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when HelioLan dashboard is running"
                    setShowBadge(false)
                }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(
        url: String?,
        warningMessage: String? = null,
    ) {
        startForeground(
            NOTIFICATION_ID,
            createNotification(
                url = url,
                warningMessage = warningMessage,
            ),
        )
    }

    private fun createNotification(
        url: String?,
        isStarting: Boolean = false,
        warningMessage: String? = null,
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

        val openDashboardPendingIntent =
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

        val openAppPendingIntent =
            PendingIntent.getActivity(
                this,
                103,
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val contentText =
            when {
                isStarting -> getString(R.string.dashboard_service_starting)
                !warningMessage.isNullOrBlank() -> warningMessage
                !url.isNullOrBlank() -> getString(R.string.dashboard_service_url, url)
                else -> getString(R.string.dashboard_service_error_start_failed)
            }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.dashboard_service_running))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .setLargeIcon(latestDashboardQrCode)
            .addAction(
                android.R.drawable.ic_menu_view,
                getString(R.string.dashboard_service_action_open),
                openDashboardPendingIntent ?: openAppPendingIntent,
            )
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

    private fun isWifiConnected(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val active = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun batterySaverWarningOrNull(): String? {
        val powerManager = getSystemService(PowerManager::class.java)
        return if (powerManager?.isPowerSaveMode == true) {
            getString(R.string.dashboard_service_battery_warning)
        } else {
            null
        }
    }
}

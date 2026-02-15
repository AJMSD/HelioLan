package com.heliolan.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.heliolan.app.R
import com.heliolan.app.databinding.ActivityMainBinding
import com.heliolan.app.service.DashboardForegroundService
import com.heliolan.app.setup.SetupPreferences
import com.heliolan.app.ui.setup.SetupActivity
import com.heliolan.healthconnect.model.HealthConnectAvailability
import com.heliolan.healthconnect.model.PermissionState
import com.heliolan.healthconnect.model.PermissionStatus
import com.heliolan.healthconnect.permission.PermissionManager
import com.heliolan.server.DashboardServerController
import com.heliolan.sync.engine.SyncEngine
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.scheduler.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private companion object {
        const val STARTUP_SYNC_TIMEOUT_MILLIS = 20_000L
    }

    @Inject
    lateinit var permissionManager: PermissionManager

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var syncEngine: SyncEngine

    @Inject
    lateinit var dashboardServerController: DashboardServerController

    @Inject
    lateinit var setupPreferences: SetupPreferences

    private lateinit var binding: ActivityMainBinding
    private lateinit var healthPermissionLauncher: ActivityResultLauncher<Set<String>>

    private var startupSyncJob: Job? = null
    private var statusMonitorJob: Job? = null
    private var currentDashboardUrl: String? = null
    private var lastAvailability: HealthConnectAvailability? = null
    private var currentSection: MainSection = MainSection.OVERVIEW

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (!setupPreferences.isSetupCompleted()) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        healthPermissionLauncher =
            registerForActivityResult(permissionManager.createPermissionRequestContract()) {
                lifecycleScope.launch {
                    refreshAvailabilityAndPermissions()
                    updateEnvironmentWarnings()
                }
            }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindActions()
        bindBottomNavigation()
        showSection(MainSection.OVERVIEW)

        startupSyncJob =
            lifecycleScope.launch {
                refreshAvailabilityAndPermissions()
                refreshServerState()
                runStartupSync()
                refreshSyncSummary()
                updateEnvironmentWarnings()
            }
    }

    override fun onResume() {
        super.onResume()
        startStatusMonitor()
    }

    override fun onPause() {
        statusMonitorJob?.cancel()
        statusMonitorJob = null
        super.onPause()
    }

    private fun bindActions() {
        binding.requestPermissionsButton.setOnClickListener {
            requestHealthPermissions()
        }
        binding.syncNowButton.setOnClickListener {
            runSyncNow()
        }
        binding.refreshStatusButton.setOnClickListener {
            lifecycleScope.launch {
                refreshAvailabilityAndPermissions()
                refreshSyncSummary()
                updateEnvironmentWarnings()
            }
        }
        binding.rebuildAggregatesButton.setOnClickListener {
            rebuildAggregates()
        }
        binding.toggleDashboardButton.setOnClickListener {
            if (dashboardServerController.isRunning()) {
                stopDashboardServer()
            } else {
                startDashboardServer()
            }
        }
        binding.refreshServerInfoButton.setOnClickListener {
            lifecycleScope.launch {
                refreshServerState()
            }
        }
        binding.openDashboardButton.setOnClickListener {
            openDashboardInBrowser(openSettingsTab = false)
        }
        binding.openDashboardSettingsButton.setOnClickListener {
            openDashboardInBrowser(openSettingsTab = true)
        }
        binding.openSetupButton.setOnClickListener {
            val intent =
                Intent(this, SetupActivity::class.java).apply {
                    putExtra(SetupActivity.EXTRA_FORCE_SHOW, true)
                }
            startActivity(intent)
        }
        binding.aboutButton.setOnClickListener {
            showAboutDialog()
        }
    }

    private fun bindBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_overview -> {
                    showSection(MainSection.OVERVIEW)
                    true
                }

                R.id.nav_sync -> {
                    showSection(MainSection.SYNC)
                    true
                }

                R.id.nav_access -> {
                    showSection(MainSection.ACCESS)
                    true
                }

                R.id.nav_more -> {
                    showSection(MainSection.MORE)
                    true
                }

                else -> false
            }
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_overview
    }

    private fun showSection(section: MainSection) {
        if (currentSection == section) return
        currentSection = section
        binding.overviewSection.isVisible = section == MainSection.OVERVIEW
        binding.syncSection.isVisible = section == MainSection.SYNC
        binding.accessSection.isVisible = section == MainSection.ACCESS
        binding.moreSection.isVisible = section == MainSection.MORE
    }

    private fun startStatusMonitor() {
        if (statusMonitorJob != null) return
        statusMonitorJob =
            lifecycleScope.launch {
                startupSyncJob?.join()
                while (true) {
                    refreshServerState()
                    refreshSyncSummary()
                    updateEnvironmentWarnings()
                    delay(5_000)
                }
            }
    }

    private fun requestHealthPermissions() {
        lifecycleScope.launch {
            when (val availability = permissionManager.checkAvailability()) {
                is HealthConnectAvailability.Available -> {
                    healthPermissionLauncher.launch(permissionManager.getRequiredPermissions())
                }

                is HealthConnectAvailability.NotInstalled -> {
                    binding.healthConnectStateValueTextView.text =
                        getString(R.string.main_health_not_installed)
                    try {
                        startActivity(permissionManager.createInstallHealthConnectIntent())
                    } catch (_: ActivityNotFoundException) {
                        // Keep status text when Play Store is unavailable.
                    }
                }

                is HealthConnectAvailability.Outdated -> {
                    binding.healthConnectStateValueTextView.text =
                        getString(R.string.main_health_outdated)
                }

                is HealthConnectAvailability.Unknown -> {
                    binding.healthConnectStateValueTextView.text =
                        getString(R.string.main_health_unknown, availability.error)
                }
            }
        }
    }

    private fun runSyncNow() {
        lifecycleScope.launch {
            binding.syncNowButton.isEnabled = false
            binding.syncStateValueTextView.text = getString(R.string.main_sync_running)
            val result = syncScheduler.syncNow()
            binding.syncStateValueTextView.text = result.toDisplayString()
            if (result is SyncResult.Success || result is SyncResult.PartialSuccess) {
                setupPreferences.setFirstSyncCompleted(true)
            }
            binding.syncNowButton.isEnabled = true
            refreshAvailabilityAndPermissions()
            refreshSyncSummary()
        }
    }

    private suspend fun runStartupSync() {
        binding.syncStateValueTextView.text = getString(R.string.main_sync_running)
        val startupResult =
            withTimeoutOrNull(STARTUP_SYNC_TIMEOUT_MILLIS) {
                syncScheduler.syncAutomatic()
            }

        if (startupResult == null) {
            binding.syncStateValueTextView.text =
                getString(R.string.main_sync_failure, "Automatic startup sync timed out")
            return
        }

        binding.syncStateValueTextView.text = startupResult.toDisplayString()
        if (startupResult is SyncResult.Success || startupResult is SyncResult.PartialSuccess) {
            setupPreferences.setFirstSyncCompleted(true)
        }
    }

    private fun rebuildAggregates() {
        lifecycleScope.launch {
            binding.rebuildAggregatesButton.isEnabled = false
            binding.aggregateStateValueTextView.text = getString(R.string.main_aggregates_running)

            val result =
                runCatching {
                    syncScheduler.rebuildAllAggregates()
                }

            binding.aggregateStateValueTextView.text =
                result.fold(
                    onSuccess = { getString(R.string.main_aggregates_success) },
                    onFailure = { error ->
                        getString(
                            R.string.main_aggregates_failure,
                            error.message ?: error::class.java.simpleName,
                        )
                    },
                )
            binding.rebuildAggregatesButton.isEnabled = true
        }
    }

    private suspend fun refreshAvailabilityAndPermissions() {
        val availability = permissionManager.checkAvailability()
        lastAvailability = availability
        binding.healthConnectStateValueTextView.text = availability.toDisplayString()

        val permissionState = permissionManager.getPermissionState()
        binding.permissionStateValueTextView.text = permissionState.toDisplayString()
    }

    private suspend fun refreshSyncSummary() {
        val statusRows = syncEngine.getSyncStatus()
        binding.lastSyncSummaryTextView.text =
            if (statusRows.isEmpty()) {
                getString(R.string.main_sync_summary_unknown)
            } else {
                val summary =
                    statusRows.joinToString(separator = " | ") { cursor ->
                        val label = cursor.recordType.replace("_", " ").lowercase(Locale.US)
                        "$label: ${cursor.lastSyncTime}"
                    }
                getString(R.string.main_sync_summary_format, summary)
            }
    }

    private fun startDashboardServer() {
        val intent =
            Intent(this, DashboardForegroundService::class.java).apply {
                action = DashboardForegroundService.ACTION_START
            }
        ContextCompat.startForegroundService(this, intent)
        binding.serverStateValueTextView.text = getString(R.string.dashboard_service_starting)

        lifecycleScope.launch {
            repeat(20) {
                delay(500)
                val runtimeInfo = dashboardServerController.getRuntimeInfo()
                if (runtimeInfo != null) {
                    refreshServerState()
                    return@launch
                }
            }
            binding.serverStateValueTextView.text = getString(R.string.dashboard_service_error_start_failed)
        }
    }

    private fun stopDashboardServer() {
        val intent =
            Intent(this, DashboardForegroundService::class.java).apply {
                action = DashboardForegroundService.ACTION_STOP
            }
        startService(intent)
        lifecycleScope.launch {
            refreshServerState()
        }
    }

    private suspend fun refreshServerState() {
        val runtimeInfo = dashboardServerController.getRuntimeInfo()
        if (runtimeInfo == null) {
            binding.serverStateValueTextView.text = getString(R.string.main_server_stopped)
            binding.serverUrlValueTextView.text = getString(R.string.main_server_url_unknown)
            binding.serverClientsValueTextView.text = getString(R.string.main_server_clients_none)
            binding.serverHelpTextView.text = getString(R.string.main_server_help_stopped)
            binding.toggleDashboardButton.text = getString(R.string.main_action_start_dashboard)
            binding.openDashboardButton.isEnabled = false
            binding.openDashboardSettingsButton.isEnabled = false
            currentDashboardUrl = null
            binding.serverQrImageView.setImageBitmap(null)
            return
        }

        binding.serverStateValueTextView.text = getString(R.string.main_server_running)
        binding.serverUrlValueTextView.text = getString(R.string.main_server_url_format, runtimeInfo.dashboardUrl)
        binding.serverClientsValueTextView.text =
            getString(
                R.string.main_server_clients_format,
                runtimeInfo.connectedClients,
            )
        binding.serverHelpTextView.text =
            getString(
                if (setupPreferences.isPasscodeSkipped()) {
                    R.string.main_server_help_running_open_access
                } else {
                    R.string.main_server_help_running_passcode
                },
                runtimeInfo.dashboardUrl,
            )
        binding.toggleDashboardButton.text = getString(R.string.main_action_stop_dashboard)
        binding.openDashboardButton.isEnabled = true
        binding.openDashboardSettingsButton.isEnabled = true

        if (currentDashboardUrl != runtimeInfo.dashboardUrl) {
            currentDashboardUrl = runtimeInfo.dashboardUrl
            binding.serverQrImageView.setImageBitmap(generateQrCode(runtimeInfo.dashboardUrl))
        }
    }

    private fun openDashboardInBrowser(openSettingsTab: Boolean) {
        val runtimeInfo = dashboardServerController.getRuntimeInfo() ?: return
        val url = if (openSettingsTab) "${runtimeInfo.dashboardUrl}#settings" else runtimeInfo.dashboardUrl
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }

    private fun showAboutDialog() {
        val versionName =
            runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrDefault("unknown")

        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message, versionName))
            .setNegativeButton(R.string.about_action_close, null)
            .setPositiveButton(R.string.about_action_open_github) { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_github_url))))
            }.show()
    }

    private suspend fun updateEnvironmentWarnings() {
        val warnings = mutableListOf<String>()
        if (!isWifiConnected()) {
            warnings += getString(R.string.main_warning_wifi_disconnected)
        }

        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isPowerSaveMode == true) {
            warnings += getString(R.string.main_warning_battery_saver)
        }

        if (lastAvailability is HealthConnectAvailability.NotInstalled) {
            warnings += getString(R.string.main_warning_health_connect_missing)
        }

        binding.environmentWarningTextView.text =
            if (warnings.isEmpty()) {
                ""
            } else {
                warnings.joinToString(separator = "\n")
            }
        binding.environmentWarningTextView.isVisible = warnings.isNotEmpty()
    }

    private fun isWifiConnected(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
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

    private fun HealthConnectAvailability.toDisplayString(): String {
        return when (this) {
            is HealthConnectAvailability.Available -> getString(R.string.main_health_available)
            is HealthConnectAvailability.NotInstalled -> getString(R.string.main_health_not_installed)
            is HealthConnectAvailability.Outdated -> getString(R.string.main_health_outdated)
            is HealthConnectAvailability.Unknown -> getString(R.string.main_health_unknown, error)
        }
    }

    private fun PermissionState.toDisplayString(): String {
        return getString(
            R.string.main_permissions_format,
            heartRate.toLabel(),
            sleep.toLabel(),
            steps.toLabel(),
            restingHeartRate.toLabel(),
        )
    }

    private fun PermissionStatus.toLabel(): String {
        val normalized = name.lowercase(Locale.US).replace("_", " ")
        return normalized.replaceFirstChar { char ->
            if (char.isLowerCase()) {
                char.titlecase(Locale.US)
            } else {
                char.toString()
            }
        }
    }

    private fun SyncResult.toDisplayString(): String {
        return when (this) {
            is SyncResult.Success ->
                getString(
                    R.string.main_sync_success,
                    summary.totalStored,
                    summary.totalFetched,
                    summary.totalDeduplicated,
                )

            is SyncResult.PartialSuccess ->
                getString(
                    R.string.main_sync_partial,
                    summary.totalStored,
                    errors.joinToString(separator = "; ") { it.message },
                )

            is SyncResult.Failure -> getString(R.string.main_sync_failure, error.message)
        }
    }

    private enum class MainSection {
        OVERVIEW,
        SYNC,
        ACCESS,
        MORE,
    }
}

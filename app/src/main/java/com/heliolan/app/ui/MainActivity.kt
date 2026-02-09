package com.heliolan.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.heliolan.app.R
import com.heliolan.app.databinding.ActivityMainBinding
import com.heliolan.app.service.DashboardForegroundService
import com.heliolan.healthconnect.model.HealthConnectAvailability
import com.heliolan.healthconnect.model.PermissionState
import com.heliolan.healthconnect.model.PermissionStatus
import com.heliolan.healthconnect.permission.PermissionManager
import com.heliolan.server.DashboardServerController
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.scheduler.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject
    lateinit var permissionManager: PermissionManager

    @Inject
    lateinit var syncScheduler: SyncScheduler

    @Inject
    lateinit var dashboardServerController: DashboardServerController

    private lateinit var binding: ActivityMainBinding
    private lateinit var healthPermissionLauncher: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        healthPermissionLauncher =
            registerForActivityResult(permissionManager.createPermissionRequestContract()) {
                lifecycleScope.launch {
                    refreshAvailabilityAndPermissions()
                }
            }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.requestPermissionsButton.setOnClickListener {
            requestHealthPermissions()
        }
        binding.syncNowButton.setOnClickListener {
            runSyncNow()
        }
        binding.refreshStatusButton.setOnClickListener {
            lifecycleScope.launch {
                refreshAvailabilityAndPermissions()
            }
        }
        binding.rebuildAggregatesButton.setOnClickListener {
            rebuildAggregates()
        }
        binding.startDashboardButton.setOnClickListener {
            startDashboardServer()
        }
        binding.stopDashboardButton.setOnClickListener {
            stopDashboardServer()
        }
        binding.refreshServerInfoButton.setOnClickListener {
            refreshServerState()
        }

        lifecycleScope.launch {
            refreshAvailabilityAndPermissions()
            refreshServerState()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshServerState()
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
                    binding.syncStateValueTextView.text =
                        getString(R.string.main_opening_health_connect)
                    try {
                        startActivity(permissionManager.createInstallHealthConnectIntent())
                    } catch (_: ActivityNotFoundException) {
                        // Keep status text only if Play Store is unavailable.
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
            binding.syncNowButton.isEnabled = true
            refreshAvailabilityAndPermissions()
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
        binding.healthConnectStateValueTextView.text = availability.toDisplayString()

        val permissionState = permissionManager.getPermissionState()
        binding.permissionStateValueTextView.text = permissionState.toDisplayString()
    }

    private fun startDashboardServer() {
        val intent =
            Intent(this, DashboardForegroundService::class.java).apply {
                action = DashboardForegroundService.ACTION_START
            }
        ContextCompat.startForegroundService(this, intent)
        binding.serverStateValueTextView.text = getString(R.string.dashboard_service_starting)
        observeDashboardStartup()
    }

    private fun stopDashboardServer() {
        val intent =
            Intent(this, DashboardForegroundService::class.java).apply {
                action = DashboardForegroundService.ACTION_STOP
            }
        startService(intent)
        binding.serverStateValueTextView.text = getString(R.string.main_server_stopped)
    }

    private fun refreshServerState() {
        val runtimeInfo = dashboardServerController.getRuntimeInfo()
        binding.serverStateValueTextView.text =
            if (runtimeInfo == null) {
                getString(R.string.main_server_stopped)
            } else {
                getString(
                    R.string.main_server_running,
                    runtimeInfo.dashboardUrl,
                    runtimeInfo.connectedClients,
                )
            }
    }

    private fun observeDashboardStartup() {
        lifecycleScope.launch {
            repeat(20) {
                delay(500)
                val runtimeInfo = dashboardServerController.getRuntimeInfo()
                if (runtimeInfo != null) {
                    binding.serverStateValueTextView.text =
                        getString(
                            R.string.main_server_running,
                            runtimeInfo.dashboardUrl,
                            runtimeInfo.connectedClients,
                        )
                    return@launch
                }
            }
            binding.serverStateValueTextView.text = getString(R.string.dashboard_service_error_start_failed)
        }
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
}

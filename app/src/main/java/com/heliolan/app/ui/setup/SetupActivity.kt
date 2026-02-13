package com.heliolan.app.ui.setup

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.heliolan.app.R
import com.heliolan.app.databinding.ActivitySetupBinding
import com.heliolan.app.service.DashboardForegroundService
import com.heliolan.app.setup.SetupPreferences
import com.heliolan.app.setup.SetupProgress
import com.heliolan.app.setup.SetupProgressFormatter
import com.heliolan.app.ui.MainActivity
import com.heliolan.healthconnect.model.HealthConnectAvailability
import com.heliolan.healthconnect.permission.PermissionManager
import com.heliolan.server.DashboardServerController
import com.heliolan.server.security.SecuritySettingsManager
import com.heliolan.sync.engine.SyncEngine
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.scheduler.SyncScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SetupActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FORCE_SHOW = "extra_force_show"
        private const val ZEP_PACKAGE_NAME = "com.huami.watch.hmwatchmanager"
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
    lateinit var securitySettingsManager: SecuritySettingsManager

    @Inject
    lateinit var setupPreferences: SetupPreferences

    private lateinit var binding: ActivitySetupBinding
    private lateinit var healthPermissionLauncher: ActivityResultLauncher<Set<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val forceShow = intent.getBooleanExtra(EXTRA_FORCE_SHOW, false)
        if (setupPreferences.isSetupCompleted() && !forceShow) {
            navigateToMain()
            return
        }

        healthPermissionLauncher =
            registerForActivityResult(permissionManager.createPermissionRequestContract()) {
                lifecycleScope.launch {
                    refreshSetupState()
                }
            }

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindActions()
        lifecycleScope.launch {
            refreshSetupState()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            refreshSetupState()
        }
    }

    private fun bindActions() {
        binding.openZeppGuideButton.setOnClickListener {
            openZeppSyncGuide()
        }
        binding.confirmZeppSyncButton.setOnClickListener {
            setupPreferences.setZeppSyncConfirmed(true)
            binding.setupMessageTextView.text = getString(R.string.setup_message_open_zepp)
            lifecycleScope.launch {
                refreshSetupState()
            }
        }
        binding.requestSetupPermissionsButton.setOnClickListener {
            requestHealthPermissions()
        }
        binding.runFirstSyncButton.setOnClickListener {
            runFirstSync()
        }
        binding.setPasscodeButton.setOnClickListener {
            setPasscode()
        }
        binding.skipPasscodeButton.setOnClickListener {
            setupPreferences.setPasscodeSkipped(true)
            binding.setupMessageTextView.text = getString(R.string.setup_message_passcode_skip)
            lifecycleScope.launch {
                refreshSetupState()
            }
        }
        binding.startSetupDashboardButton.setOnClickListener {
            startDashboardServer()
        }
        binding.refreshSetupStatusButton.setOnClickListener {
            lifecycleScope.launch {
                refreshSetupState()
            }
        }
        binding.finishSetupButton.setOnClickListener {
            lifecycleScope.launch {
                val progress = calculateProgress()
                if (!progress.isComplete()) {
                    binding.setupMessageTextView.text = getString(R.string.setup_message_not_ready)
                    return@launch
                }
                setupPreferences.setSetupCompleted(true)
                binding.setupMessageTextView.text = getString(R.string.setup_message_ready)
                delay(250)
                navigateToMain()
            }
        }
    }

    private fun openZeppSyncGuide() {
        val launchIntent = packageManager.getLaunchIntentForPackage(ZEP_PACKAGE_NAME)
        if (launchIntent != null) {
            startActivity(launchIntent)
            binding.setupMessageTextView.text = getString(R.string.setup_message_open_zepp)
            return
        }

        val marketIntent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=$ZEP_PACKAGE_NAME"),
            ).apply {
                setPackage("com.android.vending")
            }

        runCatching {
            startActivity(marketIntent)
        }.onFailure {
            val webIntent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$ZEP_PACKAGE_NAME"),
                )
            runCatching {
                startActivity(webIntent)
            }.onFailure {
                showToast(getString(R.string.setup_message_open_zepp))
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
                    binding.setupMessageTextView.text = getString(R.string.main_opening_health_connect)
                    try {
                        startActivity(permissionManager.createInstallHealthConnectIntent())
                    } catch (_: ActivityNotFoundException) {
                        binding.setupMessageTextView.text = getString(R.string.main_health_not_installed)
                    }
                }

                is HealthConnectAvailability.Outdated -> {
                    binding.setupMessageTextView.text = getString(R.string.main_health_outdated)
                }

                is HealthConnectAvailability.Unknown -> {
                    binding.setupMessageTextView.text = getString(R.string.main_health_unknown, availability.error)
                }
            }
        }
    }

    private fun runFirstSync() {
        lifecycleScope.launch {
            binding.runFirstSyncButton.isEnabled = false
            binding.setupMessageTextView.text = getString(R.string.setup_message_sync_running)
            val result = syncScheduler.syncNow()
            if (result is SyncResult.Success || result is SyncResult.PartialSuccess) {
                setupPreferences.setFirstSyncCompleted(true)
                binding.setupMessageTextView.text = getString(R.string.setup_message_sync_success)
            } else if (result is SyncResult.Failure) {
                binding.setupMessageTextView.text = getString(R.string.setup_message_sync_failure, result.error.message)
            }
            binding.runFirstSyncButton.isEnabled = true
            refreshSetupState()
        }
    }

    private fun setPasscode() {
        val passcode = binding.passcodeInputEditText.text?.toString()?.trim().orEmpty()
        if (!passcode.matches(Regex("^\\d{4,8}$"))) {
            binding.setupMessageTextView.text = getString(R.string.setup_message_passcode_invalid)
            return
        }

        val result =
            runCatching {
                securitySettingsManager.setPasscode(passcode)
            }

        if (result.isSuccess) {
            setupPreferences.setPasscodeSkipped(false)
            binding.passcodeInputEditText.setText("")
            binding.setupMessageTextView.text = getString(R.string.setup_message_passcode_set)
            lifecycleScope.launch {
                refreshSetupState()
            }
        } else {
            binding.setupMessageTextView.text =
                getString(
                    R.string.setup_message_passcode_failure,
                    result.exceptionOrNull()?.message ?: "passcode update failed",
                )
        }
    }

    private fun startDashboardServer() {
        val intent =
            Intent(this, DashboardForegroundService::class.java).apply {
                action = DashboardForegroundService.ACTION_START
            }
        ContextCompat.startForegroundService(this, intent)
        binding.setupMessageTextView.text = getString(R.string.setup_message_dashboard_starting)

        lifecycleScope.launch {
            repeat(20) {
                delay(500)
                val runtimeInfo = dashboardServerController.getRuntimeInfo()
                if (runtimeInfo != null) {
                    binding.setupMessageTextView.text =
                        getString(
                            R.string.setup_message_dashboard_started,
                            runtimeInfo.dashboardUrl,
                        )
                    refreshSetupState()
                    return@launch
                }
            }
            refreshSetupState()
        }
    }

    private suspend fun refreshSetupState() {
        val progress = calculateProgress()
        binding.stepZeppStatusTextView.text =
            getString(
                R.string.setup_step_zepp_status,
                SetupProgressFormatter.label(progress.zeppSyncConfirmed),
            )
        binding.stepPermissionsStatusTextView.text =
            getString(
                R.string.setup_step_permissions_status,
                SetupProgressFormatter.label(progress.permissionsGranted),
            )
        binding.stepSyncStatusTextView.text =
            getString(
                R.string.setup_step_sync_status,
                SetupProgressFormatter.label(progress.firstSyncCompleted),
            )
        binding.stepPasscodeStatusTextView.text =
            getString(
                R.string.setup_step_passcode_status,
                SetupProgressFormatter.label(progress.passcodeSatisfied),
            )
        binding.stepDashboardStatusTextView.text =
            getString(
                R.string.setup_step_dashboard_status,
                SetupProgressFormatter.label(progress.dashboardRunning),
            )

        binding.setupSummaryTextView.text = SetupProgressFormatter.summary(progress)
        binding.finishSetupButton.isEnabled = progress.isComplete()

        val runtimeInfo = dashboardServerController.getRuntimeInfo()
        binding.setupDashboardUrlTextView.text =
            runtimeInfo?.dashboardUrl ?: getString(R.string.main_server_url_unknown)
        if (runtimeInfo == null) {
            binding.setupMessageTextView.text = getString(R.string.setup_message_dashboard_stopped)
        }
    }

    private suspend fun calculateProgress(): SetupProgress {
        val permissionState = permissionManager.getPermissionState()
        val hasSyncHistory = syncEngine.getSyncStatus().isNotEmpty()
        if (hasSyncHistory && !setupPreferences.isFirstSyncCompleted()) {
            setupPreferences.setFirstSyncCompleted(true)
        }

        val passcodeSatisfied = securitySettingsManager.hasPasscodeConfigured() || setupPreferences.isPasscodeSkipped()

        return SetupProgress(
            zeppSyncConfirmed = setupPreferences.isZeppSyncConfirmed(),
            permissionsGranted = permissionState.hasCorePermissions(),
            firstSyncCompleted = setupPreferences.isFirstSyncCompleted() || hasSyncHistory,
            passcodeSatisfied = passcodeSatisfied,
            dashboardRunning = dashboardServerController.isRunning(),
        )
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

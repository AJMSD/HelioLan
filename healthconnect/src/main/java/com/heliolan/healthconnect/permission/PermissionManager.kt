package com.heliolan.healthconnect.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import com.heliolan.healthconnect.model.HealthConnectAvailability
import com.heliolan.healthconnect.model.PermissionState
import com.heliolan.healthconnect.model.PermissionStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Health Connect permissions and availability.
 * Checks permission status, requests permissions, and handles edge cases.
 */
@Singleton
class PermissionManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val healthConnectClient: HealthConnectClient? by lazy {
            try {
                if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                    HealthConnectClient.getOrCreate(context)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Check if Health Connect is available on this device.
         */
        suspend fun checkAvailability(): HealthConnectAvailability {
            return try {
                when (HealthConnectClient.getSdkStatus(context)) {
                    HealthConnectClient.SDK_UNAVAILABLE -> {
                        HealthConnectAvailability.NotInstalled
                    }
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                        HealthConnectAvailability.Outdated(
                            currentVersion = "Outdated",
                            requiredVersion = "Latest",
                        )
                    }
                    HealthConnectClient.SDK_AVAILABLE -> {
                        HealthConnectAvailability.Available
                    }
                    else -> {
                        HealthConnectAvailability.Unknown("Unknown SDK status")
                    }
                }
            } catch (e: Exception) {
                HealthConnectAvailability.Unknown(e.message ?: "Unknown error")
            }
        }

        /**
         * Get current permission state for all record types.
         */
        suspend fun getPermissionState(): PermissionState {
            val client =
                healthConnectClient
                    ?: return PermissionState(
                        heartRate = PermissionStatus.UNAVAILABLE,
                        sleep = PermissionStatus.UNAVAILABLE,
                        steps = PermissionStatus.UNAVAILABLE,
                        restingHeartRate = PermissionStatus.UNAVAILABLE,
                        heartRateVariability = PermissionStatus.UNAVAILABLE,
                        historyPermission = PermissionStatus.UNAVAILABLE,
                    )

            val grantedPermissions = client.permissionController.getGrantedPermissions()

            return PermissionState(
                heartRate =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(HeartRateRecord::class),
                    ),
                sleep =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(SleepSessionRecord::class),
                    ),
                steps =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(StepsRecord::class),
                    ),
                restingHeartRate =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
                    ),
                heartRateVariability =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
                    ),
                historyPermission = checkHistoryPermissionStatus(),
            )
        }

        /**
         * Get the set of permissions that need to be requested.
         */
        fun getRequiredPermissions(): Set<String> {
            return setOf(
                HealthPermission.getReadPermission(HeartRateRecord::class),
                HealthPermission.getReadPermission(SleepSessionRecord::class),
                HealthPermission.getReadPermission(StepsRecord::class),
                HealthPermission.getReadPermission(RestingHeartRateRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            )
        }

        /**
         * Create intent to request Health Connect permissions.
         * Use this with ActivityResultLauncher to handle the permission flow.
         */
        fun createPermissionRequestIntent(): Intent {
            val providerPackageName = "com.google.android.apps.healthdata"
            return Intent("androidx.health.ACTION_REQUEST_PERMISSIONS").apply {
                setPackage(providerPackageName)
                putExtra("androidx.health.EXTRA_PERMISSIONS", getRequiredPermissions().toTypedArray())
            }
        }

        /**
         * Create intent to install Health Connect from Play Store.
         */
        fun createInstallHealthConnectIntent(): Intent {
            val packageName = "com.google.android.apps.healthdata"
            return Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.android.vending")
                data = android.net.Uri.parse("market://details?id=$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }

        /**
         * Check if a specific permission is granted.
         */
        private fun checkPermissionStatus(
            grantedPermissions: Set<String>,
            permission: String,
        ): PermissionStatus {
            return if (grantedPermissions.contains(permission)) {
                PermissionStatus.GRANTED
            } else {
                // We can't distinguish between DENIED and NOT_REQUESTED with current API
                // Default to NOT_REQUESTED for better UX
                PermissionStatus.NOT_REQUESTED
            }
        }

        /**
         * Check history permission status (for reading >30 days of data).
         */
        private fun checkHistoryPermissionStatus(): PermissionStatus {
            return try {
                val permission = "android.permission.health.READ_HEALTH_DATA_HISTORY"
                if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                    PermissionStatus.GRANTED
                } else {
                    PermissionStatus.NOT_REQUESTED
                }
            } catch (e: Exception) {
                PermissionStatus.UNAVAILABLE
            }
        }
    }

/**
 * Activity result contract for requesting Health Connect permissions.
 */
class RequestHealthPermissions : ActivityResultContract<Set<String>, Set<String>>() {
    override fun createIntent(
        context: Context,
        input: Set<String>,
    ): Intent {
        val providerPackageName = "com.google.android.apps.healthdata"
        return Intent("androidx.health.ACTION_REQUEST_PERMISSIONS").apply {
            setPackage(providerPackageName)
            putExtra("androidx.health.EXTRA_PERMISSIONS", input.toTypedArray())
        }
    }

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): Set<String> {
        // Re-check permissions after the flow completes
        // The actual granted permissions need to be queried from HealthConnectClient
        return emptySet()
    }
}

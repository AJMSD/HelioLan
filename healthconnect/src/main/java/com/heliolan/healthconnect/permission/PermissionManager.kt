package com.heliolan.healthconnect.permission

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
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
                        activeCalories = PermissionStatus.UNAVAILABLE,
                        distance = PermissionStatus.UNAVAILABLE,
                        totalCalories = PermissionStatus.UNAVAILABLE,
                        nutrition = PermissionStatus.UNAVAILABLE,
                        oxygenSaturation = PermissionStatus.UNAVAILABLE,
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
                activeCalories =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
                    ),
                distance =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(DistanceRecord::class),
                    ),
                totalCalories =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                    ),
                nutrition =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(NutritionRecord::class),
                    ),
                oxygenSaturation =
                    checkPermissionStatus(
                        grantedPermissions,
                        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
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
                HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(DistanceRecord::class),
                HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
                HealthPermission.getReadPermission(NutritionRecord::class),
                HealthPermission.getReadPermission(OxygenSaturationRecord::class),
                HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
            )
        }

        /**
         * Create the official Health Connect permission request contract.
         */
        fun createPermissionRequestContract(): ActivityResultContract<Set<String>, Set<String>> {
            return PermissionController.createRequestPermissionResultContract()
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

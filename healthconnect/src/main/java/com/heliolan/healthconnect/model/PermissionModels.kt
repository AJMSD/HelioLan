package com.heliolan.healthconnect.model

/**
 * Health Connect record types supported by HelioLAN.
 */
enum class HealthRecordType {
    HEART_RATE,
    SLEEP,
    STEPS,
    RESTING_HEART_RATE,
    HEART_RATE_VARIABILITY,
    ;

    companion object {
        /**
         * Get the Health Connect permission string for this record type.
         */
        fun HealthRecordType.toPermissionString(): String {
            return when (this) {
                HEART_RATE -> "android.permission.health.READ_HEART_RATE"
                SLEEP -> "android.permission.health.READ_SLEEP"
                STEPS -> "android.permission.health.READ_STEPS"
                RESTING_HEART_RATE -> "android.permission.health.READ_RESTING_HEART_RATE"
                HEART_RATE_VARIABILITY -> "android.permission.health.READ_HEART_RATE_VARIABILITY"
            }
        }
    }
}

/**
 * Permission status for a specific health record type.
 */
enum class PermissionStatus {
    /** Permission has been granted by the user */
    GRANTED,

    /** Permission has been explicitly denied by the user */
    DENIED,

    /** Permission has not been requested yet */
    NOT_REQUESTED,

    /** Health Connect is not available (not installed or outdated) */
    UNAVAILABLE,
}

/**
 * Complete permission state for all record types.
 */
data class PermissionState(
    val heartRate: PermissionStatus,
    val sleep: PermissionStatus,
    val steps: PermissionStatus,
    val restingHeartRate: PermissionStatus,
    val heartRateVariability: PermissionStatus,
    val historyPermission: PermissionStatus,
) {
    /**
     * Check if all core permissions (HR, sleep, steps) are granted.
     */
    fun hasCorePermissions(): Boolean {
        return heartRate == PermissionStatus.GRANTED &&
            sleep == PermissionStatus.GRANTED &&
            steps == PermissionStatus.GRANTED
    }

    /**
     * Check if any permission is granted.
     */
    fun hasAnyPermission(): Boolean {
        return heartRate == PermissionStatus.GRANTED ||
            sleep == PermissionStatus.GRANTED ||
            steps == PermissionStatus.GRANTED ||
            restingHeartRate == PermissionStatus.GRANTED ||
            heartRateVariability == PermissionStatus.GRANTED
    }
}

/**
 * Result of Health Connect availability check.
 */
sealed class HealthConnectAvailability {
    /** Health Connect is installed and available */
    object Available : HealthConnectAvailability()

    /** Health Connect is not installed */
    object NotInstalled : HealthConnectAvailability()

    /** Health Connect is installed but outdated */
    data class Outdated(val currentVersion: String, val requiredVersion: String) : HealthConnectAvailability()

    /** Unable to determine availability */
    data class Unknown(val error: String) : HealthConnectAvailability()
}

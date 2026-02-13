package com.heliolan.data.util

import java.time.Duration
import java.time.Instant

/**
 * Record type identifiers for sync cursors and aggregates.
 */
object RecordType {
    const val HEART_RATE = "heart_rate"
    const val SLEEP = "sleep"
    const val STEPS = "steps"
    const val RESTING_HR = "resting_hr"
    const val ACTIVE_CALORIES = "active_calories"
    const val DISTANCE = "distance"
    const val TOTAL_CALORIES = "total_calories"
    const val NUTRITION = "nutrition"
    const val OXYGEN_SATURATION = "oxygen_saturation"
    const val HRV = "hrv"
}

/**
 * Helper to calculate data freshness (time since last sync).
 * Used for "last updated X minutes ago" UI indicators.
 */
object DataFreshness {
    /**
     * Calculate staleness duration from last sync time.
     * Returns human-readable string: "2 min ago", "1 hour ago", "3 days ago".
     */
    fun getStaleness(lastSyncTime: Instant?): String {
        if (lastSyncTime == null) {
            return "Never synced"
        }

        val duration = Duration.between(lastSyncTime, Instant.now())
        val seconds = duration.seconds

        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86400 -> "${seconds / 3600} hour${if (seconds / 3600 > 1) "s" else ""} ago"
            else -> "${seconds / 86400} day${if (seconds / 86400 > 1L) "s" else ""} ago"
        }
    }

    /**
     * Check if data is stale (older than threshold).
     */
    fun isStale(
        lastSyncTime: Instant?,
        thresholdMinutes: Long = 15,
    ): Boolean {
        if (lastSyncTime == null) return true
        val duration = Duration.between(lastSyncTime, Instant.now())
        return duration.toMinutes() > thresholdMinutes
    }
}

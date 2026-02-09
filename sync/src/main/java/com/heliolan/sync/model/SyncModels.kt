package com.heliolan.sync.model

import java.time.Instant

/**
 * High-level error categories for sync failures.
 */
enum class SyncErrorCode {
    DEBOUNCED,
    TIMEOUT,
    HEALTH_CONNECT_UNAVAILABLE,
    PERMISSION_DENIED,
    READ_FAILED,
    WRITE_FAILED,
    UNSUPPORTED_RECORD_TYPE,
}

/**
 * Error returned by sync operations.
 */
data class SyncError(
    val recordType: String?,
    val code: SyncErrorCode,
    val message: String,
    val throwable: Throwable? = null,
)

/**
 * Per-record-type sync summary.
 */
data class RecordSyncSummary(
    val recordType: String,
    val fetched: Int,
    val stored: Int,
    val deduplicated: Int,
    val startedAt: Instant,
    val completedAt: Instant,
)

/**
 * Combined sync summary across one or more record types.
 */
data class SyncSummary(
    val startedAt: Instant,
    val completedAt: Instant,
    val records: List<RecordSyncSummary>,
) {
    val totalFetched: Int = records.sumOf { it.fetched }
    val totalStored: Int = records.sumOf { it.stored }
    val totalDeduplicated: Int = records.sumOf { it.deduplicated }
}

/**
 * Public sync result contract.
 */
sealed class SyncResult {
    data class Success(val summary: SyncSummary) : SyncResult()

    data class PartialSuccess(
        val summary: SyncSummary,
        val errors: List<SyncError>,
    ) : SyncResult()

    data class Failure(val error: SyncError) : SyncResult()
}

/**
 * Runtime progress state for UI/observability.
 */
enum class SyncProgressState {
    STARTED,
    READING,
    WRITING,
    COMPLETED,
    FAILED,
}

/**
 * Progress update emitted while syncing.
 */
data class SyncProgress(
    val recordType: String,
    val state: SyncProgressState,
    val fetched: Int = 0,
    val stored: Int = 0,
    val message: String? = null,
)

/**
 * Controls initial sync scope when no cursor exists.
 */
enum class SyncWindowMode {
    LAST_30_DAYS,
    FULL_HISTORY,
}

/**
 * Tunables for sync behavior.
 */
data class SyncConfig(
    val safetyWindowHours: Long = 6,
    val initialWindowDays: Long = 30,
    val debounceSeconds: Long = 30,
    val timeoutMillis: Long = 60_000,
    val periodicSyncMinutes: Long = 15,
)

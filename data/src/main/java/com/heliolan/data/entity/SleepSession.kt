package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Sleep session record from Health Connect.
 * Contains overall session info; sleep stages are stored separately in SleepStage.
 */
@Entity(
    tableName = "sleep_sessions",
    indices = [
        Index(value = ["health_connect_id"], name = "idx_sleep_health_connect_id"),
        Index(value = ["start_time"], name = "idx_sleep_start_time"),
        Index(value = ["end_time"], name = "idx_sleep_end_time"),
        Index(value = ["source", "start_time"], name = "idx_sleep_source_start"),
        Index(value = ["source", "end_time"], name = "idx_sleep_source_end"),
    ],
)
data class SleepSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Health Connect record unique identifier for deduplication */
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    /** Sleep session start time in UTC */
    @ColumnInfo(name = "start_time")
    val startTime: Instant,
    /** Sleep session end time in UTC */
    @ColumnInfo(name = "end_time")
    val endTime: Instant,
    /** Total sleep duration in milliseconds */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    /** Source package name */
    @ColumnInfo(name = "source")
    val source: String,
    /** When this record was synced from Health Connect */
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

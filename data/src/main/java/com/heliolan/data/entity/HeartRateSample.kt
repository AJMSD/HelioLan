package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Heart rate measurement sample from Health Connect.
 * Indexed by timestamp for efficient time-range queries.
 */
@Entity(
    tableName = "heart_rate_samples",
    indices = [
        Index(value = ["health_connect_id"], name = "idx_heart_rate_health_connect_id"),
        Index(value = ["timestamp"], name = "idx_heart_rate_timestamp"),
        Index(value = ["source", "timestamp"], name = "idx_heart_rate_source_timestamp"),
    ],
)
data class HeartRateSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Health Connect record unique identifier for deduplication */
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    /** Measurement timestamp in UTC */
    @ColumnInfo(name = "timestamp")
    val timestamp: Instant,
    /** Heart rate in beats per minute */
    @ColumnInfo(name = "bpm")
    val bpm: Int,
    /** Source package name (e.g., "com.huami.watch.hmwatchmanager") */
    @ColumnInfo(name = "source")
    val source: String,
    /** When this record was synced from Health Connect */
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

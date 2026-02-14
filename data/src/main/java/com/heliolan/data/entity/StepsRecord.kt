package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Steps count record from Health Connect.
 * Represents step count within a time interval.
 */
@Entity(
    tableName = "steps_records",
    indices = [
        Index(value = ["health_connect_id"], name = "idx_steps_health_connect_id"),
        Index(value = ["start_time"], name = "idx_steps_start_time"),
        Index(value = ["source", "start_time"], name = "idx_steps_source_start"),
    ],
)
data class StepsRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Health Connect record unique identifier for deduplication */
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    /** Interval start time in UTC */
    @ColumnInfo(name = "start_time")
    val startTime: Instant,
    /** Interval end time in UTC */
    @ColumnInfo(name = "end_time")
    val endTime: Instant,
    /** Number of steps in this interval */
    @ColumnInfo(name = "count")
    val count: Int,
    /** Source package name */
    @ColumnInfo(name = "source")
    val source: String,
    /** When this record was synced from Health Connect */
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

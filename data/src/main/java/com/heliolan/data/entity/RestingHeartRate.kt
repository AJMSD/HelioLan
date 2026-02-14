package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * Daily resting heart rate from Health Connect.
 * One value per day.
 */
@Entity(
    tableName = "resting_heart_rate",
    indices = [
        Index(value = ["health_connect_id"], name = "idx_resting_hr_health_connect_id"),
        Index(value = ["date"], name = "idx_resting_hr_date"),
        Index(value = ["source", "date"], name = "idx_resting_hr_source_date"),
    ],
)
data class RestingHeartRate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Health Connect record unique identifier for deduplication */
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    /** Date for this resting heart rate value (local date) */
    @ColumnInfo(name = "date")
    val date: LocalDate,
    /** Resting heart rate in beats per minute */
    @ColumnInfo(name = "bpm")
    val bpm: Int,
    /** Source package name */
    @ColumnInfo(name = "source")
    val source: String,
    /** When this record was synced from Health Connect */
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

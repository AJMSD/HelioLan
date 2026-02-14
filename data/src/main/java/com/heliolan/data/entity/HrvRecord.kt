package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Heart rate variability (RMSSD) sample from Health Connect.
 */
@Entity(
    tableName = "hrv_records",
    indices = [
        Index(value = ["health_connect_id"], name = "idx_hrv_health_connect_id"),
        Index(value = ["timestamp"], name = "idx_hrv_timestamp"),
        Index(value = ["source", "timestamp"], name = "idx_hrv_source_timestamp"),
    ],
)
data class HrvRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Instant,
    @ColumnInfo(name = "rmssd")
    val rmssd: Double,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

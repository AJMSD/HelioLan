package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Distance interval record from Health Connect.
 */
@Entity(
    tableName = "distance_records",
    indices = [
        Index(value = ["start_time"], name = "idx_distance_start_time"),
        Index(value = ["source", "start_time"], name = "idx_distance_source_start"),
    ],
)
data class DistanceRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    @ColumnInfo(name = "start_time")
    val startTime: Instant,
    @ColumnInfo(name = "end_time")
    val endTime: Instant,
    @ColumnInfo(name = "distance_meters")
    val distanceMeters: Double,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

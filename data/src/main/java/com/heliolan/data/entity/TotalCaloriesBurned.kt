package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Total calories burned interval record from Health Connect.
 */
@Entity(
    tableName = "total_calories_burned",
    indices = [
        Index(value = ["start_time"], name = "idx_total_calories_start_time"),
        Index(value = ["source", "start_time"], name = "idx_total_calories_source_start"),
    ],
)
data class TotalCaloriesBurned(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    @ColumnInfo(name = "start_time")
    val startTime: Instant,
    @ColumnInfo(name = "end_time")
    val endTime: Instant,
    @ColumnInfo(name = "energy_kcal")
    val energyKcal: Double,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

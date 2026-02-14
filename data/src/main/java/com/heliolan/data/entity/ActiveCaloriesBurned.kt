package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * Daily active calories burned from Health Connect.
 */
@Entity(
    tableName = "active_calories_burned",
    indices = [
        Index(value = ["health_connect_id"], name = "idx_active_calories_health_connect_id"),
        Index(value = ["date"], name = "idx_active_calories_date"),
        Index(value = ["source", "date"], name = "idx_active_calories_source_date"),
    ],
)
data class ActiveCaloriesBurned(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    @ColumnInfo(name = "date")
    val date: LocalDate,
    @ColumnInfo(name = "calories")
    val calories: Double,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

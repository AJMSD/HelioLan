package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Oxygen saturation sample from Health Connect.
 */
@Entity(
    tableName = "oxygen_saturation",
    indices = [
        Index(value = ["timestamp"], name = "idx_oxygen_timestamp"),
        Index(value = ["source", "timestamp"], name = "idx_oxygen_source_timestamp"),
    ],
)
data class OxygenSaturation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Instant,
    @ColumnInfo(name = "percentage")
    val percentage: Double,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

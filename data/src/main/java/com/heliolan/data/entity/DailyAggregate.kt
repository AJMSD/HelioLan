package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate

/**
 * Pre-computed daily aggregates for efficient dashboard queries.
 * Updated by aggregation pipeline (Phase 4).
 */
@Entity(
    tableName = "daily_aggregates",
    indices = [
        Index(value = ["date"], name = "idx_daily_agg_date"),
        Index(value = ["record_type", "date"], name = "idx_daily_agg_type_date"),
    ],
)
data class DailyAggregate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Date for this aggregate (local date) */
    @ColumnInfo(name = "date")
    val date: LocalDate,
    /** Type of record: "heart_rate", "steps", "sleep", "resting_hr" */
    @ColumnInfo(name = "record_type")
    val recordType: String,
    /** Primary aggregate value (e.g., avg heart rate, total steps) */
    @ColumnInfo(name = "value")
    val value: Double,
    /** Number of samples contributing to this aggregate */
    @ColumnInfo(name = "count")
    val count: Int,
    /** Minimum value in this day */
    @ColumnInfo(name = "min")
    val min: Double?,
    /** Maximum value in this day */
    @ColumnInfo(name = "max")
    val max: Double?,
    /** Average value in this day */
    @ColumnInfo(name = "avg")
    val avg: Double?,
    /** When this aggregate was last updated */
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

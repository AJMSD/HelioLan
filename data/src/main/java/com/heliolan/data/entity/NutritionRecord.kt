package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Nutrition interval record from Health Connect.
 * Stores major macronutrients plus optional serialized nutrients payload.
 */
@Entity(
    tableName = "nutrition_records",
    indices = [
        Index(value = ["start_time"], name = "idx_nutrition_start_time"),
        Index(value = ["source", "start_time"], name = "idx_nutrition_source_start"),
    ],
)
data class NutritionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    @ColumnInfo(name = "start_time")
    val startTime: Instant,
    @ColumnInfo(name = "end_time")
    val endTime: Instant,
    @ColumnInfo(name = "energy_kcal")
    val energyKcal: Double?,
    @ColumnInfo(name = "protein_grams")
    val proteinGrams: Double?,
    @ColumnInfo(name = "carbs_grams")
    val carbsGrams: Double?,
    @ColumnInfo(name = "fat_grams")
    val fatGrams: Double?,
    @ColumnInfo(name = "meal_type")
    val mealType: String?,
    @ColumnInfo(name = "nutrients_json")
    val nutrientsJson: String?,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

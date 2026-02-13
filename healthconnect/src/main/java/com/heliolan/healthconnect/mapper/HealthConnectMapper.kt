package com.heliolan.healthconnect.mapper

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import com.heliolan.data.entity.ActiveCaloriesBurned
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.HrvRecord
import com.heliolan.data.entity.OxygenSaturation
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.SleepStage
import com.heliolan.data.entity.TotalCaloriesBurned
import java.time.Instant
import java.time.ZoneId
import com.heliolan.data.entity.DistanceRecord as DistanceRecordEntity
import com.heliolan.data.entity.NutritionRecord as NutritionRecordEntity
import com.heliolan.data.entity.StepsRecord as StepsRecordEntity

/**
 * Maps Health Connect records to Room entities.
 */
object HealthConnectMapper {
    /**
     * Map Health Connect HeartRateRecord to HeartRateSample entity.
     * Extracts all samples from the record.
     */
    fun mapHeartRateRecord(
        record: HeartRateRecord,
        syncedAt: Instant = Instant.now(),
    ): List<HeartRateSample> {
        return record.samples.map { sample ->
            HeartRateSample(
                healthConnectId = "${record.metadata.id}_${sample.time.toEpochMilli()}",
                timestamp = sample.time,
                bpm = sample.beatsPerMinute.toInt(),
                source = record.metadata.dataOrigin.packageName,
                syncedAt = syncedAt,
            )
        }
    }

    /**
     * Map Health Connect SleepSessionRecord to SleepSession entity.
     */
    fun mapSleepSessionRecord(
        record: SleepSessionRecord,
        syncedAt: Instant = Instant.now(),
    ): SleepSession {
        val durationMs = record.endTime.toEpochMilli() - record.startTime.toEpochMilli()

        return SleepSession(
            healthConnectId = record.metadata.id,
            startTime = record.startTime,
            endTime = record.endTime,
            durationMs = durationMs,
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect sleep stages to SleepStage entities.
     * Requires the parent session's local database ID.
     */
    fun mapSleepStages(
        record: SleepSessionRecord,
        sessionId: Long,
        syncedAt: Instant = Instant.now(),
    ): List<SleepStage> {
        return record.stages.mapIndexed { index, stage ->
            SleepStage(
                sessionId = sessionId,
                healthConnectId = "${record.metadata.id}_stage_$index",
                startTime = stage.startTime,
                endTime = stage.endTime,
                stageType = mapSleepStageType(stage.stage),
                syncedAt = syncedAt,
            )
        }
    }

    /**
     * Map Health Connect StepsRecord to StepsRecord entity.
     */
    fun mapStepsRecord(
        record: StepsRecord,
        syncedAt: Instant = Instant.now(),
    ): StepsRecordEntity {
        return StepsRecordEntity(
            healthConnectId = record.metadata.id,
            startTime = record.startTime,
            endTime = record.endTime,
            count = record.count.toInt(),
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect RestingHeartRateRecord to RestingHeartRate entity.
     */
    fun mapRestingHeartRateRecord(
        record: RestingHeartRateRecord,
        syncedAt: Instant = Instant.now(),
    ): RestingHeartRate {
        // Convert time to local date in device timezone
        val localDate =
            record.time
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

        return RestingHeartRate(
            healthConnectId = record.metadata.id,
            date = localDate,
            bpm = record.beatsPerMinute.toInt(),
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect ActiveCaloriesBurnedRecord to ActiveCaloriesBurned entity.
     */
    fun mapActiveCaloriesBurnedRecord(
        record: ActiveCaloriesBurnedRecord,
        syncedAt: Instant = Instant.now(),
    ): ActiveCaloriesBurned {
        val localDate =
            record.startTime
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

        return ActiveCaloriesBurned(
            healthConnectId = record.metadata.id,
            date = localDate,
            calories = record.energy.inKilocalories,
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect DistanceRecord to DistanceRecord entity.
     */
    fun mapDistanceRecord(
        record: DistanceRecord,
        syncedAt: Instant = Instant.now(),
    ): DistanceRecordEntity {
        return DistanceRecordEntity(
            healthConnectId = record.metadata.id,
            startTime = record.startTime,
            endTime = record.endTime,
            distanceMeters = record.distance.inMeters,
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect TotalCaloriesBurnedRecord to TotalCaloriesBurned entity.
     */
    fun mapTotalCaloriesBurnedRecord(
        record: TotalCaloriesBurnedRecord,
        syncedAt: Instant = Instant.now(),
    ): TotalCaloriesBurned {
        return TotalCaloriesBurned(
            healthConnectId = record.metadata.id,
            startTime = record.startTime,
            endTime = record.endTime,
            energyKcal = record.energy.inKilocalories,
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect NutritionRecord to NutritionRecord entity.
     */
    fun mapNutritionRecord(
        record: NutritionRecord,
        syncedAt: Instant = Instant.now(),
    ): NutritionRecordEntity {
        val nutrientsJson =
            encodeSimpleJson(
                mapOf(
                    "energy_kcal" to record.energy?.inKilocalories,
                    "protein_g" to record.protein?.inGrams,
                    "carbs_g" to record.totalCarbohydrate?.inGrams,
                    "fat_g" to record.totalFat?.inGrams,
                ),
            )
        return NutritionRecordEntity(
            healthConnectId = record.metadata.id,
            startTime = record.startTime,
            endTime = record.endTime,
            energyKcal = record.energy?.inKilocalories,
            proteinGrams = record.protein?.inGrams,
            carbsGrams = record.totalCarbohydrate?.inGrams,
            fatGrams = record.totalFat?.inGrams,
            mealType = mapMealType(record.mealType),
            nutrientsJson = nutrientsJson,
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect OxygenSaturationRecord to OxygenSaturation entity.
     */
    fun mapOxygenSaturationRecord(
        record: OxygenSaturationRecord,
        syncedAt: Instant = Instant.now(),
    ): OxygenSaturation {
        return OxygenSaturation(
            healthConnectId = record.metadata.id,
            timestamp = record.time,
            percentage = normalizeOxygenPercentage(record.percentage.value),
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect HRV RMSSD record to HrvRecord entity.
     */
    fun mapHrvRecord(
        record: HeartRateVariabilityRmssdRecord,
        syncedAt: Instant = Instant.now(),
    ): HrvRecord {
        return HrvRecord(
            healthConnectId = record.metadata.id,
            timestamp = record.time,
            rmssd = record.heartRateVariabilityMillis,
            source = record.metadata.dataOrigin.packageName,
            syncedAt = syncedAt,
        )
    }

    /**
     * Map Health Connect sleep stage type to our string representation.
     */
    private fun mapSleepStageType(stage: Int): String {
        return when (stage) {
            1 -> "awake"
            2 -> "sleeping"
            3 -> "out_of_bed"
            4 -> "light"
            5 -> "deep"
            6 -> "rem"
            7 -> "awake_in_bed"
            else -> "unknown"
        }
    }

    private fun mapMealType(mealType: Int): String {
        return when (mealType) {
            1 -> "breakfast"
            2 -> "lunch"
            3 -> "dinner"
            4 -> "snack"
            else -> "unknown"
        }
    }

    private fun encodeSimpleJson(values: Map<String, Double?>): String? {
        val entries =
            values
                .filterValues { it != null }
                .entries
                .joinToString(separator = ",") { (key, value) ->
                    "\"$key\":${value ?: "null"}"
                }
        return if (entries.isBlank()) null else "{$entries}"
    }

    private fun normalizeOxygenPercentage(rawValue: Double): Double {
        if (!rawValue.isFinite()) return 0.0
        val scaled = if (rawValue <= 1.0) rawValue * 100.0 else rawValue
        return scaled.coerceIn(0.0, 100.0)
    }
}

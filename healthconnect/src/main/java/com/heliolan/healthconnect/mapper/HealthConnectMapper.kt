package com.heliolan.healthconnect.mapper

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.SleepStage
import java.time.Instant
import java.time.ZoneId
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
}

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
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HealthConnectMapperTest {
    @Test
    fun mapHeartRateRecord_mapsAllSamplesAndPreservesMetadata() {
        val syncedAt = Instant.parse("2026-02-10T12:00:00Z")
        val sampleTime1 = Instant.parse("2026-02-10T09:00:00Z")
        val sampleTime2 = Instant.parse("2026-02-10T09:05:00Z")

        val sample1 = mockk<HeartRateRecord.Sample>()
        every { sample1.time } returns sampleTime1
        every { sample1.beatsPerMinute } returns 71L

        val sample2 = mockk<HeartRateRecord.Sample>()
        every { sample2.time } returns sampleTime2
        every { sample2.beatsPerMinute } returns 75L

        val record = mockk<HeartRateRecord>(relaxed = true)
        every { record.samples } returns listOf(sample1, sample2)
        every { record.metadata.id } returns "hr-record-1"
        every { record.metadata.dataOrigin.packageName } returns "com.zepp"

        val mapped = HealthConnectMapper.mapHeartRateRecord(record, syncedAt)

        assertThat(mapped).hasSize(2)
        assertThat(mapped[0].healthConnectId).isEqualTo("hr-record-1_${sampleTime1.toEpochMilli()}")
        assertThat(mapped[0].timestamp).isEqualTo(sampleTime1)
        assertThat(mapped[0].bpm).isEqualTo(71)
        assertThat(mapped[0].source).isEqualTo("com.zepp")
        assertThat(mapped[0].syncedAt).isEqualTo(syncedAt)

        assertThat(mapped[1].healthConnectId).isEqualTo("hr-record-1_${sampleTime2.toEpochMilli()}")
        assertThat(mapped[1].bpm).isEqualTo(75)
    }

    @Test
    fun mapSleepSessionRecord_computesDurationAndCopiesFields() {
        val syncedAt = Instant.parse("2026-02-10T12:00:00Z")
        val start = Instant.parse("2026-02-09T23:00:00Z")
        val end = Instant.parse("2026-02-10T07:00:00Z")
        val record = mockk<SleepSessionRecord>(relaxed = true)

        every { record.startTime } returns start
        every { record.endTime } returns end
        every { record.metadata.id } returns "sleep-42"
        every { record.metadata.dataOrigin.packageName } returns "com.zepp"

        val mapped = HealthConnectMapper.mapSleepSessionRecord(record, syncedAt)

        assertThat(mapped.healthConnectId).isEqualTo("sleep-42")
        assertThat(mapped.startTime).isEqualTo(start)
        assertThat(mapped.endTime).isEqualTo(end)
        assertThat(mapped.durationMs).isEqualTo(8L * 60L * 60L * 1000L)
        assertThat(mapped.source).isEqualTo("com.zepp")
        assertThat(mapped.syncedAt).isEqualTo(syncedAt)
    }

    @Test
    fun mapSleepStages_mapsKnownAndUnknownTypesInOrder() {
        val syncedAt = Instant.parse("2026-02-10T12:00:00Z")
        val stage1 = mockk<SleepSessionRecord.Stage>()
        every { stage1.stage } returns 5
        every { stage1.startTime } returns Instant.parse("2026-02-10T00:00:00Z")
        every { stage1.endTime } returns Instant.parse("2026-02-10T01:00:00Z")

        val stage2 = mockk<SleepSessionRecord.Stage>()
        every { stage2.stage } returns 999
        every { stage2.startTime } returns Instant.parse("2026-02-10T01:00:00Z")
        every { stage2.endTime } returns Instant.parse("2026-02-10T02:00:00Z")

        val record = mockk<SleepSessionRecord>(relaxed = true)
        every { record.metadata.id } returns "sleep-99"
        every { record.stages } returns listOf(stage1, stage2)

        val mapped = HealthConnectMapper.mapSleepStages(record, sessionId = 123L, syncedAt = syncedAt)

        assertThat(mapped).hasSize(2)
        assertThat(mapped[0].sessionId).isEqualTo(123L)
        assertThat(mapped[0].healthConnectId).isEqualTo("sleep-99_stage_0")
        assertThat(mapped[0].stageType).isEqualTo("deep")
        assertThat(mapped[1].healthConnectId).isEqualTo("sleep-99_stage_1")
        assertThat(mapped[1].stageType).isEqualTo("unknown")
        assertThat(mapped[1].syncedAt).isEqualTo(syncedAt)
    }

    @Test
    fun mapStepsRecord_mapsCoreFields() {
        val syncedAt = Instant.parse("2026-02-10T12:00:00Z")
        val start = Instant.parse("2026-02-10T08:00:00Z")
        val end = Instant.parse("2026-02-10T08:30:00Z")

        val record = mockk<StepsRecord>(relaxed = true)
        every { record.metadata.id } returns "steps-7"
        every { record.metadata.dataOrigin.packageName } returns "com.zepp"
        every { record.startTime } returns start
        every { record.endTime } returns end
        every { record.count } returns 450L

        val mapped = HealthConnectMapper.mapStepsRecord(record, syncedAt)

        assertThat(mapped.healthConnectId).isEqualTo("steps-7")
        assertThat(mapped.startTime).isEqualTo(start)
        assertThat(mapped.endTime).isEqualTo(end)
        assertThat(mapped.count).isEqualTo(450)
        assertThat(mapped.source).isEqualTo("com.zepp")
        assertThat(mapped.syncedAt).isEqualTo(syncedAt)
    }

    @Test
    fun mapRestingHeartRateRecord_mapsDateUsingSystemZone() {
        val syncedAt = Instant.parse("2026-02-10T12:00:00Z")
        val time = Instant.parse("2026-02-10T11:15:00Z")
        val record = mockk<RestingHeartRateRecord>(relaxed = true)

        every { record.time } returns time
        every { record.metadata.id } returns "rhr-5"
        every { record.metadata.dataOrigin.packageName } returns "com.zepp"
        every { record.beatsPerMinute } returns 54L

        val mapped = HealthConnectMapper.mapRestingHeartRateRecord(record, syncedAt)
        val expectedDate = time.atZone(ZoneId.systemDefault()).toLocalDate()

        assertThat(mapped.healthConnectId).isEqualTo("rhr-5")
        assertThat(mapped.date).isEqualTo(expectedDate)
        assertThat(mapped.bpm).isEqualTo(54)
        assertThat(mapped.source).isEqualTo("com.zepp")
        assertThat(mapped.syncedAt).isEqualTo(syncedAt)
    }

    @Test
    fun mapAdditionalMetricRecords_mapsExpectedFields() {
        val syncedAt = Instant.parse("2026-02-11T08:00:00Z")
        val start = Instant.parse("2026-02-11T06:00:00Z")
        val end = Instant.parse("2026-02-11T07:00:00Z")
        val sampleTime = Instant.parse("2026-02-11T06:30:00Z")

        val activeRecord = mockk<ActiveCaloriesBurnedRecord>(relaxed = true)
        every { activeRecord.metadata.id } returns "active-1"
        every { activeRecord.metadata.dataOrigin.packageName } returns "com.zepp"
        every { activeRecord.startTime } returns start
        every { activeRecord.energy.inKilocalories } returns 432.1
        val mappedActive = HealthConnectMapper.mapActiveCaloriesBurnedRecord(activeRecord, syncedAt)
        assertThat(mappedActive.healthConnectId).isEqualTo("active-1")
        assertThat(mappedActive.calories).isWithin(0.001).of(432.1)

        val distanceRecord = mockk<DistanceRecord>(relaxed = true)
        every { distanceRecord.metadata.id } returns "distance-1"
        every { distanceRecord.metadata.dataOrigin.packageName } returns "com.zepp"
        every { distanceRecord.startTime } returns start
        every { distanceRecord.endTime } returns end
        every { distanceRecord.distance.inMeters } returns 1234.5
        val mappedDistance = HealthConnectMapper.mapDistanceRecord(distanceRecord, syncedAt)
        assertThat(mappedDistance.distanceMeters).isWithin(0.001).of(1234.5)

        val totalCaloriesRecord = mockk<TotalCaloriesBurnedRecord>(relaxed = true)
        every { totalCaloriesRecord.metadata.id } returns "total-1"
        every { totalCaloriesRecord.metadata.dataOrigin.packageName } returns "com.zepp"
        every { totalCaloriesRecord.startTime } returns start
        every { totalCaloriesRecord.endTime } returns end
        every { totalCaloriesRecord.energy.inKilocalories } returns 1789.4
        val mappedTotal = HealthConnectMapper.mapTotalCaloriesBurnedRecord(totalCaloriesRecord, syncedAt)
        assertThat(mappedTotal.energyKcal).isWithin(0.001).of(1789.4)

        val nutritionRecord = mockk<NutritionRecord>(relaxed = true)
        every { nutritionRecord.metadata.id } returns "nutrition-1"
        every { nutritionRecord.metadata.dataOrigin.packageName } returns "com.zepp"
        every { nutritionRecord.startTime } returns start
        every { nutritionRecord.endTime } returns end
        every { nutritionRecord.energy?.inKilocalories } returns 700.0
        every { nutritionRecord.protein?.inGrams } returns 42.0
        every { nutritionRecord.totalCarbohydrate?.inGrams } returns 85.0
        every { nutritionRecord.totalFat?.inGrams } returns 20.0
        every { nutritionRecord.mealType } returns 3
        val mappedNutrition = HealthConnectMapper.mapNutritionRecord(nutritionRecord, syncedAt)
        assertThat(mappedNutrition.energyKcal).isWithin(0.001).of(700.0)
        assertThat(mappedNutrition.proteinGrams).isWithin(0.001).of(42.0)
        assertThat(mappedNutrition.mealType).isEqualTo("dinner")

        val oxygenRecord = mockk<OxygenSaturationRecord>(relaxed = true)
        every { oxygenRecord.metadata.id } returns "spo2-1"
        every { oxygenRecord.metadata.dataOrigin.packageName } returns "com.zepp"
        every { oxygenRecord.time } returns sampleTime
        every { oxygenRecord.percentage.value } returns 0.98
        val mappedOxygen = HealthConnectMapper.mapOxygenSaturationRecord(oxygenRecord, syncedAt)
        assertThat(mappedOxygen.percentage).isWithin(0.0001).of(0.98)

        val hrvRecord = mockk<HeartRateVariabilityRmssdRecord>(relaxed = true)
        every { hrvRecord.metadata.id } returns "hrv-1"
        every { hrvRecord.metadata.dataOrigin.packageName } returns "com.zepp"
        every { hrvRecord.time } returns sampleTime
        every { hrvRecord.heartRateVariabilityMillis } returns 27.3
        val mappedHrv = HealthConnectMapper.mapHrvRecord(hrvRecord, syncedAt)
        assertThat(mappedHrv.rmssd).isWithin(0.0001).of(27.3)
    }
}

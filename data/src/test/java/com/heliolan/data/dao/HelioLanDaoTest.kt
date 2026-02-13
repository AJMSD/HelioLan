package com.heliolan.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.heliolan.data.database.HelioLanDatabase
import com.heliolan.data.entity.ActiveCaloriesBurned
import com.heliolan.data.entity.DailyAggregate
import com.heliolan.data.entity.DistanceRecord
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.HrvRecord
import com.heliolan.data.entity.NutritionRecord
import com.heliolan.data.entity.OxygenSaturation
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.SleepStage
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.entity.SyncCursor
import com.heliolan.data.entity.TotalCaloriesBurned
import com.heliolan.data.util.RecordType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HelioLanDaoTest {
    private lateinit var database: HelioLanDatabase
    private lateinit var heartRateSampleDao: HeartRateSampleDao
    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var sleepStageDao: SleepStageDao
    private lateinit var stepsRecordDao: StepsRecordDao
    private lateinit var restingHeartRateDao: RestingHeartRateDao
    private lateinit var activeCaloriesBurnedDao: ActiveCaloriesBurnedDao
    private lateinit var distanceRecordDao: DistanceRecordDao
    private lateinit var totalCaloriesBurnedDao: TotalCaloriesBurnedDao
    private lateinit var nutritionRecordDao: NutritionRecordDao
    private lateinit var oxygenSaturationDao: OxygenSaturationDao
    private lateinit var hrvRecordDao: HrvRecordDao
    private lateinit var dailyAggregateDao: DailyAggregateDao
    private lateinit var syncCursorDao: SyncCursorDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HelioLanDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        heartRateSampleDao = database.heartRateSampleDao()
        sleepSessionDao = database.sleepSessionDao()
        sleepStageDao = database.sleepStageDao()
        stepsRecordDao = database.stepsRecordDao()
        restingHeartRateDao = database.restingHeartRateDao()
        activeCaloriesBurnedDao = database.activeCaloriesBurnedDao()
        distanceRecordDao = database.distanceRecordDao()
        totalCaloriesBurnedDao = database.totalCaloriesBurnedDao()
        nutritionRecordDao = database.nutritionRecordDao()
        oxygenSaturationDao = database.oxygenSaturationDao()
        hrvRecordDao = database.hrvRecordDao()
        dailyAggregateDao = database.dailyAggregateDao()
        syncCursorDao = database.syncCursorDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun heartRateSampleDao_returnsDescendingAndRespectsPagination() =
        runTest {
            val start = Instant.parse("2026-02-10T00:00:00Z")
            val middle = Instant.parse("2026-02-10T01:00:00Z")
            val latest = Instant.parse("2026-02-10T02:00:00Z")

            heartRateSampleDao.upsert(
                listOf(
                    heartRateSample("hr-1", start, 60),
                    heartRateSample("hr-2", middle, 70),
                    heartRateSample("hr-3", latest, 80),
                ),
            )

            val paged =
                heartRateSampleDao
                    .getByDateRange(
                        startTime = start.minusSeconds(1),
                        endTime = latest.plusSeconds(1),
                        limit = 2,
                        offset = 1,
                    ).first()

            assertThat(paged.map { it.healthConnectId }).containsExactly("hr-2", "hr-1").inOrder()
            assertThat(heartRateSampleDao.getLatest().first()?.healthConnectId).isEqualTo("hr-3")
            assertThat(
                heartRateSampleDao.getCountInRange(
                    startTime = start.minusSeconds(1),
                    endTime = latest.plusSeconds(1),
                ),
            ).isEqualTo(3)
        }

    @Test
    fun sleepStageDao_cascadesDeleteWhenParentSessionDeleted() =
        runTest {
            val sessionId =
                sleepSessionDao.upsert(
                    SleepSession(
                        healthConnectId = "sleep-1",
                        startTime = Instant.parse("2026-02-10T22:00:00Z"),
                        endTime = Instant.parse("2026-02-11T06:00:00Z"),
                        durationMs = 28_800_000L,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-11T06:01:00Z"),
                    ),
                )

            sleepStageDao.upsert(
                listOf(
                    SleepStage(
                        sessionId = sessionId,
                        healthConnectId = "sleep-1-stage-1",
                        startTime = Instant.parse("2026-02-10T22:30:00Z"),
                        endTime = Instant.parse("2026-02-10T23:00:00Z"),
                        stageType = "light",
                        syncedAt = Instant.parse("2026-02-11T06:01:00Z"),
                    ),
                ),
            )

            assertThat(sleepStageDao.getBySessionId(sessionId).first()).hasSize(1)

            sleepSessionDao.deleteByHealthConnectId("sleep-1")

            assertThat(sleepSessionDao.getByHealthConnectId("sleep-1")).isNull()
            assertThat(sleepStageDao.getBySessionId(sessionId).first()).isEmpty()
        }

    @Test
    fun sleepSessionDao_attributesOvernightSessionsToWakeDay() =
        runTest {
            sleepSessionDao.upsert(
                listOf(
                    SleepSession(
                        healthConnectId = "sleep-overnight",
                        startTime = Instant.parse("2026-02-11T23:00:00Z"),
                        endTime = Instant.parse("2026-02-12T08:00:00Z"),
                        durationMs = 32_400_000L,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-12T08:01:00Z"),
                    ),
                    SleepSession(
                        healthConnectId = "sleep-prior",
                        startTime = Instant.parse("2026-02-10T23:30:00Z"),
                        endTime = Instant.parse("2026-02-11T07:00:00Z"),
                        durationMs = 27_000_000L,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-11T07:01:00Z"),
                    ),
                ),
            )

            val sessions =
                sleepSessionDao
                    .getByDateRange(
                        startTime = Instant.parse("2026-02-12T00:00:00Z"),
                        endTime = Instant.parse("2026-02-12T23:59:59Z"),
                        limit = 10,
                        offset = 0,
                    ).first()

            assertThat(sessions.map { it.healthConnectId }).containsExactly("sleep-overnight")
        }

    @Test
    fun stepsRecordDao_totalStepsSumsOnlyWithinRequestedRange() =
        runTest {
            stepsRecordDao.upsert(
                listOf(
                    stepsRecord(
                        id = "steps-1",
                        start = Instant.parse("2026-02-10T08:00:00Z"),
                        end = Instant.parse("2026-02-10T08:15:00Z"),
                        count = 100,
                    ),
                    stepsRecord(
                        id = "steps-2",
                        start = Instant.parse("2026-02-10T09:00:00Z"),
                        end = Instant.parse("2026-02-10T09:15:00Z"),
                        count = 250,
                    ),
                    stepsRecord(
                        id = "steps-outside",
                        start = Instant.parse("2026-02-11T09:00:00Z"),
                        end = Instant.parse("2026-02-11T09:15:00Z"),
                        count = 900,
                    ),
                ),
            )

            val total =
                stepsRecordDao
                    .getTotalSteps(
                        startTime = Instant.parse("2026-02-10T00:00:00Z"),
                        endTime = Instant.parse("2026-02-10T23:59:59Z"),
                    ).first()

            assertThat(total).isEqualTo(350)
            assertThat(stepsRecordDao.getLatest().first()?.healthConnectId).isEqualTo("steps-outside")
        }

    @Test
    fun restingHeartRateDao_getLatestAndDateBoundsWork() =
        runTest {
            val firstDate = LocalDate.of(2026, 2, 9)
            val secondDate = firstDate.plusDays(1)
            restingHeartRateDao.upsert(
                listOf(
                    restingHeartRate("rhr-1", firstDate, 56),
                    restingHeartRate("rhr-2", secondDate, 54),
                ),
            )

            val records = restingHeartRateDao.getByDateRange(firstDate, secondDate, limit = 10, offset = 0).first()

            assertThat(records.map { it.healthConnectId }).containsExactly("rhr-2", "rhr-1").inOrder()
            assertThat(restingHeartRateDao.getLatest().first()?.healthConnectId).isEqualTo("rhr-2")
            assertThat(restingHeartRateDao.getOldestDate()).isEqualTo(firstDate)
            assertThat(restingHeartRateDao.getLatestDate()).isEqualTo(secondDate)
        }

    @Test
    fun dailyAggregateDao_filtersByTypeAndDateRange() =
        runTest {
            val date1 = LocalDate.of(2026, 2, 8)
            val date2 = LocalDate.of(2026, 2, 9)
            val date3 = LocalDate.of(2026, 2, 10)

            dailyAggregateDao.upsert(
                listOf(
                    dailyAggregate(date = date1, type = RecordType.STEPS, value = 1_000.0),
                    dailyAggregate(date = date2, type = RecordType.STEPS, value = 2_000.0),
                    dailyAggregate(date = date3, type = RecordType.HEART_RATE, value = 72.0),
                ),
            )

            val steps =
                dailyAggregateDao
                    .getByTypeAndDateRange(RecordType.STEPS, date1, date3)
                    .first()
            assertThat(steps.map { it.date }).containsExactly(date2, date1).inOrder()
            assertThat(dailyAggregateDao.getLatestForType(RecordType.STEPS).first()?.date).isEqualTo(date2)

            dailyAggregateDao.deleteForDateAndType(RecordType.STEPS, date1)
            val remaining = dailyAggregateDao.getByTypeAndDateRange(RecordType.STEPS, date1, date3).first()
            assertThat(remaining).hasSize(1)
            assertThat(remaining.first().date).isEqualTo(date2)
        }

    @Test
    fun newMetricDaos_supportRangeQueriesLatestAndTotals() =
        runTest {
            val date = LocalDate.of(2026, 2, 10)
            val start = Instant.parse("2026-02-10T00:00:00Z")
            val end = Instant.parse("2026-02-10T23:59:59Z")

            activeCaloriesBurnedDao.upsert(
                listOf(
                    ActiveCaloriesBurned(
                        healthConnectId = "active-1",
                        date = date,
                        calories = 300.0,
                        source = "zepp",
                        syncedAt = start,
                    ),
                    ActiveCaloriesBurned(
                        healthConnectId = "active-2",
                        date = date,
                        calories = 200.0,
                        source = "zepp",
                        syncedAt = start.plusSeconds(1),
                    ),
                ),
            )
            assertThat(activeCaloriesBurnedDao.getByDateRange(date, date).first()).hasSize(2)
            assertThat(activeCaloriesBurnedDao.getTotalCalories(date, date).first()).isWithin(0.001).of(500.0)
            assertThat(activeCaloriesBurnedDao.getLatest().first()).isNotNull()

            distanceRecordDao.upsert(
                DistanceRecord(
                    healthConnectId = "distance-1",
                    startTime = start.plusSeconds(60),
                    endTime = start.plusSeconds(120),
                    distanceMeters = 1100.0,
                    source = "zepp",
                    syncedAt = start.plusSeconds(121),
                ),
            )
            assertThat(distanceRecordDao.getByDateRange(start, end).first()).hasSize(1)
            assertThat(distanceRecordDao.getTotalDistanceMeters(start, end).first()).isWithin(0.001).of(1100.0)

            totalCaloriesBurnedDao.upsert(
                TotalCaloriesBurned(
                    healthConnectId = "total-1",
                    startTime = start.plusSeconds(60),
                    endTime = start.plusSeconds(120),
                    energyKcal = 1600.0,
                    source = "zepp",
                    syncedAt = start.plusSeconds(121),
                ),
            )
            assertThat(totalCaloriesBurnedDao.getByDateRange(start, end).first()).hasSize(1)
            assertThat(totalCaloriesBurnedDao.getTotalEnergyKcal(start, end).first()).isWithin(0.001).of(1600.0)

            nutritionRecordDao.upsert(
                NutritionRecord(
                    healthConnectId = "nutrition-1",
                    startTime = start.plusSeconds(300),
                    endTime = start.plusSeconds(360),
                    energyKcal = 650.0,
                    proteinGrams = 35.0,
                    carbsGrams = 80.0,
                    fatGrams = 15.0,
                    mealType = "lunch",
                    nutrientsJson = null,
                    source = "zepp",
                    syncedAt = start.plusSeconds(361),
                ),
            )
            assertThat(nutritionRecordDao.getByDateRange(start, end).first()).hasSize(1)
            assertThat(nutritionRecordDao.getLatest().first()?.healthConnectId).isEqualTo("nutrition-1")

            oxygenSaturationDao.upsert(
                OxygenSaturation(
                    healthConnectId = "spo2-1",
                    timestamp = start.plusSeconds(500),
                    percentage = 0.97,
                    source = "zepp",
                    syncedAt = start.plusSeconds(501),
                ),
            )
            assertThat(oxygenSaturationDao.getByDateRange(start, end).first()).hasSize(1)
            assertThat(oxygenSaturationDao.getAveragePercentage(start, end).first()).isWithin(0.0001).of(0.97)

            hrvRecordDao.upsert(
                HrvRecord(
                    healthConnectId = "hrv-1",
                    timestamp = start.plusSeconds(700),
                    rmssd = 28.4,
                    source = "zepp",
                    syncedAt = start.plusSeconds(701),
                ),
            )
            assertThat(hrvRecordDao.getByDateRange(start, end).first()).hasSize(1)
            assertThat(hrvRecordDao.getAverageRmssd(start, end).first()).isWithin(0.0001).of(28.4)
        }

    @Test
    fun totalCaloriesDao_includesOverlappingIntervalsAndSumsAllMatchingRows() =
        runTest {
            val dayStart = Instant.parse("2026-02-12T00:00:00Z")
            val dayEnd = Instant.parse("2026-02-12T23:59:59Z")

            totalCaloriesBurnedDao.upsert(
                listOf(
                    TotalCaloriesBurned(
                        healthConnectId = "total-overlap",
                        startTime = Instant.parse("2026-02-11T23:30:00Z"),
                        endTime = Instant.parse("2026-02-12T00:30:00Z"),
                        energyKcal = 120.0,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-12T00:31:00Z"),
                    ),
                    TotalCaloriesBurned(
                        healthConnectId = "total-main",
                        startTime = Instant.parse("2026-02-12T08:00:00Z"),
                        endTime = Instant.parse("2026-02-12T09:00:00Z"),
                        energyKcal = 300.0,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-12T09:01:00Z"),
                    ),
                    TotalCaloriesBurned(
                        healthConnectId = "total-main-duplicate-source",
                        startTime = Instant.parse("2026-02-12T08:00:00Z"),
                        endTime = Instant.parse("2026-02-12T09:00:00Z"),
                        energyKcal = 280.0,
                        source = "other",
                        syncedAt = Instant.parse("2026-02-12T09:02:00Z"),
                    ),
                ),
            )

            val rows = totalCaloriesBurnedDao.getByDateRange(dayStart, dayEnd, limit = 10, offset = 0).first()
            val total = totalCaloriesBurnedDao.getTotalEnergyKcal(dayStart, dayEnd).first()

            assertThat(rows).hasSize(3)
            assertThat(total).isWithin(0.001).of(700.0)
        }

    @Test
    fun syncCursorDao_upsertGetAndDeleteLifecycle() =
        runTest {
            val firstCursor =
                SyncCursor(
                    recordType = RecordType.HEART_RATE,
                    lastSyncTime = Instant.parse("2026-02-10T10:00:00Z"),
                    changeToken = "token-1",
                )
            val updatedCursor =
                firstCursor.copy(
                    lastSyncTime = Instant.parse("2026-02-10T10:30:00Z"),
                    changeToken = "token-2",
                )

            syncCursorDao.upsert(firstCursor)
            assertThat(syncCursorDao.getCursor(RecordType.HEART_RATE)?.changeToken).isEqualTo("token-1")

            syncCursorDao.upsert(updatedCursor)
            assertThat(syncCursorDao.getCursor(RecordType.HEART_RATE)?.changeToken).isEqualTo("token-2")
            assertThat(syncCursorDao.getAllCursors()).hasSize(1)

            syncCursorDao.deleteCursor(RecordType.HEART_RATE)
            assertThat(syncCursorDao.getCursor(RecordType.HEART_RATE)).isNull()
        }

    private fun heartRateSample(
        id: String,
        timestamp: Instant,
        bpm: Int,
    ): HeartRateSample =
        HeartRateSample(
            healthConnectId = id,
            timestamp = timestamp,
            bpm = bpm,
            source = "zepp",
            syncedAt = timestamp.plusSeconds(5),
        )

    private fun stepsRecord(
        id: String,
        start: Instant,
        end: Instant,
        count: Int,
    ): StepsRecord =
        StepsRecord(
            healthConnectId = id,
            startTime = start,
            endTime = end,
            count = count,
            source = "zepp",
            syncedAt = end.plusSeconds(5),
        )

    private fun restingHeartRate(
        id: String,
        date: LocalDate,
        bpm: Int,
    ): RestingHeartRate =
        RestingHeartRate(
            healthConnectId = id,
            date = date,
            bpm = bpm,
            source = "zepp",
            syncedAt = Instant.parse("${date}T12:00:00Z"),
        )

    private fun dailyAggregate(
        date: LocalDate,
        type: String,
        value: Double,
    ): DailyAggregate =
        DailyAggregate(
            date = date,
            recordType = type,
            value = value,
            count = 1,
            min = value,
            max = value,
            avg = value,
            updatedAt = Instant.parse("${date}T23:59:59Z"),
        )
}

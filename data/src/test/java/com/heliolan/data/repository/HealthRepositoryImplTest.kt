package com.heliolan.data.repository

import com.google.common.truth.Truth.assertThat
import com.heliolan.data.dao.ActiveCaloriesBurnedDao
import com.heliolan.data.dao.DailyAggregateDao
import com.heliolan.data.dao.DistanceRecordDao
import com.heliolan.data.dao.HeartRateSampleDao
import com.heliolan.data.dao.HrvRecordDao
import com.heliolan.data.dao.NutritionRecordDao
import com.heliolan.data.dao.OxygenSaturationDao
import com.heliolan.data.dao.RestingHeartRateDao
import com.heliolan.data.dao.SleepSessionDao
import com.heliolan.data.dao.SleepStageDao
import com.heliolan.data.dao.StepsRecordDao
import com.heliolan.data.dao.TotalCaloriesBurnedDao
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
import com.heliolan.data.entity.TotalCaloriesBurned
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HealthRepositoryImplTest {
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

    private lateinit var repository: HealthRepositoryImpl

    @Before
    fun setUp() {
        heartRateSampleDao = mockk()
        sleepSessionDao = mockk()
        sleepStageDao = mockk()
        stepsRecordDao = mockk()
        restingHeartRateDao = mockk()
        activeCaloriesBurnedDao = mockk()
        distanceRecordDao = mockk()
        totalCaloriesBurnedDao = mockk()
        nutritionRecordDao = mockk()
        oxygenSaturationDao = mockk()
        hrvRecordDao = mockk()
        dailyAggregateDao = mockk()

        repository =
            HealthRepositoryImpl(
                heartRateSampleDao = heartRateSampleDao,
                sleepSessionDao = sleepSessionDao,
                sleepStageDao = sleepStageDao,
                stepsRecordDao = stepsRecordDao,
                restingHeartRateDao = restingHeartRateDao,
                activeCaloriesBurnedDao = activeCaloriesBurnedDao,
                distanceRecordDao = distanceRecordDao,
                totalCaloriesBurnedDao = totalCaloriesBurnedDao,
                nutritionRecordDao = nutritionRecordDao,
                oxygenSaturationDao = oxygenSaturationDao,
                hrvRecordDao = hrvRecordDao,
                dailyAggregateDao = dailyAggregateDao,
            )
    }

    @Test
    fun getHeartRateSamples_delegatesToDaoWithExactArguments() =
        runTest {
            val start = Instant.parse("2026-02-10T00:00:00Z")
            val end = Instant.parse("2026-02-10T23:59:59Z")
            val expected =
                listOf(
                    HeartRateSample(
                        healthConnectId = "hr-1",
                        timestamp = Instant.parse("2026-02-10T12:00:00Z"),
                        bpm = 72,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-10T12:00:05Z"),
                    ),
                )
            every { heartRateSampleDao.getByDateRange(start, end, 50, 10) } returns flowOf(expected)

            val actual =
                repository
                    .getHeartRateSamples(
                        startTime = start,
                        endTime = end,
                        limit = 50,
                        offset = 10,
                    ).first()

            assertThat(actual).isEqualTo(expected)
            verify(exactly = 1) { heartRateSampleDao.getByDateRange(start, end, 50, 10) }
        }

    @Test
    fun sleepAndStagesQueries_delegateToRespectiveDaos() =
        runTest {
            val start = Instant.parse("2026-02-09T00:00:00Z")
            val end = Instant.parse("2026-02-10T23:59:59Z")
            val sessions = flowOf(emptyList<SleepSession>())
            val stages = flowOf(listOf<SleepStage>())
            every { sleepSessionDao.getByDateRange(start, end, 5, 1) } returns sessions
            every { sleepSessionDao.getLatest() } returns flowOf(null)
            every { sleepStageDao.getBySessionId(42L) } returns stages

            assertThat(repository.getSleepSessions(start, end, 5, 1).first()).isEmpty()
            assertThat(repository.getLatestSleepSession().first()).isNull()
            assertThat(repository.getSleepStages(42L).first()).isEmpty()

            verify(exactly = 1) { sleepSessionDao.getByDateRange(start, end, 5, 1) }
            verify(exactly = 1) { sleepSessionDao.getLatest() }
            verify(exactly = 1) { sleepStageDao.getBySessionId(42L) }
        }

    @Test
    fun stepsQueries_delegateAndExposeLatestAndTotalFlow() =
        runTest {
            val start = Instant.parse("2026-02-10T00:00:00Z")
            val end = Instant.parse("2026-02-10T23:59:59Z")
            val records =
                listOf(
                    StepsRecord(
                        healthConnectId = "steps-1",
                        startTime = Instant.parse("2026-02-10T08:00:00Z"),
                        endTime = Instant.parse("2026-02-10T08:15:00Z"),
                        count = 125,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-10T08:15:05Z"),
                    ),
                )

            every { stepsRecordDao.getByDateRange(start, end, 1000, 0) } returns flowOf(records)
            every { stepsRecordDao.getTotalSteps(start, end) } returns flowOf(125)
            every { stepsRecordDao.getLatest() } returns flowOf(records.first())

            assertThat(repository.getStepsRecords(start, end).first()).isEqualTo(records)
            assertThat(repository.getTotalSteps(start, end).first()).isEqualTo(125)
            assertThat(repository.getLatestStepsRecord().first()?.healthConnectId).isEqualTo("steps-1")

            verify(exactly = 1) { stepsRecordDao.getByDateRange(start, end, 1000, 0) }
            verify(exactly = 1) { stepsRecordDao.getTotalSteps(start, end) }
            verify(exactly = 1) { stepsRecordDao.getLatest() }
        }

    @Test
    fun restingHeartRateAndDailyAggregates_delegateCorrectly() =
        runTest {
            val startDate = LocalDate.of(2026, 2, 1)
            val endDate = LocalDate.of(2026, 2, 10)

            val restingRecords =
                listOf(
                    RestingHeartRate(
                        healthConnectId = "rhr-1",
                        date = LocalDate.of(2026, 2, 10),
                        bpm = 56,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-10T05:00:00Z"),
                    ),
                )
            val aggregates =
                listOf(
                    DailyAggregate(
                        date = LocalDate.of(2026, 2, 10),
                        recordType = "steps",
                        value = 8_450.0,
                        count = 1,
                        min = 8_450.0,
                        max = 8_450.0,
                        avg = 8_450.0,
                        updatedAt = Instant.parse("2026-02-10T23:59:59Z"),
                    ),
                )

            every { restingHeartRateDao.getByDateRange(startDate, endDate, 365, 0) } returns flowOf(restingRecords)
            every { restingHeartRateDao.getLatest() } returns flowOf(restingRecords.first())
            every { dailyAggregateDao.getByTypeAndDateRange("steps", startDate, endDate) } returns flowOf(aggregates)
            every { dailyAggregateDao.getByDateRange(startDate, endDate) } returns flowOf(aggregates)

            assertThat(repository.getRestingHeartRate(startDate, endDate).first()).isEqualTo(restingRecords)
            assertThat(repository.getLatestRestingHeartRate().first()?.healthConnectId).isEqualTo("rhr-1")
            assertThat(repository.getDailyAggregates("steps", startDate, endDate).first()).isEqualTo(aggregates)
            assertThat(repository.getAllDailyAggregates(startDate, endDate).first()).isEqualTo(aggregates)

            verify(exactly = 1) { restingHeartRateDao.getByDateRange(startDate, endDate, 365, 0) }
            verify(exactly = 1) { restingHeartRateDao.getLatest() }
            verify(exactly = 1) { dailyAggregateDao.getByTypeAndDateRange("steps", startDate, endDate) }
            verify(exactly = 1) { dailyAggregateDao.getByDateRange(startDate, endDate) }
        }

    @Test
    fun newMetricQueries_delegateToRespectiveDaos() =
        runTest {
            val startDate = LocalDate.of(2026, 2, 10)
            val endDate = startDate.plusDays(1)
            val startTime = Instant.parse("2026-02-10T00:00:00Z")
            val endTime = Instant.parse("2026-02-10T23:59:59Z")

            val activeCaloriesRecords =
                listOf(
                    ActiveCaloriesBurned(
                        healthConnectId = "active-1",
                        date = startDate,
                        calories = 420.5,
                        source = "zepp",
                        syncedAt = startTime,
                    ),
                )
            val distanceRecords =
                listOf(
                    DistanceRecord(
                        healthConnectId = "distance-1",
                        startTime = startTime,
                        endTime = startTime.plusSeconds(600),
                        distanceMeters = 1200.0,
                        source = "zepp",
                        syncedAt = startTime.plusSeconds(1),
                    ),
                )
            val totalCaloriesRecords =
                listOf(
                    TotalCaloriesBurned(
                        healthConnectId = "total-1",
                        startTime = startTime,
                        endTime = startTime.plusSeconds(600),
                        energyKcal = 1500.0,
                        source = "zepp",
                        syncedAt = startTime.plusSeconds(1),
                    ),
                )
            val nutritionRecords =
                listOf(
                    NutritionRecord(
                        healthConnectId = "nutrition-1",
                        startTime = startTime,
                        endTime = startTime.plusSeconds(1800),
                        energyKcal = 650.0,
                        proteinGrams = 40.0,
                        carbsGrams = 70.0,
                        fatGrams = 20.0,
                        mealType = "lunch",
                        nutrientsJson = null,
                        source = "zepp",
                        syncedAt = startTime.plusSeconds(1),
                    ),
                )
            val oxygenRecords =
                listOf(
                    OxygenSaturation(
                        healthConnectId = "spo2-1",
                        timestamp = startTime,
                        percentage = 0.98,
                        source = "zepp",
                        syncedAt = startTime.plusSeconds(1),
                    ),
                )
            val hrvRecords =
                listOf(
                    HrvRecord(
                        healthConnectId = "hrv-1",
                        timestamp = startTime,
                        rmssd = 32.4,
                        source = "zepp",
                        syncedAt = startTime.plusSeconds(1),
                    ),
                )

            every {
                activeCaloriesBurnedDao.getByDateRange(startDate, endDate, 365, 0)
            } returns flowOf(activeCaloriesRecords)
            every { activeCaloriesBurnedDao.getLatest() } returns flowOf(activeCaloriesRecords.first())
            every { activeCaloriesBurnedDao.getTotalCalories(startDate, endDate) } returns flowOf(420.5)
            every {
                distanceRecordDao.getByDateRange(startTime, endTime, 1000, 0)
            } returns flowOf(distanceRecords)
            every { distanceRecordDao.getLatest() } returns flowOf(distanceRecords.first())
            every { distanceRecordDao.getTotalDistanceMeters(startTime, endTime) } returns flowOf(1200.0)
            every {
                totalCaloriesBurnedDao.getByDateRange(startTime, endTime, 1000, 0)
            } returns flowOf(totalCaloriesRecords)
            every { totalCaloriesBurnedDao.getLatest() } returns flowOf(totalCaloriesRecords.first())
            every { totalCaloriesBurnedDao.getTotalEnergyKcal(startTime, endTime) } returns flowOf(1500.0)
            every {
                nutritionRecordDao.getByDateRange(startTime, endTime, 1000, 0)
            } returns flowOf(nutritionRecords)
            every { nutritionRecordDao.getLatest() } returns flowOf(nutritionRecords.first())
            every {
                oxygenSaturationDao.getByDateRange(startTime, endTime, 1000, 0)
            } returns flowOf(oxygenRecords)
            every { oxygenSaturationDao.getLatest() } returns flowOf(oxygenRecords.first())
            every { oxygenSaturationDao.getAveragePercentage(startTime, endTime) } returns flowOf(0.98)
            every {
                hrvRecordDao.getByDateRange(startTime, endTime, 1000, 0)
            } returns flowOf(hrvRecords)
            every { hrvRecordDao.getLatest() } returns flowOf(hrvRecords.first())
            every { hrvRecordDao.getAverageRmssd(startTime, endTime) } returns flowOf(32.4)

            assertThat(
                repository.getActiveCaloriesBurned(startDate, endDate).first(),
            ).isEqualTo(activeCaloriesRecords)
            assertThat(
                repository.getLatestActiveCaloriesBurned().first()?.healthConnectId,
            ).isEqualTo("active-1")
            assertThat(
                repository.getTotalActiveCalories(startDate, endDate).first(),
            ).isWithin(0.001).of(420.5)
            assertThat(repository.getDistanceRecords(startTime, endTime).first()).isEqualTo(distanceRecords)
            assertThat(
                repository.getTotalDistanceMeters(startTime, endTime).first(),
            ).isWithin(0.001).of(1200.0)
            assertThat(
                repository.getTotalCaloriesBurnedRecords(startTime, endTime).first(),
            ).isEqualTo(totalCaloriesRecords)
            assertThat(
                repository.getTotalCaloriesBurned(startTime, endTime).first(),
            ).isWithin(0.001).of(1500.0)
            assertThat(repository.getNutritionRecords(startTime, endTime).first()).isEqualTo(nutritionRecords)
            assertThat(
                repository.getOxygenSaturationRecords(startTime, endTime).first(),
            ).isEqualTo(oxygenRecords)
            assertThat(
                repository.getAverageOxygenSaturation(startTime, endTime).first(),
            ).isWithin(0.001).of(0.98)
            assertThat(repository.getHrvRecords(startTime, endTime).first()).isEqualTo(hrvRecords)
            assertThat(
                repository.getAverageHrvRmssd(startTime, endTime).first(),
            ).isWithin(0.001).of(32.4)

            verify(exactly = 1) {
                activeCaloriesBurnedDao.getByDateRange(startDate, endDate, 365, 0)
            }
            verify(exactly = 1) {
                distanceRecordDao.getByDateRange(startTime, endTime, 1000, 0)
            }
            verify(exactly = 1) {
                totalCaloriesBurnedDao.getByDateRange(startTime, endTime, 1000, 0)
            }
            verify(exactly = 1) {
                nutritionRecordDao.getByDateRange(startTime, endTime, 1000, 0)
            }
            verify(exactly = 1) {
                oxygenSaturationDao.getByDateRange(startTime, endTime, 1000, 0)
            }
            verify(exactly = 1) {
                hrvRecordDao.getByDateRange(startTime, endTime, 1000, 0)
            }
        }
}

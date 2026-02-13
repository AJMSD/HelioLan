package com.heliolan.sync.engine

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
import com.heliolan.data.dao.SyncCursorDao
import com.heliolan.data.dao.TotalCaloriesBurnedDao
import com.heliolan.data.entity.DailyAggregate
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.util.RecordType
import com.heliolan.healthconnect.reader.HealthConnectReader
import com.heliolan.healthconnect.reader.ReadResult
import com.heliolan.sync.model.SyncErrorCode
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.model.SyncTrigger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    private lateinit var healthConnectReader: HealthConnectReader
    private lateinit var aggregationEngine: AggregationEngine
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
    private lateinit var syncCursorDao: SyncCursorDao
    private lateinit var syncEngine: SyncEngine

    @Before
    fun setUp() {
        healthConnectReader = mockk(relaxed = true)
        aggregationEngine = mockk(relaxed = true)
        heartRateSampleDao = mockk(relaxed = true)
        sleepSessionDao = mockk(relaxed = true)
        sleepStageDao = mockk(relaxed = true)
        stepsRecordDao = mockk(relaxed = true)
        restingHeartRateDao = mockk(relaxed = true)
        activeCaloriesBurnedDao = mockk(relaxed = true)
        distanceRecordDao = mockk(relaxed = true)
        totalCaloriesBurnedDao = mockk(relaxed = true)
        nutritionRecordDao = mockk(relaxed = true)
        oxygenSaturationDao = mockk(relaxed = true)
        hrvRecordDao = mockk(relaxed = true)
        syncCursorDao = mockk(relaxed = true)

        syncEngine =
            SyncEngine(
                healthConnectReader = healthConnectReader,
                aggregationEngine = aggregationEngine,
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
                syncCursorDao = syncCursorDao,
            )

        coEvery { syncCursorDao.getCursor(any()) } returns null
        coEvery { healthConnectReader.readActiveCaloriesBurned(any(), any()) } returns ReadResult.Success(emptyList())
        coEvery { healthConnectReader.readDistance(any(), any()) } returns ReadResult.Success(emptyList())
        coEvery { healthConnectReader.readTotalCaloriesBurned(any(), any()) } returns ReadResult.Success(emptyList())
        coEvery { healthConnectReader.readNutrition(any(), any()) } returns ReadResult.Success(emptyList())
        coEvery { healthConnectReader.readOxygenSaturation(any(), any()) } returns ReadResult.Success(emptyList())
        coEvery { healthConnectReader.readHrv(any(), any()) } returns ReadResult.Success(emptyList())
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun syncAll_success_deduplicatesAndStoresRecords() =
        runTest {
            val now = Instant.now()
            val heartRateDuplicateId = "hr-1"
            val heartRateRecords =
                listOf(
                    HeartRateSample(
                        healthConnectId = heartRateDuplicateId,
                        timestamp = now,
                        bpm = 70,
                        source = "test",
                        syncedAt = now,
                    ),
                    HeartRateSample(
                        healthConnectId = heartRateDuplicateId,
                        timestamp = now,
                        bpm = 71,
                        source = "test",
                        syncedAt = now,
                    ),
                )

            val stepsRecords =
                listOf(
                    StepsRecord(
                        healthConnectId = "steps-1",
                        startTime = now,
                        endTime = now.plusSeconds(60),
                        count = 12,
                        source = "test",
                        syncedAt = now,
                    ),
                )

            coEvery { healthConnectReader.readHeartRate(any(), any()) } returns ReadResult.Success(heartRateRecords)
            coEvery { healthConnectReader.readSleep(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSteps(any(), any()) } returns ReadResult.Success(stepsRecords)
            coEvery { healthConnectReader.readRestingHeartRate(any(), any()) } returns ReadResult.Success(emptyList())

            val result = syncEngine.syncAll()
            val expectedDate = now.atZone(ZoneId.systemDefault()).toLocalDate()

            assertThat(result).isInstanceOf(SyncResult.Success::class.java)
            val success = result as SyncResult.Success
            assertThat(success.summary.totalFetched).isEqualTo(3)
            assertThat(success.summary.totalStored).isEqualTo(2)
            assertThat(success.summary.totalDeduplicated).isEqualTo(1)

            coVerify(exactly = 1) { heartRateSampleDao.deleteByHealthConnectIds(any()) }
            coVerify(exactly = 1) { heartRateSampleDao.upsert(any<List<HeartRateSample>>()) }
            coVerify(exactly = 1) { stepsRecordDao.deleteByHealthConnectIds(any()) }
            coVerify(exactly = 1) { stepsRecordDao.upsert(any<List<StepsRecord>>()) }
            coVerify(exactly = 2) { aggregationEngine.updateAggregatesForDates(setOf(expectedDate)) }
        }

    @Test
    fun syncAll_partialSuccess_whenOneRecordTypeFailsPermission() =
        runTest {
            coEvery { healthConnectReader.readHeartRate(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSleep(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSteps(any(), any()) } returns ReadResult.PermissionDenied
            coEvery { healthConnectReader.readRestingHeartRate(any(), any()) } returns ReadResult.Success(emptyList())

            val result = syncEngine.syncAll()

            assertThat(result).isInstanceOf(SyncResult.PartialSuccess::class.java)
            val partial = result as SyncResult.PartialSuccess
            assertThat(partial.errors).hasSize(1)
            assertThat(partial.errors.first().recordType).isEqualTo(RecordType.STEPS)
            assertThat(partial.errors.first().code).isEqualTo(SyncErrorCode.PERMISSION_DENIED)
            coVerify(exactly = 0) { stepsRecordDao.deleteByHealthConnectIds(any()) }
        }

    @Test
    fun syncAll_returnsDebouncedFailure_whenCalledTooSoon() =
        runTest {
            coEvery { healthConnectReader.readHeartRate(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSleep(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSteps(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readRestingHeartRate(any(), any()) } returns ReadResult.Success(emptyList())

            val first = syncEngine.syncAll()
            val second = syncEngine.syncAll()

            assertThat(first).isInstanceOf(SyncResult.Success::class.java)
            assertThat(second).isInstanceOf(SyncResult.Failure::class.java)
            val failure = second as SyncResult.Failure
            assertThat(failure.error.code).isEqualTo(SyncErrorCode.DEBOUNCED)
        }

    @Test
    fun syncAll_userTrigger_bypassesDebounce() =
        runTest {
            coEvery { healthConnectReader.readHeartRate(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSleep(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSteps(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readRestingHeartRate(any(), any()) } returns ReadResult.Success(emptyList())

            val first = syncEngine.syncAll(trigger = SyncTrigger.USER)
            val second = syncEngine.syncAll(trigger = SyncTrigger.USER)

            assertThat(first).isInstanceOf(SyncResult.Success::class.java)
            assertThat(second).isInstanceOf(SyncResult.Success::class.java)
        }

    @Test
    fun syncRecordType_returnsUnsupportedFailure_forUnknownType() =
        runTest {
            val result = syncEngine.syncRecordType("unknown_type")

            assertThat(result).isInstanceOf(SyncResult.Failure::class.java)
            val failure = result as SyncResult.Failure
            assertThat(failure.error.code).isEqualTo(SyncErrorCode.UNSUPPORTED_RECORD_TYPE)
        }

    @Test
    fun syncAll_triggersAggregationForAffectedDates() =
        runTest {
            val now = Instant.now()
            val expectedDate = now.atZone(ZoneId.systemDefault()).toLocalDate()
            val heartRateRecords =
                listOf(
                    HeartRateSample(
                        healthConnectId = "hr-aggregation-1",
                        timestamp = now,
                        bpm = 74,
                        source = "test",
                        syncedAt = now,
                    ),
                )

            coEvery { healthConnectReader.readHeartRate(any(), any()) } returns ReadResult.Success(heartRateRecords)
            coEvery { healthConnectReader.readSleep(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSteps(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readRestingHeartRate(any(), any()) } returns ReadResult.Success(emptyList())

            val result = syncEngine.syncAll()

            assertThat(result).isInstanceOf(SyncResult.Success::class.java)
            coVerify(exactly = 1) { aggregationEngine.updateAggregatesForDates(setOf(expectedDate)) }
        }

    @Test
    fun syncAll_succeedsWhenAggregationRefreshFails() =
        runTest {
            val now = Instant.now()
            val heartRateRecords =
                listOf(
                    HeartRateSample(
                        healthConnectId = "hr-aggregation-2",
                        timestamp = now,
                        bpm = 68,
                        source = "test",
                        syncedAt = now,
                    ),
                )

            coEvery { healthConnectReader.readHeartRate(any(), any()) } returns ReadResult.Success(heartRateRecords)
            coEvery { healthConnectReader.readSleep(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSteps(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readRestingHeartRate(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery {
                aggregationEngine.updateAggregatesForDates(any())
            } throws IllegalStateException("aggregate failed")

            val result = syncEngine.syncAll()

            assertThat(result).isInstanceOf(SyncResult.Success::class.java)
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AggregationEngineTest {
    private lateinit var heartRateSampleDao: HeartRateSampleDao
    private lateinit var sleepSessionDao: SleepSessionDao
    private lateinit var stepsRecordDao: StepsRecordDao
    private lateinit var restingHeartRateDao: RestingHeartRateDao
    private lateinit var activeCaloriesBurnedDao: ActiveCaloriesBurnedDao
    private lateinit var distanceRecordDao: DistanceRecordDao
    private lateinit var totalCaloriesBurnedDao: TotalCaloriesBurnedDao
    private lateinit var nutritionRecordDao: NutritionRecordDao
    private lateinit var oxygenSaturationDao: OxygenSaturationDao
    private lateinit var hrvRecordDao: HrvRecordDao
    private lateinit var dailyAggregateDao: DailyAggregateDao
    private lateinit var aggregationEngine: AggregationEngine

    @Before
    fun setUp() {
        heartRateSampleDao = mockk(relaxed = true)
        sleepSessionDao = mockk(relaxed = true)
        stepsRecordDao = mockk(relaxed = true)
        restingHeartRateDao = mockk(relaxed = true)
        activeCaloriesBurnedDao = mockk(relaxed = true)
        distanceRecordDao = mockk(relaxed = true)
        totalCaloriesBurnedDao = mockk(relaxed = true)
        nutritionRecordDao = mockk(relaxed = true)
        oxygenSaturationDao = mockk(relaxed = true)
        hrvRecordDao = mockk(relaxed = true)
        dailyAggregateDao = mockk(relaxed = true)

        aggregationEngine =
            AggregationEngine(
                heartRateSampleDao = heartRateSampleDao,
                sleepSessionDao = sleepSessionDao,
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

        coEvery { activeCaloriesBurnedDao.getRecordsForAggregation(any(), any()) } returns emptyList()
        coEvery { distanceRecordDao.getRecordsForAggregation(any(), any()) } returns emptyList()
        coEvery { totalCaloriesBurnedDao.getRecordsForAggregation(any(), any()) } returns emptyList()
        coEvery { nutritionRecordDao.getRecordsForAggregation(any(), any()) } returns emptyList()
        coEvery { oxygenSaturationDao.getRecordsForAggregation(any(), any()) } returns emptyList()
        coEvery { hrvRecordDao.getRecordsForAggregation(any(), any()) } returns emptyList()
        coEvery { activeCaloriesBurnedDao.getOldestDate() } returns null
        coEvery { activeCaloriesBurnedDao.getLatestDate() } returns null
        coEvery { distanceRecordDao.getOldestStartTime() } returns null
        coEvery { distanceRecordDao.getLatestEndTime() } returns null
        coEvery { totalCaloriesBurnedDao.getOldestStartTime() } returns null
        coEvery { totalCaloriesBurnedDao.getLatestEndTime() } returns null
        coEvery { nutritionRecordDao.getOldestStartTime() } returns null
        coEvery { nutritionRecordDao.getLatestEndTime() } returns null
        coEvery { oxygenSaturationDao.getOldestTimestamp() } returns null
        coEvery { oxygenSaturationDao.getLatestTimestamp() } returns null
        coEvery { hrvRecordDao.getOldestTimestamp() } returns null
        coEvery { hrvRecordDao.getLatestTimestamp() } returns null
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @Suppress("LongMethod")
    fun updateAggregatesForDate_computesExpectedDailyValues() =
        runTest {
            val zoneId = ZoneId.systemDefault()
            val date = LocalDate.of(2026, 2, 8)
            val dayStart = date.atStartOfDay(zoneId).toInstant()

            val heartRateSamples =
                listOf(
                    HeartRateSample(
                        healthConnectId = "hr-1",
                        timestamp = dayStart.plusSeconds(3600),
                        bpm = 60,
                        source = "test",
                        syncedAt = dayStart.plusSeconds(7200),
                    ),
                    HeartRateSample(
                        healthConnectId = "hr-2",
                        timestamp = dayStart.plusSeconds(5400),
                        bpm = 80,
                        source = "test",
                        syncedAt = dayStart.plusSeconds(7300),
                    ),
                )
            val stepsRecords =
                listOf(
                    StepsRecord(
                        healthConnectId = "steps-1",
                        startTime = dayStart.plusSeconds(1800),
                        endTime = dayStart.plusSeconds(2400),
                        count = 100,
                        source = "test",
                        syncedAt = dayStart.plusSeconds(2500),
                    ),
                    StepsRecord(
                        healthConnectId = "steps-2",
                        startTime = dayStart.plusSeconds(3600),
                        endTime = dayStart.plusSeconds(4200),
                        count = 200,
                        source = "test",
                        syncedAt = dayStart.plusSeconds(4300),
                    ),
                )
            val sleepSessions =
                listOf(
                    SleepSession(
                        healthConnectId = "sleep-1",
                        startTime = dayStart.plusSeconds(60),
                        endTime = dayStart.plusSeconds(28_860),
                        durationMs = 28_800_000L,
                        source = "test",
                        syncedAt = dayStart.plusSeconds(29_000),
                    ),
                )
            val restingHeartRates =
                listOf(
                    RestingHeartRate(
                        healthConnectId = "rhr-1",
                        date = date,
                        bpm = 55,
                        source = "test",
                        syncedAt = dayStart.plusSeconds(100),
                    ),
                    RestingHeartRate(
                        healthConnectId = "rhr-2",
                        date = date,
                        bpm = 57,
                        source = "test",
                        syncedAt = dayStart.plusSeconds(200),
                    ),
                )

            coEvery { heartRateSampleDao.getSamplesForAggregation(any(), any()) } returns heartRateSamples
            coEvery { stepsRecordDao.getRecordsForAggregation(any(), any()) } returns stepsRecords
            coEvery { sleepSessionDao.getSessionsForAggregation(any(), any()) } returns sleepSessions
            coEvery { restingHeartRateDao.getRecordsForAggregation(date, date) } returns restingHeartRates

            val inserted = mutableListOf<DailyAggregate>()
            coEvery { dailyAggregateDao.upsert(capture(inserted)) } returns Unit

            aggregationEngine.updateAggregatesForDate(date)

            coVerify(exactly = 1) { dailyAggregateDao.deleteForDateAndType(RecordType.STEPS, date) }
            coVerify(exactly = 1) { dailyAggregateDao.deleteForDateAndType(RecordType.SLEEP, date) }
            coVerify(exactly = 1) { dailyAggregateDao.deleteForDateAndType(RecordType.HEART_RATE, date) }
            coVerify(exactly = 1) { dailyAggregateDao.deleteForDateAndType(RecordType.RESTING_HR, date) }

            assertThat(inserted).hasSize(4)

            val stepsAggregate = inserted.first { it.recordType == RecordType.STEPS }
            assertThat(stepsAggregate.value).isEqualTo(300.0)
            assertThat(stepsAggregate.count).isEqualTo(2)
            assertThat(stepsAggregate.min).isEqualTo(100.0)
            assertThat(stepsAggregate.max).isEqualTo(200.0)
            assertThat(stepsAggregate.avg).isEqualTo(150.0)

            val sleepAggregate = inserted.first { it.recordType == RecordType.SLEEP }
            assertThat(sleepAggregate.value).isEqualTo(28_800_000.0)
            assertThat(sleepAggregate.count).isEqualTo(1)
            assertThat(sleepAggregate.min).isEqualTo(dayStart.plusSeconds(60).toEpochMilli().toDouble())
            assertThat(sleepAggregate.max).isEqualTo(dayStart.plusSeconds(28_860).toEpochMilli().toDouble())
            assertThat(sleepAggregate.avg).isEqualTo(28_800_000.0)

            val heartRateAggregate = inserted.first { it.recordType == RecordType.HEART_RATE }
            assertThat(heartRateAggregate.value).isEqualTo(70.0)
            assertThat(heartRateAggregate.count).isEqualTo(2)
            assertThat(heartRateAggregate.min).isEqualTo(60.0)
            assertThat(heartRateAggregate.max).isEqualTo(80.0)
            assertThat(heartRateAggregate.avg).isEqualTo(70.0)

            val restingHeartRateAggregate = inserted.first { it.recordType == RecordType.RESTING_HR }
            assertThat(restingHeartRateAggregate.value).isEqualTo(57.0)
            assertThat(restingHeartRateAggregate.count).isEqualTo(2)
            assertThat(restingHeartRateAggregate.min).isEqualTo(55.0)
            assertThat(restingHeartRateAggregate.max).isEqualTo(57.0)
            assertThat(restingHeartRateAggregate.avg).isEqualTo(56.0)
        }

    @Test
    fun rebuildAggregates_rebuildsOnlyWithinRequestedRange() =
        runTest {
            val firstDate = LocalDate.of(2026, 2, 10)
            val secondDate = firstDate.plusDays(1)
            val firstDateInstant = firstDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
            val secondDateInstant = secondDate.atStartOfDay(ZoneId.systemDefault()).toInstant()

            coEvery { heartRateSampleDao.getSamplesForAggregation(any(), any()) } returns emptyList()
            coEvery { stepsRecordDao.getRecordsForAggregation(any(), any()) } returns emptyList()
            coEvery { sleepSessionDao.getSessionsForAggregation(any(), any()) } returns emptyList()
            coEvery { restingHeartRateDao.getRecordsForAggregation(firstDate, firstDate) } returns
                listOf(
                    RestingHeartRate(
                        healthConnectId = "range-rhr-1",
                        date = firstDate,
                        bpm = 52,
                        source = "test",
                        syncedAt = firstDateInstant.plusSeconds(1),
                    ),
                )
            coEvery { restingHeartRateDao.getRecordsForAggregation(secondDate, secondDate) } returns
                listOf(
                    RestingHeartRate(
                        healthConnectId = "range-rhr-2",
                        date = secondDate,
                        bpm = 54,
                        source = "test",
                        syncedAt = secondDateInstant.plusSeconds(1),
                    ),
                )

            aggregationEngine.rebuildAggregates(firstDate..secondDate)

            coVerify(exactly = 0) { dailyAggregateDao.deleteAll() }
            coVerify(exactly = 2) { dailyAggregateDao.deleteForDateAndType(RecordType.RESTING_HR, any()) }
            coVerify(exactly = 2) { dailyAggregateDao.upsert(any<DailyAggregate>()) }
        }

    @Test
    fun rebuildAllAggregates_clearsTableWhenNoSourceDataExists() =
        runTest {
            coEvery { heartRateSampleDao.getOldestTimestamp() } returns null
            coEvery { heartRateSampleDao.getLatestTimestamp() } returns null
            coEvery { sleepSessionDao.getOldestStartTime() } returns null
            coEvery { sleepSessionDao.getLatestEndTime() } returns null
            coEvery { stepsRecordDao.getOldestStartTime() } returns null
            coEvery { stepsRecordDao.getLatestEndTime() } returns null
            coEvery { restingHeartRateDao.getOldestDate() } returns null
            coEvery { restingHeartRateDao.getLatestDate() } returns null

            aggregationEngine.rebuildAllAggregates()

            coVerify(exactly = 1) { dailyAggregateDao.deleteAll() }
            coVerify(exactly = 0) { dailyAggregateDao.upsert(any<DailyAggregate>()) }
            coVerify(exactly = 0) { dailyAggregateDao.deleteForDateAndType(any(), any()) }
        }

    @Test
    fun rebuildAllAggregates_rebuildsAcrossDetectedDateRange() =
        runTest {
            val firstDate = LocalDate.of(2026, 2, 1)
            val secondDate = firstDate.plusDays(1)
            val firstDateInstant = firstDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
            val secondDateInstant = secondDate.atStartOfDay(ZoneId.systemDefault()).toInstant()

            coEvery { heartRateSampleDao.getOldestTimestamp() } returns null
            coEvery { heartRateSampleDao.getLatestTimestamp() } returns null
            coEvery { sleepSessionDao.getOldestStartTime() } returns null
            coEvery { sleepSessionDao.getLatestEndTime() } returns null
            coEvery { stepsRecordDao.getOldestStartTime() } returns null
            coEvery { stepsRecordDao.getLatestEndTime() } returns null
            coEvery { restingHeartRateDao.getOldestDate() } returns firstDate
            coEvery { restingHeartRateDao.getLatestDate() } returns secondDate

            coEvery { heartRateSampleDao.getSamplesForAggregation(any(), any()) } returns emptyList()
            coEvery { stepsRecordDao.getRecordsForAggregation(any(), any()) } returns emptyList()
            coEvery { sleepSessionDao.getSessionsForAggregation(any(), any()) } returns emptyList()
            coEvery { restingHeartRateDao.getRecordsForAggregation(firstDate, firstDate) } returns
                listOf(
                    RestingHeartRate(
                        healthConnectId = "rhr-a",
                        date = firstDate,
                        bpm = 51,
                        source = "test",
                        syncedAt = firstDateInstant.plusSeconds(1),
                    ),
                )
            coEvery { restingHeartRateDao.getRecordsForAggregation(secondDate, secondDate) } returns
                listOf(
                    RestingHeartRate(
                        healthConnectId = "rhr-b",
                        date = secondDate,
                        bpm = 53,
                        source = "test",
                        syncedAt = secondDateInstant.plusSeconds(1),
                    ),
                )

            aggregationEngine.rebuildAllAggregates()

            coVerify(exactly = 1) { dailyAggregateDao.deleteAll() }
            coVerify(exactly = 2) { dailyAggregateDao.deleteForDateAndType(RecordType.RESTING_HR, any()) }
            coVerify(exactly = 2) { dailyAggregateDao.upsert(any<DailyAggregate>()) }
        }
}

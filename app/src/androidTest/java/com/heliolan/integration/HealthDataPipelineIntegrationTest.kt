package com.heliolan.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.heliolan.data.database.HelioLanDatabase
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.repository.HealthRepositoryImpl
import com.heliolan.data.util.RecordType
import com.heliolan.healthconnect.reader.HealthConnectReader
import com.heliolan.healthconnect.reader.ReadResult
import com.heliolan.sync.engine.AggregationEngine
import com.heliolan.sync.engine.SyncEngine
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.model.SyncTrigger
import com.heliolan.sync.model.SyncWindowMode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class HealthDataPipelineIntegrationTest {
    private lateinit var database: HelioLanDatabase
    private lateinit var repository: HealthRepositoryImpl
    private lateinit var aggregationEngine: AggregationEngine
    private lateinit var syncEngine: SyncEngine
    private lateinit var healthConnectReader: HealthConnectReader

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HelioLanDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        repository =
            HealthRepositoryImpl(
                heartRateSampleDao = database.heartRateSampleDao(),
                sleepSessionDao = database.sleepSessionDao(),
                sleepStageDao = database.sleepStageDao(),
                stepsRecordDao = database.stepsRecordDao(),
                restingHeartRateDao = database.restingHeartRateDao(),
                activeCaloriesBurnedDao = database.activeCaloriesBurnedDao(),
                distanceRecordDao = database.distanceRecordDao(),
                totalCaloriesBurnedDao = database.totalCaloriesBurnedDao(),
                nutritionRecordDao = database.nutritionRecordDao(),
                oxygenSaturationDao = database.oxygenSaturationDao(),
                hrvRecordDao = database.hrvRecordDao(),
                dailyAggregateDao = database.dailyAggregateDao(),
            )

        aggregationEngine =
            AggregationEngine(
                heartRateSampleDao = database.heartRateSampleDao(),
                sleepSessionDao = database.sleepSessionDao(),
                stepsRecordDao = database.stepsRecordDao(),
                restingHeartRateDao = database.restingHeartRateDao(),
                activeCaloriesBurnedDao = database.activeCaloriesBurnedDao(),
                distanceRecordDao = database.distanceRecordDao(),
                totalCaloriesBurnedDao = database.totalCaloriesBurnedDao(),
                nutritionRecordDao = database.nutritionRecordDao(),
                oxygenSaturationDao = database.oxygenSaturationDao(),
                hrvRecordDao = database.hrvRecordDao(),
                dailyAggregateDao = database.dailyAggregateDao(),
            )

        healthConnectReader = mockk()
        syncEngine =
            SyncEngine(
                healthConnectReader = healthConnectReader,
                aggregationEngine = aggregationEngine,
                heartRateSampleDao = database.heartRateSampleDao(),
                sleepSessionDao = database.sleepSessionDao(),
                sleepStageDao = database.sleepStageDao(),
                stepsRecordDao = database.stepsRecordDao(),
                restingHeartRateDao = database.restingHeartRateDao(),
                activeCaloriesBurnedDao = database.activeCaloriesBurnedDao(),
                distanceRecordDao = database.distanceRecordDao(),
                totalCaloriesBurnedDao = database.totalCaloriesBurnedDao(),
                nutritionRecordDao = database.nutritionRecordDao(),
                oxygenSaturationDao = database.oxygenSaturationDao(),
                hrvRecordDao = database.hrvRecordDao(),
                syncCursorDao = database.syncCursorDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun syncEngine_syncAll_storesReaderResults() =
        runBlocking {
            val now = Instant.now()
            val heartRate =
                listOf(
                    HeartRateSample(
                        healthConnectId = "hr-1",
                        timestamp = now,
                        bpm = 78,
                        source = "test",
                        syncedAt = now,
                    ),
                )
            val steps =
                listOf(
                    StepsRecord(
                        healthConnectId = "steps-1",
                        startTime = now.minus(15, ChronoUnit.MINUTES),
                        endTime = now,
                        count = 420,
                        source = "test",
                        syncedAt = now,
                    ),
                )

            coEvery { healthConnectReader.readHeartRate(any(), any()) } returns ReadResult.Success(heartRate)
            coEvery { healthConnectReader.readSleep(any(), any()) } returns ReadResult.Success(emptyList())
            coEvery { healthConnectReader.readSteps(any(), any()) } returns ReadResult.Success(steps)
            coEvery { healthConnectReader.readRestingHeartRate(any(), any()) } returns ReadResult.Success(emptyList())

            val result = syncEngine.syncAll(windowMode = SyncWindowMode.LAST_30_DAYS, trigger = SyncTrigger.USER)
            assertThat(result).isInstanceOf(SyncResult.Success::class.java)

            val storedHeartRate =
                database
                    .heartRateSampleDao()
                    .getByDateRange(
                        startTime = now.minus(1, ChronoUnit.DAYS),
                        endTime = now.plus(1, ChronoUnit.DAYS),
                    ).first()
            val storedSteps =
                database
                    .stepsRecordDao()
                    .getByDateRange(
                        startTime = now.minus(1, ChronoUnit.DAYS),
                        endTime = now.plus(1, ChronoUnit.DAYS),
                    ).first()

            assertThat(storedHeartRate).hasSize(1)
            assertThat(storedHeartRate.first().bpm).isEqualTo(78)
            assertThat(storedSteps).hasSize(1)
            assertThat(storedSteps.first().count).isEqualTo(420)
        }

    @Test
    fun aggregationEngine_updatesStepsDailyAggregate() =
        runBlocking {
            val today = LocalDate.now(ZoneId.systemDefault())
            val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant()

            database.stepsRecordDao().upsert(
                listOf(
                    StepsRecord(
                        healthConnectId = "steps-a",
                        startTime = start.plus(1, ChronoUnit.HOURS),
                        endTime = start.plus(2, ChronoUnit.HOURS),
                        count = 1000,
                        source = "test",
                        syncedAt = Instant.now(),
                    ),
                    StepsRecord(
                        healthConnectId = "steps-b",
                        startTime = start.plus(3, ChronoUnit.HOURS),
                        endTime = start.plus(4, ChronoUnit.HOURS),
                        count = 1500,
                        source = "test",
                        syncedAt = Instant.now(),
                    ),
                ),
            )

            aggregationEngine.updateAggregatesForDate(today)

            val aggregates =
                repository
                    .getDailyAggregates(
                        recordType = RecordType.STEPS,
                        startDate = today,
                        endDate = today,
                    ).first()

            assertThat(aggregates).hasSize(1)
            assertThat(aggregates.first().value).isEqualTo(2500.0)
        }

    @Test
    fun repository_getLatestHeartRate_returnsMostRecentSample() =
        runBlocking {
            val now = Instant.now()
            database.heartRateSampleDao().upsert(
                listOf(
                    HeartRateSample(
                        healthConnectId = "hr-old",
                        timestamp = now.minus(2, ChronoUnit.HOURS),
                        bpm = 70,
                        source = "test",
                        syncedAt = now,
                    ),
                    HeartRateSample(
                        healthConnectId = "hr-new",
                        timestamp = now,
                        bpm = 82,
                        source = "test",
                        syncedAt = now,
                    ),
                ),
            )

            val latest = repository.getLatestHeartRate().first()
            assertThat(latest).isNotNull()
            assertThat(latest?.healthConnectId).isEqualTo("hr-new")
        }
}

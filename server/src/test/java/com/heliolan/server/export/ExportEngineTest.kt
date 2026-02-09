package com.heliolan.server.export

import com.google.common.truth.Truth.assertThat
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.repository.HealthRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

@OptIn(ExperimentalCoroutinesApi::class)
class ExportEngineTest {
    private lateinit var healthRepository: HealthRepository
    private lateinit var exportEngine: ExportEngine
    private lateinit var mutableClock: MutableClock
    private val testZoneId: ZoneId = ZoneOffset.UTC
    private val dateRange = LocalDate.of(2026, 2, 1)..LocalDate.of(2026, 2, 10)
    private val exportDirectory = createTempDirectory("export-engine-test").toFile()

    @Before
    fun setUp() {
        healthRepository = mockk(relaxed = true)
        mutableClock = MutableClock(Instant.parse("2026-02-10T12:00:00Z"), testZoneId)
        exportEngine =
            ExportEngine(
                healthRepository = healthRepository,
                outputDirectory = exportDirectory,
                clock = mutableClock,
                zoneId = testZoneId,
            )
    }

    @After
    fun tearDown() {
        exportDirectory.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun exportCsv_heartRate_writesHeaderAndKnownRows() =
        runTest {
            val heartRateSamples =
                listOf(
                    HeartRateSample(
                        healthConnectId = "hr-1",
                        timestamp = Instant.parse("2026-02-08T01:02:03Z"),
                        bpm = 70,
                        source = "zepp,app",
                        syncedAt = Instant.parse("2026-02-08T01:03:03Z"),
                    ),
                )

            stubHeartRateSamples(heartRateSamples)

            val csvFile = exportEngine.exportCsv(ExportMetricType.HEART_RATE, dateRange)

            assertThat(csvFile.exists()).isTrue()
            val lines = csvFile.readLines(StandardCharsets.UTF_8)
            assertThat(lines).hasSize(2)
            assertThat(lines[0]).isEqualTo("health_connect_id,timestamp,bpm,source,synced_at")
            assertThat(lines[1]).isEqualTo("hr-1,2026-02-08T01:02:03Z,70,\"zepp,app\",2026-02-08T01:03:03Z")
        }

    @Test
    fun exportAll_writesZipWithOneCsvPerMetricType() =
        runTest {
            stubHeartRateSamples(
                listOf(
                    HeartRateSample(
                        healthConnectId = "hr-zip",
                        timestamp = Instant.parse("2026-02-08T10:00:00Z"),
                        bpm = 65,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-08T10:01:00Z"),
                    ),
                ),
            )
            stubSleepSessions(
                listOf(
                    SleepSession(
                        healthConnectId = "sleep-zip",
                        startTime = Instant.parse("2026-02-07T22:00:00Z"),
                        endTime = Instant.parse("2026-02-08T06:00:00Z"),
                        durationMs = 28_800_000L,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-08T06:01:00Z"),
                    ),
                ),
            )
            stubStepsRecords(
                listOf(
                    StepsRecord(
                        healthConnectId = "steps-zip",
                        startTime = Instant.parse("2026-02-08T08:00:00Z"),
                        endTime = Instant.parse("2026-02-08T08:15:00Z"),
                        count = 120,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-08T08:16:00Z"),
                    ),
                ),
            )
            stubRestingHeartRateRecords(
                listOf(
                    RestingHeartRate(
                        healthConnectId = "rhr-zip",
                        date = LocalDate.of(2026, 2, 8),
                        bpm = 54,
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-08T09:00:00Z"),
                    ),
                ),
            )

            val zipFile = exportEngine.exportAll(dateRange)

            assertThat(zipFile.exists()).isTrue()

            ZipFile(zipFile).use { zip ->
                val entryNames = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    entryNames += entries.nextElement().name
                }

                assertThat(entryNames)
                    .containsExactly(
                        "heart_rate.csv",
                        "sleep.csv",
                        "steps.csv",
                        "resting_heart_rate.csv",
                    )

                val heartRateEntry = zip.getEntry("heart_rate.csv")
                assertThat(heartRateEntry).isNotNull()
                val heartRateLines =
                    zip
                        .getInputStream(heartRateEntry)
                        .bufferedReader(StandardCharsets.UTF_8)
                        .readLines()
                assertThat(heartRateLines.first()).isEqualTo("health_connect_id,timestamp,bpm,source,synced_at")
            }
        }

    @Test
    fun exportCsv_appliesRateLimitWithinThirtySeconds() =
        runTest {
            stubStepsRecords(emptyList())

            val firstFile = exportEngine.exportCsv(ExportMetricType.STEPS, dateRange)

            assertThat(firstFile.exists()).isTrue()

            var thrown: Throwable? = null
            try {
                exportEngine.exportCsv(ExportMetricType.STEPS, dateRange)
            } catch (error: Throwable) {
                thrown = error
            }

            assertThat(thrown).isInstanceOf(ExportRateLimitException::class.java)
            val rateLimitError = thrown as ExportRateLimitException
            assertThat(rateLimitError.retryAfterSeconds).isEqualTo(30L)

            mutableClock.advanceSeconds(31)

            val secondFile = exportEngine.exportCsv(ExportMetricType.STEPS, dateRange)
            assertThat(secondFile.exists()).isTrue()
        }

    @Test
    fun exportCsv_readsLargeDatasetsInPages() =
        runTest {
            val records =
                (0 until 1001).map { index ->
                    HeartRateSample(
                        healthConnectId = "hr-$index",
                        timestamp = Instant.parse("2026-02-08T00:00:00Z").plusSeconds(index.toLong()),
                        bpm = 60 + (index % 10),
                        source = "zepp",
                        syncedAt = Instant.parse("2026-02-08T00:10:00Z").plusSeconds(index.toLong()),
                    )
                }
            stubHeartRateSamples(records)

            val csvFile = exportEngine.exportCsv(ExportMetricType.HEART_RATE, dateRange)

            assertThat(csvFile.exists()).isTrue()
            verify(exactly = 2) {
                healthRepository.getHeartRateSamples(any(), any(), 1000, any())
            }
        }

    private fun stubHeartRateSamples(records: List<HeartRateSample>) {
        every {
            healthRepository.getHeartRateSamples(any(), any(), any(), any())
        } answers {
            val limit = arg<Int>(2)
            val offset = arg<Int>(3)
            flowOf(records.drop(offset).take(limit))
        }
    }

    private fun stubSleepSessions(records: List<SleepSession>) {
        every {
            healthRepository.getSleepSessions(any(), any(), any(), any())
        } answers {
            val limit = arg<Int>(2)
            val offset = arg<Int>(3)
            flowOf(records.drop(offset).take(limit))
        }
    }

    private fun stubStepsRecords(records: List<StepsRecord>) {
        every {
            healthRepository.getStepsRecords(any(), any(), any(), any())
        } answers {
            val limit = arg<Int>(2)
            val offset = arg<Int>(3)
            flowOf(records.drop(offset).take(limit))
        }
    }

    private fun stubRestingHeartRateRecords(records: List<RestingHeartRate>) {
        every {
            healthRepository.getRestingHeartRate(any(), any(), any(), any())
        } answers {
            val limit = arg<Int>(2)
            val offset = arg<Int>(3)
            flowOf(records.drop(offset).take(limit))
        }
    }
}

private class MutableClock(
    private var now: Instant,
    private val zoneId: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

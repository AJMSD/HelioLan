package com.heliolan.performance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.heliolan.data.database.HelioLanDatabase
import com.heliolan.data.entity.HeartRateSample
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class PerformanceValidationTest {
    private lateinit var database: HelioLanDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database =
            Room
                .inMemoryDatabaseBuilder(context, HelioLanDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun heartRateRangeQuery_completesWithinTargetBudget() =
        runBlocking {
            val now = Instant.now()
            val samples =
                (1..1500).map { index ->
                    HeartRateSample(
                        healthConnectId = "perf-$index",
                        timestamp = now.minus(index.toLong(), ChronoUnit.MINUTES),
                        bpm = 60 + (index % 40),
                        source = "perf-test",
                        syncedAt = now,
                    )
                }

            database.heartRateSampleDao().upsert(samples)

            val elapsedMs =
                measureTimeMillis {
                    val result =
                        database.heartRateSampleDao().getByDateRange(
                            startTime = now.minus(500, ChronoUnit.MINUTES),
                            endTime = now,
                            limit = 500,
                            offset = 0,
                        ).first()
                    assertThat(result).isNotEmpty()
                }

            assertThat(elapsedMs).isLessThan(150L)
        }

    @Test
    fun latestHeartRateLookup_remainsFastWithLargeDataset() =
        runBlocking {
            val now = Instant.now()
            val samples =
                (1..5000).map { index ->
                    HeartRateSample(
                        healthConnectId = "latest-$index",
                        timestamp = now.minus(index.toLong(), ChronoUnit.SECONDS),
                        bpm = 55 + (index % 35),
                        source = "perf-test",
                        syncedAt = now,
                    )
                }

            database.heartRateSampleDao().upsert(samples)

            val elapsedMs =
                measureTimeMillis {
                    val latest = database.heartRateSampleDao().getLatest().first()
                    assertThat(latest).isNotNull()
                }

            assertThat(elapsedMs).isLessThan(50L)
        }
}

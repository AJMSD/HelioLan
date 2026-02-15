package com.heliolan.sync.scheduler

import com.google.common.truth.Truth.assertThat
import com.heliolan.sync.engine.AggregationEngine
import com.heliolan.sync.engine.SyncEngine
import com.heliolan.sync.model.SyncError
import com.heliolan.sync.model.SyncErrorCode
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.model.SyncSummary
import com.heliolan.sync.model.SyncTrigger
import com.heliolan.sync.model.SyncWindowMode
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

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    private lateinit var syncEngine: SyncEngine
    private lateinit var aggregationEngine: AggregationEngine
    private lateinit var scheduler: SyncScheduler

    @Before
    fun setUp() {
        syncEngine = mockk(relaxed = true)
        aggregationEngine = mockk(relaxed = true)
        scheduler =
            SyncScheduler(
                syncEngine = syncEngine,
                aggregationEngine = aggregationEngine,
            )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun syncNow_delegatesToSyncEngine() =
        runTest {
            val now = Instant.now()
            val expected =
                SyncResult.Success(
                    SyncSummary(
                        startedAt = now,
                        completedAt = now,
                        records = emptyList(),
                    ),
                )
            coEvery {
                syncEngine.syncAll(
                    windowMode = SyncWindowMode.LAST_30_DAYS,
                    trigger = SyncTrigger.USER,
                )
            } returns expected

            val result = scheduler.syncNow()

            assertThat(result).isEqualTo(expected)
            coVerify(exactly = 1) {
                syncEngine.syncAll(
                    windowMode = SyncWindowMode.LAST_30_DAYS,
                    trigger = SyncTrigger.USER,
                )
            }
        }

    @Test
    fun syncAutomatic_delegatesToSyncEngineWithAutomaticTrigger() =
        runTest {
            val now = Instant.now()
            val expected =
                SyncResult.Success(
                    SyncSummary(
                        startedAt = now,
                        completedAt = now,
                        records = emptyList(),
                    ),
                )
            coEvery {
                syncEngine.syncAll(
                    windowMode = SyncWindowMode.LAST_30_DAYS,
                    trigger = SyncTrigger.AUTOMATIC,
                )
            } returns expected

            val result = scheduler.syncAutomatic()

            assertThat(result).isEqualTo(expected)
            coVerify(exactly = 1) {
                syncEngine.syncAll(
                    windowMode = SyncWindowMode.LAST_30_DAYS,
                    trigger = SyncTrigger.AUTOMATIC,
                )
            }
        }

    @Test
    fun syncAutomatic_retriesOnceForTransientFailure() =
        runTest {
            val now = Instant.now()
            val success =
                SyncResult.Success(
                    SyncSummary(
                        startedAt = now,
                        completedAt = now,
                        records = emptyList(),
                    ),
                )
            val transientFailure =
                SyncResult.Failure(
                    SyncError(
                        recordType = null,
                        code = SyncErrorCode.READ_FAILED,
                        message = "Temporary read issue",
                    ),
                )
            coEvery {
                syncEngine.syncAll(
                    windowMode = SyncWindowMode.LAST_30_DAYS,
                    trigger = SyncTrigger.AUTOMATIC,
                )
            } returnsMany listOf(transientFailure, success)

            val result = scheduler.syncAutomatic()

            assertThat(result).isEqualTo(success)
            coVerify(exactly = 2) {
                syncEngine.syncAll(
                    windowMode = SyncWindowMode.LAST_30_DAYS,
                    trigger = SyncTrigger.AUTOMATIC,
                )
            }
        }

    @Test
    fun syncAutomatic_doesNotRetryForNonTransientFailure() =
        runTest {
            val nonTransientFailure =
                SyncResult.Failure(
                    SyncError(
                        recordType = null,
                        code = SyncErrorCode.PERMISSION_DENIED,
                        message = "Permission denied",
                    ),
                )
            coEvery {
                syncEngine.syncAll(
                    windowMode = SyncWindowMode.LAST_30_DAYS,
                    trigger = SyncTrigger.AUTOMATIC,
                )
            } returns nonTransientFailure

            val result = scheduler.syncAutomatic()

            assertThat(result).isEqualTo(nonTransientFailure)
            coVerify(exactly = 1) {
                syncEngine.syncAll(
                    windowMode = SyncWindowMode.LAST_30_DAYS,
                    trigger = SyncTrigger.AUTOMATIC,
                )
            }
        }

    @Test
    fun rebuildAllAggregates_delegatesToAggregationEngine() =
        runTest {
            coEvery { aggregationEngine.rebuildAllAggregates() } returns Unit

            scheduler.rebuildAllAggregates()

            coVerify(exactly = 1) { aggregationEngine.rebuildAllAggregates() }
        }
}

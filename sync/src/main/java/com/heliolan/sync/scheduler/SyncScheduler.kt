package com.heliolan.sync.scheduler

import com.heliolan.sync.engine.AggregationEngine
import com.heliolan.sync.engine.SyncEngine
import com.heliolan.sync.model.SyncErrorCode
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.model.SyncTrigger
import com.heliolan.sync.model.SyncWindowMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime scheduler for user-triggered and lifecycle-bound periodic sync.
 */
@Singleton
class SyncScheduler
    @Inject
    constructor(
        private val syncEngine: SyncEngine,
        private val aggregationEngine: AggregationEngine,
    ) {
        private companion object {
            const val AUTOMATIC_SYNC_RETRY_DELAY_MILLIS = 5_000L
        }

        private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var periodicSyncJob: Job? = null

        fun onAppForeground() {
            triggerAutomaticSync()
        }

        fun triggerSyncNow(windowMode: SyncWindowMode = SyncWindowMode.LAST_30_DAYS) {
            schedulerScope.launch {
                syncEngine.syncAll(windowMode = windowMode, trigger = SyncTrigger.USER)
            }
        }

        fun triggerAutomaticSync(windowMode: SyncWindowMode = SyncWindowMode.LAST_30_DAYS) {
            schedulerScope.launch {
                syncAutomaticWithRetry(windowMode = windowMode)
            }
        }

        suspend fun syncNow(windowMode: SyncWindowMode = SyncWindowMode.LAST_30_DAYS): SyncResult {
            return syncEngine.syncAll(windowMode = windowMode, trigger = SyncTrigger.USER)
        }

        suspend fun syncAutomatic(windowMode: SyncWindowMode = SyncWindowMode.LAST_30_DAYS): SyncResult {
            return syncAutomaticWithRetry(windowMode = windowMode)
        }

        suspend fun rebuildAllAggregates() {
            aggregationEngine.rebuildAllAggregates()
        }

        fun startPeriodicSync() {
            if (periodicSyncJob?.isActive == true) return
            periodicSyncJob =
                schedulerScope.launch {
                    while (isActive) {
                        delay(syncEngine.config.periodicSyncMinutes * 60_000L)
                        syncAutomatic()
                    }
                }
        }

        fun stopPeriodicSync() {
            periodicSyncJob?.cancel()
            periodicSyncJob = null
        }

        private suspend fun syncAutomaticWithRetry(windowMode: SyncWindowMode): SyncResult {
            val firstAttempt = syncEngine.syncAll(windowMode = windowMode, trigger = SyncTrigger.AUTOMATIC)
            if (firstAttempt !is SyncResult.Failure || !firstAttempt.isRetryableAutomaticFailure()) {
                return firstAttempt
            }
            delay(AUTOMATIC_SYNC_RETRY_DELAY_MILLIS)
            return syncEngine.syncAll(windowMode = windowMode, trigger = SyncTrigger.AUTOMATIC)
        }

        private fun SyncResult.Failure.isRetryableAutomaticFailure(): Boolean {
            return when (error.code) {
                SyncErrorCode.HEALTH_CONNECT_UNAVAILABLE,
                SyncErrorCode.READ_FAILED,
                SyncErrorCode.TIMEOUT,
                -> true
                else -> false
            }
        }
    }

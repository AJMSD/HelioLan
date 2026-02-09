package com.heliolan.sync.scheduler

import com.heliolan.sync.engine.AggregationEngine
import com.heliolan.sync.engine.SyncEngine
import com.heliolan.sync.model.SyncResult
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
        private val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var periodicSyncJob: Job? = null

        fun onAppForeground() {
            triggerSyncNow()
        }

        fun triggerSyncNow(windowMode: SyncWindowMode = SyncWindowMode.LAST_30_DAYS) {
            schedulerScope.launch {
                syncEngine.syncAll(windowMode)
            }
        }

        suspend fun syncNow(windowMode: SyncWindowMode = SyncWindowMode.LAST_30_DAYS): SyncResult {
            return syncEngine.syncAll(windowMode)
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
                        syncEngine.syncAll()
                    }
                }
        }

        fun stopPeriodicSync() {
            periodicSyncJob?.cancel()
            periodicSyncJob = null
        }
    }

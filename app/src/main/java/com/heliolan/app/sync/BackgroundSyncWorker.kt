package com.heliolan.app.sync

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.heliolan.sync.model.SyncErrorCode
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.scheduler.SyncScheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Runs periodic background sync when the app process is not in the foreground.
 */
class BackgroundSyncWorker
    constructor(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {
        private val syncScheduler: SyncScheduler by lazy {
            val entryPoint =
                EntryPointAccessors.fromApplication(
                    applicationContext,
                    BackgroundSyncWorkerEntryPoint::class.java,
                )
            entryPoint.syncScheduler()
        }

        override suspend fun doWork(): Result {
            if (isAppInForeground()) {
                // Foreground sync is handled by lifecycle-driven scheduler.
                return Result.success()
            }
            return when (val result = syncScheduler.syncAutomatic()) {
                is SyncResult.Success -> Result.success()
                is SyncResult.PartialSuccess -> Result.success()
                is SyncResult.Failure -> {
                    if (result.error.code == SyncErrorCode.DEBOUNCED) {
                        Result.success()
                    } else {
                        Result.retry()
                    }
                }
            }
        }

        private fun isAppInForeground(): Boolean {
            return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundSyncWorkerEntryPoint {
    fun syncScheduler(): SyncScheduler
}

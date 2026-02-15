package com.heliolan.app.sync

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.heliolan.healthconnect.model.HealthConnectAvailability
import com.heliolan.healthconnect.permission.PermissionManager
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
        private companion object {
            const val MAX_TRANSIENT_RETRIES = 3
            const val MAX_WRITE_FAILURE_RETRIES = 2
        }

        private val entryPoint: BackgroundSyncWorkerEntryPoint by lazy {
            EntryPointAccessors.fromApplication(
                applicationContext,
                BackgroundSyncWorkerEntryPoint::class.java,
            )
        }

        private val syncScheduler: SyncScheduler by lazy {
            entryPoint.syncScheduler()
        }

        private val permissionManager: PermissionManager by lazy {
            entryPoint.permissionManager()
        }

        override suspend fun doWork(): Result {
            if (isAppInForeground()) {
                // Foreground sync is handled by lifecycle-driven scheduler.
                return Result.success()
            }
            if (shouldSkipDueToPreflight()) {
                return Result.success()
            }

            return when (val result = syncScheduler.syncAutomatic()) {
                is SyncResult.Success -> Result.success()
                is SyncResult.PartialSuccess -> Result.success()
                is SyncResult.Failure -> resolveFailureResult(result.error.code)
            }
        }

        private fun isAppInForeground(): Boolean {
            return ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(
                Lifecycle.State.STARTED,
            )
        }

        private suspend fun shouldSkipDueToPreflight(): Boolean {
            val availability = permissionManager.checkAvailability()
            if (availability !is HealthConnectAvailability.Available) {
                return true
            }
            val permissionState = permissionManager.getPermissionState()
            return !permissionState.hasAnyPermission()
        }

        private fun resolveFailureResult(errorCode: SyncErrorCode): Result {
            return when (errorCode) {
                SyncErrorCode.DEBOUNCED,
                SyncErrorCode.PERMISSION_DENIED,
                SyncErrorCode.UNSUPPORTED_RECORD_TYPE,
                -> Result.success()

                SyncErrorCode.TIMEOUT,
                SyncErrorCode.HEALTH_CONNECT_UNAVAILABLE,
                SyncErrorCode.READ_FAILED,
                -> retryWithAttemptCap(MAX_TRANSIENT_RETRIES)

                SyncErrorCode.WRITE_FAILED -> retryWithAttemptCap(MAX_WRITE_FAILURE_RETRIES)
            }
        }

        private fun retryWithAttemptCap(maxAttempts: Int): Result {
            return if (runAttemptCount < maxAttempts) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundSyncWorkerEntryPoint {
    fun syncScheduler(): SyncScheduler

    fun permissionManager(): PermissionManager
}

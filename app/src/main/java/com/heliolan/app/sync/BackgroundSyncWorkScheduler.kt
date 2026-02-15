package com.heliolan.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules periodic background sync via WorkManager.
 */
@Singleton
class BackgroundSyncWorkScheduler
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        companion object {
            const val SYNC_INTERVAL_MINUTES = 15L
            const val INITIAL_DELAY_MINUTES = 1L
            const val RETRY_BACKOFF_MINUTES = 10L
            const val UNIQUE_WORK_NAME = "heliolan_background_sync"
        }

        fun ensureScheduled() {
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<BackgroundSyncWorker>(SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        RETRY_BACKOFF_MINUTES,
                        TimeUnit.MINUTES,
                    )
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }

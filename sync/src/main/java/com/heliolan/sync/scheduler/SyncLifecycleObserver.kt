package com.heliolan.sync.scheduler

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App lifecycle hook for phase 3 scheduling behavior.
 * - App enters foreground: trigger an immediate sync and start periodic sync.
 * - App leaves foreground: stop periodic sync.
 */
@Singleton
class SyncLifecycleObserver
    @Inject
    constructor(
        private val syncScheduler: SyncScheduler,
    ) : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            syncScheduler.onAppForeground()
            syncScheduler.startPeriodicSync()
        }

        override fun onStop(owner: LifecycleOwner) {
            syncScheduler.stopPeriodicSync()
        }
    }

package com.heliolan.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.heliolan.app.crash.CrashLogStore
import com.heliolan.app.crash.LocalCrashReporter
import com.heliolan.app.sync.BackgroundSyncWorkScheduler
import com.heliolan.sync.scheduler.SyncLifecycleObserver
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class HelioLanApplication : Application() {
    @Inject
    lateinit var syncLifecycleObserver: SyncLifecycleObserver

    @Inject
    lateinit var backgroundSyncWorkScheduler: BackgroundSyncWorkScheduler

    override fun onCreate() {
        super.onCreate()
        LocalCrashReporter.install(
            CrashLogStore(
                crashDirectory = File(filesDir, "crash-logs"),
            ),
        )
        // Phase 3: start lifecycle-driven sync orchestration.
        ProcessLifecycleOwner.get().lifecycle.addObserver(syncLifecycleObserver)
        // Phase 11: keep sync active when app is not foregrounded.
        backgroundSyncWorkScheduler.ensureScheduled()
    }
}

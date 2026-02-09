package com.heliolan.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.heliolan.sync.scheduler.SyncLifecycleObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HelioLanApplication : Application() {
    @Inject
    lateinit var syncLifecycleObserver: SyncLifecycleObserver

    override fun onCreate() {
        super.onCreate()
        // Phase 3: start lifecycle-driven sync orchestration.
        ProcessLifecycleOwner.get().lifecycle.addObserver(syncLifecycleObserver)
    }
}

package com.heliolan.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class HelioLanApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // TODO: Initialize any required components
        // - Crash reporting (if using local-only solution)
        // - Logging framework
        // - Health Connect availability check
    }
}

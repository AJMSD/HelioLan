package com.heliolan.app.crash

object LocalCrashReporter {
    private val lock = Any()

    @Volatile
    private var installed: Boolean = false

    fun install(crashLogStore: CrashLogStore) {
        if (installed) {
            return
        }
        synchronized(lock) {
            if (installed) {
                return
            }

            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                crashLogStore.writeCrash(thread.name.ifBlank { "unknown" }, throwable)
                previousHandler?.uncaughtException(thread, throwable)
            }
            installed = true
        }
    }
}

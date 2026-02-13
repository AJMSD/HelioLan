package com.heliolan.app.setup

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SetupPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        companion object {
            private const val PREFS_NAME = "heliolan_setup_prefs"
            private const val KEY_SETUP_COMPLETED = "key_setup_completed"
            private const val KEY_ZEPP_SYNC_CONFIRMED = "key_zepp_sync_confirmed"
            private const val KEY_FIRST_SYNC_COMPLETED = "key_first_sync_completed"
            private const val KEY_PASSCODE_SKIPPED = "key_passcode_skipped"
        }

        private val preferences: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun isSetupCompleted(): Boolean = preferences.getBoolean(KEY_SETUP_COMPLETED, false)

        fun setSetupCompleted(completed: Boolean) {
            preferences.edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply()
        }

        fun isZeppSyncConfirmed(): Boolean = preferences.getBoolean(KEY_ZEPP_SYNC_CONFIRMED, false)

        fun setZeppSyncConfirmed(confirmed: Boolean) {
            preferences.edit().putBoolean(KEY_ZEPP_SYNC_CONFIRMED, confirmed).apply()
        }

        fun isFirstSyncCompleted(): Boolean = preferences.getBoolean(KEY_FIRST_SYNC_COMPLETED, false)

        fun setFirstSyncCompleted(completed: Boolean) {
            preferences.edit().putBoolean(KEY_FIRST_SYNC_COMPLETED, completed).apply()
        }

        fun isPasscodeSkipped(): Boolean = preferences.getBoolean(KEY_PASSCODE_SKIPPED, false)

        fun setPasscodeSkipped(skipped: Boolean) {
            preferences.edit().putBoolean(KEY_PASSCODE_SKIPPED, skipped).apply()
        }

        fun resetSetupState() {
            preferences.edit()
                .putBoolean(KEY_SETUP_COMPLETED, false)
                .putBoolean(KEY_ZEPP_SYNC_CONFIRMED, false)
                .putBoolean(KEY_FIRST_SYNC_COMPLETED, false)
                .putBoolean(KEY_PASSCODE_SKIPPED, false)
                .apply()
        }
    }

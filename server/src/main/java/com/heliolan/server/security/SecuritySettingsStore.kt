package com.heliolan.server.security

import android.content.Context
import android.content.SharedPreferences
import com.heliolan.server.DashboardSecurityConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface SecuritySettingsStore {
    fun getPasscodeHash(): String?

    fun setPasscodeHash(hash: String?)

    fun isOpenAccessEnabled(): Boolean

    fun setOpenAccessEnabled(enabled: Boolean)
}

@Singleton
class SharedPreferencesSecuritySettingsStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val securityConfig: DashboardSecurityConfig,
    ) : SecuritySettingsStore {
        companion object {
            private const val PREFS_NAME = "heliolan_security_prefs"
            private const val KEY_PASSCODE_HASH = "key_passcode_hash"
            private const val KEY_OPEN_ACCESS = "key_open_access"
        }

        private val preferences: SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun getPasscodeHash(): String? = preferences.getString(KEY_PASSCODE_HASH, null)

        override fun setPasscodeHash(hash: String?) {
            preferences.edit().apply {
                if (hash.isNullOrBlank()) {
                    remove(KEY_PASSCODE_HASH)
                } else {
                    putString(KEY_PASSCODE_HASH, hash)
                }
            }.apply()
        }

        override fun isOpenAccessEnabled(): Boolean {
            return preferences.getBoolean(KEY_OPEN_ACCESS, securityConfig.openAccessByDefault)
        }

        override fun setOpenAccessEnabled(enabled: Boolean) {
            preferences.edit().putBoolean(KEY_OPEN_ACCESS, enabled).apply()
        }
    }

@Singleton
class SecuritySettingsManager
    @Inject
    constructor(
        private val store: SecuritySettingsStore,
        private val passcodeHasher: PasscodeHasher,
    ) {
        fun hasPasscodeConfigured(): Boolean = !store.getPasscodeHash().isNullOrBlank()

        fun verifyPasscode(passcode: String): Boolean {
            val hash = store.getPasscodeHash() ?: return false
            return passcodeHasher.verifyPasscode(passcode, hash)
        }

        fun setPasscode(passcode: String) {
            val hash = passcodeHasher.hashPasscode(passcode)
            store.setPasscodeHash(hash)
        }

        fun clearPasscode() {
            store.setPasscodeHash(null)
        }

        fun isOpenAccessEnabled(): Boolean = store.isOpenAccessEnabled()

        fun setOpenAccessEnabled(enabled: Boolean) {
            store.setOpenAccessEnabled(enabled)
        }
    }

package com.heliolan.server.security

import com.heliolan.server.DashboardSecurityConfig
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class LoginAttemptState(
    val failedAttempts: Int,
    val lockedUntil: Instant?,
) {
    fun isLocked(now: Instant): Boolean = lockedUntil?.isAfter(now) == true
}

data class LoginThrottleDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0,
    val failedAttempts: Int = 0,
)

/**
 * Tracks failed auth attempts in memory and enforces temporary lockout.
 */
@Singleton
class LoginAttemptTracker
    @Inject
    constructor(
        private val securityConfig: DashboardSecurityConfig,
        private val clock: Clock,
    ) {
        private val stateByIp = ConcurrentHashMap<String, LoginAttemptState>()
        private val lockoutDuration = Duration.ofMinutes(securityConfig.lockoutMinutes)

        fun canAttempt(ipAddress: String): LoginThrottleDecision {
            val now = clock.instant()
            val state = stateByIp[ipAddress] ?: return LoginThrottleDecision(allowed = true)
            if (!state.isLocked(now)) {
                return LoginThrottleDecision(
                    allowed = true,
                    failedAttempts = state.failedAttempts,
                )
            }
            val retryAfter = Duration.between(now, state.lockedUntil).seconds.coerceAtLeast(1)
            return LoginThrottleDecision(
                allowed = false,
                retryAfterSeconds = retryAfter,
                failedAttempts = state.failedAttempts,
            )
        }

        fun onFailedAttempt(ipAddress: String): LoginAttemptState {
            val now = clock.instant()
            val nextState =
                stateByIp.compute(ipAddress) { _, current ->
                    val normalizedCurrent =
                        if (current == null || !current.isLocked(now)) {
                            current?.copy(lockedUntil = null)
                        } else {
                            current
                        }

                    val failedAttempts = (normalizedCurrent?.failedAttempts ?: 0) + 1
                    val lockedUntil =
                        if (failedAttempts >= securityConfig.maxFailedLoginAttempts) {
                            now.plus(lockoutDuration)
                        } else {
                            null
                        }
                    LoginAttemptState(
                        failedAttempts = failedAttempts,
                        lockedUntil = lockedUntil,
                    )
                } ?: LoginAttemptState(1, null)
            return nextState
        }

        fun onSuccessfulAttempt(ipAddress: String) {
            stateByIp.remove(ipAddress)
        }

        fun clearAll() {
            stateByIp.clear()
        }
    }

package com.heliolan.server

import com.google.common.truth.Truth.assertThat
import com.heliolan.server.security.LoginAttemptTracker
import com.heliolan.server.security.NetworkSecurityValidator
import com.heliolan.server.security.PasscodeHasher
import com.heliolan.server.security.SecuritySettingsManager
import com.heliolan.server.security.SecuritySettingsStore
import com.heliolan.server.security.SessionManager
import org.junit.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ServerInfrastructureTest {
    @Test
    fun resolvePortCandidates_returnsPreferredThenFallbackWithoutDuplicates() {
        val config =
            DashboardServerConfig(
                preferredPort = 8082,
                fallbackPorts = 8081..8084,
            )

        val candidates = resolvePortCandidates(config)

        assertThat(candidates).containsExactly(8082, 8081, 8083, 8084).inOrder()
    }

    @Test
    fun apiRateLimiter_blocksRequestBeyondConfiguredLimitPerMinute() {
        val mutableClock = MutableClock(Instant.parse("2026-02-09T12:00:05Z"), ZoneOffset.UTC)
        val limiter = ApiRateLimiter(maxRequestsPerMinute = 2, clock = mutableClock)

        val first = limiter.tryConsume("192.168.1.10")
        val second = limiter.tryConsume("192.168.1.10")
        val third = limiter.tryConsume("192.168.1.10")

        assertThat(first.allowed).isTrue()
        assertThat(second.allowed).isTrue()
        assertThat(third.allowed).isFalse()
        assertThat(third.retryAfterSeconds).isGreaterThan(0L)
    }

    @Test
    fun apiRateLimiter_resetsWindowAfterMinuteBoundary() {
        val mutableClock = MutableClock(Instant.parse("2026-02-09T12:00:58Z"), ZoneOffset.UTC)
        val limiter = ApiRateLimiter(maxRequestsPerMinute = 1, clock = mutableClock)

        assertThat(limiter.tryConsume("192.168.1.12").allowed).isTrue()
        assertThat(limiter.tryConsume("192.168.1.12").allowed).isFalse()

        mutableClock.advanceSeconds(3) // next minute
        assertThat(limiter.tryConsume("192.168.1.12").allowed).isTrue()
    }

    @Test
    fun connectedClientTracker_countsOnlyActiveClientsInWindow() {
        val mutableClock = MutableClock(Instant.parse("2026-02-09T12:00:00Z"), ZoneOffset.UTC)
        val tracker =
            ConnectedClientTracker(
                clock = mutableClock,
                activityWindow = Duration.ofMinutes(5),
            )

        tracker.recordClient("192.168.1.2")
        tracker.recordClient("192.168.1.3")
        assertThat(tracker.getActiveClientCount()).isEqualTo(2)

        mutableClock.advanceSeconds(301)
        tracker.recordClient("192.168.1.3")
        assertThat(tracker.getActiveClientCount()).isEqualTo(1)
    }

    @Test
    fun passcodeHasher_hashesAndVerifiesWithoutStoringPlaintext() {
        val config = DashboardSecurityConfig()
        val hasher = PasscodeHasher(config)

        val hash = hasher.hashPasscode("1234")

        assertThat(hash).isNotEqualTo("1234")
        assertThat(hasher.verifyPasscode("1234", hash)).isTrue()
        assertThat(hasher.verifyPasscode("9999", hash)).isFalse()
    }

    @Test
    fun securitySettingsManager_storesHashedPasscodeAndOpenAccessFlag() {
        val store = InMemorySecuritySettingsStore()
        val manager =
            SecuritySettingsManager(
                store = store,
                passcodeHasher = PasscodeHasher(DashboardSecurityConfig()),
            )

        manager.setPasscode("2468")
        manager.setOpenAccessEnabled(true)

        assertThat(store.storedPasscodeHash).isNotEqualTo("2468")
        assertThat(manager.verifyPasscode("2468")).isTrue()
        assertThat(manager.isOpenAccessEnabled()).isTrue()
    }

    @Test
    fun sessionManager_expiresSessionsAfterConfiguredTtl() {
        val mutableClock = MutableClock(Instant.parse("2026-02-09T13:00:00Z"), ZoneOffset.UTC)
        val manager =
            SessionManager(
                securityConfig = DashboardSecurityConfig(sessionTtlHours = 24),
                clock = mutableClock,
            )

        val session = manager.issueSession()

        assertThat(session.token.length).isAtLeast(43) // 256-bit URL-safe token.
        assertThat(manager.validateSession(session.token)).isNotNull()

        mutableClock.advanceSeconds(24 * 3600 + 1L)
        assertThat(manager.validateSession(session.token)).isNull()
    }

    @Test
    fun loginAttemptTracker_locksAfterConfiguredFailedAttempts() {
        val mutableClock = MutableClock(Instant.parse("2026-02-09T14:00:00Z"), ZoneOffset.UTC)
        val tracker =
            LoginAttemptTracker(
                securityConfig =
                    DashboardSecurityConfig(
                        maxFailedLoginAttempts = 3,
                        lockoutMinutes = 5,
                    ),
                clock = mutableClock,
            )

        val ip = "192.168.1.90"
        assertThat(tracker.canAttempt(ip).allowed).isTrue()

        tracker.onFailedAttempt(ip)
        tracker.onFailedAttempt(ip)
        tracker.onFailedAttempt(ip)

        val locked = tracker.canAttempt(ip)
        assertThat(locked.allowed).isFalse()
        assertThat(locked.retryAfterSeconds).isGreaterThan(0)

        mutableClock.advanceSeconds(301)
        assertThat(tracker.canAttempt(ip).allowed).isTrue()
    }

    @Test
    fun networkSecurityValidator_allowsPrivateRangesAndRejectsPublicHosts() {
        val validator = NetworkSecurityValidator()

        assertThat(validator.isAllowedClientAddress("192.168.1.10")).isTrue()
        assertThat(validator.isAllowedClientAddress("10.0.2.15")).isTrue()
        assertThat(validator.isAllowedClientAddress("172.20.1.5")).isTrue()
        assertThat(validator.isAllowedClientAddress("169.254.12.1")).isTrue()
        assertThat(validator.isAllowedClientAddress("8.8.8.8")).isFalse()
        assertThat(validator.isAllowedClientAddress("fd00::1")).isTrue()
        assertThat(validator.isAllowedClientAddress("2001:4860:4860::8888")).isFalse()

        val allowedHosts = setOf("192.168.1.10")
        assertThat(validator.isAllowedHostHeader("192.168.1.10:8080", allowedHosts)).isTrue()
        assertThat(validator.isAllowedHostHeader("localhost:8080", allowedHosts)).isTrue()
        assertThat(validator.isAllowedHostHeader("evil.example.com", allowedHosts)).isFalse()
    }
}

private class InMemorySecuritySettingsStore : SecuritySettingsStore {
    var storedPasscodeHash: String? = null
    private var openAccessEnabled: Boolean = false

    override fun getPasscodeHash(): String? = storedPasscodeHash

    override fun setPasscodeHash(hash: String?) {
        storedPasscodeHash = hash
    }

    override fun isOpenAccessEnabled(): Boolean = openAccessEnabled

    override fun setOpenAccessEnabled(enabled: Boolean) {
        openAccessEnabled = enabled
    }
}

private class MutableClock(
    private var now: Instant,
    private val zoneId: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now

    fun advanceSeconds(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

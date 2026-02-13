package com.heliolan.server

import com.google.common.truth.Truth.assertThat
import com.heliolan.server.security.LoginAttemptTracker
import com.heliolan.server.security.NetworkSecurityValidator
import com.heliolan.server.security.PasscodeHasher
import com.heliolan.server.security.SecuritySettingsManager
import com.heliolan.server.security.SecuritySettingsStore
import com.heliolan.server.security.SessionManager
import org.junit.Test
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun connectedClientTracker_tracksThreeConcurrentClients() {
        val tracker =
            ConnectedClientTracker(
                clock = Clock.systemUTC(),
                activityWindow = Duration.ofMinutes(5),
            )
        val clients = listOf("192.168.1.2", "192.168.1.3", "192.168.1.4")
        val executor = Executors.newFixedThreadPool(clients.size)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(clients.size)

        clients.forEach { clientIp ->
            executor.execute {
                startLatch.await()
                tracker.recordClient(clientIp)
                doneLatch.countDown()
            }
        }

        startLatch.countDown()
        assertThat(doneLatch.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(tracker.getActiveClientCount()).isEqualTo(3)
        executor.shutdownNow()
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

    @Test
    fun dashboardAssets_includeRequiredPhase8Files() {
        val repoRoot = locateRepoRoot()
        val requiredFiles =
            listOf(
                "dashboard/src/main/assets/dashboard/index.html",
                "dashboard/src/main/assets/dashboard/css/style.css",
                "dashboard/src/main/assets/dashboard/js/api.js",
                "dashboard/src/main/assets/dashboard/js/app.js",
                "dashboard/src/main/assets/dashboard/js/charts.js",
                "dashboard/src/main/assets/dashboard/js/utils.js",
                "dashboard/src/main/assets/dashboard/lib/chart.min.js",
            )

        requiredFiles.forEach { relativePath ->
            val file = File(repoRoot, relativePath)
            assertThat(file.exists()).isTrue()
            assertThat(file.length()).isGreaterThan(0L)
        }
    }

    @Test
    fun dashboardIndex_referencesAllFrontendModulesAndNoPlaceholderText() {
        val repoRoot = locateRepoRoot()
        val indexFile = File(repoRoot, "dashboard/src/main/assets/dashboard/index.html")
        val indexText = indexFile.readText()

        assertThat(indexText).contains("id=\"loginScreen\"")
        assertThat(indexText).contains("id=\"appShell\" class=\"screen app-shell hidden\" hidden")
        assertThat(indexText).contains("id=\"setPasscodeForm\" class=\"auth-form hidden\" autocomplete=\"off\" hidden")
        assertThat(indexText).contains("href=\"/dashboard/css/style.css?v=")
        assertThat(indexText).contains("/dashboard/js/api.js?v=")
        assertThat(indexText).contains("/dashboard/js/utils.js?v=")
        assertThat(indexText).contains("/dashboard/js/charts.js?v=")
        assertThat(indexText).contains("/dashboard/js/app.js?v=")
        assertThat(indexText).contains("/dashboard/lib/chart.min.js?v=")
        assertThat(indexText).doesNotContain("placeholder - will be implemented in Phase 8")
    }

    @Test
    fun dashboardStaticAssets_fitLanPerformanceBudget() {
        val repoRoot = locateRepoRoot()
        val assetFiles =
            listOf(
                "dashboard/src/main/assets/dashboard/index.html",
                "dashboard/src/main/assets/dashboard/css/style.css",
                "dashboard/src/main/assets/dashboard/js/api.js",
                "dashboard/src/main/assets/dashboard/js/utils.js",
                "dashboard/src/main/assets/dashboard/js/charts.js",
                "dashboard/src/main/assets/dashboard/js/app.js",
                "dashboard/src/main/assets/dashboard/lib/chart.min.js",
            )

        val totalBytes =
            assetFiles.sumOf { relativePath ->
                File(repoRoot, relativePath).length()
            }

        // Keep dashboard payload in a conservative LAN budget to preserve sub-second render targets.
        assertThat(totalBytes).isLessThan(700_000L)
    }

    @Test
    fun vendoredChartJs_isPresentAndNonTrivialSize() {
        val repoRoot = locateRepoRoot()
        val chartJsFile = File(repoRoot, "dashboard/src/main/assets/dashboard/lib/chart.min.js")

        assertThat(chartJsFile.exists()).isTrue()
        assertThat(chartJsFile.length()).isGreaterThan(100_000L)
    }

    @Test
    fun dashboardAppScript_includesBootstrapFailureMessagingForMissingAssets() {
        val repoRoot = locateRepoRoot()
        val appJsFile = File(repoRoot, "dashboard/src/main/assets/dashboard/js/app.js")
        val script = appJsFile.readText()

        assertThat(script).contains("Required dashboard scripts did not load")
        assertThat(script).contains("Dashboard markup mismatch. Missing elements")
        assertThat(script).contains("setVisible")
    }

    @Test
    fun dashboardAppScript_usesAutomaticPollingAndNoManualRefreshButtons() {
        val repoRoot = locateRepoRoot()
        val appJsFile = File(repoRoot, "dashboard/src/main/assets/dashboard/js/app.js")
        val script = appJsFile.readText()

        assertThat(script).doesNotContain("Manual Refresh")
        assertThat(script).contains("setInterval(pollToday, 10000)")
        assertThat(script).contains("setInterval(async function () {")
        assertThat(script).contains("api.triggerSync({ automatic: true })")
    }

    @Test
    fun dashboardSettingsScript_keepsOnlyTimeFormatPreferenceControls() {
        val repoRoot = locateRepoRoot()
        val appJsFile = File(repoRoot, "dashboard/src/main/assets/dashboard/js/app.js")
        val script = appJsFile.readText()

        assertThat(script).contains("label for=\\\"prefTime\\\"")
        assertThat(script).doesNotContain("prefRefresh")
        assertThat(script).doesNotContain("prefSync")
        assertThat(script).doesNotContain("Refresh behavior")
        assertThat(script).doesNotContain("Sync window")
    }

    @Test
    fun dashboardServerSyncRoute_supportsAutomaticAndUserTriggers() {
        val repoRoot = locateRepoRoot()
        val serverFile = File(repoRoot, "server/src/main/java/com/heliolan/server/DashboardServerApplication.kt")
        val source = serverFile.readText()

        assertThat(source).contains("call.request.queryParameters[\"trigger\"]?.lowercase()")
        assertThat(source).contains("\"automatic\" -> syncScheduler.triggerAutomaticSync()")
        assertThat(source).contains("else -> syncScheduler.triggerSyncNow()")
    }

    @Test
    fun dashboardServer_includesPhase11MetricRoutesAndRecordTypes() {
        val repoRoot = locateRepoRoot()
        val serverFile = File(repoRoot, "server/src/main/java/com/heliolan/server/DashboardServerApplication.kt")
        val source = serverFile.readText()

        assertThat(source).contains("get(\"/calories/active\")")
        assertThat(source).contains("get(\"/distance\")")
        assertThat(source).contains("get(\"/calories/total\")")
        assertThat(source).contains("get(\"/nutrition\")")
        assertThat(source).contains("get(\"/oxygen-saturation\")")
        assertThat(source).contains("get(\"/hrv\")")
        assertThat(source).contains("RecordType.ACTIVE_CALORIES")
        assertThat(source).contains("RecordType.DISTANCE")
        assertThat(source).contains("RecordType.TOTAL_CALORIES")
        assertThat(source).contains("RecordType.NUTRITION")
        assertThat(source).contains("RecordType.OXYGEN_SATURATION")
        assertThat(source).contains("RecordType.HRV")
    }

    @Test
    fun dashboardFrontend_scriptsReferenceNutritionAndMetricPermissions() {
        val repoRoot = locateRepoRoot()
        val appJsFile = File(repoRoot, "dashboard/src/main/assets/dashboard/js/app.js")
        val appSource = appJsFile.readText()
        val apiJsFile = File(repoRoot, "dashboard/src/main/assets/dashboard/js/api.js")
        val apiSource = apiJsFile.readText()

        assertThat(appSource).contains("\"nutrition\"")
        assertThat(appSource).contains("type: \"active_calories\"")
        assertThat(appSource).contains("type: \"distance\"")
        assertThat(appSource).contains("type: \"nutrition\"")
        assertThat(appSource).contains("type: \"oxygen_saturation\"")
        assertThat(appSource).contains("perm.active_calories")
        assertThat(appSource).contains("perm.distance")
        assertThat(appSource).contains("perm.total_calories")
        assertThat(appSource).contains("perm.nutrition")
        assertThat(appSource).contains("perm.oxygen_saturation")
        assertThat(apiSource).contains("ApiClient.prototype.getActiveCalories")
        assertThat(apiSource).contains("ApiClient.prototype.getDistance")
        assertThat(apiSource).contains("ApiClient.prototype.getTotalCalories")
        assertThat(apiSource).contains("ApiClient.prototype.getNutrition")
        assertThat(apiSource).contains("ApiClient.prototype.getOxygenSaturation")
    }

    @Test
    fun dashboardServerSecurityHeaders_includeExpectedHardeningDefaults() {
        val repoRoot = locateRepoRoot()
        val serverFile = File(repoRoot, "server/src/main/java/com/heliolan/server/DashboardServerApplication.kt")
        val source = serverFile.readText()

        assertThat(source).contains("X-Content-Type-Options")
        assertThat(source).contains("X-Frame-Options")
        assertThat(source).contains("Content-Security-Policy")
        assertThat(source).contains("Referrer-Policy")
    }

    @Test
    fun resolveDashboardAssetPath_mapsDashboardRequestsToExpectedAssetFiles() {
        assertThat(resolveDashboardAssetPath("/dashboard/")).isEqualTo("dashboard/index.html")
        assertThat(resolveDashboardAssetPath("/dashboard/js/app.js")).isEqualTo("dashboard/js/app.js")
        assertThat(resolveDashboardAssetPath("/dashboard/css/style.css")).isEqualTo("dashboard/css/style.css")
        assertThat(resolveDashboardAssetPath("/dashboard/lib/chart.min.js")).isEqualTo("dashboard/lib/chart.min.js")
        assertThat(resolveDashboardAssetPath("/dashboard/%6a%73/%61%70%70.js")).isEqualTo("dashboard/js/app.js")
    }

    @Test
    fun resolveDashboardAssetPath_rejectsPathTraversalSegments() {
        assertThat(resolveDashboardAssetPath("/dashboard/../index.html")).isNull()
        assertThat(resolveDashboardAssetPath("/dashboard/%2e%2e/index.html")).isNull()
    }
}

private fun locateRepoRoot(startDirectory: File = File(System.getProperty("user.dir") ?: ".")): File {
    var current: File? = startDirectory.absoluteFile
    while (current != null) {
        if (File(current, "settings.gradle.kts").exists()) {
            return current
        }
        current = current.parentFile
    }
    error("Unable to locate repository root from ${startDirectory.absolutePath}")
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

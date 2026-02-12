package com.heliolan.server

import android.content.Context
import android.os.Build
import com.heliolan.data.entity.DailyAggregate
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.entity.SyncCursor
import com.heliolan.data.repository.HealthRepository
import com.heliolan.data.util.DataFreshness
import com.heliolan.data.util.RecordType
import com.heliolan.healthconnect.permission.PermissionManager
import com.heliolan.server.export.ExportEngine
import com.heliolan.server.export.registerExportRoutes
import com.heliolan.server.security.LoginAttemptTracker
import com.heliolan.server.security.LoginRequest
import com.heliolan.server.security.NetworkSecurityValidator
import com.heliolan.server.security.OpenAccessToggleRequest
import com.heliolan.server.security.SecuritySettingsManager
import com.heliolan.server.security.SessionInfo
import com.heliolan.server.security.SessionManager
import com.heliolan.server.security.SetPasscodeRequest
import com.heliolan.sync.engine.SyncEngine
import com.heliolan.sync.scheduler.SyncScheduler
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.origin
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.util.pipeline.intercept
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.FileNotFoundException
import java.net.NetworkInterface
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections

private val json =
    Json {
        prettyPrint = false
        encodeDefaults = true
    }

private data class PaginationRequest(
    val limit: Int,
    val offset: Int,
)

private data class InstantRange(
    val start: Instant,
    val end: Instant,
)

private data class DateRange(
    val start: LocalDate,
    val end: LocalDate,
)

fun Application.configureDashboardApplication(
    context: Context,
    healthRepository: HealthRepository,
    syncScheduler: SyncScheduler,
    syncEngine: SyncEngine,
    permissionManager: PermissionManager,
    exportEngine: ExportEngine,
    config: DashboardServerConfig,
    rateLimiter: ApiRateLimiter,
    clientTracker: ConnectedClientTracker,
    sessionManager: SessionManager,
    securitySettingsManager: SecuritySettingsManager,
    loginAttemptTracker: LoginAttemptTracker,
    networkSecurityValidator: NetworkSecurityValidator,
    localIpAddressProvider: () -> String,
    runtimeInfoProvider: () -> DashboardServerRuntimeInfo?,
    clock: Clock,
    zoneId: ZoneId,
) {
    install(Compression) {
        gzip {
            minimumSize(1024)
        }
    }

    val securityPhase = PipelinePhase("SecurityMiddleware")
    insertPhaseAfter(io.ktor.server.application.ApplicationCallPipeline.Setup, securityPhase)
    intercept(securityPhase) {
        call.applySecurityHeaders()

        val clientIp = call.request.origin.remoteHost
        clientTracker.recordClient(clientIp)

        if (!networkSecurityValidator.isAllowedClientAddress(clientIp)) {
            call.respondApiError(
                status = HttpStatusCode.Forbidden,
                code = "FORBIDDEN_NETWORK",
                message = "Access is restricted to local private-network clients.",
            )
            finish()
            return@intercept
        }

        val allowedHosts =
            buildSet {
                add(localIpAddressProvider())
                runtimeInfoProvider()?.localIpAddress?.let { add(it) }
                addAll(resolveLocalHostAddressCandidates())
            }
        if (!networkSecurityValidator.isAllowedHostHeader(call.request.headers[HttpHeaders.Host], allowedHosts)) {
            call.respondApiError(
                status = HttpStatusCode.Forbidden,
                code = "INVALID_HOST",
                message = "Host header is not allowed.",
            )
            finish()
            return@intercept
        }
    }

    val authPhase = PipelinePhase("ApiAuthentication")
    insertPhaseAfter(securityPhase, authPhase)
    intercept(authPhase) {
        val path = call.request.path()
        if (!path.startsWith("/api/v1")) return@intercept
        if (path in publicAuthPaths()) return@intercept
        if (securitySettingsManager.isOpenAccessEnabled()) return@intercept

        if (!securitySettingsManager.hasPasscodeConfigured()) {
            call.respondApiError(
                status = HttpStatusCode.Unauthorized,
                code = "PASSCODE_NOT_CONFIGURED",
                message = "Passcode is not configured. Set a passcode or enable open access.",
            )
            finish()
            return@intercept
        }

        val session = call.resolveSession(sessionManager, config.security.authCookieName)
        if (session == null) {
            call.respondApiError(
                status = HttpStatusCode.Unauthorized,
                code = "AUTH_REQUIRED",
                message = "Authentication required.",
            )
            finish()
            return@intercept
        }
    }

    val rateLimitPhase = PipelinePhase("ApiRateLimit")
    insertPhaseAfter(authPhase, rateLimitPhase)
    intercept(rateLimitPhase) {
        if (!call.request.path().startsWith("/api/")) return@intercept

        val decision = rateLimiter.tryConsume(call.request.origin.remoteHost)
        if (!decision.allowed) {
            call.response.header(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
            call.respondApiError(
                status = HttpStatusCode.TooManyRequests,
                code = "RATE_LIMITED",
                message = "Too many requests. Retry later.",
            )
            finish()
            return@intercept
        }
    }

    routing {
        get("/") {
            call.respondRedirect("/dashboard/")
        }

        get("/dashboard") {
            call.respondRedirect("/dashboard/")
        }

        get("/dashboard/") {
            call.respondDashboardAsset(context, "dashboard/index.html")
        }

        get("/dashboard/{...}") {
            val assetPath = resolveDashboardAssetPath(call.request.path())
            if (assetPath == null) {
                call.respondApiError(
                    status = HttpStatusCode.NotFound,
                    code = "ASSET_NOT_FOUND",
                    message = "Dashboard asset path is invalid.",
                )
                return@get
            }
            call.respondDashboardAsset(context, assetPath)
        }

        route("/api/v1") {
            route("/auth") {
                post("/login") {
                    if (!securitySettingsManager.hasPasscodeConfigured()) {
                        call.respondApiError(
                            status = HttpStatusCode.Conflict,
                            code = "PASSCODE_NOT_CONFIGURED",
                            message = "Set a passcode before login.",
                        )
                        return@post
                    }

                    val clientIp = call.request.origin.remoteHost
                    val throttle = loginAttemptTracker.canAttempt(clientIp)
                    if (!throttle.allowed) {
                        call.response.header(HttpHeaders.RetryAfter, throttle.retryAfterSeconds.toString())
                        call.respondApiError(
                            status = HttpStatusCode.TooManyRequests,
                            code = "AUTH_LOCKED",
                            message = "Too many failed attempts. Try again later.",
                        )
                        return@post
                    }

                    val requestBody =
                        call.receiveJsonBody<LoginRequest>()
                            ?: run {
                                call.respondApiError(
                                    status = HttpStatusCode.BadRequest,
                                    code = "INVALID_REQUEST",
                                    message = "Request body must include a passcode.",
                                )
                                return@post
                            }

                    val valid = securitySettingsManager.verifyPasscode(requestBody.passcode)
                    if (!valid) {
                        val state = loginAttemptTracker.onFailedAttempt(clientIp)
                        val now = clock.instant()
                        if (state.lockedUntil?.isAfter(now) == true) {
                            val retryAfter = java.time.Duration.between(now, state.lockedUntil).seconds.coerceAtLeast(1)
                            call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                            call.respondApiError(
                                status = HttpStatusCode.TooManyRequests,
                                code = "AUTH_LOCKED",
                                message = "Too many failed attempts. Try again later.",
                            )
                        } else {
                            call.respondApiError(
                                status = HttpStatusCode.Unauthorized,
                                code = "INVALID_CREDENTIALS",
                                message = "Passcode is incorrect.",
                            )
                        }
                        return@post
                    }

                    loginAttemptTracker.onSuccessfulAttempt(clientIp)
                    val session = sessionManager.issueSession()
                    call.appendSessionCookie(
                        cookieName = config.security.authCookieName,
                        session = session,
                    )
                    call.respondApiSuccess(
                        data =
                            buildJsonObject {
                                put("authenticated", true)
                                put("expires_at", session.expiresAt.toString())
                                put("open_access_enabled", securitySettingsManager.isOpenAccessEnabled())
                            },
                        clock = clock,
                    )
                }

                get("/session") {
                    val session = call.resolveSession(sessionManager, config.security.authCookieName)
                    call.respondApiSuccess(
                        data =
                            buildJsonObject {
                                put("authenticated", session != null || securitySettingsManager.isOpenAccessEnabled())
                                put("open_access_enabled", securitySettingsManager.isOpenAccessEnabled())
                                put("passcode_configured", securitySettingsManager.hasPasscodeConfigured())
                                if (session != null) {
                                    put("expires_at", session.expiresAt.toString())
                                }
                            },
                        clock = clock,
                    )
                }

                post("/passcode") {
                    val requestBody =
                        call.receiveJsonBody<SetPasscodeRequest>()
                            ?: run {
                                call.respondApiError(
                                    status = HttpStatusCode.BadRequest,
                                    code = "INVALID_REQUEST",
                                    message = "Request body must include a passcode.",
                                )
                                return@post
                            }

                    val existingPasscodeConfigured = securitySettingsManager.hasPasscodeConfigured()
                    if (existingPasscodeConfigured) {
                        val session = call.resolveSession(sessionManager, config.security.authCookieName)
                        val currentPasscodeValid =
                            requestBody.currentPasscode?.let { securitySettingsManager.verifyPasscode(it) } == true
                        if (session == null && !currentPasscodeValid) {
                            call.respondApiError(
                                status = HttpStatusCode.Unauthorized,
                                code = "AUTH_REQUIRED",
                                message = "Authentication or current passcode is required.",
                            )
                            return@post
                        }
                    }

                    val setResult =
                        runCatching {
                            securitySettingsManager.setPasscode(requestBody.passcode)
                        }
                    if (setResult.isFailure) {
                        call.respondApiError(
                            status = HttpStatusCode.BadRequest,
                            code = "INVALID_PASSCODE_FORMAT",
                            message =
                                setResult.exceptionOrNull()?.message
                                    ?: "Passcode must be 4-8 digits.",
                        )
                        return@post
                    }

                    val session = sessionManager.issueSession()
                    call.appendSessionCookie(
                        cookieName = config.security.authCookieName,
                        session = session,
                    )
                    call.respondApiSuccess(
                        data =
                            buildJsonObject {
                                put("passcode_configured", true)
                                put("expires_at", session.expiresAt.toString())
                            },
                        clock = clock,
                    )
                }

                post("/open-access") {
                    val requestBody =
                        call.receiveJsonBody<OpenAccessToggleRequest>()
                            ?: run {
                                call.respondApiError(
                                    status = HttpStatusCode.BadRequest,
                                    code = "INVALID_REQUEST",
                                    message = "Request body must include open access toggle details.",
                                )
                                return@post
                            }

                    if (requestBody.enabled && !requestBody.confirm) {
                        call.respondApiError(
                            status = HttpStatusCode.BadRequest,
                            code = "OPEN_ACCESS_CONFIRMATION_REQUIRED",
                            message = "Set confirm=true to enable open access.",
                        )
                        return@post
                    }

                    val existingPasscodeConfigured = securitySettingsManager.hasPasscodeConfigured()
                    val session = call.resolveSession(sessionManager, config.security.authCookieName)
                    if (existingPasscodeConfigured && session == null) {
                        call.respondApiError(
                            status = HttpStatusCode.Unauthorized,
                            code = "AUTH_REQUIRED",
                            message = "Authentication is required to change open access.",
                        )
                        return@post
                    }

                    securitySettingsManager.setOpenAccessEnabled(requestBody.enabled)
                    if (requestBody.enabled) {
                        sessionManager.clearAllSessions()
                        call.clearSessionCookie(config.security.authCookieName)
                    }
                    call.respondApiSuccess(
                        data =
                            buildJsonObject {
                                put("open_access_enabled", requestBody.enabled)
                                put("warning", "Open access exposes data to anyone on your local network.")
                            },
                        clock = clock,
                    )
                }

                post("/logout") {
                    call.extractSessionToken(config.security.authCookieName)?.let { token ->
                        sessionManager.revokeSession(token)
                    }
                    call.clearSessionCookie(config.security.authCookieName)
                    call.respondApiSuccess(
                        data =
                            buildJsonObject {
                                put("authenticated", false)
                            },
                        clock = clock,
                    )
                }
            }

            get("/today") {
                val (fingerprint, lastModified) =
                    syncEngine.conditionalFingerprint(
                        recordTypes =
                            setOf(
                                RecordType.STEPS,
                                RecordType.HEART_RATE,
                                RecordType.SLEEP,
                                RecordType.RESTING_HR,
                            ),
                        clock = clock,
                    )
                if (call.respondNotModifiedIfMatch(fingerprint, lastModified)) {
                    return@get
                }

                val today = LocalDate.now(clock.withZone(zoneId))
                val dayStart = today.atStartOfDay(zoneId).toInstant()
                val dayEnd = today.plusDays(1).atStartOfDay(zoneId).toInstant().minusNanos(1)

                val stepsToday = healthRepository.getTotalSteps(dayStart, dayEnd).first()
                val latestHeartRate = healthRepository.getLatestHeartRate().first()
                val latestSleep = healthRepository.getLatestSleepSession().first()
                val latestRestingHeartRate = healthRepository.getLatestRestingHeartRate().first()
                val syncStatus = syncEngine.getSyncStatus()

                call.respondApiSuccess(
                    data =
                        buildJsonObject {
                            put("date", today.toString())
                            put("steps_today", stepsToday)
                            put("latest_heart_rate", latestHeartRate?.toJson() ?: JsonNull)
                            put("latest_sleep", latestSleep?.toJson() ?: JsonNull)
                            put("latest_resting_hr", latestRestingHeartRate?.toJson() ?: JsonNull)
                        },
                    clock = clock,
                    meta = {
                        put("freshness", syncStatus.toFreshnessJson())
                    },
                )
            }

            get("/heartrate") {
                val range = call.parseInstantRange(zoneId = zoneId, clock = clock) ?: return@get
                val pagination = call.parsePagination(config) ?: return@get
                val (fingerprint, lastModified) =
                    syncEngine.conditionalFingerprint(
                        recordTypes = setOf(RecordType.HEART_RATE),
                        clock = clock,
                    )
                val etagSeed =
                    listOf(
                        "heartrate",
                        range.start,
                        range.end,
                        pagination.limit,
                        pagination.offset,
                        fingerprint,
                    ).joinToString(":")
                if (
                    call.respondNotModifiedIfMatch(
                        seed = etagSeed,
                        lastModified = lastModified,
                    )
                ) {
                    return@get
                }

                val samples =
                    healthRepository.getHeartRateSamples(
                        startTime = range.start,
                        endTime = range.end,
                        limit = pagination.limit,
                        offset = pagination.offset,
                    ).first()

                call.respondApiSuccess(
                    data = JsonArray(samples.map { it.toJson() }),
                    clock = clock,
                    meta = {
                        put("range_start", range.start.toString())
                        put("range_end", range.end.toString())
                        put("pagination", pagination.toJson(returnedCount = samples.size))
                    },
                )
            }

            get("/sleep") {
                val range = call.parseInstantRange(zoneId = zoneId, clock = clock) ?: return@get
                val pagination = call.parsePagination(config) ?: return@get
                val (fingerprint, lastModified) =
                    syncEngine.conditionalFingerprint(
                        recordTypes = setOf(RecordType.SLEEP),
                        clock = clock,
                    )
                val etagSeed =
                    listOf(
                        "sleep",
                        range.start,
                        range.end,
                        pagination.limit,
                        pagination.offset,
                        fingerprint,
                    ).joinToString(":")
                if (
                    call.respondNotModifiedIfMatch(
                        seed = etagSeed,
                        lastModified = lastModified,
                    )
                ) {
                    return@get
                }

                val sessions =
                    healthRepository.getSleepSessions(
                        startTime = range.start,
                        endTime = range.end,
                        limit = pagination.limit,
                        offset = pagination.offset,
                    ).first()

                call.respondApiSuccess(
                    data = JsonArray(sessions.map { it.toJson() }),
                    clock = clock,
                    meta = {
                        put("range_start", range.start.toString())
                        put("range_end", range.end.toString())
                        put("pagination", pagination.toJson(returnedCount = sessions.size))
                    },
                )
            }

            get("/steps") {
                val range = call.parseInstantRange(zoneId = zoneId, clock = clock) ?: return@get
                val pagination = call.parsePagination(config) ?: return@get
                val (fingerprint, lastModified) =
                    syncEngine.conditionalFingerprint(
                        recordTypes = setOf(RecordType.STEPS),
                        clock = clock,
                    )
                val etagSeed =
                    listOf(
                        "steps",
                        range.start,
                        range.end,
                        pagination.limit,
                        pagination.offset,
                        fingerprint,
                    ).joinToString(":")
                if (
                    call.respondNotModifiedIfMatch(
                        seed = etagSeed,
                        lastModified = lastModified,
                    )
                ) {
                    return@get
                }

                val records =
                    healthRepository.getStepsRecords(
                        startTime = range.start,
                        endTime = range.end,
                        limit = pagination.limit,
                        offset = pagination.offset,
                    ).first()

                call.respondApiSuccess(
                    data = JsonArray(records.map { it.toJson() }),
                    clock = clock,
                    meta = {
                        put("range_start", range.start.toString())
                        put("range_end", range.end.toString())
                        put("pagination", pagination.toJson(returnedCount = records.size))
                    },
                )
            }

            get("/resting-hr") {
                val range = call.parseDateRange(clock = clock, zoneId = zoneId) ?: return@get
                val pagination = call.parsePagination(config) ?: return@get
                val (fingerprint, lastModified) =
                    syncEngine.conditionalFingerprint(
                        recordTypes = setOf(RecordType.RESTING_HR),
                        clock = clock,
                    )
                val etagSeed =
                    listOf(
                        "resting_hr",
                        range.start,
                        range.end,
                        pagination.limit,
                        pagination.offset,
                        fingerprint,
                    ).joinToString(":")
                if (
                    call.respondNotModifiedIfMatch(
                        seed = etagSeed,
                        lastModified = lastModified,
                    )
                ) {
                    return@get
                }

                val records =
                    healthRepository.getRestingHeartRate(
                        startDate = range.start,
                        endDate = range.end,
                        limit = pagination.limit,
                        offset = pagination.offset,
                    ).first()

                call.respondApiSuccess(
                    data = JsonArray(records.map { it.toJson() }),
                    clock = clock,
                    meta = {
                        put("range_start", range.start.toString())
                        put("range_end", range.end.toString())
                        put("pagination", pagination.toJson(returnedCount = records.size))
                    },
                )
            }

            get("/hrv") {
                call.respondApiSuccess(
                    data = buildJsonArray { },
                    clock = clock,
                    meta = {
                        put("note", "HRV export/read is deferred until schema v1.1.")
                    },
                )
            }

            get("/aggregates") {
                val range = call.parseDateRange(clock = clock, zoneId = zoneId) ?: return@get
                val type = call.request.queryParameters["type"]
                if (type != null && type !in supportedRecordTypes()) {
                    call.respondApiError(
                        status = HttpStatusCode.BadRequest,
                        code = "INVALID_RECORD_TYPE",
                        message = "Unsupported record type '$type'.",
                    )
                    return@get
                }

                val records =
                    if (type != null) {
                        healthRepository.getDailyAggregates(
                            recordType = type,
                            startDate = range.start,
                            endDate = range.end,
                        ).first()
                    } else {
                        healthRepository.getAllDailyAggregates(
                            startDate = range.start,
                            endDate = range.end,
                        ).first()
                    }
                call.respondApiSuccess(
                    data = JsonArray(records.map { it.toJson() }),
                    clock = clock,
                    meta = {
                        put("range_start", range.start.toString())
                        put("range_end", range.end.toString())
                        if (type != null) {
                            put("record_type", type)
                        }
                    },
                )
            }

            get("/sync/status") {
                val syncStatus = syncEngine.getSyncStatus()
                call.respondApiSuccess(
                    data = JsonArray(syncStatus.map { it.toJson() }),
                    clock = clock,
                    meta = {
                        put("freshness", syncStatus.toFreshnessJson())
                    },
                )
            }

            post("/sync/trigger") {
                syncScheduler.triggerSyncNow()
                call.respondApiSuccess(
                    data =
                        buildJsonObject {
                            put("accepted", true)
                            put("queued_at", clock.instant().toString())
                        },
                    clock = clock,
                    status = HttpStatusCode.Accepted,
                )
            }

            get("/permissions") {
                val permissionState = permissionManager.getPermissionState()
                call.respondApiSuccess(
                    data =
                        buildJsonObject {
                            put("heart_rate", permissionState.heartRate.name.lowercase())
                            put("sleep", permissionState.sleep.name.lowercase())
                            put("steps", permissionState.steps.name.lowercase())
                            put("resting_heart_rate", permissionState.restingHeartRate.name.lowercase())
                            put("heart_rate_variability", permissionState.heartRateVariability.name.lowercase())
                            put("history", permissionState.historyPermission.name.lowercase())
                        },
                    clock = clock,
                )
            }

            get("/server/info") {
                val runtimeInfo = runtimeInfoProvider()
                val now = clock.instant()
                val uptimeSeconds = runtimeInfo?.let { java.time.Duration.between(it.startedAt, now).seconds } ?: 0L
                call.respondApiSuccess(
                    data =
                        buildJsonObject {
                            put("running", runtimeInfo != null)
                            put("bind_host", runtimeInfo?.bindHost ?: config.bindHost)
                            put("port", runtimeInfo?.port ?: 0)
                            put("dashboard_url", runtimeInfo?.dashboardUrl ?: "")
                            put("local_ip_address", runtimeInfo?.localIpAddress ?: "")
                            put("uptime_seconds", uptimeSeconds)
                            put("connected_clients", runtimeInfo?.connectedClients ?: 0)
                            put("phone_name", "${Build.MANUFACTURER} ${Build.MODEL}")
                            put("app_version", resolveAppVersion(context))
                        },
                    clock = clock,
                )
            }
        }

        registerExportRoutes(exportEngine)
    }
}

private fun supportedRecordTypes(): Set<String> =
    setOf(
        RecordType.HEART_RATE,
        RecordType.SLEEP,
        RecordType.STEPS,
        RecordType.RESTING_HR,
    )

internal fun resolveDashboardAssetPath(requestPath: String): String? {
    val prefix = "/dashboard/"
    if (!requestPath.startsWith(prefix)) {
        return "dashboard/index.html"
    }

    val rawRelativePath = requestPath.removePrefix(prefix)
    if (rawRelativePath.isBlank()) {
        return "dashboard/index.html"
    }

    val decodedRelativePath =
        runCatching {
            URLDecoder.decode(rawRelativePath, StandardCharsets.UTF_8.name())
        }.getOrDefault(rawRelativePath)

    val normalizedRelativePath = decodedRelativePath.trim().trim('/')
    if (normalizedRelativePath.isBlank()) {
        return "dashboard/index.html"
    }

    val segments = normalizedRelativePath.split('/').filter { it.isNotBlank() }
    if (segments.any { it == ".." }) {
        return null
    }

    return "dashboard/${segments.joinToString("/")}"
}

private suspend fun io.ktor.server.application.ApplicationCall.respondDashboardAsset(
    context: Context,
    assetPath: String,
) {
    val contentBytes =
        try {
            context.assets.open(assetPath).use { input ->
                input.readBytes()
            }
        } catch (_: FileNotFoundException) {
            null
        }

    if (contentBytes == null) {
        respondApiError(
            status = HttpStatusCode.NotFound,
            code = "ASSET_NOT_FOUND",
            message = "Dashboard asset '$assetPath' not found.",
        )
        return
    }

    val etag = weakEtagForSeed("$assetPath:${contentBytes.size}:${contentBytes.hashCode()}")
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.LastModified, httpDateHeaderValue(Instant.now()))
    response.header(HttpHeaders.CacheControl, cacheControlForAssetPath(assetPath))

    if (request.headers[HttpHeaders.IfNoneMatch] == etag) {
        respond(HttpStatusCode.NotModified)
        return
    }

    respondBytes(
        bytes = contentBytes,
        contentType = contentTypeForAssetPath(assetPath),
    )
}

private fun cacheControlForAssetPath(assetPath: String): String {
    return when (assetPath.substringAfterLast('.', missingDelimiterValue = "")) {
        "html", "css", "js" -> "no-cache"
        "png", "jpg", "jpeg", "svg", "ico", "woff", "woff2" ->
            "public, max-age=31536000, immutable"
        else -> "public, max-age=3600"
    }
}

private fun contentTypeForAssetPath(assetPath: String): ContentType {
    return when (assetPath.substringAfterLast('.', missingDelimiterValue = "")) {
        "html" -> ContentType.Text.Html
        "css" -> ContentType.Text.CSS
        "js" -> ContentType.Application.JavaScript
        "json" -> ContentType.Application.Json
        "png" -> ContentType.Image.PNG
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "svg" -> ContentType.Image.SVG
        "ico" -> ContentType.Image.XIcon
        else -> ContentType.Application.OctetStream
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondApiSuccess(
    data: JsonElement,
    clock: Clock,
    status: HttpStatusCode = HttpStatusCode.OK,
    meta: JsonObjectBuilder.() -> Unit = {},
) {
    val payload =
        buildJsonObject {
            put("ok", true)
            put("data", data)
            put(
                "meta",
                buildJsonObject {
                    put("generatedAt", clock.instant().toString())
                    meta()
                },
            )
        }
    respondJson(payload, status)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondApiError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    val payload =
        buildJsonObject {
            put("ok", false)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
            put(
                "meta",
                buildJsonObject {
                    put("path", request.uri)
                    put("generatedAt", Instant.now().toString())
                },
            )
        }
    respondJson(payload, status)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    payload: JsonObject,
    status: HttpStatusCode,
) {
    response.header(HttpHeaders.Connection, "keep-alive")
    respondText(
        text = json.encodeToString(payload),
        contentType = ContentType.Application.Json,
        status = status,
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondNotModifiedIfMatch(
    seed: String,
    lastModified: Instant,
): Boolean {
    val etag = weakEtagForSeed(seed)
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.LastModified, httpDateHeaderValue(lastModified))
    if (request.headers[HttpHeaders.IfNoneMatch] == etag) {
        respond(HttpStatusCode.NotModified)
        return true
    }
    return false
}

private fun weakEtagForSeed(seed: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
    val hex = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    return "W/\"${hex.take(32)}\""
}

private fun httpDateHeaderValue(instant: Instant): String {
    return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC))
}

private suspend fun SyncEngine.conditionalFingerprint(
    recordTypes: Set<String>,
    clock: Clock,
): Pair<String, Instant> {
    val syncStatus = getSyncStatus().filter { it.recordType in recordTypes }
    if (syncStatus.isEmpty()) {
        return "never_synced_${recordTypes.sorted().joinToString(",")}" to clock.instant()
    }

    val fingerprint =
        syncStatus
            .sortedBy { it.recordType }
            .joinToString(separator = "|") { cursor ->
                "${cursor.recordType}:${cursor.lastSyncTime}:${cursor.changeToken ?: ""}"
            }
    val lastModified = syncStatus.maxOf { it.lastSyncTime }
    return fingerprint to lastModified
}

private fun SyncCursor.toJson(): JsonObject =
    buildJsonObject {
        put("record_type", recordType)
        put("last_sync_time", lastSyncTime.toString())
        put("freshness", DataFreshness.getStaleness(lastSyncTime))
        put("has_change_token", changeToken != null)
    }

private fun List<SyncCursor>.toFreshnessJson(): JsonObject {
    val byType = associateBy { it.recordType }
    return buildJsonObject {
        supportedRecordTypes().forEach { type ->
            val lastSync = byType[type]?.lastSyncTime
            put(type, DataFreshness.getStaleness(lastSync))
        }
    }
}

private fun HeartRateSample.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("health_connect_id", healthConnectId)
        put("timestamp", timestamp.toString())
        put("bpm", bpm)
        put("source", source)
        put("synced_at", syncedAt.toString())
    }

private fun SleepSession.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("health_connect_id", healthConnectId)
        put("start_time", startTime.toString())
        put("end_time", endTime.toString())
        put("duration_ms", durationMs)
        put("source", source)
        put("synced_at", syncedAt.toString())
    }

private fun StepsRecord.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("health_connect_id", healthConnectId)
        put("start_time", startTime.toString())
        put("end_time", endTime.toString())
        put("count", count)
        put("source", source)
        put("synced_at", syncedAt.toString())
    }

private fun RestingHeartRate.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("health_connect_id", healthConnectId)
        put("date", date.toString())
        put("bpm", bpm)
        put("source", source)
        put("synced_at", syncedAt.toString())
    }

private fun DailyAggregate.toJson(): JsonObject =
    buildJsonObject {
        put("id", id)
        put("date", date.toString())
        put("record_type", recordType)
        put("value", value)
        put("count", count)
        put("min", min?.let { JsonPrimitive(it) } ?: JsonNull)
        put("max", max?.let { JsonPrimitive(it) } ?: JsonNull)
        put("avg", avg?.let { JsonPrimitive(it) } ?: JsonNull)
        put("updated_at", updatedAt.toString())
    }

private fun PaginationRequest.toJson(returnedCount: Int): JsonObject =
    buildJsonObject {
        put("limit", limit)
        put("offset", offset)
        put("returned", returnedCount)
    }

private suspend fun io.ktor.server.application.ApplicationCall.parsePagination(
    config: DashboardServerConfig,
): PaginationRequest? {
    val rawLimit = request.queryParameters["limit"]
    val rawOffset = request.queryParameters["offset"]

    val limit =
        rawLimit?.toIntOrNull() ?: config.defaultPageLimit
    val offset =
        rawOffset?.toIntOrNull() ?: 0

    if (limit <= 0 || limit > config.maxPageLimit) {
        respondApiError(
            status = HttpStatusCode.BadRequest,
            code = "INVALID_LIMIT",
            message = "Limit must be between 1 and ${config.maxPageLimit}.",
        )
        return null
    }

    if (offset < 0) {
        respondApiError(
            status = HttpStatusCode.BadRequest,
            code = "INVALID_OFFSET",
            message = "Offset must be 0 or greater.",
        )
        return null
    }

    return PaginationRequest(limit = limit, offset = offset)
}

private suspend fun io.ktor.server.application.ApplicationCall.parseInstantRange(
    zoneId: ZoneId,
    clock: Clock,
): InstantRange? {
    val now = clock.instant()
    val defaultStart = now.minusSeconds(30L * 24L * 60L * 60L)
    val rawFrom = request.queryParameters["from"]
    val rawTo = request.queryParameters["to"]

    val start =
        if (rawFrom == null) {
            defaultStart
        } else {
            rawFrom.toInstantOrNull(zoneId)
                ?: run {
                    respondApiError(
                        status = HttpStatusCode.BadRequest,
                        code = "INVALID_FROM",
                        message = "'from' must be ISO-8601 instant or date.",
                    )
                    return null
                }
        }
    val end =
        if (rawTo == null) {
            now
        } else {
            rawTo.toInstantOrNull(zoneId)
                ?: run {
                    respondApiError(
                        status = HttpStatusCode.BadRequest,
                        code = "INVALID_TO",
                        message = "'to' must be ISO-8601 instant or date.",
                    )
                    return null
                }
        }

    if (start.isAfter(end)) {
        respondApiError(
            status = HttpStatusCode.BadRequest,
            code = "INVALID_DATE_RANGE",
            message = "'from' must be on or before 'to'.",
        )
        return null
    }
    return InstantRange(start = start, end = end)
}

private suspend fun io.ktor.server.application.ApplicationCall.parseDateRange(
    clock: Clock,
    zoneId: ZoneId,
): DateRange? {
    val today = LocalDate.now(clock.withZone(zoneId))
    val defaultStart = today.minusDays(30)

    val start =
        request.queryParameters["from"]?.let { raw ->
            raw.toLocalDateOrNull()
                ?: run {
                    respondApiError(
                        status = HttpStatusCode.BadRequest,
                        code = "INVALID_FROM_DATE",
                        message = "'from' must be an ISO-8601 date (yyyy-MM-dd).",
                    )
                    return null
                }
        } ?: defaultStart

    val end =
        request.queryParameters["to"]?.let { raw ->
            raw.toLocalDateOrNull()
                ?: run {
                    respondApiError(
                        status = HttpStatusCode.BadRequest,
                        code = "INVALID_TO_DATE",
                        message = "'to' must be an ISO-8601 date (yyyy-MM-dd).",
                    )
                    return null
                }
        } ?: today

    if (start.isAfter(end)) {
        respondApiError(
            status = HttpStatusCode.BadRequest,
            code = "INVALID_DATE_RANGE",
            message = "'from' must be on or before 'to'.",
        )
        return null
    }

    return DateRange(start = start, end = end)
}

private fun String.toInstantOrNull(zoneId: ZoneId): Instant? {
    return runCatching { Instant.parse(this) }.getOrNull()
        ?: runCatching { LocalDate.parse(this).atStartOfDay(zoneId).toInstant() }.getOrNull()
}

private fun String.toLocalDateOrNull(): LocalDate? {
    return runCatching { LocalDate.parse(this) }.getOrNull()
}

private fun resolveAppVersion(context: Context): String {
    return runCatching {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName ?: packageInfo.longVersionCode.toString()
    }.getOrElse { "unknown" }
}

private fun resolveLocalHostAddressCandidates(): Set<String> {
    val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return emptySet()
    return Collections
        .list(interfaces)
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { networkInterface -> Collections.list(networkInterface.inetAddresses).asSequence() }
        .filterNot { address ->
            address.isLoopbackAddress ||
                address.isAnyLocalAddress ||
                address.isMulticastAddress
        }
        .mapNotNull { address -> address.hostAddress?.substringBefore('%') }
        .toSet()
}

private fun publicAuthPaths(): Set<String> =
    setOf(
        "/api/v1/auth/login",
        "/api/v1/auth/session",
        "/api/v1/auth/passcode",
        "/api/v1/auth/open-access",
    )

private fun io.ktor.server.application.ApplicationCall.applySecurityHeaders() {
    response.header("X-Content-Type-Options", "nosniff")
    response.header("X-Frame-Options", "DENY")
    response.header(
        "Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'",
    )
    response.header("Referrer-Policy", "no-referrer")
}

private fun io.ktor.server.application.ApplicationCall.extractSessionToken(cookieName: String): String? {
    val authHeader = request.headers[HttpHeaders.Authorization]
    val bearerToken =
        authHeader
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter("Bearer ", missingDelimiterValue = "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    if (!bearerToken.isNullOrBlank()) return bearerToken
    return request.cookies[cookieName]?.takeIf { it.isNotBlank() }
}

private fun io.ktor.server.application.ApplicationCall.resolveSession(
    sessionManager: SessionManager,
    cookieName: String,
): SessionInfo? {
    val token = extractSessionToken(cookieName) ?: return null
    return sessionManager.validateSession(token)
}

private fun io.ktor.server.application.ApplicationCall.appendSessionCookie(
    cookieName: String,
    session: SessionInfo,
) {
    val maxAgeSeconds =
        java.time.Duration.between(session.issuedAt, session.expiresAt).seconds
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    response.cookies.append(
        Cookie(
            name = cookieName,
            value = session.token,
            path = "/",
            httpOnly = true,
            maxAge = maxAgeSeconds,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

private fun io.ktor.server.application.ApplicationCall.clearSessionCookie(cookieName: String) {
    response.cookies.append(
        Cookie(
            name = cookieName,
            value = "",
            path = "/",
            httpOnly = true,
            maxAge = 0,
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
}

private suspend inline fun <reified T> io.ktor.server.application.ApplicationCall.receiveJsonBody(): T? {
    val rawBody = runCatching { receiveText() }.getOrNull()?.trim().orEmpty()
    if (rawBody.isBlank()) return null

    val unescapedQuotes = rawBody.replace("\\\"", "\"")
    val candidates =
        buildList {
            add(rawBody)
            add(rawBody.removeSurrounding("'"))
            add(rawBody.removeSurrounding("\""))
            add(unescapedQuotes)
            add(unescapedQuotes.removeSurrounding("\""))
            add(unescapedQuotes.removeSurrounding("'"))
        }.map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    candidates.forEach { candidate ->
        runCatching { json.decodeFromString<T>(candidate) }.getOrNull()?.let { return it }
    }

    val formValues = parseFormUrlEncoded(rawBody)
    if (formValues.isNotEmpty()) {
        val formAsJson =
            buildJsonObject {
                formValues.forEach { (key, value) ->
                    put(key, JsonPrimitive(value))
                }
            }
        return runCatching { json.decodeFromString<T>(formAsJson.toString()) }.getOrNull()
    }

    return null
}

private fun parseFormUrlEncoded(rawBody: String): Map<String, String> {
    val normalized =
        rawBody
            .trim()
            .removePrefix("?")
            .removeSurrounding("'")
            .removeSurrounding("\"")

    if (!normalized.contains("=")) return emptyMap()

    return normalized
        .split("&")
        .mapNotNull { pair ->
            val rawKey = pair.substringBefore("=", "").trim()
            if (rawKey.isBlank()) {
                return@mapNotNull null
            }

            val rawValue = pair.substringAfter("=", "")
            val key =
                runCatching {
                    URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                }.getOrDefault(rawKey)
            val value =
                runCatching {
                    URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                }.getOrDefault(rawValue)

            key to value
        }.toMap()
}

private fun JsonObjectBuilder.put(
    key: String,
    value: String,
) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.put(
    key: String,
    value: Int,
) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.put(
    key: String,
    value: Long,
) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.put(
    key: String,
    value: Double,
) {
    put(key, JsonPrimitive(value))
}

private fun JsonObjectBuilder.put(
    key: String,
    value: Boolean,
) {
    put(key, JsonPrimitive(value))
}

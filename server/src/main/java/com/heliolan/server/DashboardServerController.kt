package com.heliolan.server

import android.content.Context
import com.heliolan.data.repository.HealthRepository
import com.heliolan.healthconnect.permission.PermissionManager
import com.heliolan.healthconnect.reader.HealthConnectReader
import com.heliolan.server.export.ExportEngine
import com.heliolan.server.security.LoginAttemptTracker
import com.heliolan.server.security.NetworkSecurityValidator
import com.heliolan.server.security.SecuritySettingsManager
import com.heliolan.server.security.SessionManager
import com.heliolan.sync.engine.SyncEngine
import com.heliolan.sync.scheduler.SyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

interface DashboardServerController {
    suspend fun start(preferredPort: Int? = null): DashboardServerRuntimeInfo

    suspend fun stop()

    suspend fun restart(preferredPort: Int? = null): DashboardServerRuntimeInfo

    fun isRunning(): Boolean

    fun getRuntimeInfo(): DashboardServerRuntimeInfo?
}

@Singleton
class KtorDashboardServerController
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
        private val healthRepository: HealthRepository,
        private val syncScheduler: SyncScheduler,
        private val syncEngine: SyncEngine,
        private val permissionManager: PermissionManager,
        private val healthConnectReader: HealthConnectReader,
        private val exportEngine: ExportEngine,
        private val sessionManager: SessionManager,
        private val securitySettingsManager: SecuritySettingsManager,
        private val loginAttemptTracker: LoginAttemptTracker,
        private val networkSecurityValidator: NetworkSecurityValidator,
        private val lanAddressResolver: LanAddressResolver,
        private val tlsCertificateManager: TlsCertificateManager,
        private val config: DashboardServerConfig,
        private val clock: Clock,
        private val zoneId: ZoneId,
    ) : DashboardServerController {
        private val startStopMutex = Mutex()
        private val stateLock = Any()

        private var engine: ApplicationEngine? = null
        private var runtimeInfo: DashboardServerRuntimeInfo? = null
        private var clientTracker: ConnectedClientTracker = ConnectedClientTracker(clock)

        override suspend fun start(preferredPort: Int?): DashboardServerRuntimeInfo =
            startStopMutex.withLock {
                val existing = getRuntimeInfo()
                if (existing != null) {
                    return@withLock existing
                }

                val requestedConfig =
                    preferredPort?.let { config.copy(preferredPort = it) } ?: config
                val localIpAddress = lanAddressResolver.resolveLocalIpAddress()
                var lastError: Throwable? = null

                val portCandidates =
                    if (requestedConfig.tls.enabled) {
                        linkedSetOf(
                            requestedConfig.tls.preferredPort,
                            *requestedConfig.tls.fallbackPorts.toList().toTypedArray(),
                        ).toList()
                    } else {
                        resolvePortCandidates(requestedConfig)
                    }

                for (port in portCandidates) {
                    val candidateClientTracker = ConnectedClientTracker(clock)
                    val candidateRateLimiter =
                        ApiRateLimiter(
                            maxRequestsPerMinute = requestedConfig.maxRequestsPerMinutePerIp,
                            clock = clock,
                        )
                    val tlsConfig = requestedConfig.tls
                    val candidateEngine =
                        if (tlsConfig.enabled) {
                            val keyStore = tlsCertificateManager.loadOrCreateKeyStore(tlsConfig, localIpAddress)
                            val keyStoreFile = tlsCertificateManager.resolveKeyStoreFile(tlsConfig)
                            val environment =
                                applicationEngineEnvironment {
                                    sslConnector(
                                        keyStore = keyStore,
                                        keyAlias = tlsConfig.keyAlias,
                                        keyStorePassword = { tlsConfig.keyStorePassword.toCharArray() },
                                        privateKeyPassword = { tlsConfig.privateKeyPassword.toCharArray() },
                                    ) {
                                        host = requestedConfig.bindHost
                                        this.port = port
                                        keyStorePath = keyStoreFile
                                    }
                                    module {
                                        configureDashboardApplication(
                                            context = appContext,
                                            healthRepository = healthRepository,
                                            syncScheduler = syncScheduler,
                                            syncEngine = syncEngine,
                                            permissionManager = permissionManager,
                                            healthConnectReader = healthConnectReader,
                                            exportEngine = exportEngine,
                                            config = requestedConfig,
                                            rateLimiter = candidateRateLimiter,
                                            clientTracker = candidateClientTracker,
                                            sessionManager = sessionManager,
                                            securitySettingsManager = securitySettingsManager,
                                            loginAttemptTracker = loginAttemptTracker,
                                            networkSecurityValidator = networkSecurityValidator,
                                            localIpAddressProvider = { lanAddressResolver.resolveLocalIpAddress() },
                                            runtimeInfoProvider = { getRuntimeInfo() },
                                            clock = clock,
                                            zoneId = zoneId,
                                        )
                                    }
                                }
                            embeddedServer(CIO, environment)
                        } else {
                            embeddedServer(
                                factory = CIO,
                                host = requestedConfig.bindHost,
                                port = port,
                            ) {
                                configureDashboardApplication(
                                    context = appContext,
                                    healthRepository = healthRepository,
                                    syncScheduler = syncScheduler,
                                    syncEngine = syncEngine,
                                    permissionManager = permissionManager,
                                    healthConnectReader = healthConnectReader,
                                    exportEngine = exportEngine,
                                    config = requestedConfig,
                                    rateLimiter = candidateRateLimiter,
                                    clientTracker = candidateClientTracker,
                                    sessionManager = sessionManager,
                                    securitySettingsManager = securitySettingsManager,
                                    loginAttemptTracker = loginAttemptTracker,
                                    networkSecurityValidator = networkSecurityValidator,
                                    localIpAddressProvider = { lanAddressResolver.resolveLocalIpAddress() },
                                    runtimeInfoProvider = { getRuntimeInfo() },
                                    clock = clock,
                                    zoneId = zoneId,
                                )
                            }
                        }

                    try {
                        candidateEngine.start(wait = false)
                        val startedAt = clock.instant()
                        val url =
                            buildDashboardUrl(
                                localIpAddress = localIpAddress,
                                port = port,
                                tlsEnabled = requestedConfig.tls.enabled,
                            )
                        synchronized(stateLock) {
                            engine = candidateEngine
                            clientTracker = candidateClientTracker
                            runtimeInfo =
                                DashboardServerRuntimeInfo(
                                    bindHost = requestedConfig.bindHost,
                                    port = port,
                                    startedAt = startedAt,
                                    dashboardUrl = url,
                                    localIpAddress = localIpAddress,
                                    isTlsEnabled = requestedConfig.tls.enabled,
                                    connectedClients = 0,
                                )
                        }
                        return@withLock getRuntimeInfo()
                            ?: throw IllegalStateException("Server runtime info unavailable after start.")
                    } catch (error: Throwable) {
                        runCatching {
                            candidateEngine.stop(gracePeriodMillis = 250, timeoutMillis = 500)
                        }
                        lastError = error
                    }
                }

                throw IllegalStateException(
                    "Unable to bind embedded server to any configured port.",
                    lastError,
                )
            }

        override suspend fun stop() {
            startStopMutex.withLock {
                val runningEngine: ApplicationEngine? =
                    synchronized(stateLock) {
                        val snapshot = engine
                        engine = null
                        runtimeInfo = null
                        snapshot
                    }
                sessionManager.clearAllSessions()
                loginAttemptTracker.clearAll()
                runningEngine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 2_500)
            }
        }

        override suspend fun restart(preferredPort: Int?): DashboardServerRuntimeInfo {
            stop()
            return start(preferredPort)
        }

        override fun isRunning(): Boolean {
            synchronized(stateLock) {
                return engine != null
            }
        }

        override fun getRuntimeInfo(): DashboardServerRuntimeInfo? {
            synchronized(stateLock) {
                val current = runtimeInfo ?: return null
                val refreshedIp = lanAddressResolver.resolveLocalIpAddress()
                val refreshedUrl =
                    buildDashboardUrl(
                        localIpAddress = refreshedIp,
                        port = current.port,
                        tlsEnabled = current.isTlsEnabled,
                    )
                val refreshedClients = clientTracker.getActiveClientCount()
                if (
                    refreshedIp != current.localIpAddress ||
                    refreshedUrl != current.dashboardUrl ||
                    refreshedClients != current.connectedClients
                ) {
                    runtimeInfo =
                        current.copy(
                            localIpAddress = refreshedIp,
                            dashboardUrl = refreshedUrl,
                            connectedClients = refreshedClients,
                        )
                }
                return runtimeInfo
            }
        }

        private fun buildDashboardUrl(
            localIpAddress: String,
            port: Int,
            tlsEnabled: Boolean,
        ): String {
            val scheme = if (tlsEnabled) "https" else "http"
            return "$scheme://$localIpAddress:$port/dashboard/"
        }
    }

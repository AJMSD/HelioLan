package com.heliolan.server

import java.time.Instant

data class DashboardServerConfig(
    val bindHost: String = "0.0.0.0",
    val preferredPort: Int = 8080,
    val fallbackPorts: IntRange = 8081..8090,
    val defaultPageLimit: Int = 500,
    val maxPageLimit: Int = 5000,
    val maxRequestsPerMinutePerIp: Int = 60,
    val tls: DashboardTlsConfig = DashboardTlsConfig(),
    val security: DashboardSecurityConfig = DashboardSecurityConfig(),
)

data class DashboardTlsConfig(
    val enabled: Boolean = false,
    val preferredPort: Int = 8443,
    val fallbackPorts: IntRange = 8444..8450,
    val keyAlias: String = "heliolan_tls",
    val keyStoreFileName: String = "heliolan-dashboard.jks",
    val keyStorePassword: String = "changeit",
    val privateKeyPassword: String = "changeit",
    val validityDays: Long = 3650,
)

data class DashboardSecurityConfig(
    val passcodeMinDigits: Int = 4,
    val passcodeMaxDigits: Int = 8,
    val sessionTtlHours: Long = 24,
    val authCookieName: String = "heliolan_session",
    val maxFailedLoginAttempts: Int = 5,
    val lockoutMinutes: Long = 5,
    val openAccessByDefault: Boolean = false,
)

data class DashboardServerRuntimeInfo(
    val bindHost: String,
    val port: Int,
    val startedAt: Instant,
    val dashboardUrl: String,
    val localIpAddress: String,
    val isTlsEnabled: Boolean,
    val connectedClients: Int,
)

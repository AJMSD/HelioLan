package com.heliolan.server.security

import com.heliolan.server.DashboardSecurityConfig
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class SessionInfo(
    val token: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

@Singleton
class SessionManager
    @Inject
    constructor(
        private val securityConfig: DashboardSecurityConfig,
        private val clock: Clock,
    ) {
        private val random = SecureRandom()
        private val sessionByToken = ConcurrentHashMap<String, SessionInfo>()
        private val sessionTtl: Duration = Duration.ofHours(securityConfig.sessionTtlHours)

        fun issueSession(): SessionInfo {
            val now = clock.instant()
            val token = createToken()
            val session =
                SessionInfo(
                    token = token,
                    issuedAt = now,
                    expiresAt = now.plus(sessionTtl),
                )
            sessionByToken[token] = session
            return session
        }

        fun validateSession(token: String): SessionInfo? {
            val session = sessionByToken[token] ?: return null
            if (session.expiresAt.isAfter(clock.instant())) {
                return session
            }
            sessionByToken.remove(token)
            return null
        }

        fun revokeSession(token: String) {
            sessionByToken.remove(token)
        }

        fun clearAllSessions() {
            sessionByToken.clear()
        }

        fun activeSessionCount(): Int {
            pruneExpiredSessions()
            return sessionByToken.size
        }

        fun pruneExpiredSessions() {
            val now = clock.instant()
            sessionByToken.entries.removeIf { (_, session) -> !session.expiresAt.isAfter(now) }
        }

        private fun createToken(): String {
            val bytes = ByteArray(32) // 256-bit
            random.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

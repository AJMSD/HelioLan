package com.heliolan.server

import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ApiRateLimitDecision(
    val allowed: Boolean,
    val retryAfterSeconds: Long = 0,
)

/**
 * Fixed-window (1 minute) in-memory rate limiter keyed by client IP.
 */
class ApiRateLimiter(
    private val maxRequestsPerMinute: Int = 60,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class Entry(
        var windowEpochMinute: Long,
        var count: Int,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun tryConsume(clientIp: String): ApiRateLimitDecision {
        val now = clock.instant()
        val currentWindow = now.epochSecond / 60

        val entry =
            entries.compute(clientIp) { _, existing ->
                when {
                    existing == null -> Entry(windowEpochMinute = currentWindow, count = 1)
                    existing.windowEpochMinute != currentWindow -> {
                        existing.windowEpochMinute = currentWindow
                        existing.count = 1
                        existing
                    }
                    else -> {
                        existing.count += 1
                        existing
                    }
                }
            } ?: return ApiRateLimitDecision(allowed = false, retryAfterSeconds = 60)

        if (entry.count <= maxRequestsPerMinute) {
            return ApiRateLimitDecision(allowed = true)
        }

        val currentSecond = now.epochSecond
        val retryAfter = 60 - (currentSecond % 60)
        return ApiRateLimitDecision(
            allowed = false,
            retryAfterSeconds = retryAfter.coerceAtLeast(1),
        )
    }

    fun pruneInactiveEntries(olderThan: Instant) {
        val cutoffMinute = olderThan.epochSecond / 60
        entries.entries.removeIf { (_, entry) -> entry.windowEpochMinute < cutoffMinute }
    }
}

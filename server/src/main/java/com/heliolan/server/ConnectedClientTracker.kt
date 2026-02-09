package com.heliolan.server

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class ConnectedClientTracker(
    private val clock: Clock = Clock.systemUTC(),
    private val activityWindow: Duration = Duration.ofMinutes(5),
) {
    private val lastSeenByIp = ConcurrentHashMap<String, Instant>()

    fun recordClient(clientIp: String) {
        lastSeenByIp[clientIp] = clock.instant()
    }

    fun getActiveClientCount(): Int {
        val cutoff = clock.instant().minus(activityWindow)
        lastSeenByIp.entries.removeIf { (_, lastSeen) -> lastSeen.isBefore(cutoff) }
        return lastSeenByIp.size
    }
}

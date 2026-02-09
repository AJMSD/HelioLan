package com.heliolan.server

internal fun resolvePortCandidates(config: DashboardServerConfig): List<Int> {
    val ports = LinkedHashSet<Int>()
    ports += config.preferredPort
    ports += config.fallbackPorts
    return ports.filter { it in 1..65535 }
}

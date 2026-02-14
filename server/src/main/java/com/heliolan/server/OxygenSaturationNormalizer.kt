package com.heliolan.server

internal fun normalizeOxygenPercentage(rawValue: Double): Double {
    if (!rawValue.isFinite()) return 0.0
    val scaled = if (rawValue <= 1.0) rawValue * 100.0 else rawValue
    return scaled.coerceIn(0.0, 100.0)
}

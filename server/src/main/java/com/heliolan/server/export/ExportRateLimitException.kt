package com.heliolan.server.export

/**
 * Thrown when export is attempted within the configured cooldown window.
 */
class ExportRateLimitException(
    val retryAfterSeconds: Long,
) : IllegalStateException(
        "Export rate limit exceeded. Retry after $retryAfterSeconds second(s).",
    )

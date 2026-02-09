package com.heliolan.server.export

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Phase 7 export endpoints.
 *
 * Routes:
 * - GET /api/v1/export/csv?type=&from=&to=
 * - GET /api/v1/export/all?from=&to=
 */
fun Route.registerExportRoutes(exportEngine: ExportEngine) {
    route("/api/v1/export") {
        get("/csv") {
            val typeParam = call.request.queryParameters["type"]
            if (typeParam.isNullOrBlank()) {
                call.respondError(
                    status = HttpStatusCode.BadRequest,
                    code = "MISSING_TYPE",
                    message = "Query parameter 'type' is required.",
                )
                return@get
            }

            val exportType = ExportMetricType.fromRecordType(typeParam)
            if (exportType == null) {
                call.respondError(
                    status = HttpStatusCode.BadRequest,
                    code = "UNSUPPORTED_TYPE",
                    message = "Unsupported export type '$typeParam'.",
                )
                return@get
            }

            val dateRange = call.parseDateRange() ?: return@get

            try {
                val csvFile = exportEngine.exportCsv(exportType, dateRange)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"${csvFile.name}\"",
                )
                call.respondOutputStream(contentType = ContentType.parse("text/csv")) {
                    csvFile.inputStream().use { input ->
                        input.copyTo(this)
                    }
                }
                csvFile.delete()
            } catch (rateLimitError: ExportRateLimitException) {
                call.response.header(HttpHeaders.RetryAfter, rateLimitError.retryAfterSeconds.toString())
                call.respondError(
                    status = HttpStatusCode.TooManyRequests,
                    code = "EXPORT_RATE_LIMITED",
                    message = rateLimitError.message ?: "Export rate limit exceeded.",
                )
            }
        }

        get("/all") {
            val dateRange = call.parseDateRange() ?: return@get
            try {
                val zipFile = exportEngine.exportAll(dateRange)
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    "attachment; filename=\"${zipFile.name}\"",
                )
                call.respondOutputStream(contentType = ContentType.parse("application/zip")) {
                    zipFile.inputStream().use { input ->
                        input.copyTo(this)
                    }
                }
                zipFile.delete()
            } catch (rateLimitError: ExportRateLimitException) {
                call.response.header(HttpHeaders.RetryAfter, rateLimitError.retryAfterSeconds.toString())
                call.respondError(
                    status = HttpStatusCode.TooManyRequests,
                    code = "EXPORT_RATE_LIMITED",
                    message = rateLimitError.message ?: "Export rate limit exceeded.",
                )
            }
        }
    }
}

private suspend fun ApplicationCall.parseDateRange(): ClosedRange<LocalDate>? {
    val fromParam = request.queryParameters["from"]
    val toParam = request.queryParameters["to"]
    if (fromParam.isNullOrBlank() || toParam.isNullOrBlank()) {
        respondError(
            status = HttpStatusCode.BadRequest,
            code = "MISSING_DATE_RANGE",
            message = "Query parameters 'from' and 'to' are required in ISO-8601 date format.",
        )
        return null
    }

    val startDate: LocalDate
    val endDate: LocalDate
    try {
        startDate = LocalDate.parse(fromParam)
        endDate = LocalDate.parse(toParam)
    } catch (_: DateTimeParseException) {
        respondError(
            status = HttpStatusCode.BadRequest,
            code = "INVALID_DATE_RANGE",
            message = "Date parameters must use ISO-8601 format (yyyy-MM-dd).",
        )
        return null
    }

    if (startDate.isAfter(endDate)) {
        respondError(
            status = HttpStatusCode.BadRequest,
            code = "INVALID_DATE_RANGE",
            message = "'from' must be on or before 'to'.",
        )
        return null
    }

    return startDate..endDate
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    val escapedMessage = message.replace("\"", "\\\"")
    val escapedCode = code.replace("\"", "\\\"")
    val payload =
        "{" +
            "\"ok\":false," +
            "\"error\":{\"code\":\"$escapedCode\",\"message\":\"$escapedMessage\"}," +
            "\"meta\":{\"path\":\"${request.uri}\"}" +
            "}"
    respondText(
        text = payload,
        contentType = ContentType.Application.Json,
        status = status,
    )
}

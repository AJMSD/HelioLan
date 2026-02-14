package com.heliolan.server.export

import com.google.common.truth.Truth.assertThat
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Test
import java.io.File
import java.time.LocalDate

class ExportRoutesTest {
    @Test
    fun csvRoute_returnsBadRequestWhenTypeMissing() =
        testApplication {
            val exportEngine = mockk<ExportEngine>()
            application {
                routing {
                    registerExportRoutes(exportEngine)
                }
            }

            val response =
                client.get("/api/v1/export/csv") {
                    parameter("from", "2026-02-01")
                    parameter("to", "2026-02-10")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("\"code\":\"MISSING_TYPE\"")
        }

    @Test
    fun csvRoute_returnsBadRequestForUnsupportedType() =
        testApplication {
            val exportEngine = mockk<ExportEngine>()
            application {
                routing {
                    registerExportRoutes(exportEngine)
                }
            }

            val response =
                client.get("/api/v1/export/csv") {
                    parameter("type", "unknown_type")
                    parameter("from", "2026-02-01")
                    parameter("to", "2026-02-10")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("\"code\":\"UNSUPPORTED_TYPE\"")
        }

    @Test
    fun csvRoute_acceptsExpandedMetricTypeHrv() =
        testApplication {
            val exportEngine = mockk<ExportEngine>()
            val csvFile = File.createTempFile("heliolan-export-hrv", ".csv")
            csvFile.writeText("health_connect_id,rmssd_ms\nhrv-1,32.1\n")

            coEvery {
                exportEngine.exportCsv(
                    ExportMetricType.HRV,
                    LocalDate.of(2026, 2, 1)..LocalDate.of(2026, 2, 10),
                )
            } returns csvFile

            application {
                routing {
                    registerExportRoutes(exportEngine)
                }
            }

            val response =
                client.get("/api/v1/export/csv") {
                    parameter("type", "hrv")
                    parameter("from", "2026-02-01")
                    parameter("to", "2026-02-10")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(response.bodyAsText()).contains("rmssd_ms")
            assertThat(csvFile.exists()).isFalse()
        }

    @Test
    fun csvRoute_returnsBadRequestForInvalidDateRange() =
        testApplication {
            val exportEngine = mockk<ExportEngine>()
            application {
                routing {
                    registerExportRoutes(exportEngine)
                }
            }

            val response =
                client.get("/api/v1/export/csv") {
                    parameter("type", "steps")
                    parameter("from", "2026-02-10")
                    parameter("to", "2026-02-01")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("\"code\":\"INVALID_DATE_RANGE\"")
        }

    @Test
    fun csvRoute_returnsTooManyRequestsWhenExportRateLimited() =
        testApplication {
            val exportEngine = mockk<ExportEngine>()
            coEvery {
                exportEngine.exportCsv(
                    ExportMetricType.STEPS,
                    LocalDate.of(2026, 2, 1)..LocalDate.of(2026, 2, 10),
                )
            } throws ExportRateLimitException(retryAfterSeconds = 17L)

            application {
                routing {
                    registerExportRoutes(exportEngine)
                }
            }

            val response =
                client.get("/api/v1/export/csv") {
                    parameter("type", "steps")
                    parameter("from", "2026-02-01")
                    parameter("to", "2026-02-10")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.TooManyRequests)
            assertThat(response.headers[HttpHeaders.RetryAfter]).isEqualTo("17")
            assertThat(response.bodyAsText()).contains("\"code\":\"EXPORT_RATE_LIMITED\"")
        }

    @Test
    fun csvRoute_returnsStructuredServerErrorWhenExportFailsUnexpectedly() =
        testApplication {
            val exportEngine = mockk<ExportEngine>()
            coEvery {
                exportEngine.exportCsv(
                    ExportMetricType.STEPS,
                    LocalDate.of(2026, 2, 1)..LocalDate.of(2026, 2, 10),
                )
            } throws IllegalStateException("disk full")

            application {
                routing {
                    registerExportRoutes(exportEngine)
                }
            }

            val response =
                client.get("/api/v1/export/csv") {
                    parameter("type", "steps")
                    parameter("from", "2026-02-01")
                    parameter("to", "2026-02-10")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
            assertThat(response.bodyAsText()).contains("\"code\":\"EXPORT_FAILED\"")
        }

    @Test
    fun csvRoute_streamsCsvFileAndSetsAttachmentHeaders() =
        testApplication {
            val exportEngine = mockk<ExportEngine>()
            val csvFile = File.createTempFile("heliolan-export", ".csv")
            csvFile.writeText("health_connect_id,bpm\nhr-1,72\n")

            coEvery {
                exportEngine.exportCsv(
                    ExportMetricType.STEPS,
                    LocalDate.of(2026, 2, 1)..LocalDate.of(2026, 2, 10),
                )
            } returns csvFile

            application {
                routing {
                    registerExportRoutes(exportEngine)
                }
            }

            val response =
                client.get("/api/v1/export/csv") {
                    parameter("type", "steps")
                    parameter("from", "2026-02-01")
                    parameter("to", "2026-02-10")
                }

            assertThat(response.status).isEqualTo(HttpStatusCode.OK)
            assertThat(
                response.headers[HttpHeaders.ContentDisposition],
            ).contains("attachment; filename=\"${csvFile.name}\"")
            assertThat(response.bodyAsText()).contains("health_connect_id,bpm")
            assertThat(csvFile.exists()).isFalse()
        }

    @Test
    fun allRoute_requiresDateRangeParameters() =
        testApplication {
            val exportEngine = mockk<ExportEngine>()
            application {
                routing {
                    registerExportRoutes(exportEngine)
                }
            }

            val response = client.get("/api/v1/export/all")

            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(response.bodyAsText()).contains("\"code\":\"MISSING_DATE_RANGE\"")
        }
}

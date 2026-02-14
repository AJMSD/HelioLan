package com.heliolan.server.export

import com.heliolan.data.entity.ActiveCaloriesBurned
import com.heliolan.data.entity.DistanceRecord
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.HrvRecord
import com.heliolan.data.entity.NutritionRecord
import com.heliolan.data.entity.OxygenSaturation
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.entity.TotalCaloriesBurned
import com.heliolan.data.repository.HealthRepository
import com.heliolan.server.normalizeOxygenPercentage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 7 export engine.
 * Produces CSV files for single metrics and ZIP bundles for all metrics.
 */
class ExportEngine(
    private val healthRepository: HealthRepository,
    private val outputDirectory: File = defaultOutputDirectory(),
    private val clock: Clock = Clock.systemUTC(),
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    companion object {
        private const val EXPORT_PAGE_SIZE = 1000
        private const val EXPORT_COOLDOWN_SECONDS = 30L

        private fun defaultOutputDirectory(): File {
            val tmpDirectory = System.getProperty("java.io.tmpdir") ?: "."
            return File(tmpDirectory, "heliolan_exports")
        }

        private val SUPPORTED_TYPES =
            listOf(
                ExportMetricType.HEART_RATE,
                ExportMetricType.SLEEP,
                ExportMetricType.STEPS,
                ExportMetricType.RESTING_HEART_RATE,
                ExportMetricType.ACTIVE_CALORIES,
                ExportMetricType.DISTANCE,
                ExportMetricType.TOTAL_CALORIES,
                ExportMetricType.NUTRITION,
                ExportMetricType.OXYGEN_SATURATION,
                ExportMetricType.HRV,
            )
    }

    private val rateLimitMutex = Mutex()
    private var lastExportAt: Instant? = null

    init {
        outputDirectory.mkdirs()
    }

    suspend fun exportCsv(
        type: ExportMetricType,
        dateRange: ClosedRange<LocalDate>,
    ): File =
        withContext(Dispatchers.IO) {
            validateDateRange(dateRange)
            enforceRateLimit()
            writeCsv(type, dateRange)
        }

    suspend fun exportAll(dateRange: ClosedRange<LocalDate>): File =
        withContext(Dispatchers.IO) {
            validateDateRange(dateRange)
            enforceRateLimit()

            val csvFilesByType = mutableMapOf<ExportMetricType, File>()
            try {
                SUPPORTED_TYPES.forEach { type ->
                    csvFilesByType[type] = writeCsv(type, dateRange)
                }
                writeZipBundle(csvFilesByType, dateRange)
            } finally {
                csvFilesByType.values.forEach { csvFile ->
                    if (csvFile.exists()) {
                        csvFile.delete()
                    }
                }
            }
        }

    private suspend fun enforceRateLimit() {
        rateLimitMutex.withLock {
            val now = clock.instant()
            val previousExportAt = lastExportAt
            if (previousExportAt != null) {
                val elapsedSeconds = Duration.between(previousExportAt, now).seconds.coerceAtLeast(0)
                if (elapsedSeconds < EXPORT_COOLDOWN_SECONDS) {
                    val retryAfterSeconds = EXPORT_COOLDOWN_SECONDS - elapsedSeconds
                    throw ExportRateLimitException(retryAfterSeconds)
                }
            }
            lastExportAt = now
        }
    }

    private suspend fun writeCsv(
        type: ExportMetricType,
        dateRange: ClosedRange<LocalDate>,
    ): File {
        val timestamp = clock.instant().toEpochMilli()
        val csvFile =
            File(
                outputDirectory,
                "${type.filePrefix}_${dateRange.start}_${dateRange.endInclusive}_$timestamp.csv",
            )

        BufferedWriter(
            OutputStreamWriter(
                FileOutputStream(csvFile),
                StandardCharsets.UTF_8,
            ),
        ).use { writer ->
            when (type) {
                ExportMetricType.HEART_RATE -> writeHeartRateCsv(writer, dateRange)
                ExportMetricType.SLEEP -> writeSleepCsv(writer, dateRange)
                ExportMetricType.STEPS -> writeStepsCsv(writer, dateRange)
                ExportMetricType.RESTING_HEART_RATE -> writeRestingHeartRateCsv(writer, dateRange)
                ExportMetricType.ACTIVE_CALORIES -> writeActiveCaloriesCsv(writer, dateRange)
                ExportMetricType.DISTANCE -> writeDistanceCsv(writer, dateRange)
                ExportMetricType.TOTAL_CALORIES -> writeTotalCaloriesCsv(writer, dateRange)
                ExportMetricType.NUTRITION -> writeNutritionCsv(writer, dateRange)
                ExportMetricType.OXYGEN_SATURATION -> writeOxygenSaturationCsv(writer, dateRange)
                ExportMetricType.HRV -> writeHrvCsv(writer, dateRange)
            }
        }

        return csvFile
    }

    private suspend fun writeHeartRateCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        val (startInstant, endInstant) = toInstantBounds(dateRange)
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "timestamp",
                "bpm",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getHeartRateSamples(
                    startTime = startInstant,
                    endTime = endInstant,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { sample ->
                sample.toCsvColumns().also { columns ->
                    writer.writeCsvLine(columns)
                }
            },
        )
    }

    private suspend fun writeSleepCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        val (startInstant, endInstant) = toInstantBounds(dateRange)
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "start_time",
                "end_time",
                "duration_ms",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getSleepSessions(
                    startTime = startInstant,
                    endTime = endInstant,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { session ->
                session.toCsvColumns().also { columns ->
                    writer.writeCsvLine(columns)
                }
            },
        )
    }

    private suspend fun writeStepsCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        val (startInstant, endInstant) = toInstantBounds(dateRange)
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "start_time",
                "end_time",
                "count",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getStepsRecords(
                    startTime = startInstant,
                    endTime = endInstant,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { record ->
                record.toCsvColumns().also { columns ->
                    writer.writeCsvLine(columns)
                }
            },
        )
    }

    private suspend fun writeRestingHeartRateCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "date",
                "bpm",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getRestingHeartRate(
                    startDate = dateRange.start,
                    endDate = dateRange.endInclusive,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { record ->
                record.toCsvColumns().also { columns ->
                    writer.writeCsvLine(columns)
                }
            },
        )
    }

    private suspend fun writeActiveCaloriesCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "date",
                "calories_kcal",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getActiveCaloriesBurned(
                    startDate = dateRange.start,
                    endDate = dateRange.endInclusive,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { record ->
                writer.writeCsvLine(record.toCsvColumns())
            },
        )
    }

    private suspend fun writeDistanceCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        val (startInstant, endInstant) = toInstantBounds(dateRange)
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "start_time",
                "end_time",
                "distance_meters",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getDistanceRecords(
                    startTime = startInstant,
                    endTime = endInstant,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { record ->
                writer.writeCsvLine(record.toCsvColumns())
            },
        )
    }

    private suspend fun writeTotalCaloriesCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        val (startInstant, endInstant) = toInstantBounds(dateRange)
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "start_time",
                "end_time",
                "energy_kcal",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getTotalCaloriesBurnedRecords(
                    startTime = startInstant,
                    endTime = endInstant,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { record ->
                writer.writeCsvLine(record.toCsvColumns())
            },
        )
    }

    private suspend fun writeNutritionCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        val (startInstant, endInstant) = toInstantBounds(dateRange)
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "start_time",
                "end_time",
                "energy_kcal",
                "protein_grams",
                "carbs_grams",
                "fat_grams",
                "meal_type",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getNutritionRecords(
                    startTime = startInstant,
                    endTime = endInstant,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { record ->
                writer.writeCsvLine(record.toCsvColumns())
            },
        )
    }

    private suspend fun writeOxygenSaturationCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        val (startInstant, endInstant) = toInstantBounds(dateRange)
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "timestamp",
                "percentage",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getOxygenSaturationRecords(
                    startTime = startInstant,
                    endTime = endInstant,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { record ->
                writer.writeCsvLine(record.toCsvColumns())
            },
        )
    }

    private suspend fun writeHrvCsv(
        writer: BufferedWriter,
        dateRange: ClosedRange<LocalDate>,
    ) {
        val (startInstant, endInstant) = toInstantBounds(dateRange)
        writer.writeCsvLine(
            listOf(
                "health_connect_id",
                "timestamp",
                "rmssd_ms",
                "source",
                "synced_at",
            ),
        )

        writePagedRows(
            fetchPage = { offset, limit ->
                healthRepository.getHrvRecords(
                    startTime = startInstant,
                    endTime = endInstant,
                    limit = limit,
                    offset = offset,
                ).first()
            },
            writeRow = { record ->
                writer.writeCsvLine(record.toCsvColumns())
            },
        )
    }

    private suspend fun <T> writePagedRows(
        fetchPage: suspend (offset: Int, limit: Int) -> List<T>,
        writeRow: (T) -> Unit,
    ) {
        var offset = 0
        while (true) {
            val records = fetchPage(offset, EXPORT_PAGE_SIZE)
            if (records.isEmpty()) {
                return
            }

            records.forEach(writeRow)
            if (records.size < EXPORT_PAGE_SIZE) {
                return
            }
            offset += records.size
        }
    }

    private fun writeZipBundle(
        csvFilesByType: Map<ExportMetricType, File>,
        dateRange: ClosedRange<LocalDate>,
    ): File {
        val timestamp = clock.instant().toEpochMilli()
        val zipFile =
            File(
                outputDirectory,
                "export_all_${dateRange.start}_${dateRange.endInclusive}_$timestamp.zip",
            )

        ZipOutputStream(
            BufferedOutputStream(FileOutputStream(zipFile)),
        ).use { zipOutput ->
            SUPPORTED_TYPES.forEach { type ->
                val csvFile = csvFilesByType[type] ?: return@forEach
                zipOutput.putNextEntry(ZipEntry("${type.filePrefix}.csv"))
                FileInputStream(csvFile).use { input ->
                    input.copyTo(zipOutput)
                }
                zipOutput.closeEntry()
            }
        }

        return zipFile
    }

    private fun validateDateRange(dateRange: ClosedRange<LocalDate>) {
        require(!dateRange.start.isAfter(dateRange.endInclusive)) {
            "Invalid date range: start date must be on or before end date."
        }
    }

    private fun toInstantBounds(dateRange: ClosedRange<LocalDate>): Pair<Instant, Instant> {
        val start = dateRange.start.atStartOfDay(zoneId).toInstant()
        val endInclusive =
            dateRange
                .endInclusive
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .minusNanos(1)
        return start to endInclusive
    }
}

private fun HeartRateSample.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        timestamp.toString(),
        bpm.toString(),
        source,
        syncedAt.toString(),
    )

private fun SleepSession.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        startTime.toString(),
        endTime.toString(),
        durationMs.toString(),
        source,
        syncedAt.toString(),
    )

private fun StepsRecord.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        startTime.toString(),
        endTime.toString(),
        count.toString(),
        source,
        syncedAt.toString(),
    )

private fun RestingHeartRate.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        date.toString(),
        bpm.toString(),
        source,
        syncedAt.toString(),
    )

private fun ActiveCaloriesBurned.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        date.toString(),
        calories.toString(),
        source,
        syncedAt.toString(),
    )

private fun DistanceRecord.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        startTime.toString(),
        endTime.toString(),
        distanceMeters.toString(),
        source,
        syncedAt.toString(),
    )

private fun TotalCaloriesBurned.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        startTime.toString(),
        endTime.toString(),
        energyKcal.toString(),
        source,
        syncedAt.toString(),
    )

private fun NutritionRecord.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        startTime.toString(),
        endTime.toString(),
        energyKcal?.toString().orEmpty(),
        proteinGrams?.toString().orEmpty(),
        carbsGrams?.toString().orEmpty(),
        fatGrams?.toString().orEmpty(),
        mealType.orEmpty(),
        source,
        syncedAt.toString(),
    )

private fun OxygenSaturation.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        timestamp.toString(),
        normalizeOxygenPercentage(percentage).toString(),
        source,
        syncedAt.toString(),
    )

private fun HrvRecord.toCsvColumns(): List<String> =
    listOf(
        healthConnectId,
        timestamp.toString(),
        rmssd.toString(),
        source,
        syncedAt.toString(),
    )

private fun BufferedWriter.writeCsvLine(columns: List<String>) {
    write(columns.joinToString(",") { it.escapeCsvField() })
    newLine()
}

private fun String.escapeCsvField(): String {
    val hasSpecialCharacters =
        contains(',') || contains('"') || contains('\n') || contains('\r')
    if (!hasSpecialCharacters) {
        return this
    }
    return "\"${replace("\"", "\"\"")}\""
}

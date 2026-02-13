package com.heliolan.app.crash

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class CrashLogStore(
    private val crashDirectory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val maxFiles: Int = 20,
) {
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", Locale.US)
            .withZone(ZoneOffset.UTC)

    @Synchronized
    fun writeCrash(
        threadName: String,
        throwable: Throwable,
    ): File? {
        return runCatching {
            if (!crashDirectory.exists()) {
                crashDirectory.mkdirs()
            }

            val timestamp = clock.instant()
            val fileName = "crash-${timestampFormatter.format(timestamp)}-${timestamp.toEpochMilli()}.log"
            val output = File(crashDirectory, fileName)
            output.writeText(buildCrashReport(threadName, timestamp, throwable))
            pruneOldLogsIfNeeded()
            output
        }.getOrNull()
    }

    fun listCrashLogs(): List<File> {
        return crashDirectory
            .listFiles { file ->
                file.isFile && file.name.startsWith("crash-") && file.name.endsWith(".log")
            }?.sortedByDescending { file -> file.lastModified() }
            ?: emptyList()
    }

    @Synchronized
    private fun pruneOldLogsIfNeeded() {
        val logs = listCrashLogs()
        if (logs.size <= maxFiles) {
            return
        }
        logs.drop(maxFiles).forEach { file ->
            file.delete()
        }
    }

    private fun buildCrashReport(
        threadName: String,
        timestamp: Instant,
        throwable: Throwable,
    ): String {
        val stackTrace =
            StringWriter().use { writer ->
                PrintWriter(writer).use { printer ->
                    throwable.printStackTrace(printer)
                }
                writer.toString().trim()
            }

        return buildString {
            appendLine("timestamp_utc=$timestamp")
            appendLine("thread=$threadName")
            appendLine("stacktrace=")
            appendLine(stackTrace)
        }
    }
}

package com.heliolan.server.export

import com.heliolan.data.util.RecordType

/**
 * Exportable metric types and their API/file identifiers.
 */
enum class ExportMetricType(
    val recordType: String,
    val filePrefix: String,
) {
    HEART_RATE(
        recordType = RecordType.HEART_RATE,
        filePrefix = "heart_rate",
    ),
    SLEEP(
        recordType = RecordType.SLEEP,
        filePrefix = "sleep",
    ),
    STEPS(
        recordType = RecordType.STEPS,
        filePrefix = "steps",
    ),
    RESTING_HEART_RATE(
        recordType = RecordType.RESTING_HR,
        filePrefix = "resting_heart_rate",
    ),
    ;

    companion object {
        fun fromRecordType(recordType: String): ExportMetricType? {
            return entries.firstOrNull { it.recordType == recordType }
        }
    }
}

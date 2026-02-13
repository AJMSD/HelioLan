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
    ACTIVE_CALORIES(
        recordType = RecordType.ACTIVE_CALORIES,
        filePrefix = "active_calories",
    ),
    DISTANCE(
        recordType = RecordType.DISTANCE,
        filePrefix = "distance",
    ),
    TOTAL_CALORIES(
        recordType = RecordType.TOTAL_CALORIES,
        filePrefix = "total_calories",
    ),
    NUTRITION(
        recordType = RecordType.NUTRITION,
        filePrefix = "nutrition",
    ),
    OXYGEN_SATURATION(
        recordType = RecordType.OXYGEN_SATURATION,
        filePrefix = "oxygen_saturation",
    ),
    HRV(
        recordType = RecordType.HRV,
        filePrefix = "hrv",
    ),
    ;

    companion object {
        fun fromRecordType(recordType: String): ExportMetricType? {
            if (recordType == "resting_heart_rate") {
                return RESTING_HEART_RATE
            }
            return entries.firstOrNull { it.recordType == recordType }
        }
    }
}

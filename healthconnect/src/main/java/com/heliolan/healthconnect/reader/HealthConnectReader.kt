package com.heliolan.healthconnect.reader

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.heliolan.data.entity.ActiveCaloriesBurned
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.HrvRecord
import com.heliolan.data.entity.OxygenSaturation
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.SleepStage
import com.heliolan.data.entity.TotalCaloriesBurned
import com.heliolan.data.util.RecordType
import com.heliolan.healthconnect.mapper.HealthConnectMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.Period
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass
import com.heliolan.data.entity.DistanceRecord as DistanceRecordEntity
import com.heliolan.data.entity.NutritionRecord as NutritionRecordEntity
import com.heliolan.data.entity.StepsRecord as StepsRecordEntity

/**
 * Result of a Health Connect read operation.
 */
sealed class ReadResult<out T> {
    data class Success<T>(val data: List<T>) : ReadResult<T>()

    data class Error(val message: String, val throwable: Throwable? = null) : ReadResult<Nothing>()

    object PermissionDenied : ReadResult<Nothing>()

    object HealthConnectUnavailable : ReadResult<Nothing>()
}

data class TotalCaloriesDailyAggregate(
    val startTime: Instant,
    val endTime: Instant,
    val energyKcal: Double,
)

sealed class AggregateReadResult<out T> {
    data class Success<T>(val data: T) : AggregateReadResult<T>()

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
        val requiresHistoryPermission: Boolean = false,
    ) : AggregateReadResult<Nothing>()

    object PermissionDenied : AggregateReadResult<Nothing>()

    object HealthConnectUnavailable : AggregateReadResult<Nothing>()
}

sealed class ChangesTokenResult {
    data class Success(val token: String) : ChangesTokenResult()

    data class Error(val message: String, val throwable: Throwable? = null) : ChangesTokenResult()

    object PermissionDenied : ChangesTokenResult()

    object HealthConnectUnavailable : ChangesTokenResult()
}

data class IncrementalChanges<T>(
    val upserted: List<T>,
    val deletedRecordIds: List<String>,
    val nextChangesToken: String,
)

sealed class IncrementalReadResult<out T> {
    data class Success<T>(val data: IncrementalChanges<T>) : IncrementalReadResult<T>()

    data class Error(val message: String, val throwable: Throwable? = null) : IncrementalReadResult<Nothing>()

    object PermissionDenied : IncrementalReadResult<Nothing>()

    object HealthConnectUnavailable : IncrementalReadResult<Nothing>()

    object TokenExpired : IncrementalReadResult<Nothing>()
}

/**
 * Reads health data from Health Connect and maps to local entities.
 * Handles pagination, error cases, and permission issues.
 */
@Singleton
class HealthConnectReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private companion object {
            const val HEALTH_CONNECT_PAGE_SIZE = 1000
            const val HISTORY_WINDOW_DAYS = 30L
        }

        private val healthConnectClient: HealthConnectClient? by lazy<HealthConnectClient?> {
            try {
                if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) {
                    HealthConnectClient.getOrCreate(context)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Read heart rate samples from Health Connect.
         * Returns all samples within the time range.
         */
        suspend fun readHeartRate(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<HeartRateSample> {
            val client =
                healthConnectClient
                    ?: return ReadResult.HealthConnectUnavailable

            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )

                val response = client.readRecords(request)
                val samples =
                    response.records.flatMap { record ->
                        HealthConnectMapper.mapHeartRateRecord(record)
                    }

                ReadResult.Success(samples)
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read heart rate: ${e.message}", e)
            }
        }

        /**
         * Read sleep sessions from Health Connect.
         * Returns sessions that overlap with the time range.
         */
        suspend fun readSleep(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<SleepSession> {
            val client =
                healthConnectClient
                    ?: return ReadResult.HealthConnectUnavailable

            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )

                val response = client.readRecords(request)
                val sessions =
                    response.records.map { record ->
                        HealthConnectMapper.mapSleepSessionRecord(record)
                    }

                ReadResult.Success(sessions)
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read sleep: ${e.message}", e)
            }
        }

        /**
         * Read sleep session with stages from Health Connect by ID.
         * Used after inserting a sleep session to get its stages.
         */
        private suspend fun readSleepSessionById(healthConnectId: String): SleepSessionRecord? {
            val client = healthConnectClient ?: return null

            return try {
                // Read all recent sleep sessions and find the one matching this ID
                // Note: Health Connect doesn't have a "read by ID" API yet
                val request =
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = TimeRangeFilter.before(Instant.now()),
                    )

                val response = client.readRecords(request)
                response.records.firstOrNull { it.metadata.id == healthConnectId }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Read and map sleep stages for a single sleep session ID.
         * Returns empty list if session is not found or stages are unavailable.
         */
        suspend fun readSleepStages(
            healthConnectId: String,
            sessionId: Long,
            syncedAt: Instant = Instant.now(),
        ): List<SleepStage> {
            val record = readSleepSessionById(healthConnectId) ?: return emptyList()
            return HealthConnectMapper.mapSleepStages(record, sessionId, syncedAt)
        }

        /**
         * Read steps records from Health Connect.
         */
        suspend fun readSteps(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<StepsRecordEntity> {
            val client =
                healthConnectClient
                    ?: return ReadResult.HealthConnectUnavailable

            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )

                val response = client.readRecords(request)
                val steps =
                    response.records.map { record ->
                        HealthConnectMapper.mapStepsRecord(record)
                    }

                ReadResult.Success(steps)
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read steps: ${e.message}", e)
            }
        }

        /**
         * Read resting heart rate records from Health Connect.
         */
        suspend fun readRestingHeartRate(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<RestingHeartRate> {
            val client =
                healthConnectClient
                    ?: return ReadResult.HealthConnectUnavailable

            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = RestingHeartRateRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )

                val response = client.readRecords(request)
                val restingHr =
                    response.records.map { record ->
                        HealthConnectMapper.mapRestingHeartRateRecord(record)
                    }

                ReadResult.Success(restingHr)
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read resting heart rate: ${e.message}", e)
            }
        }

        /**
         * Read active calories burned records from Health Connect.
         */
        suspend fun readActiveCaloriesBurned(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<ActiveCaloriesBurned> {
            val client = healthConnectClient ?: return ReadResult.HealthConnectUnavailable
            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = ActiveCaloriesBurnedRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )
                val response = client.readRecords(request)
                ReadResult.Success(
                    response.records.map { record ->
                        HealthConnectMapper.mapActiveCaloriesBurnedRecord(record)
                    },
                )
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read active calories: ${e.message}", e)
            }
        }

        /**
         * Read distance records from Health Connect.
         */
        suspend fun readDistance(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<DistanceRecordEntity> {
            val client = healthConnectClient ?: return ReadResult.HealthConnectUnavailable
            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = DistanceRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )
                val response = client.readRecords(request)
                ReadResult.Success(
                    response.records.map { record ->
                        HealthConnectMapper.mapDistanceRecord(record)
                    },
                )
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read distance: ${e.message}", e)
            }
        }

        /**
         * Read total calories burned records from Health Connect.
         */
        suspend fun readTotalCaloriesBurned(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<TotalCaloriesBurned> {
            val client = healthConnectClient ?: return ReadResult.HealthConnectUnavailable
            return try {
                val mapped = mutableListOf<TotalCaloriesBurned>()
                var nextPageToken: String? = null

                do {
                    val request =
                        ReadRecordsRequest(
                            recordType = TotalCaloriesBurnedRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                            pageToken = nextPageToken,
                            pageSize = HEALTH_CONNECT_PAGE_SIZE,
                        )
                    val response = client.readRecords(request)
                    mapped +=
                        response.records.map { record ->
                            HealthConnectMapper.mapTotalCaloriesBurnedRecord(record)
                        }
                    nextPageToken = response.pageToken
                } while (nextPageToken != null)

                ReadResult.Success(mapped)
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read total calories: ${e.message}", e)
            }
        }

        suspend fun aggregateTotalCaloriesBurned(
            startTime: Instant,
            endTimeExclusive: Instant,
        ): AggregateReadResult<Double> {
            val client = healthConnectClient ?: return AggregateReadResult.HealthConnectUnavailable
            if (!startTime.isBefore(endTimeExclusive)) {
                return AggregateReadResult.Error("Failed to aggregate total calories: invalid time range.")
            }

            return try {
                val zoneId = ZoneId.systemDefault()
                val request =
                    AggregateRequest(
                        metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                        timeRangeFilter =
                            TimeRangeFilter.between(
                                startTime.atZone(zoneId).toLocalDateTime(),
                                endTimeExclusive.atZone(zoneId).toLocalDateTime(),
                            ),
                    )
                val aggregation = client.aggregate(request)
                val totalKcal = aggregation[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0
                AggregateReadResult.Success(totalKcal)
            } catch (e: SecurityException) {
                if (requiresHistoryPermission(startTime)) {
                    AggregateReadResult.Error(
                        message = "History permission required to aggregate total calories older than 30 days.",
                        throwable = e,
                        requiresHistoryPermission = true,
                    )
                } else {
                    AggregateReadResult.PermissionDenied
                }
            } catch (e: Exception) {
                AggregateReadResult.Error("Failed to aggregate total calories: ${e.message}", e)
            }
        }

        suspend fun aggregateTotalCaloriesBurnedByDay(
            startTime: Instant,
            endTimeExclusive: Instant,
        ): AggregateReadResult<List<TotalCaloriesDailyAggregate>> {
            val client = healthConnectClient ?: return AggregateReadResult.HealthConnectUnavailable
            if (!startTime.isBefore(endTimeExclusive)) {
                return AggregateReadResult.Error("Failed to aggregate total calories by day: invalid time range.")
            }

            return try {
                val zoneId = ZoneId.systemDefault()
                val request =
                    AggregateGroupByPeriodRequest(
                        metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                        timeRangeFilter =
                            TimeRangeFilter.between(
                                startTime.atZone(zoneId).toLocalDateTime(),
                                endTimeExclusive.atZone(zoneId).toLocalDateTime(),
                            ),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                val grouped = client.aggregateGroupByPeriod(request)
                val daily =
                    grouped.map { row ->
                        TotalCaloriesDailyAggregate(
                            startTime = row.startTime.atZone(zoneId).toInstant(),
                            endTime = row.endTime.atZone(zoneId).toInstant(),
                            energyKcal = row.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
                        )
                    }
                AggregateReadResult.Success(daily)
            } catch (e: SecurityException) {
                if (requiresHistoryPermission(startTime)) {
                    AggregateReadResult.Error(
                        message = "History permission required to aggregate daily total calories older than 30 days.",
                        throwable = e,
                        requiresHistoryPermission = true,
                    )
                } else {
                    AggregateReadResult.PermissionDenied
                }
            } catch (e: Exception) {
                AggregateReadResult.Error("Failed to aggregate total calories by day: ${e.message}", e)
            }
        }

        /**
         * Read nutrition records from Health Connect.
         */
        suspend fun readNutrition(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<NutritionRecordEntity> {
            val client = healthConnectClient ?: return ReadResult.HealthConnectUnavailable
            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = NutritionRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )
                val response = client.readRecords(request)
                ReadResult.Success(
                    response.records.map { record ->
                        HealthConnectMapper.mapNutritionRecord(record)
                    },
                )
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read nutrition: ${e.message}", e)
            }
        }

        /**
         * Read oxygen saturation records from Health Connect.
         */
        suspend fun readOxygenSaturation(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<OxygenSaturation> {
            val client = healthConnectClient ?: return ReadResult.HealthConnectUnavailable
            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = OxygenSaturationRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )
                val response = client.readRecords(request)
                ReadResult.Success(
                    response.records.map { record ->
                        HealthConnectMapper.mapOxygenSaturationRecord(record)
                    },
                )
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read oxygen saturation: ${e.message}", e)
            }
        }

        /**
         * Read HRV RMSSD records from Health Connect.
         */
        suspend fun readHrv(
            startTime: Instant,
            endTime: Instant,
        ): ReadResult<HrvRecord> {
            val client = healthConnectClient ?: return ReadResult.HealthConnectUnavailable
            return try {
                val request =
                    ReadRecordsRequest(
                        recordType = HeartRateVariabilityRmssdRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(startTime, endTime),
                    )
                val response = client.readRecords(request)
                ReadResult.Success(
                    response.records.map { record ->
                        HealthConnectMapper.mapHrvRecord(record)
                    },
                )
            } catch (e: SecurityException) {
                ReadResult.PermissionDenied
            } catch (e: Exception) {
                ReadResult.Error("Failed to read HRV: ${e.message}", e)
            }
        }

        suspend fun getChangesTokenForRecordType(recordType: String): ChangesTokenResult {
            val recordClass =
                resolveRecordClassForType(recordType)
                    ?: return ChangesTokenResult.Error("Unsupported record type '$recordType' for changes token.")
            return getChangesToken(setOf(recordClass))
        }

        suspend fun readHeartRateChanges(changesToken: String): IncrementalReadResult<HeartRateSample> {
            return readChanges(
                changesToken = changesToken,
                recordType = HeartRateRecord::class,
                mapper = { record ->
                    HealthConnectMapper.mapHeartRateRecord(record)
                },
            )
        }

        suspend fun readSleepChanges(changesToken: String): IncrementalReadResult<SleepSession> {
            return readChanges(
                changesToken = changesToken,
                recordType = SleepSessionRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapSleepSessionRecord(record))
                },
            )
        }

        suspend fun readStepsChanges(changesToken: String): IncrementalReadResult<StepsRecordEntity> {
            return readChanges(
                changesToken = changesToken,
                recordType = StepsRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapStepsRecord(record))
                },
            )
        }

        suspend fun readRestingHeartRateChanges(changesToken: String): IncrementalReadResult<RestingHeartRate> {
            return readChanges(
                changesToken = changesToken,
                recordType = RestingHeartRateRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapRestingHeartRateRecord(record))
                },
            )
        }

        suspend fun readActiveCaloriesChanges(changesToken: String): IncrementalReadResult<ActiveCaloriesBurned> {
            return readChanges(
                changesToken = changesToken,
                recordType = ActiveCaloriesBurnedRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapActiveCaloriesBurnedRecord(record))
                },
            )
        }

        suspend fun readDistanceChanges(changesToken: String): IncrementalReadResult<DistanceRecordEntity> {
            return readChanges(
                changesToken = changesToken,
                recordType = DistanceRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapDistanceRecord(record))
                },
            )
        }

        suspend fun readTotalCaloriesChanges(changesToken: String): IncrementalReadResult<TotalCaloriesBurned> {
            return readChanges(
                changesToken = changesToken,
                recordType = TotalCaloriesBurnedRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapTotalCaloriesBurnedRecord(record))
                },
            )
        }

        suspend fun readNutritionChanges(changesToken: String): IncrementalReadResult<NutritionRecordEntity> {
            return readChanges(
                changesToken = changesToken,
                recordType = NutritionRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapNutritionRecord(record))
                },
            )
        }

        suspend fun readOxygenSaturationChanges(changesToken: String): IncrementalReadResult<OxygenSaturation> {
            return readChanges(
                changesToken = changesToken,
                recordType = OxygenSaturationRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapOxygenSaturationRecord(record))
                },
            )
        }

        suspend fun readHrvChanges(changesToken: String): IncrementalReadResult<HrvRecord> {
            return readChanges(
                changesToken = changesToken,
                recordType = HeartRateVariabilityRmssdRecord::class,
                mapper = { record ->
                    listOf(HealthConnectMapper.mapHrvRecord(record))
                },
            )
        }

        /**
         * Check if Health Connect is available.
         */
        fun isAvailable(): Boolean {
            return try {
                HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
            } catch (e: Exception) {
                false
            }
        }

        private fun requiresHistoryPermission(startTime: Instant): Boolean {
            val threshold = Instant.now().minus(HISTORY_WINDOW_DAYS, ChronoUnit.DAYS)
            return startTime.isBefore(threshold)
        }

        private suspend fun getChangesToken(recordTypes: Set<KClass<out Record>>): ChangesTokenResult {
            val client = healthConnectClient ?: return ChangesTokenResult.HealthConnectUnavailable
            return try {
                @Suppress("UNCHECKED_CAST")
                val request =
                    ChangesTokenRequest(
                        recordTypes = recordTypes as Set<KClass<Record>>,
                        dataOriginFilters = emptySet<DataOrigin>(),
                    )
                ChangesTokenResult.Success(client.getChangesToken(request))
            } catch (e: SecurityException) {
                ChangesTokenResult.PermissionDenied
            } catch (e: Exception) {
                ChangesTokenResult.Error("Failed to get changes token: ${e.message}", e)
            }
        }

        private suspend fun <R : Record, T> readChanges(
            changesToken: String,
            recordType: KClass<R>,
            mapper: (R) -> List<T>,
        ): IncrementalReadResult<T> {
            val client = healthConnectClient ?: return IncrementalReadResult.HealthConnectUnavailable
            return try {
                var nextToken = changesToken
                var hasMore = false
                val upserted = mutableListOf<T>()
                val deletedRecordIds = linkedSetOf<String>()

                do {
                    val response = client.getChanges(nextToken)
                    if (response.changesTokenExpired) {
                        return IncrementalReadResult.TokenExpired
                    }

                    response.changes.forEach { change ->
                        when (change) {
                            is UpsertionChange -> {
                                val record = change.record
                                if (!recordType.isInstance(record)) return@forEach
                                @Suppress("UNCHECKED_CAST")
                                upserted += mapper(record as R)
                            }

                            is DeletionChange -> {
                                deletedRecordIds += change.recordId
                            }

                            else -> Unit
                        }
                    }

                    nextToken = response.nextChangesToken
                    hasMore = response.hasMore
                } while (hasMore)

                IncrementalReadResult.Success(
                    IncrementalChanges(
                        upserted = upserted,
                        deletedRecordIds = deletedRecordIds.toList(),
                        nextChangesToken = nextToken,
                    ),
                )
            } catch (e: SecurityException) {
                IncrementalReadResult.PermissionDenied
            } catch (e: Exception) {
                IncrementalReadResult.Error("Failed to read changes: ${e.message}", e)
            }
        }

        private fun resolveRecordClassForType(recordType: String): KClass<out Record>? {
            return when (recordType) {
                RecordType.HEART_RATE -> HeartRateRecord::class
                RecordType.SLEEP -> SleepSessionRecord::class
                RecordType.STEPS -> StepsRecord::class
                RecordType.RESTING_HR -> RestingHeartRateRecord::class
                RecordType.ACTIVE_CALORIES -> ActiveCaloriesBurnedRecord::class
                RecordType.DISTANCE -> DistanceRecord::class
                RecordType.TOTAL_CALORIES -> TotalCaloriesBurnedRecord::class
                RecordType.NUTRITION -> NutritionRecord::class
                RecordType.OXYGEN_SATURATION -> OxygenSaturationRecord::class
                RecordType.HRV -> HeartRateVariabilityRmssdRecord::class
                else -> null
            }
        }
    }

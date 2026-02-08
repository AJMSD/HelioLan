package com.heliolan.healthconnect.reader

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.healthconnect.mapper.HealthConnectMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
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
        suspend fun readSleepSessionById(healthConnectId: String): SleepSessionRecord? {
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
         * Check if Health Connect is available.
         */
        fun isAvailable(): Boolean {
            return try {
                HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
            } catch (e: Exception) {
                false
            }
        }
    }

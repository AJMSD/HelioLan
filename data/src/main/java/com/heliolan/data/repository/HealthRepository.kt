package com.heliolan.data.repository

import com.heliolan.data.entity.DailyAggregate
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.SleepStage
import com.heliolan.data.entity.StepsRecord
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/**
 * Repository interface for health data.
 * Abstracts data source (currently Room DB, could add remote/cache layers).
 */
interface HealthRepository {
    // Heart Rate
    fun getHeartRateSamples(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<HeartRateSample>>

    fun getLatestHeartRate(): Flow<HeartRateSample?>

    // Sleep
    fun getSleepSessions(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 100,
        offset: Int = 0,
    ): Flow<List<SleepSession>>

    fun getLatestSleepSession(): Flow<SleepSession?>

    fun getSleepStages(sessionId: Long): Flow<List<SleepStage>>

    // Steps
    fun getStepsRecords(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<StepsRecord>>

    fun getTotalSteps(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Int>

    fun getLatestStepsRecord(): Flow<StepsRecord?>

    // Resting Heart Rate
    fun getRestingHeartRate(
        startDate: LocalDate,
        endDate: LocalDate,
        limit: Int = 365,
        offset: Int = 0,
    ): Flow<List<RestingHeartRate>>

    fun getLatestRestingHeartRate(): Flow<RestingHeartRate?>

    // Daily Aggregates
    fun getDailyAggregates(
        recordType: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<List<DailyAggregate>>

    fun getAllDailyAggregates(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<List<DailyAggregate>>
}

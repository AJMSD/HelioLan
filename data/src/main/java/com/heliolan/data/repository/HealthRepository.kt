package com.heliolan.data.repository

import com.heliolan.data.entity.ActiveCaloriesBurned
import com.heliolan.data.entity.DailyAggregate
import com.heliolan.data.entity.DistanceRecord
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.HrvRecord
import com.heliolan.data.entity.NutritionRecord
import com.heliolan.data.entity.OxygenSaturation
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.SleepStage
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.entity.TotalCaloriesBurned
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

    // Active Calories
    fun getActiveCaloriesBurned(
        startDate: LocalDate,
        endDate: LocalDate,
        limit: Int = 365,
        offset: Int = 0,
    ): Flow<List<ActiveCaloriesBurned>>

    fun getLatestActiveCaloriesBurned(): Flow<ActiveCaloriesBurned?>

    fun getTotalActiveCalories(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<Double>

    // Distance
    fun getDistanceRecords(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<DistanceRecord>>

    fun getLatestDistanceRecord(): Flow<DistanceRecord?>

    fun getTotalDistanceMeters(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Double>

    // Total Calories
    fun getTotalCaloriesBurnedRecords(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<TotalCaloriesBurned>>

    fun getLatestTotalCaloriesBurned(): Flow<TotalCaloriesBurned?>

    fun getTotalCaloriesBurned(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Double>

    // Nutrition
    fun getNutritionRecords(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<NutritionRecord>>

    fun getLatestNutritionRecord(): Flow<NutritionRecord?>

    // Oxygen Saturation
    fun getOxygenSaturationRecords(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<OxygenSaturation>>

    fun getLatestOxygenSaturation(): Flow<OxygenSaturation?>

    fun getAverageOxygenSaturation(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Double?>

    // HRV
    fun getHrvRecords(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<HrvRecord>>

    fun getLatestHrvRecord(): Flow<HrvRecord?>

    fun getAverageHrvRmssd(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Double?>

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

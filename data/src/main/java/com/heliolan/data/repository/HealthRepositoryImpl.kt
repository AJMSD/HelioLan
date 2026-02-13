package com.heliolan.data.repository

import com.heliolan.data.dao.ActiveCaloriesBurnedDao
import com.heliolan.data.dao.DailyAggregateDao
import com.heliolan.data.dao.DistanceRecordDao
import com.heliolan.data.dao.HeartRateSampleDao
import com.heliolan.data.dao.HrvRecordDao
import com.heliolan.data.dao.NutritionRecordDao
import com.heliolan.data.dao.OxygenSaturationDao
import com.heliolan.data.dao.RestingHeartRateDao
import com.heliolan.data.dao.SleepSessionDao
import com.heliolan.data.dao.SleepStageDao
import com.heliolan.data.dao.StepsRecordDao
import com.heliolan.data.dao.TotalCaloriesBurnedDao
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of HealthRepository backed by Room DAOs.
 */
@Singleton
class HealthRepositoryImpl
    @Inject
    constructor(
        private val heartRateSampleDao: HeartRateSampleDao,
        private val sleepSessionDao: SleepSessionDao,
        private val sleepStageDao: SleepStageDao,
        private val stepsRecordDao: StepsRecordDao,
        private val restingHeartRateDao: RestingHeartRateDao,
        private val activeCaloriesBurnedDao: ActiveCaloriesBurnedDao,
        private val distanceRecordDao: DistanceRecordDao,
        private val totalCaloriesBurnedDao: TotalCaloriesBurnedDao,
        private val nutritionRecordDao: NutritionRecordDao,
        private val oxygenSaturationDao: OxygenSaturationDao,
        private val hrvRecordDao: HrvRecordDao,
        private val dailyAggregateDao: DailyAggregateDao,
    ) : HealthRepository {
        override fun getHeartRateSamples(
            startTime: Instant,
            endTime: Instant,
            limit: Int,
            offset: Int,
        ): Flow<List<HeartRateSample>> {
            return heartRateSampleDao.getByDateRange(startTime, endTime, limit, offset)
        }

        override fun getLatestHeartRate(): Flow<HeartRateSample?> {
            return heartRateSampleDao.getLatest()
        }

        override fun getSleepSessions(
            startTime: Instant,
            endTime: Instant,
            limit: Int,
            offset: Int,
        ): Flow<List<SleepSession>> {
            return sleepSessionDao.getByDateRange(startTime, endTime, limit, offset)
        }

        override fun getLatestSleepSession(): Flow<SleepSession?> {
            return sleepSessionDao.getLatest()
        }

        override fun getSleepStages(sessionId: Long): Flow<List<SleepStage>> {
            return sleepStageDao.getBySessionId(sessionId)
        }

        override fun getStepsRecords(
            startTime: Instant,
            endTime: Instant,
            limit: Int,
            offset: Int,
        ): Flow<List<StepsRecord>> {
            return stepsRecordDao.getByDateRange(startTime, endTime, limit, offset)
        }

        override fun getTotalSteps(
            startTime: Instant,
            endTime: Instant,
        ): Flow<Int> {
            return stepsRecordDao.getTotalSteps(startTime, endTime)
        }

        override fun getLatestStepsRecord(): Flow<StepsRecord?> {
            return stepsRecordDao.getLatest()
        }

        override fun getRestingHeartRate(
            startDate: LocalDate,
            endDate: LocalDate,
            limit: Int,
            offset: Int,
        ): Flow<List<RestingHeartRate>> {
            return restingHeartRateDao.getByDateRange(startDate, endDate, limit, offset)
        }

        override fun getLatestRestingHeartRate(): Flow<RestingHeartRate?> {
            return restingHeartRateDao.getLatest()
        }

        override fun getActiveCaloriesBurned(
            startDate: LocalDate,
            endDate: LocalDate,
            limit: Int,
            offset: Int,
        ): Flow<List<ActiveCaloriesBurned>> {
            return activeCaloriesBurnedDao.getByDateRange(startDate, endDate, limit, offset)
        }

        override fun getLatestActiveCaloriesBurned(): Flow<ActiveCaloriesBurned?> {
            return activeCaloriesBurnedDao.getLatest()
        }

        override fun getTotalActiveCalories(
            startDate: LocalDate,
            endDate: LocalDate,
        ): Flow<Double> {
            return activeCaloriesBurnedDao.getTotalCalories(startDate, endDate)
        }

        override fun getDistanceRecords(
            startTime: Instant,
            endTime: Instant,
            limit: Int,
            offset: Int,
        ): Flow<List<DistanceRecord>> {
            return distanceRecordDao.getByDateRange(startTime, endTime, limit, offset)
        }

        override fun getLatestDistanceRecord(): Flow<DistanceRecord?> {
            return distanceRecordDao.getLatest()
        }

        override fun getTotalDistanceMeters(
            startTime: Instant,
            endTime: Instant,
        ): Flow<Double> {
            return distanceRecordDao.getTotalDistanceMeters(startTime, endTime)
        }

        override fun getTotalCaloriesBurnedRecords(
            startTime: Instant,
            endTime: Instant,
            limit: Int,
            offset: Int,
        ): Flow<List<TotalCaloriesBurned>> {
            return totalCaloriesBurnedDao.getByDateRange(startTime, endTime, limit, offset)
        }

        override fun getLatestTotalCaloriesBurned(): Flow<TotalCaloriesBurned?> {
            return totalCaloriesBurnedDao.getLatest()
        }

        override fun getTotalCaloriesBurned(
            startTime: Instant,
            endTime: Instant,
        ): Flow<Double> {
            return totalCaloriesBurnedDao.getTotalEnergyKcal(startTime, endTime)
        }

        override fun getNutritionRecords(
            startTime: Instant,
            endTime: Instant,
            limit: Int,
            offset: Int,
        ): Flow<List<NutritionRecord>> {
            return nutritionRecordDao.getByDateRange(startTime, endTime, limit, offset)
        }

        override fun getLatestNutritionRecord(): Flow<NutritionRecord?> {
            return nutritionRecordDao.getLatest()
        }

        override fun getOxygenSaturationRecords(
            startTime: Instant,
            endTime: Instant,
            limit: Int,
            offset: Int,
        ): Flow<List<OxygenSaturation>> {
            return oxygenSaturationDao.getByDateRange(startTime, endTime, limit, offset)
        }

        override fun getLatestOxygenSaturation(): Flow<OxygenSaturation?> {
            return oxygenSaturationDao.getLatest()
        }

        override fun getAverageOxygenSaturation(
            startTime: Instant,
            endTime: Instant,
        ): Flow<Double?> {
            return oxygenSaturationDao.getAveragePercentage(startTime, endTime)
        }

        override fun getHrvRecords(
            startTime: Instant,
            endTime: Instant,
            limit: Int,
            offset: Int,
        ): Flow<List<HrvRecord>> {
            return hrvRecordDao.getByDateRange(startTime, endTime, limit, offset)
        }

        override fun getLatestHrvRecord(): Flow<HrvRecord?> {
            return hrvRecordDao.getLatest()
        }

        override fun getAverageHrvRmssd(
            startTime: Instant,
            endTime: Instant,
        ): Flow<Double?> {
            return hrvRecordDao.getAverageRmssd(startTime, endTime)
        }

        override fun getDailyAggregates(
            recordType: String,
            startDate: LocalDate,
            endDate: LocalDate,
        ): Flow<List<DailyAggregate>> {
            return dailyAggregateDao.getByTypeAndDateRange(recordType, startDate, endDate)
        }

        override fun getAllDailyAggregates(
            startDate: LocalDate,
            endDate: LocalDate,
        ): Flow<List<DailyAggregate>> {
            return dailyAggregateDao.getByDateRange(startDate, endDate)
        }
    }

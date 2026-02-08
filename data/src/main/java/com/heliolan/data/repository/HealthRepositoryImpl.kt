package com.heliolan.data.repository

import com.heliolan.data.dao.DailyAggregateDao
import com.heliolan.data.dao.HeartRateSampleDao
import com.heliolan.data.dao.RestingHeartRateDao
import com.heliolan.data.dao.SleepSessionDao
import com.heliolan.data.dao.SleepStageDao
import com.heliolan.data.dao.StepsRecordDao
import com.heliolan.data.entity.DailyAggregate
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.SleepStage
import com.heliolan.data.entity.StepsRecord
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

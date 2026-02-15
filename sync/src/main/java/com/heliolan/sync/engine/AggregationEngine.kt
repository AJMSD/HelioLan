package com.heliolan.sync.engine

import com.heliolan.data.dao.ActiveCaloriesBurnedDao
import com.heliolan.data.dao.DailyAggregateDao
import com.heliolan.data.dao.DistanceRecordDao
import com.heliolan.data.dao.HeartRateSampleDao
import com.heliolan.data.dao.HrvRecordDao
import com.heliolan.data.dao.NutritionRecordDao
import com.heliolan.data.dao.OxygenSaturationDao
import com.heliolan.data.dao.RestingHeartRateDao
import com.heliolan.data.dao.SleepSessionDao
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
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.entity.TotalCaloriesBurned
import com.heliolan.data.util.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 aggregation engine.
 * Pre-computes daily summaries for fast dashboard/chart lookups.
 */
@Singleton
class AggregationEngine
    @Inject
    constructor(
        private val heartRateSampleDao: HeartRateSampleDao,
        private val sleepSessionDao: SleepSessionDao,
        private val stepsRecordDao: StepsRecordDao,
        private val restingHeartRateDao: RestingHeartRateDao,
        private val activeCaloriesBurnedDao: ActiveCaloriesBurnedDao,
        private val distanceRecordDao: DistanceRecordDao,
        private val totalCaloriesBurnedDao: TotalCaloriesBurnedDao,
        private val nutritionRecordDao: NutritionRecordDao,
        private val oxygenSaturationDao: OxygenSaturationDao,
        private val hrvRecordDao: HrvRecordDao,
        private val dailyAggregateDao: DailyAggregateDao,
    ) {
        private val localZoneId = ZoneId.systemDefault()

        /**
         * Rebuild all known aggregates from earliest available raw data to latest available raw data.
         * Useful as a recovery action after corruption/schema changes.
         */
        suspend fun rebuildAllAggregates() =
            withContext(Dispatchers.IO) {
                dailyAggregateDao.deleteAll()
                val range = resolveGlobalDateRange() ?: return@withContext
                rebuildAggregatesInternal(range)
            }

        /**
         * Rebuild aggregates within the given date range (inclusive).
         */
        suspend fun rebuildAggregates(dateRange: ClosedRange<LocalDate>) =
            withContext(Dispatchers.IO) {
                rebuildAggregatesInternal(dateRange)
            }

        /**
         * Recompute all aggregate types for one date.
         */
        suspend fun updateAggregatesForDate(date: LocalDate) =
            withContext(Dispatchers.IO) {
                updateAggregatesForDateInternal(date)
            }

        /**
         * Recompute aggregates for a set of dates.
         */
        suspend fun updateAggregatesForDates(dates: Set<LocalDate>) =
            withContext(Dispatchers.IO) {
                dates
                    .asSequence()
                    .sorted()
                    .forEach { updateAggregatesForDateInternal(it) }
            }

        private suspend fun rebuildAggregatesInternal(dateRange: ClosedRange<LocalDate>) {
            if (dateRange.start.isAfter(dateRange.endInclusive)) return

            var currentDate = dateRange.start
            while (!currentDate.isAfter(dateRange.endInclusive)) {
                updateAggregatesForDateInternal(currentDate)
                currentDate = currentDate.plusDays(1)
            }
        }

        private suspend fun updateAggregatesForDateInternal(date: LocalDate) {
            val (dayStart, dayEndInclusive) = toDayBounds(date)
            val updatedAt = Instant.now()

            val heartRateSamples =
                heartRateSampleDao.getSamplesForAggregation(
                    startTime = dayStart,
                    endTime = dayEndInclusive,
                )
            val stepsRecords =
                stepsRecordDao.getRecordsForAggregation(
                    startTime = dayStart,
                    endTime = dayEndInclusive,
                )
            val sleepSessions =
                sleepSessionDao.getSessionsForAggregation(
                    startTime = dayStart,
                    endTime = dayEndInclusive,
                )
            val restingHeartRateRecords = restingHeartRateDao.getRecordsForAggregation(date, date)
            val activeCaloriesRecords = activeCaloriesBurnedDao.getRecordsForAggregation(date, date)
            val distanceRecords =
                distanceRecordDao.getRecordsForAggregation(
                    startTime = dayStart,
                    endTime = dayEndInclusive,
                )
            val totalCaloriesRecords =
                totalCaloriesBurnedDao.getRecordsForAggregation(
                    startTime = dayStart,
                    endTime = dayEndInclusive,
                )
            val nutritionRecords =
                nutritionRecordDao.getRecordsForAggregation(
                    startTime = dayStart,
                    endTime = dayEndInclusive,
                )
            val oxygenRecords =
                oxygenSaturationDao.getRecordsForAggregation(
                    startTime = dayStart,
                    endTime = dayEndInclusive,
                )
            val hrvRecords =
                hrvRecordDao.getRecordsForAggregation(
                    startTime = dayStart,
                    endTime = dayEndInclusive,
                )

            replaceAggregate(
                date = date,
                recordType = RecordType.STEPS,
                aggregate = buildStepsAggregate(date, stepsRecords, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.SLEEP,
                aggregate = buildSleepAggregate(date, sleepSessions, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.HEART_RATE,
                aggregate = buildHeartRateAggregate(date, heartRateSamples, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.RESTING_HR,
                aggregate = buildRestingHeartRateAggregate(date, restingHeartRateRecords, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.ACTIVE_CALORIES,
                aggregate = buildActiveCaloriesAggregate(date, activeCaloriesRecords, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.DISTANCE,
                aggregate = buildDistanceAggregate(date, distanceRecords, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.TOTAL_CALORIES,
                aggregate = buildTotalCaloriesAggregate(date, totalCaloriesRecords, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.NUTRITION,
                aggregate = buildNutritionAggregate(date, nutritionRecords, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.OXYGEN_SATURATION,
                aggregate = buildOxygenSaturationAggregate(date, oxygenRecords, updatedAt),
            )
            replaceAggregate(
                date = date,
                recordType = RecordType.HRV,
                aggregate = buildHrvAggregate(date, hrvRecords, updatedAt),
            )
        }

        private suspend fun replaceAggregate(
            date: LocalDate,
            recordType: String,
            aggregate: DailyAggregate?,
        ) {
            dailyAggregateDao.deleteForDateAndType(recordType, date)
            if (aggregate != null) {
                dailyAggregateDao.upsert(aggregate)
            }
        }

        private fun buildStepsAggregate(
            date: LocalDate,
            records: List<StepsRecord>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (records.isEmpty()) return null

            val values = records.map { it.count.toDouble() }
            val total = values.sum()
            val avg = values.average()

            return DailyAggregate(
                date = date,
                recordType = RecordType.STEPS,
                value = total,
                count = records.size,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                avg = avg,
                updatedAt = updatedAt,
            )
        }

        private fun buildSleepAggregate(
            date: LocalDate,
            sessions: List<SleepSession>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (sessions.isEmpty()) return null

            val totalDurationMs = computeOverlapSafeSleepDurationMs(sessions).toDouble()
            val avgDurationMs = totalDurationMs / sessions.size
            val earliestStartEpochMs = sessions.minOf { it.startTime.toEpochMilli().toDouble() }
            val latestEndEpochMs = sessions.maxOf { it.endTime.toEpochMilli().toDouble() }

            return DailyAggregate(
                date = date,
                recordType = RecordType.SLEEP,
                value = totalDurationMs,
                count = sessions.size,
                min = earliestStartEpochMs,
                max = latestEndEpochMs,
                avg = avgDurationMs,
                updatedAt = updatedAt,
            )
        }

        private fun computeOverlapSafeSleepDurationMs(sessions: List<SleepSession>): Long {
            val sortedRanges =
                sessions
                    .map { session -> session.startTime.toEpochMilli() to session.endTime.toEpochMilli() }
                    .filter { (start, end) -> end > start }
                    .sortedBy { (start, _) -> start }
            if (sortedRanges.isEmpty()) return 0L

            var totalDurationMs = 0L
            var currentStart = sortedRanges.first().first
            var currentEnd = sortedRanges.first().second

            sortedRanges.drop(1).forEach { (nextStart, nextEnd) ->
                if (nextStart <= currentEnd) {
                    currentEnd = maxOf(currentEnd, nextEnd)
                } else {
                    totalDurationMs += currentEnd - currentStart
                    currentStart = nextStart
                    currentEnd = nextEnd
                }
            }
            totalDurationMs += currentEnd - currentStart
            return totalDurationMs
        }

        private fun buildHeartRateAggregate(
            date: LocalDate,
            samples: List<HeartRateSample>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (samples.isEmpty()) return null

            val values = samples.map { it.bpm.toDouble() }
            val avg = values.average()

            return DailyAggregate(
                date = date,
                recordType = RecordType.HEART_RATE,
                value = avg,
                count = samples.size,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                avg = avg,
                updatedAt = updatedAt,
            )
        }

        private fun buildRestingHeartRateAggregate(
            date: LocalDate,
            records: List<RestingHeartRate>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (records.isEmpty()) return null

            val latest = records.maxByOrNull { it.syncedAt } ?: return null
            val values = records.map { it.bpm.toDouble() }
            val avg = values.average()

            return DailyAggregate(
                date = date,
                recordType = RecordType.RESTING_HR,
                value = latest.bpm.toDouble(),
                count = records.size,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                avg = avg,
                updatedAt = updatedAt,
            )
        }

        private fun buildActiveCaloriesAggregate(
            date: LocalDate,
            records: List<ActiveCaloriesBurned>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (records.isEmpty()) return null
            val values = records.map { it.calories }
            val total = values.sum()
            return DailyAggregate(
                date = date,
                recordType = RecordType.ACTIVE_CALORIES,
                value = total,
                count = records.size,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                avg = values.average(),
                updatedAt = updatedAt,
            )
        }

        private fun buildDistanceAggregate(
            date: LocalDate,
            records: List<DistanceRecord>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (records.isEmpty()) return null
            val values = records.map { it.distanceMeters }
            val total = values.sum()
            return DailyAggregate(
                date = date,
                recordType = RecordType.DISTANCE,
                value = total,
                count = records.size,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                avg = values.average(),
                updatedAt = updatedAt,
            )
        }

        private fun buildTotalCaloriesAggregate(
            date: LocalDate,
            records: List<TotalCaloriesBurned>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (records.isEmpty()) return null

            val values = records.map { it.energyKcal }
            val total = values.sum()
            return DailyAggregate(
                date = date,
                recordType = RecordType.TOTAL_CALORIES,
                value = total,
                count = records.size,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                avg = values.average(),
                updatedAt = updatedAt,
            )
        }

        private fun buildNutritionAggregate(
            date: LocalDate,
            records: List<NutritionRecord>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (records.isEmpty()) return null
            val calories = records.mapNotNull { it.energyKcal }
            if (calories.isEmpty()) {
                return DailyAggregate(
                    date = date,
                    recordType = RecordType.NUTRITION,
                    value = 0.0,
                    count = records.size,
                    min = null,
                    max = null,
                    avg = null,
                    updatedAt = updatedAt,
                )
            }
            return DailyAggregate(
                date = date,
                recordType = RecordType.NUTRITION,
                value = calories.sum(),
                count = records.size,
                min = calories.minOrNull(),
                max = calories.maxOrNull(),
                avg = calories.average(),
                updatedAt = updatedAt,
            )
        }

        private fun buildOxygenSaturationAggregate(
            date: LocalDate,
            records: List<OxygenSaturation>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (records.isEmpty()) return null
            val values = records.map { normalizeOxygenPercentage(it.percentage) }
            val avg = values.average()
            return DailyAggregate(
                date = date,
                recordType = RecordType.OXYGEN_SATURATION,
                value = avg,
                count = records.size,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                avg = avg,
                updatedAt = updatedAt,
            )
        }

        private fun buildHrvAggregate(
            date: LocalDate,
            records: List<HrvRecord>,
            updatedAt: Instant,
        ): DailyAggregate? {
            if (records.isEmpty()) return null
            val values = records.map { it.rmssd }
            val avg = values.average()
            return DailyAggregate(
                date = date,
                recordType = RecordType.HRV,
                value = avg,
                count = records.size,
                min = values.minOrNull(),
                max = values.maxOrNull(),
                avg = avg,
                updatedAt = updatedAt,
            )
        }

        private fun toDayBounds(date: LocalDate): Pair<Instant, Instant> {
            val start = date.atStartOfDay(localZoneId).toInstant()
            val endInclusive = date.plusDays(1).atStartOfDay(localZoneId).toInstant().minusNanos(1)
            return start to endInclusive
        }

        private suspend fun resolveGlobalDateRange(): ClosedRange<LocalDate>? {
            val oldestCandidates =
                buildList {
                    heartRateSampleDao.getOldestTimestamp()?.let { add(it.toLocalDate()) }
                    sleepSessionDao.getOldestStartTime()?.let { add(it.toLocalDate()) }
                    stepsRecordDao.getOldestStartTime()?.let { add(it.toLocalDate()) }
                    restingHeartRateDao.getOldestDate()?.let { add(it) }
                    activeCaloriesBurnedDao.getOldestDate()?.let { add(it) }
                    distanceRecordDao.getOldestStartTime()?.let { add(it.toLocalDate()) }
                    totalCaloriesBurnedDao.getOldestStartTime()?.let { add(it.toLocalDate()) }
                    nutritionRecordDao.getOldestStartTime()?.let { add(it.toLocalDate()) }
                    oxygenSaturationDao.getOldestTimestamp()?.let { add(it.toLocalDate()) }
                    hrvRecordDao.getOldestTimestamp()?.let { add(it.toLocalDate()) }
                }
            val latestCandidates =
                buildList {
                    heartRateSampleDao.getLatestTimestamp()?.let { add(it.toLocalDate()) }
                    sleepSessionDao.getLatestEndTime()?.let { add(it.toLocalDate()) }
                    stepsRecordDao.getLatestEndTime()?.let { add(it.toLocalDate()) }
                    restingHeartRateDao.getLatestDate()?.let { add(it) }
                    activeCaloriesBurnedDao.getLatestDate()?.let { add(it) }
                    distanceRecordDao.getLatestEndTime()?.let { add(it.toLocalDate()) }
                    totalCaloriesBurnedDao.getLatestEndTime()?.let { add(it.toLocalDate()) }
                    nutritionRecordDao.getLatestEndTime()?.let { add(it.toLocalDate()) }
                    oxygenSaturationDao.getLatestTimestamp()?.let { add(it.toLocalDate()) }
                    hrvRecordDao.getLatestTimestamp()?.let { add(it.toLocalDate()) }
                }

            val rangeStart = oldestCandidates.minOrNull() ?: return null
            val rangeEnd = latestCandidates.maxOrNull() ?: return null
            if (rangeStart.isAfter(rangeEnd)) return null
            return rangeStart..rangeEnd
        }

        private fun Instant.toLocalDate(): LocalDate = atZone(localZoneId).toLocalDate()

        private fun normalizeOxygenPercentage(rawValue: Double): Double {
            if (!rawValue.isFinite()) return 0.0
            val scaled = if (rawValue <= 1.0) rawValue * 100.0 else rawValue
            return scaled.coerceIn(0.0, 100.0)
        }
    }

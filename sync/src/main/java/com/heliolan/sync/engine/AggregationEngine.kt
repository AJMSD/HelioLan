package com.heliolan.sync.engine

import com.heliolan.data.dao.DailyAggregateDao
import com.heliolan.data.dao.HeartRateSampleDao
import com.heliolan.data.dao.RestingHeartRateDao
import com.heliolan.data.dao.SleepSessionDao
import com.heliolan.data.dao.StepsRecordDao
import com.heliolan.data.entity.DailyAggregate
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.StepsRecord
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

            // HRV is intentionally omitted here; schema support is deferred to v1.1.
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

            val durations = sessions.map { it.durationMs.toDouble() }
            val totalDurationMs = durations.sum()
            val avgDurationMs = durations.average()
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
                }
            val latestCandidates =
                buildList {
                    heartRateSampleDao.getLatestTimestamp()?.let { add(it.toLocalDate()) }
                    sleepSessionDao.getLatestEndTime()?.let { add(it.toLocalDate()) }
                    stepsRecordDao.getLatestEndTime()?.let { add(it.toLocalDate()) }
                    restingHeartRateDao.getLatestDate()?.let { add(it) }
                }

            val rangeStart = oldestCandidates.minOrNull() ?: return null
            val rangeEnd = latestCandidates.maxOrNull() ?: return null
            if (rangeStart.isAfter(rangeEnd)) return null
            return rangeStart..rangeEnd
        }

        private fun Instant.toLocalDate(): LocalDate = atZone(localZoneId).toLocalDate()
    }

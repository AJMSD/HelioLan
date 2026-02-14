package com.heliolan.sync.engine

import com.heliolan.data.dao.ActiveCaloriesBurnedDao
import com.heliolan.data.dao.DistanceRecordDao
import com.heliolan.data.dao.HeartRateSampleDao
import com.heliolan.data.dao.HrvRecordDao
import com.heliolan.data.dao.NutritionRecordDao
import com.heliolan.data.dao.OxygenSaturationDao
import com.heliolan.data.dao.RestingHeartRateDao
import com.heliolan.data.dao.SleepSessionDao
import com.heliolan.data.dao.SleepStageDao
import com.heliolan.data.dao.StepsRecordDao
import com.heliolan.data.dao.SyncCursorDao
import com.heliolan.data.dao.TotalCaloriesBurnedDao
import com.heliolan.data.entity.ActiveCaloriesBurned
import com.heliolan.data.entity.DistanceRecord
import com.heliolan.data.entity.HeartRateSample
import com.heliolan.data.entity.HrvRecord
import com.heliolan.data.entity.NutritionRecord
import com.heliolan.data.entity.OxygenSaturation
import com.heliolan.data.entity.RestingHeartRate
import com.heliolan.data.entity.SleepSession
import com.heliolan.data.entity.StepsRecord
import com.heliolan.data.entity.SyncCursor
import com.heliolan.data.entity.TotalCaloriesBurned
import com.heliolan.data.util.RecordType
import com.heliolan.healthconnect.reader.HealthConnectReader
import com.heliolan.healthconnect.reader.ReadResult
import com.heliolan.sync.model.RecordSyncSummary
import com.heliolan.sync.model.SyncConfig
import com.heliolan.sync.model.SyncError
import com.heliolan.sync.model.SyncErrorCode
import com.heliolan.sync.model.SyncProgress
import com.heliolan.sync.model.SyncProgressState
import com.heliolan.sync.model.SyncResult
import com.heliolan.sync.model.SyncSummary
import com.heliolan.sync.model.SyncTrigger
import com.heliolan.sync.model.SyncWindowMode
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3 sync engine.
 * Handles incremental reads, dedup/upsert writes, cursor updates, and progress emission.
 */
@Suppress("LongMethod", "LongParameterList", "TooManyFunctions")
@Singleton
class SyncEngine
    @Inject
    constructor(
        private val healthConnectReader: HealthConnectReader,
        private val aggregationEngine: AggregationEngine,
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
        private val syncCursorDao: SyncCursorDao,
    ) {
        companion object {
            val SUPPORTED_RECORD_TYPES =
                listOf(
                    RecordType.HEART_RATE,
                    RecordType.SLEEP,
                    RecordType.STEPS,
                    RecordType.RESTING_HR,
                    RecordType.ACTIVE_CALORIES,
                    RecordType.DISTANCE,
                    RecordType.TOTAL_CALORIES,
                    RecordType.NUTRITION,
                    RecordType.OXYGEN_SATURATION,
                    RecordType.HRV,
                )
        }

        private val syncMutex = Mutex()
        private val progressUpdates = MutableSharedFlow<SyncProgress>(extraBufferCapacity = 32)
        private var lastSyncAttemptAt: Instant? = null

        val progress: SharedFlow<SyncProgress> = progressUpdates.asSharedFlow()

        val config: SyncConfig = SyncConfig()

        suspend fun getSyncStatus(): List<SyncCursor> = syncCursorDao.getAllCursors()

        suspend fun syncAll(
            windowMode: SyncWindowMode = SyncWindowMode.LAST_30_DAYS,
            trigger: SyncTrigger = SyncTrigger.AUTOMATIC,
        ): SyncResult {
            return syncInternal(
                recordTypes = SUPPORTED_RECORD_TYPES,
                windowMode = windowMode,
                trigger = trigger,
            )
        }

        suspend fun syncRecordType(
            recordType: String,
            windowMode: SyncWindowMode = SyncWindowMode.LAST_30_DAYS,
            trigger: SyncTrigger = SyncTrigger.AUTOMATIC,
        ): SyncResult {
            return syncInternal(
                recordTypes = listOf(recordType),
                windowMode = windowMode,
                trigger = trigger,
            )
        }

        private suspend fun syncInternal(
            recordTypes: List<String>,
            windowMode: SyncWindowMode,
            trigger: SyncTrigger,
        ): SyncResult {
            return syncMutex.withLock {
                val startedAt = Instant.now()
                if (trigger != SyncTrigger.USER && isDebounced(startedAt)) {
                    return@withLock SyncResult.Failure(
                        SyncError(
                            recordType = null,
                            code = SyncErrorCode.DEBOUNCED,
                            message = "Sync ignored because another sync ran recently.",
                        ),
                    )
                }
                lastSyncAttemptAt = startedAt

                try {
                    withTimeout(config.timeoutMillis) {
                        val summaries = mutableListOf<RecordSyncSummary>()
                        val errors = mutableListOf<SyncError>()

                        for (recordType in recordTypes) {
                            when (val outcome = syncOneRecordType(recordType, windowMode)) {
                                is TypeSyncOutcome.Success -> summaries += outcome.summary
                                is TypeSyncOutcome.Failure -> errors += outcome.error
                            }
                        }

                        val summary =
                            SyncSummary(
                                startedAt = startedAt,
                                completedAt = Instant.now(),
                                records = summaries,
                            )

                        when {
                            errors.isEmpty() -> SyncResult.Success(summary)
                            summaries.isNotEmpty() -> SyncResult.PartialSuccess(summary, errors)
                            else -> SyncResult.Failure(errors.first())
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    SyncResult.Failure(
                        SyncError(
                            recordType = null,
                            code = SyncErrorCode.TIMEOUT,
                            message = "Sync timed out after ${config.timeoutMillis}ms.",
                            throwable = timeout,
                        ),
                    )
                }
            }
        }

        private suspend fun syncOneRecordType(
            recordType: String,
            windowMode: SyncWindowMode,
        ): TypeSyncOutcome {
            if (!SUPPORTED_RECORD_TYPES.contains(recordType)) {
                return TypeSyncOutcome.Failure(
                    SyncError(
                        recordType = recordType,
                        code = SyncErrorCode.UNSUPPORTED_RECORD_TYPE,
                        message = "Record type '$recordType' is not supported.",
                    ),
                )
            }

            val typeStartedAt = Instant.now()
            emitProgress(recordType, SyncProgressState.STARTED, message = "Preparing sync")

            val cursor = syncCursorDao.getCursor(recordType)
            val endTime = Instant.now()
            val startTime = resolveReadStart(cursor, windowMode, endTime, recordType)
            emitProgress(recordType, SyncProgressState.READING, message = "Reading Health Connect")

            return when (recordType) {
                RecordType.HEART_RATE -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readHeartRate(startTime, endTime),
                    ) { records ->
                        persistHeartRate(records)
                    }
                }

                RecordType.SLEEP -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readSleep(startTime, endTime),
                    ) { records ->
                        persistSleepSessions(records)
                    }
                }

                RecordType.STEPS -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readSteps(startTime, endTime),
                    ) { records ->
                        persistSteps(records)
                    }
                }

                RecordType.RESTING_HR -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readRestingHeartRate(startTime, endTime),
                    ) { records ->
                        persistRestingHeartRate(records)
                    }
                }

                RecordType.ACTIVE_CALORIES -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readActiveCaloriesBurned(startTime, endTime),
                    ) { records ->
                        persistActiveCalories(records)
                    }
                }

                RecordType.DISTANCE -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readDistance(startTime, endTime),
                    ) { records ->
                        persistDistance(records)
                    }
                }

                RecordType.TOTAL_CALORIES -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readTotalCaloriesBurned(startTime, endTime),
                    ) { records ->
                        persistTotalCalories(records)
                    }
                }

                RecordType.NUTRITION -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readNutrition(startTime, endTime),
                    ) { records ->
                        persistNutrition(records)
                    }
                }

                RecordType.OXYGEN_SATURATION -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readOxygenSaturation(startTime, endTime),
                    ) { records ->
                        persistOxygenSaturation(records)
                    }
                }

                RecordType.HRV -> {
                    processReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        existingCursor = cursor,
                        readResult = healthConnectReader.readHrv(startTime, endTime),
                    ) { records ->
                        persistHrv(records)
                    }
                }

                else -> {
                    TypeSyncOutcome.Failure(
                        SyncError(
                            recordType = recordType,
                            code = SyncErrorCode.UNSUPPORTED_RECORD_TYPE,
                            message = "Record type '$recordType' is not supported.",
                        ),
                    )
                }
            }
        }

        private suspend fun <T> processReadResult(
            recordType: String,
            typeStartedAt: Instant,
            existingCursor: SyncCursor?,
            readResult: ReadResult<T>,
            persistRecords: suspend (List<T>) -> PersistResult,
        ): TypeSyncOutcome {
            return when (readResult) {
                is ReadResult.Success -> {
                    try {
                        emitProgress(
                            recordType = recordType,
                            state = SyncProgressState.WRITING,
                            fetched = readResult.data.size,
                            message = "Persisting data",
                        )

                        val persistResult = persistRecords(readResult.data)
                        val completedAt = Instant.now()
                        syncCursorDao.upsert(
                            SyncCursor(
                                recordType = recordType,
                                lastSyncTime = completedAt,
                                changeToken = existingCursor?.changeToken,
                            ),
                        )
                        refreshAggregates(
                            recordType = recordType,
                            fetched = readResult.data.size,
                            persistResult = persistResult,
                        )

                        emitProgress(
                            recordType = recordType,
                            state = SyncProgressState.COMPLETED,
                            fetched = readResult.data.size,
                            stored = persistResult.stored,
                            message = "Sync complete",
                        )

                        TypeSyncOutcome.Success(
                            RecordSyncSummary(
                                recordType = recordType,
                                fetched = readResult.data.size,
                                stored = persistResult.stored,
                                deduplicated = persistResult.deduplicated,
                                startedAt = typeStartedAt,
                                completedAt = completedAt,
                            ),
                        )
                    } catch (writeError: Exception) {
                        val syncError =
                            SyncError(
                                recordType = recordType,
                                code = SyncErrorCode.WRITE_FAILED,
                                message = "Failed to store $recordType data: ${writeError.message}",
                                throwable = writeError,
                            )
                        emitProgress(
                            recordType = recordType,
                            state = SyncProgressState.FAILED,
                            message = syncError.message,
                        )
                        TypeSyncOutcome.Failure(syncError)
                    }
                }

                is ReadResult.PermissionDenied -> {
                    val error =
                        SyncError(
                            recordType = recordType,
                            code = SyncErrorCode.PERMISSION_DENIED,
                            message = "Permission denied for $recordType",
                        )
                    emitProgress(recordType, SyncProgressState.FAILED, message = error.message)
                    TypeSyncOutcome.Failure(error)
                }

                is ReadResult.HealthConnectUnavailable -> {
                    val error =
                        SyncError(
                            recordType = recordType,
                            code = SyncErrorCode.HEALTH_CONNECT_UNAVAILABLE,
                            message = "Health Connect unavailable for $recordType",
                        )
                    emitProgress(recordType, SyncProgressState.FAILED, message = error.message)
                    TypeSyncOutcome.Failure(error)
                }

                is ReadResult.Error -> {
                    val error =
                        SyncError(
                            recordType = recordType,
                            code = SyncErrorCode.READ_FAILED,
                            message = readResult.message,
                            throwable = readResult.throwable,
                        )
                    emitProgress(recordType, SyncProgressState.FAILED, message = error.message)
                    TypeSyncOutcome.Failure(error)
                }
            }
        }

        private suspend fun persistHeartRate(records: List<HeartRateSample>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                heartRateSampleDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                heartRateSampleDao.upsert(unique)
            }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = unique.mapTo(mutableSetOf()) { it.timestamp.toLocalDate() },
            )
        }

        private suspend fun persistSteps(records: List<StepsRecord>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                stepsRecordDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                stepsRecordDao.upsert(unique)
            }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = unique.mapTo(mutableSetOf()) { it.startTime.toLocalDate() },
            )
        }

        private suspend fun persistRestingHeartRate(records: List<RestingHeartRate>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                restingHeartRateDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                restingHeartRateDao.upsert(unique)
            }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = unique.mapTo(mutableSetOf()) { it.date },
            )
        }

        private suspend fun persistActiveCalories(records: List<ActiveCaloriesBurned>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                activeCaloriesBurnedDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                activeCaloriesBurnedDao.upsert(unique)
            }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = unique.mapTo(mutableSetOf()) { it.date },
            )
        }

        private suspend fun persistDistance(records: List<DistanceRecord>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                distanceRecordDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                distanceRecordDao.upsert(unique)
            }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = unique.mapTo(mutableSetOf()) { it.startTime.toLocalDate() },
            )
        }

        private suspend fun persistTotalCalories(records: List<TotalCaloriesBurned>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                totalCaloriesBurnedDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                totalCaloriesBurnedDao.upsert(unique)
            }
            val affectedDates =
                unique.flatMapTo(mutableSetOf()) { record ->
                    listOf(record.startTime.toLocalDate(), record.endTime.toLocalDate())
                }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = affectedDates,
            )
        }

        private suspend fun persistNutrition(records: List<NutritionRecord>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                nutritionRecordDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                nutritionRecordDao.upsert(unique)
            }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = unique.mapTo(mutableSetOf()) { it.startTime.toLocalDate() },
            )
        }

        private suspend fun persistOxygenSaturation(records: List<OxygenSaturation>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                oxygenSaturationDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                oxygenSaturationDao.upsert(unique)
            }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = unique.mapTo(mutableSetOf()) { it.timestamp.toLocalDate() },
            )
        }

        private suspend fun persistHrv(records: List<HrvRecord>): PersistResult {
            val unique = records.distinctBy { it.healthConnectId }
            if (unique.isNotEmpty()) {
                hrvRecordDao.deleteByHealthConnectIds(unique.map { it.healthConnectId })
                hrvRecordDao.upsert(unique)
            }
            return PersistResult(
                stored = unique.size,
                deduplicated = records.size - unique.size,
                affectedDates = unique.mapTo(mutableSetOf()) { it.timestamp.toLocalDate() },
            )
        }

        private suspend fun persistSleepSessions(records: List<SleepSession>): PersistResult {
            val uniqueSessions = records.distinctBy { it.healthConnectId }
            for (session in uniqueSessions) {
                sleepSessionDao.deleteByHealthConnectId(session.healthConnectId)
                val sessionId = sleepSessionDao.upsert(session)

                // Best effort stage sync: if this call fails or returns null, keep session data.
                val stages =
                    healthConnectReader.readSleepStages(
                        healthConnectId = session.healthConnectId,
                        sessionId = sessionId,
                        syncedAt = session.syncedAt,
                    )
                if (stages.isNotEmpty()) {
                    sleepStageDao.deleteForSession(sessionId)
                    sleepStageDao.upsert(stages)
                }
            }

            return PersistResult(
                stored = uniqueSessions.size,
                deduplicated = records.size - uniqueSessions.size,
                affectedDates =
                    uniqueSessions.flatMapTo(mutableSetOf()) { session ->
                        listOf(session.startTime.toLocalDate(), session.endTime.toLocalDate())
                    },
            )
        }

        private fun resolveReadStart(
            cursor: SyncCursor?,
            windowMode: SyncWindowMode,
            now: Instant,
            recordType: String,
        ): Instant {
            val initialStart =
                when (windowMode) {
                    SyncWindowMode.LAST_30_DAYS -> now.minus(config.initialWindowDays, ChronoUnit.DAYS)
                    SyncWindowMode.FULL_HISTORY -> Instant.EPOCH
                }

            val baselineStart =
                if (cursor == null) {
                    initialStart
                } else {
                    cursor.lastSyncTime.minus(config.safetyWindowHours, ChronoUnit.HOURS)
                }

            // Sleep and total-calorie records can be long-running/cross-midnight; widen replay window.
            val recordSpecificStart =
                when (recordType) {
                    RecordType.SLEEP,
                    RecordType.TOTAL_CALORIES,
                    -> now.minus(36, ChronoUnit.HOURS)
                    else -> baselineStart
                }
            val start = minOf(baselineStart, recordSpecificStart)

            return if (start.isAfter(now)) now else start
        }

        private fun isDebounced(now: Instant): Boolean {
            val last = lastSyncAttemptAt ?: return false
            return Duration.between(last, now).seconds < config.debounceSeconds
        }

        private fun emitProgress(
            recordType: String,
            state: SyncProgressState,
            fetched: Int = 0,
            stored: Int = 0,
            message: String? = null,
        ) {
            progressUpdates.tryEmit(
                SyncProgress(
                    recordType = recordType,
                    state = state,
                    fetched = fetched,
                    stored = stored,
                    message = message,
                ),
            )
        }

        private suspend fun refreshAggregates(
            recordType: String,
            fetched: Int,
            persistResult: PersistResult,
        ) {
            if (persistResult.affectedDates.isEmpty()) return
            try {
                aggregationEngine.updateAggregatesForDates(persistResult.affectedDates)
            } catch (aggregationError: Exception) {
                // Keep sync writes as source of truth; aggregates can be rebuilt later.
                emitProgress(
                    recordType = recordType,
                    state = SyncProgressState.WRITING,
                    fetched = fetched,
                    stored = persistResult.stored,
                    message = "Data synced, aggregate refresh failed: ${aggregationError.message}",
                )
            }
        }

        private fun Instant.toLocalDate(): LocalDate {
            return atZone(ZoneId.systemDefault()).toLocalDate()
        }
    }

private data class PersistResult(
    val stored: Int,
    val deduplicated: Int,
    val affectedDates: Set<LocalDate>,
)

private sealed class TypeSyncOutcome {
    data class Success(val summary: RecordSyncSummary) : TypeSyncOutcome()

    data class Failure(val error: SyncError) : TypeSyncOutcome()
}

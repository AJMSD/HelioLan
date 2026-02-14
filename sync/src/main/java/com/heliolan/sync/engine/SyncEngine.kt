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
import com.heliolan.healthconnect.reader.ChangesTokenResult
import com.heliolan.healthconnect.reader.HealthConnectReader
import com.heliolan.healthconnect.reader.IncrementalReadResult
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

        var config: SyncConfig = SyncConfig()

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
                            when (val outcome = syncOneRecordType(recordType, windowMode, trigger)) {
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
            trigger: SyncTrigger,
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
            val shouldUseChanges = config.useChangesApiForAutomaticSync && trigger != SyncTrigger.USER
            return if (shouldUseChanges) {
                syncOneRecordTypeWithChanges(
                    recordType = recordType,
                    typeStartedAt = typeStartedAt,
                    cursor = cursor,
                    windowMode = windowMode,
                )
            } else {
                syncOneRecordTypeWithPolling(
                    recordType = recordType,
                    typeStartedAt = typeStartedAt,
                    cursor = cursor,
                    windowMode = windowMode,
                )
            }
        }

        private suspend fun syncOneRecordTypeWithPolling(
            recordType: String,
            typeStartedAt: Instant,
            cursor: SyncCursor?,
            windowMode: SyncWindowMode,
            changeTokenOverride: String? = cursor?.changeToken,
        ): TypeSyncOutcome {
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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
                        changeTokenOverride = changeTokenOverride,
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

        private suspend fun syncOneRecordTypeWithChanges(
            recordType: String,
            typeStartedAt: Instant,
            cursor: SyncCursor?,
            windowMode: SyncWindowMode,
        ): TypeSyncOutcome {
            val existingToken = cursor?.changeToken
            if (existingToken.isNullOrBlank()) {
                emitProgress(
                    recordType = recordType,
                    state = SyncProgressState.READING,
                    message = "No changes token yet, bootstrapping with polling",
                )
                val bootstrapToken = tryGetChangesToken(recordType)
                return syncOneRecordTypeWithPolling(
                    recordType = recordType,
                    typeStartedAt = typeStartedAt,
                    cursor = cursor,
                    windowMode = windowMode,
                    changeTokenOverride = bootstrapToken,
                )
            }

            emitProgress(recordType, SyncProgressState.READING, message = "Reading Health Connect changes")
            return when (recordType) {
                RecordType.HEART_RATE -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readHeartRateChanges(existingToken),
                        persistRecords = { records -> persistHeartRate(records) },
                        deleteRecords = { recordIds -> deleteHeartRate(recordIds) },
                    )
                }

                RecordType.SLEEP -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readSleepChanges(existingToken),
                        persistRecords = { records -> persistSleepSessions(records) },
                        deleteRecords = { recordIds -> deleteSleep(recordIds) },
                    )
                }

                RecordType.STEPS -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readStepsChanges(existingToken),
                        persistRecords = { records -> persistSteps(records) },
                        deleteRecords = { recordIds -> deleteSteps(recordIds) },
                    )
                }

                RecordType.RESTING_HR -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readRestingHeartRateChanges(existingToken),
                        persistRecords = { records -> persistRestingHeartRate(records) },
                        deleteRecords = { recordIds -> deleteRestingHeartRate(recordIds) },
                    )
                }

                RecordType.ACTIVE_CALORIES -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readActiveCaloriesChanges(existingToken),
                        persistRecords = { records -> persistActiveCalories(records) },
                        deleteRecords = { recordIds -> deleteActiveCalories(recordIds) },
                    )
                }

                RecordType.DISTANCE -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readDistanceChanges(existingToken),
                        persistRecords = { records -> persistDistance(records) },
                        deleteRecords = { recordIds -> deleteDistance(recordIds) },
                    )
                }

                RecordType.TOTAL_CALORIES -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readTotalCaloriesChanges(existingToken),
                        persistRecords = { records -> persistTotalCalories(records) },
                        deleteRecords = { recordIds -> deleteTotalCalories(recordIds) },
                    )
                }

                RecordType.NUTRITION -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readNutritionChanges(existingToken),
                        persistRecords = { records -> persistNutrition(records) },
                        deleteRecords = { recordIds -> deleteNutrition(recordIds) },
                    )
                }

                RecordType.OXYGEN_SATURATION -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readOxygenSaturationChanges(existingToken),
                        persistRecords = { records -> persistOxygenSaturation(records) },
                        deleteRecords = { recordIds -> deleteOxygenSaturation(recordIds) },
                    )
                }

                RecordType.HRV -> {
                    processIncrementalReadResult(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        incrementalReadResult = healthConnectReader.readHrvChanges(existingToken),
                        persistRecords = { records -> persistHrv(records) },
                        deleteRecords = { recordIds -> deleteHrv(recordIds) },
                    )
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

        private suspend fun tryGetChangesToken(recordType: String): String? {
            return when (val result = healthConnectReader.getChangesTokenForRecordType(recordType)) {
                is ChangesTokenResult.Success -> result.token
                else -> null
            }
        }

        private suspend fun <T> processIncrementalReadResult(
            recordType: String,
            typeStartedAt: Instant,
            cursor: SyncCursor?,
            windowMode: SyncWindowMode,
            incrementalReadResult: IncrementalReadResult<T>,
            persistRecords: suspend (List<T>) -> PersistResult,
            deleteRecords: suspend (List<String>) -> DeleteResult,
        ): TypeSyncOutcome {
            return when (incrementalReadResult) {
                is IncrementalReadResult.Success -> {
                    try {
                        val incrementalData = incrementalReadResult.data
                        emitProgress(
                            recordType = recordType,
                            state = SyncProgressState.WRITING,
                            fetched = incrementalData.upserted.size,
                            message = "Persisting incremental data",
                        )

                        val persistResult = persistRecords(incrementalData.upserted)
                        val deleteResult = deleteRecords(incrementalData.deletedRecordIds)
                        val affectedDates = persistResult.affectedDates + deleteResult.affectedDates
                        val combinedPersistResult = persistResult.copy(affectedDates = affectedDates)

                        val completedAt = Instant.now()
                        syncCursorDao.upsert(
                            SyncCursor(
                                recordType = recordType,
                                lastSyncTime = completedAt,
                                changeToken = incrementalData.nextChangesToken,
                            ),
                        )
                        refreshAggregates(
                            recordType = recordType,
                            fetched = incrementalData.upserted.size + deleteResult.deleted,
                            persistResult = combinedPersistResult,
                        )

                        val completionMessage =
                            if (deleteResult.deleted == 0) {
                                "Sync complete"
                            } else {
                                "Sync complete (${deleteResult.deleted} deletions)"
                            }

                        emitProgress(
                            recordType = recordType,
                            state = SyncProgressState.COMPLETED,
                            fetched = incrementalData.upserted.size + deleteResult.deleted,
                            stored = persistResult.stored,
                            message = completionMessage,
                        )

                        TypeSyncOutcome.Success(
                            RecordSyncSummary(
                                recordType = recordType,
                                fetched = incrementalData.upserted.size + deleteResult.deleted,
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
                                message = "Failed to store $recordType changes: ${writeError.message}",
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

                is IncrementalReadResult.TokenExpired -> {
                    emitProgress(
                        recordType = recordType,
                        state = SyncProgressState.READING,
                        message = "Changes token expired, falling back to polling",
                    )
                    val refreshedToken = tryGetChangesToken(recordType)
                    syncOneRecordTypeWithPolling(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                        changeTokenOverride = refreshedToken,
                    )
                }

                is IncrementalReadResult.Error -> {
                    emitProgress(
                        recordType = recordType,
                        state = SyncProgressState.READING,
                        message = "Changes read failed, falling back to polling",
                    )
                    syncOneRecordTypeWithPolling(
                        recordType = recordType,
                        typeStartedAt = typeStartedAt,
                        cursor = cursor,
                        windowMode = windowMode,
                    )
                }

                is IncrementalReadResult.PermissionDenied -> {
                    val error =
                        SyncError(
                            recordType = recordType,
                            code = SyncErrorCode.PERMISSION_DENIED,
                            message = "Permission denied for $recordType",
                        )
                    emitProgress(recordType, SyncProgressState.FAILED, message = error.message)
                    TypeSyncOutcome.Failure(error)
                }

                is IncrementalReadResult.HealthConnectUnavailable -> {
                    val error =
                        SyncError(
                            recordType = recordType,
                            code = SyncErrorCode.HEALTH_CONNECT_UNAVAILABLE,
                            message = "Health Connect unavailable for $recordType",
                        )
                    emitProgress(recordType, SyncProgressState.FAILED, message = error.message)
                    TypeSyncOutcome.Failure(error)
                }
            }
        }

        private suspend fun <T> processReadResult(
            recordType: String,
            typeStartedAt: Instant,
            existingCursor: SyncCursor?,
            readResult: ReadResult<T>,
            changeTokenOverride: String? = existingCursor?.changeToken,
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
                                changeToken = changeTokenOverride,
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

        private suspend fun deleteHeartRate(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val affectedDates = mutableSetOf<LocalDate>()
            var deleted = 0
            uniqueIds.forEach { recordId ->
                val prefixPattern = toRecordIdPrefixPattern(recordId)
                val existing =
                    heartRateSampleDao.getByHealthConnectIdOrPrefix(
                        healthConnectId = recordId,
                        idPrefixPattern = prefixPattern,
                    )
                if (existing.isEmpty()) return@forEach

                heartRateSampleDao.deleteByHealthConnectIdOrPrefix(
                    healthConnectId = recordId,
                    idPrefixPattern = prefixPattern,
                )
                affectedDates += existing.map { it.timestamp.toLocalDate() }
                deleted += existing.size
            }
            return DeleteResult(deleted = deleted, affectedDates = affectedDates)
        }

        private suspend fun deleteSleep(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = sleepSessionDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                sleepSessionDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates =
                    existing.flatMapTo(mutableSetOf()) { session ->
                        listOf(session.startTime.toLocalDate(), session.endTime.toLocalDate())
                    },
            )
        }

        private suspend fun deleteSteps(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = stepsRecordDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                stepsRecordDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates = existing.mapTo(mutableSetOf()) { it.startTime.toLocalDate() },
            )
        }

        private suspend fun deleteRestingHeartRate(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = restingHeartRateDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                restingHeartRateDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates = existing.mapTo(mutableSetOf()) { it.date },
            )
        }

        private suspend fun deleteActiveCalories(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = activeCaloriesBurnedDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                activeCaloriesBurnedDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates = existing.mapTo(mutableSetOf()) { it.date },
            )
        }

        private suspend fun deleteDistance(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = distanceRecordDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                distanceRecordDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates = existing.mapTo(mutableSetOf()) { it.startTime.toLocalDate() },
            )
        }

        private suspend fun deleteTotalCalories(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = totalCaloriesBurnedDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                totalCaloriesBurnedDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates =
                    existing.flatMapTo(mutableSetOf()) { record ->
                        listOf(record.startTime.toLocalDate(), record.endTime.toLocalDate())
                    },
            )
        }

        private suspend fun deleteNutrition(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = nutritionRecordDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                nutritionRecordDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates = existing.mapTo(mutableSetOf()) { it.startTime.toLocalDate() },
            )
        }

        private suspend fun deleteOxygenSaturation(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = oxygenSaturationDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                oxygenSaturationDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates = existing.mapTo(mutableSetOf()) { it.timestamp.toLocalDate() },
            )
        }

        private suspend fun deleteHrv(recordIds: List<String>): DeleteResult {
            val uniqueIds = recordIds.distinct()
            if (uniqueIds.isEmpty()) return DeleteResult(deleted = 0, affectedDates = emptySet())

            val existing = hrvRecordDao.getByHealthConnectIds(uniqueIds)
            if (existing.isNotEmpty()) {
                hrvRecordDao.deleteByHealthConnectIds(uniqueIds)
            }
            return DeleteResult(
                deleted = existing.size,
                affectedDates = existing.mapTo(mutableSetOf()) { it.timestamp.toLocalDate() },
            )
        }

        private fun toRecordIdPrefixPattern(recordId: String): String {
            val escaped =
                recordId
                    .replace("\\", "\\\\")
                    .replace("%", "\\%")
                    .replace("_", "\\_")
            return "${escaped}\\_%"
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

private data class DeleteResult(
    val deleted: Int,
    val affectedDates: Set<LocalDate>,
)

private sealed class TypeSyncOutcome {
    data class Success(val summary: RecordSyncSummary) : TypeSyncOutcome()

    data class Failure(val error: SyncError) : TypeSyncOutcome()
}

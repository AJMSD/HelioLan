package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.StepsRecord
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Data Access Object for steps records.
 */
@Dao
interface StepsRecordDao {
    /**
     * Get steps records within a time range, ordered by start time descending.
     */
    @Query(
        """
        SELECT * FROM steps_records
        WHERE start_time BETWEEN :startTime AND :endTime
        ORDER BY start_time DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun getByDateRange(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<StepsRecord>>

    /**
     * Get total steps for today (sum of all records).
     */
    @Query(
        """
        SELECT COALESCE(SUM(count), 0) FROM steps_records
        WHERE start_time BETWEEN :startTime AND :endTime
        """,
    )
    fun getTotalSteps(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Int>

    /**
     * Get the most recent steps record.
     */
    @Query("SELECT * FROM steps_records ORDER BY start_time DESC LIMIT 1")
    fun getLatest(): Flow<StepsRecord?>

    /**
     * Get the oldest steps record start time.
     * Used by aggregation rebuild to determine source-data bounds.
     */
    @Query("SELECT MIN(start_time) FROM steps_records")
    suspend fun getOldestStartTime(): Instant?

    /**
     * Get the latest steps record end time.
     * Used by aggregation rebuild to determine source-data bounds.
     */
    @Query("SELECT MAX(end_time) FROM steps_records")
    suspend fun getLatestEndTime(): Instant?

    /**
     * Upsert steps records.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<StepsRecord>)

    /**
     * Insert single record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: StepsRecord)

    /**
     * Get records by Health Connect IDs.
     */
    @Query("SELECT * FROM steps_records WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun getByHealthConnectIds(healthConnectIds: List<String>): List<StepsRecord>

    /**
     * Delete records by Health Connect IDs.
     * Used by SyncEngine to replace stale data in safety windows.
     */
    @Query("DELETE FROM steps_records WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    /**
     * Delete all records.
     */
    @Query("DELETE FROM steps_records")
    suspend fun deleteAll()

    /**
     * Get records for aggregation.
     */
    @Query(
        """
        SELECT * FROM steps_records
        WHERE start_time BETWEEN :startTime AND :endTime
        ORDER BY start_time ASC
        """,
    )
    suspend fun getRecordsForAggregation(
        startTime: Instant,
        endTime: Instant,
    ): List<StepsRecord>
}

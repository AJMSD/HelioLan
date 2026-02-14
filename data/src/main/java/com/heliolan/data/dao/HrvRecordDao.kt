package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.HrvRecord
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * DAO for HRV (RMSSD) records.
 */
@Dao
interface HrvRecordDao {
    @Query(
        """
        SELECT * FROM hrv_records
        WHERE timestamp BETWEEN :startTime AND :endTime
        ORDER BY timestamp DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun getByDateRange(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<HrvRecord>>

    @Query("SELECT * FROM hrv_records ORDER BY timestamp DESC LIMIT 1")
    fun getLatest(): Flow<HrvRecord?>

    @Query(
        """
        SELECT AVG(rmssd) FROM hrv_records
        WHERE timestamp BETWEEN :startTime AND :endTime
        """,
    )
    fun getAverageRmssd(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Double?>

    @Query("SELECT MIN(timestamp) FROM hrv_records")
    suspend fun getOldestTimestamp(): Instant?

    @Query("SELECT MAX(timestamp) FROM hrv_records")
    suspend fun getLatestTimestamp(): Instant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<HrvRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: HrvRecord)

    @Query("SELECT * FROM hrv_records WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun getByHealthConnectIds(healthConnectIds: List<String>): List<HrvRecord>

    @Query("DELETE FROM hrv_records WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    @Query("DELETE FROM hrv_records")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM hrv_records
        WHERE timestamp BETWEEN :startTime AND :endTime
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getRecordsForAggregation(
        startTime: Instant,
        endTime: Instant,
    ): List<HrvRecord>
}

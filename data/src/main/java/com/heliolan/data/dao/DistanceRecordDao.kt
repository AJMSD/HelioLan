package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.DistanceRecord
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * DAO for distance records.
 */
@Dao
interface DistanceRecordDao {
    @Query(
        """
        SELECT * FROM distance_records
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
    ): Flow<List<DistanceRecord>>

    @Query("SELECT * FROM distance_records ORDER BY start_time DESC LIMIT 1")
    fun getLatest(): Flow<DistanceRecord?>

    @Query(
        """
        SELECT COALESCE(SUM(distance_meters), 0.0) FROM distance_records
        WHERE start_time BETWEEN :startTime AND :endTime
        """,
    )
    fun getTotalDistanceMeters(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Double>

    @Query("SELECT MIN(start_time) FROM distance_records")
    suspend fun getOldestStartTime(): Instant?

    @Query("SELECT MAX(end_time) FROM distance_records")
    suspend fun getLatestEndTime(): Instant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<DistanceRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: DistanceRecord)

    @Query("SELECT * FROM distance_records WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun getByHealthConnectIds(healthConnectIds: List<String>): List<DistanceRecord>

    @Query("DELETE FROM distance_records WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    @Query("DELETE FROM distance_records")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM distance_records
        WHERE start_time BETWEEN :startTime AND :endTime
        ORDER BY start_time ASC
        """,
    )
    suspend fun getRecordsForAggregation(
        startTime: Instant,
        endTime: Instant,
    ): List<DistanceRecord>
}

package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.NutritionRecord
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * DAO for nutrition records.
 */
@Dao
interface NutritionRecordDao {
    @Query(
        """
        SELECT * FROM nutrition_records
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
    ): Flow<List<NutritionRecord>>

    @Query("SELECT * FROM nutrition_records ORDER BY start_time DESC LIMIT 1")
    fun getLatest(): Flow<NutritionRecord?>

    @Query("SELECT MIN(start_time) FROM nutrition_records")
    suspend fun getOldestStartTime(): Instant?

    @Query("SELECT MAX(end_time) FROM nutrition_records")
    suspend fun getLatestEndTime(): Instant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<NutritionRecord>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: NutritionRecord)

    @Query("SELECT * FROM nutrition_records WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun getByHealthConnectIds(healthConnectIds: List<String>): List<NutritionRecord>

    @Query("DELETE FROM nutrition_records WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    @Query("DELETE FROM nutrition_records")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM nutrition_records
        WHERE start_time BETWEEN :startTime AND :endTime
        ORDER BY start_time ASC
        """,
    )
    suspend fun getRecordsForAggregation(
        startTime: Instant,
        endTime: Instant,
    ): List<NutritionRecord>
}

package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.TotalCaloriesBurned
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * DAO for total calories burned records.
 */
@Dao
interface TotalCaloriesBurnedDao {
    @Query(
        """
        SELECT * FROM total_calories_burned
        WHERE end_time >= :startTime
          AND start_time <= :endTime
        ORDER BY end_time DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun getByDateRange(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 1000,
        offset: Int = 0,
    ): Flow<List<TotalCaloriesBurned>>

    @Query("SELECT * FROM total_calories_burned ORDER BY start_time DESC LIMIT 1")
    fun getLatest(): Flow<TotalCaloriesBurned?>

    @Query(
        """
        SELECT COALESCE(SUM(energy_kcal), 0.0)
        FROM total_calories_burned
        WHERE end_time >= :startTime
          AND start_time <= :endTime
        """,
    )
    fun getTotalEnergyKcal(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Double>

    @Query("SELECT MIN(start_time) FROM total_calories_burned")
    suspend fun getOldestStartTime(): Instant?

    @Query("SELECT MAX(end_time) FROM total_calories_burned")
    suspend fun getLatestEndTime(): Instant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<TotalCaloriesBurned>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: TotalCaloriesBurned)

    @Query("DELETE FROM total_calories_burned WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    @Query("DELETE FROM total_calories_burned")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM total_calories_burned
        WHERE end_time >= :startTime
          AND start_time <= :endTime
        ORDER BY end_time ASC
        """,
    )
    suspend fun getRecordsForAggregation(
        startTime: Instant,
        endTime: Instant,
    ): List<TotalCaloriesBurned>
}

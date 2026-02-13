package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.ActiveCaloriesBurned
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * DAO for active calories burned records.
 */
@Dao
interface ActiveCaloriesBurnedDao {
    @Query(
        """
        SELECT * FROM active_calories_burned
        WHERE date BETWEEN :startDate AND :endDate
        ORDER BY date DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun getByDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
        limit: Int = 365,
        offset: Int = 0,
    ): Flow<List<ActiveCaloriesBurned>>

    @Query("SELECT * FROM active_calories_burned ORDER BY date DESC LIMIT 1")
    fun getLatest(): Flow<ActiveCaloriesBurned?>

    @Query(
        """
        SELECT COALESCE(SUM(calories), 0.0) FROM active_calories_burned
        WHERE date BETWEEN :startDate AND :endDate
        """,
    )
    fun getTotalCalories(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<Double>

    @Query("SELECT MIN(date) FROM active_calories_burned")
    suspend fun getOldestDate(): LocalDate?

    @Query("SELECT MAX(date) FROM active_calories_burned")
    suspend fun getLatestDate(): LocalDate?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<ActiveCaloriesBurned>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: ActiveCaloriesBurned)

    @Query("DELETE FROM active_calories_burned WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    @Query("DELETE FROM active_calories_burned")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM active_calories_burned
        WHERE date BETWEEN :startDate AND :endDate
        ORDER BY date ASC
        """,
    )
    suspend fun getRecordsForAggregation(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<ActiveCaloriesBurned>
}

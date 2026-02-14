package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.RestingHeartRate
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Data Access Object for resting heart rate.
 */
@Dao
interface RestingHeartRateDao {
    /**
     * Get resting heart rate records within a date range.
     */
    @Query(
        """
        SELECT * FROM resting_heart_rate
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
    ): Flow<List<RestingHeartRate>>

    /**
     * Get resting heart rate for a specific date.
     */
    @Query("SELECT * FROM resting_heart_rate WHERE date = :date LIMIT 1")
    fun getByDate(date: LocalDate): Flow<RestingHeartRate?>

    /**
     * Get the most recent resting heart rate.
     */
    @Query("SELECT * FROM resting_heart_rate ORDER BY date DESC LIMIT 1")
    fun getLatest(): Flow<RestingHeartRate?>

    /**
     * Get the oldest resting heart rate date.
     * Used by aggregation rebuild to determine source-data bounds.
     */
    @Query("SELECT MIN(date) FROM resting_heart_rate")
    suspend fun getOldestDate(): LocalDate?

    /**
     * Get the latest resting heart rate date.
     * Used by aggregation rebuild to determine source-data bounds.
     */
    @Query("SELECT MAX(date) FROM resting_heart_rate")
    suspend fun getLatestDate(): LocalDate?

    /**
     * Upsert resting heart rate records.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<RestingHeartRate>)

    /**
     * Insert single record.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: RestingHeartRate)

    /**
     * Get records by Health Connect IDs.
     */
    @Query("SELECT * FROM resting_heart_rate WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun getByHealthConnectIds(healthConnectIds: List<String>): List<RestingHeartRate>

    /**
     * Delete records by Health Connect IDs.
     * Used by SyncEngine to replace stale data in safety windows.
     */
    @Query("DELETE FROM resting_heart_rate WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    /**
     * Delete all records.
     */
    @Query("DELETE FROM resting_heart_rate")
    suspend fun deleteAll()

    /**
     * Get records for aggregation.
     */
    @Query(
        """
        SELECT * FROM resting_heart_rate
        WHERE date BETWEEN :startDate AND :endDate
        ORDER BY date ASC
        """,
    )
    suspend fun getRecordsForAggregation(
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<RestingHeartRate>
}

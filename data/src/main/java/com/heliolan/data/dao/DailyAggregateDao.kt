package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.DailyAggregate
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Data Access Object for daily aggregates.
 * These are computed values updated by the aggregation pipeline (Phase 4).
 */
@Dao
interface DailyAggregateDao {
    /**
     * Get daily aggregates for a specific record type and date range.
     */
    @Query(
        """
        SELECT * FROM daily_aggregates
        WHERE record_type = :recordType
        AND date BETWEEN :startDate AND :endDate
        ORDER BY date DESC
        """,
    )
    fun getByTypeAndDateRange(
        recordType: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<List<DailyAggregate>>

    /**
     * Get all aggregates for a date range (all types).
     */
    @Query(
        """
        SELECT * FROM daily_aggregates
        WHERE date BETWEEN :startDate AND :endDate
        ORDER BY date DESC, record_type ASC
        """,
    )
    fun getByDateRange(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Flow<List<DailyAggregate>>

    /**
     * Get latest aggregate for a specific type.
     */
    @Query(
        """
        SELECT * FROM daily_aggregates
        WHERE record_type = :recordType
        ORDER BY date DESC
        LIMIT 1
        """,
    )
    fun getLatestForType(recordType: String): Flow<DailyAggregate?>

    /**
     * Upsert daily aggregates (replaces existing by auto-generated ID conflicts).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(aggregates: List<DailyAggregate>)

    /**
     * Insert single aggregate.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(aggregate: DailyAggregate)

    /**
     * Delete aggregates for a specific date and type (for recomputation).
     */
    @Query("DELETE FROM daily_aggregates WHERE record_type = :recordType AND date = :date")
    suspend fun deleteForDateAndType(
        recordType: String,
        date: LocalDate,
    )

    /**
     * Delete all aggregates.
     */
    @Query("DELETE FROM daily_aggregates")
    suspend fun deleteAll()
}

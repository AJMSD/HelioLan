package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.HeartRateSample
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Data Access Object for heart rate samples.
 * Provides efficient time-range queries with pagination support.
 */
@Dao
interface HeartRateSampleDao {
    /**
     * Get heart rate samples within a time range, ordered by timestamp descending.
     * Returns Flow for reactive UI updates.
     */
    @Query(
        """
        SELECT * FROM heart_rate_samples
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
    ): Flow<List<HeartRateSample>>

    /**
     * Get the most recent heart rate sample.
     */
    @Query("SELECT * FROM heart_rate_samples ORDER BY timestamp DESC LIMIT 1")
    fun getLatest(): Flow<HeartRateSample?>

    /**
     * Get the oldest heart rate sample timestamp.
     * Used by aggregation rebuild to determine source-data bounds.
     */
    @Query("SELECT MIN(timestamp) FROM heart_rate_samples")
    suspend fun getOldestTimestamp(): Instant?

    /**
     * Get the latest heart rate sample timestamp.
     * Used by aggregation rebuild to determine source-data bounds.
     */
    @Query("SELECT MAX(timestamp) FROM heart_rate_samples")
    suspend fun getLatestTimestamp(): Instant?

    /**
     * Get count of samples in a time range (for pagination).
     */
    @Query(
        """
        SELECT COUNT(*) FROM heart_rate_samples
        WHERE timestamp BETWEEN :startTime AND :endTime
        """,
    )
    suspend fun getCountInRange(
        startTime: Instant,
        endTime: Instant,
    ): Int

    /**
     * Upsert samples - replaces if Health Connect ID already exists.
     * Used for deduplication during sync.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(samples: List<HeartRateSample>)

    /**
     * Insert single sample.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sample: HeartRateSample)

    /**
     * Delete records by Health Connect IDs.
     * Used by SyncEngine to replace stale data in safety windows.
     */
    @Query("DELETE FROM heart_rate_samples WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    /**
     * Delete all samples (for testing or data reset).
     */
    @Query("DELETE FROM heart_rate_samples")
    suspend fun deleteAll()

    /**
     * Get samples grouped by hour for aggregation pipeline.
     */
    @Query(
        """
        SELECT * FROM heart_rate_samples
        WHERE timestamp BETWEEN :startTime AND :endTime
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getSamplesForAggregation(
        startTime: Instant,
        endTime: Instant,
    ): List<HeartRateSample>
}

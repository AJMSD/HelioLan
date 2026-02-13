package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.SleepSession
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Data Access Object for sleep sessions.
 */
@Dao
interface SleepSessionDao {
    /**
     * Get sleep sessions attributed to wake-day (session end time), ordered by end time descending.
     */
    @Query(
        """
        SELECT * FROM sleep_sessions
        WHERE end_time BETWEEN :startTime AND :endTime
        ORDER BY end_time DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    fun getByDateRange(
        startTime: Instant,
        endTime: Instant,
        limit: Int = 100,
        offset: Int = 0,
    ): Flow<List<SleepSession>>

    /**
     * Get the most recent sleep session.
     */
    @Query("SELECT * FROM sleep_sessions ORDER BY end_time DESC LIMIT 1")
    fun getLatest(): Flow<SleepSession?>

    /**
     * Get the oldest sleep session start time.
     * Used by aggregation rebuild to determine source-data bounds.
     */
    @Query("SELECT MIN(start_time) FROM sleep_sessions")
    suspend fun getOldestStartTime(): Instant?

    /**
     * Get the latest sleep session end time.
     * Used by aggregation rebuild to determine source-data bounds.
     */
    @Query("SELECT MAX(end_time) FROM sleep_sessions")
    suspend fun getLatestEndTime(): Instant?

    /**
     * Get sleep session by ID (for loading with stages).
     */
    @Query("SELECT * FROM sleep_sessions WHERE id = :sessionId")
    suspend fun getById(sessionId: Long): SleepSession?

    /**
     * Get sleep session by Health Connect ID.
     */
    @Query("SELECT * FROM sleep_sessions WHERE health_connect_id = :healthConnectId LIMIT 1")
    suspend fun getByHealthConnectId(healthConnectId: String): SleepSession?

    /**
     * Upsert sleep sessions - replaces if Health Connect ID already exists.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(sessions: List<SleepSession>)

    /**
     * Insert single session.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SleepSession): Long

    /**
     * Delete a session by Health Connect ID.
     * Used before re-inserting records within sync safety windows.
     */
    @Query("DELETE FROM sleep_sessions WHERE health_connect_id = :healthConnectId")
    suspend fun deleteByHealthConnectId(healthConnectId: String)

    /**
     * Delete all sessions (cascade deletes stages via foreign key).
     */
    @Query("DELETE FROM sleep_sessions")
    suspend fun deleteAll()

    /**
     * Get sessions for aggregation.
     */
    @Query(
        """
        SELECT * FROM sleep_sessions
        WHERE end_time BETWEEN :startTime AND :endTime
        ORDER BY end_time ASC
        """,
    )
    suspend fun getSessionsForAggregation(
        startTime: Instant,
        endTime: Instant,
    ): List<SleepSession>
}

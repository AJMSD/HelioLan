package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.SleepStage
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for sleep stages.
 * Stages are always queried with their parent session.
 */
@Dao
interface SleepStageDao {
    /**
     * Get all stages for a specific sleep session.
     */
    @Query(
        """
        SELECT * FROM sleep_stages
        WHERE session_id = :sessionId
        ORDER BY start_time ASC
        """,
    )
    fun getBySessionId(sessionId: Long): Flow<List<SleepStage>>

    /**
     * Upsert sleep stages.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stages: List<SleepStage>)

    /**
     * Insert single stage.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stage: SleepStage)

    /**
     * Delete all stages for a session.
     */
    @Query("DELETE FROM sleep_stages WHERE session_id = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    /**
     * Delete all stages.
     */
    @Query("DELETE FROM sleep_stages")
    suspend fun deleteAll()
}

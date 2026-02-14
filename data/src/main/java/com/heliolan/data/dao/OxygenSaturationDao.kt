package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.OxygenSaturation
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * DAO for oxygen saturation records.
 */
@Dao
interface OxygenSaturationDao {
    @Query(
        """
        SELECT * FROM oxygen_saturation
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
    ): Flow<List<OxygenSaturation>>

    @Query("SELECT * FROM oxygen_saturation ORDER BY timestamp DESC LIMIT 1")
    fun getLatest(): Flow<OxygenSaturation?>

    @Query(
        """
        SELECT AVG(percentage) FROM oxygen_saturation
        WHERE timestamp BETWEEN :startTime AND :endTime
        """,
    )
    fun getAveragePercentage(
        startTime: Instant,
        endTime: Instant,
    ): Flow<Double?>

    @Query("SELECT MIN(timestamp) FROM oxygen_saturation")
    suspend fun getOldestTimestamp(): Instant?

    @Query("SELECT MAX(timestamp) FROM oxygen_saturation")
    suspend fun getLatestTimestamp(): Instant?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(records: List<OxygenSaturation>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: OxygenSaturation)

    @Query("SELECT * FROM oxygen_saturation WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun getByHealthConnectIds(healthConnectIds: List<String>): List<OxygenSaturation>

    @Query("DELETE FROM oxygen_saturation WHERE health_connect_id IN (:healthConnectIds)")
    suspend fun deleteByHealthConnectIds(healthConnectIds: List<String>)

    @Query("DELETE FROM oxygen_saturation")
    suspend fun deleteAll()

    @Query(
        """
        SELECT * FROM oxygen_saturation
        WHERE timestamp BETWEEN :startTime AND :endTime
        ORDER BY timestamp ASC
        """,
    )
    suspend fun getRecordsForAggregation(
        startTime: Instant,
        endTime: Instant,
    ): List<OxygenSaturation>
}

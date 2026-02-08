package com.heliolan.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.heliolan.data.entity.SyncCursor

/**
 * Data Access Object for sync cursors.
 * Tracks last sync timestamp per record type for incremental sync.
 */
@Dao
interface SyncCursorDao {
    /**
     * Get sync cursor for a specific record type.
     */
    @Query("SELECT * FROM sync_cursors WHERE record_type = :recordType")
    suspend fun getCursor(recordType: String): SyncCursor?

    /**
     * Get all sync cursors.
     */
    @Query("SELECT * FROM sync_cursors")
    suspend fun getAllCursors(): List<SyncCursor>

    /**
     * Update or insert sync cursor.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursor)

    /**
     * Delete cursor for a record type (forces full re-sync).
     */
    @Query("DELETE FROM sync_cursors WHERE record_type = :recordType")
    suspend fun deleteCursor(recordType: String)

    /**
     * Delete all cursors (forces full re-sync of all types).
     */
    @Query("DELETE FROM sync_cursors")
    suspend fun deleteAll()
}

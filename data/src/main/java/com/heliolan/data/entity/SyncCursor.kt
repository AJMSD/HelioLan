package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Tracks sync progress for each record type.
 * Used by SyncEngine to implement incremental sync.
 */
@Entity(tableName = "sync_cursors")
data class SyncCursor(
    /** Record type: "heart_rate", "sleep", "steps", "resting_hr" */
    @PrimaryKey
    @ColumnInfo(name = "record_type")
    val recordType: String,
    /** Last successful sync timestamp (exclusive - next sync starts here) */
    @ColumnInfo(name = "last_sync_time")
    val lastSyncTime: Instant,
    /** Health Connect change token for incremental sync (optional) */
    @ColumnInfo(name = "change_token")
    val changeToken: String?,
)

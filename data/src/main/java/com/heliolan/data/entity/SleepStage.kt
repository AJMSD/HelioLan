package com.heliolan.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Individual sleep stage within a SleepSession.
 * Foreign key relationship ensures stages are deleted when parent session is deleted.
 */
@Entity(
    tableName = "sleep_stages",
    foreignKeys = [
        ForeignKey(
            entity = SleepSession::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id"], name = "idx_sleep_stage_session"),
        Index(value = ["start_time"], name = "idx_sleep_stage_start_time"),
    ],
)
data class SleepStage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** Parent sleep session ID */
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    /** Health Connect stage unique identifier */
    @ColumnInfo(name = "health_connect_id")
    val healthConnectId: String,
    /** Stage start time in UTC */
    @ColumnInfo(name = "start_time")
    val startTime: Instant,
    /** Stage end time in UTC */
    @ColumnInfo(name = "end_time")
    val endTime: Instant,
    /** Stage type: "awake", "light", "deep", "rem", "unknown" */
    @ColumnInfo(name = "stage_type")
    val stageType: String,
    /** When this record was synced from Health Connect */
    @ColumnInfo(name = "synced_at")
    val syncedAt: Instant,
)

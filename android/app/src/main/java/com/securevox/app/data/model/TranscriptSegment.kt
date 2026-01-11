package com.securevox.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A segment of transcribed text with timing information.
 */
@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = Recording::class,
            parentColumns = ["id"],
            childColumns = ["recordingId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordingId")]
)
data class TranscriptSegment(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val recordingId: String,
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val segmentIndex: Int
)

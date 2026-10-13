package com.securevox.app.data.model

import androidx.room.ColumnInfo
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
    val segmentIndex: Int,
    /** 0-based speaker number by first appearance, or -1 when speakers were not labelled. */
    @ColumnInfo(defaultValue = "-1")
    val speaker: Int = -1,
    /**
     * The segment's timed words as compact JSON (see [WordCodec]), or "" for recordings made
     * before word timing existed and for segments whose words could not be trusted.
     */
    @ColumnInfo(defaultValue = "''")
    val words: String = "",
    /**
     * True for a continuation piece made when a Whisper segment was split where the speaker changed
     * (see assignSpeakersByWord); the first piece keeps the original id and stays false. A relabel
     * glues every true row back onto the row before it to recover Whisper's own segments, so this
     * is the only record of where the original boundaries were.
     */
    @ColumnInfo(defaultValue = "0")
    val speakerSplit: Boolean = false
)

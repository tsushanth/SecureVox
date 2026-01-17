package com.securevox.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Recording entity stored in Room database.
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val audioFilePath: String,
    val duration: Long = 0L, // in milliseconds
    val fileSize: Long = 0L, // in bytes
    val createdAt: Long = System.currentTimeMillis(),
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.PENDING,
    val transcriptionProgress: Int = 0,
    val language: String = "en",
    val isFavorite: Boolean = false
)

/**
 * Transcription status for a recording.
 */
enum class TranscriptionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

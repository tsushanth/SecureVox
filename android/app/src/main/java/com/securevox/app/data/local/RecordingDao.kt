package com.securevox.app.data.local

import androidx.room.*
import com.securevox.app.data.SegmentText
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordingDao {

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings")
    suspend fun getAllOnce(): List<Recording>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: String): Recording?

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun getRecordingByIdFlow(id: String): Flow<Recording?>

    @Query("SELECT * FROM recordings WHERE transcriptionStatus = :status")
    suspend fun getRecordingsByStatus(status: TranscriptionStatus): List<Recording>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecording(recording: Recording)

    @Update
    suspend fun updateRecording(recording: Recording)

    @Delete
    suspend fun deleteRecording(recording: Recording)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteRecordingById(id: String)

    @Query("UPDATE recordings SET transcriptionStatus = :status, transcriptionProgress = :progress WHERE id = :id")
    suspend fun updateTranscriptionStatus(id: String, status: TranscriptionStatus, progress: Int)

    @Query("SELECT SUM(fileSize) FROM recordings")
    suspend fun getTotalStorageUsed(): Long?

    @Query("SELECT SUM(duration) FROM recordings")
    suspend fun getTotalDuration(): Long?

    @Query("SELECT COUNT(*) FROM recordings")
    suspend fun getRecordingCount(): Int

    @Query("UPDATE recordings SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    /** Every segment's text, for matching queries SQLite's LIKE cannot fold case for (non-ASCII). */
    @Query("SELECT recordingId, text FROM transcript_segments")
    suspend fun allSegmentTexts(): List<SegmentText>

    /**
     * Ids of recordings with a transcript segment containing [pattern] (a LIKE pattern from
     * RecordingSearch.likePattern; LIKE is case-insensitive for ASCII only, so the caller uses it only for
     * ASCII queries). Reads no transcript text into memory.
     */
    @Query("SELECT DISTINCT recordingId FROM transcript_segments WHERE text LIKE :pattern ESCAPE '\\'")
    suspend fun recordingIdsWithTextLike(pattern: String): List<String>

    @Query("UPDATE recordings SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavoriteStatus(id: String, isFavorite: Boolean)

    @Query("SELECT * FROM recordings WHERE isFavorite = 1 ORDER BY createdAt DESC")
    fun getFavoriteRecordings(): Flow<List<Recording>>
}

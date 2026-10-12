package com.securevox.app.data.local

import androidx.room.*
import com.securevox.app.data.model.TranscriptSegment
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptSegmentDao {

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY segmentIndex ASC")
    fun getSegmentsForRecording(recordingId: String): Flow<List<TranscriptSegment>>

    @Query("SELECT * FROM transcript_segments WHERE recordingId = :recordingId ORDER BY segmentIndex ASC")
    suspend fun getSegmentsForRecordingSync(recordingId: String): List<TranscriptSegment>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegment(segment: TranscriptSegment)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptSegment>)

    @Delete
    suspend fun deleteSegment(segment: TranscriptSegment)

    @Query("DELETE FROM transcript_segments WHERE recordingId = :recordingId")
    suspend fun deleteSegmentsForRecording(recordingId: String)

    /**
     * Replaces a recording's whole segment list in ONE transaction, so a crash leaves either the old
     * rows or the new ones, never a mix or duplicates. Used by the speaker relabel.
     */
    @Transaction
    suspend fun replaceSegments(recordingId: String, segments: List<TranscriptSegment>) {
        deleteSegmentsForRecording(recordingId)
        insertSegments(segments)
    }

    @Query("SELECT GROUP_CONCAT(text, ' ') FROM transcript_segments WHERE recordingId = :recordingId ORDER BY segmentIndex ASC")
    suspend fun getFullTranscriptText(recordingId: String): String?
}

package com.securevox.app.data.repository

import com.securevox.app.data.RecordingSearch
import com.securevox.app.data.local.RecordingDao
import com.securevox.app.data.local.TranscriptSegmentDao
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.model.TranscriptionStatus
import kotlinx.coroutines.flow.Flow
import java.io.File

class RecordingRepository(
    private val recordingDao: RecordingDao,
    private val segmentDao: TranscriptSegmentDao
) {

    // Recordings
    fun getAllRecordings(): Flow<List<Recording>> = recordingDao.getAllRecordings()

    suspend fun getRecordingById(id: String): Recording? = recordingDao.getRecordingById(id)

    suspend fun getAllRecordingsOnce(): List<Recording> = recordingDao.getAllOnce()

    fun getRecordingByIdFlow(id: String): Flow<Recording?> = recordingDao.getRecordingByIdFlow(id)

    suspend fun saveRecording(recording: Recording) = recordingDao.insertRecording(recording)

    suspend fun updateRecording(recording: Recording) = recordingDao.updateRecording(recording)

    suspend fun deleteRecording(recording: Recording) {
        // Delete audio file
        val file = File(recording.audioFilePath)
        if (file.exists()) {
            file.delete()
        }
        // Delete from database (segments cascade delete)
        recordingDao.deleteRecording(recording)
    }

    suspend fun updateTranscriptionStatus(
        recordingId: String,
        status: TranscriptionStatus,
        progress: Int = 0
    ) = recordingDao.updateTranscriptionStatus(recordingId, status, progress)

    /** Removes a row only; the caller has already dealt with the audio file. Segments cascade. */
    suspend fun deleteRecordingById(id: String) = recordingDao.deleteRecordingById(id)

    suspend fun renameRecording(recordingId: String, title: String) =
        recordingDao.updateTitle(recordingId, title)

    /** Ids of recordings whose transcript contains [query], ignoring case. Cheap in the common ASCII case. */
    suspend fun recordingIdsWithTranscriptMatching(query: String): Set<String> =
        if (RecordingSearch.canUseSqlLike(query)) {
            recordingDao.recordingIdsWithTextLike(RecordingSearch.likePattern(query)).toSet()
        } else {
            RecordingSearch.matchingIds(recordingDao.allSegmentTexts(), query)
        }

    suspend fun toggleFavorite(recordingId: String, isFavorite: Boolean) =
        recordingDao.updateFavoriteStatus(recordingId, isFavorite)

    fun getFavoriteRecordings(): Flow<List<Recording>> = recordingDao.getFavoriteRecordings()

    // Segments
    fun getSegmentsForRecording(recordingId: String): Flow<List<TranscriptSegment>> =
        segmentDao.getSegmentsForRecording(recordingId)

    suspend fun getSegmentsForRecordingSync(recordingId: String): List<TranscriptSegment> =
        segmentDao.getSegmentsForRecordingSync(recordingId)

    suspend fun saveSegments(segments: List<TranscriptSegment>) =
        segmentDao.insertSegments(segments)

    suspend fun replaceSegments(recordingId: String, segments: List<TranscriptSegment>) =
        segmentDao.replaceSegments(recordingId, segments)

    suspend fun deleteSegmentsForRecording(recordingId: String) =
        segmentDao.deleteSegmentsForRecording(recordingId)

    suspend fun getFullTranscriptText(recordingId: String): String? =
        segmentDao.getFullTranscriptText(recordingId)

    // Storage stats
    suspend fun getTotalStorageUsed(): Long = recordingDao.getTotalStorageUsed() ?: 0L

    suspend fun getTotalDuration(): Long = recordingDao.getTotalDuration() ?: 0L

    suspend fun getRecordingCount(): Int = recordingDao.getRecordingCount()
}

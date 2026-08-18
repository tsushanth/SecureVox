package com.securevox.app.presentation.detail

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.SecureVoxApp
import com.securevox.app.service.AudioPlayerService
import com.securevox.app.service.ExportFormat
import com.securevox.app.service.ExportService
import com.securevox.app.service.PlaybackSpeed
import com.securevox.app.service.TranscriptionWorker
import com.securevox.app.whisper.WhisperModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class RecordingDetailViewModel(
    application: Application,
    private val recordingId: String
) : AndroidViewModel(application) {

    private val database = SecureVoxDatabase.getInstance(application)
    private val repository = RecordingRepository(
        database.recordingDao(),
        database.transcriptSegmentDao()
    )
    private val audioPlayer = AudioPlayerService.getInstance(application)
    private val exportService = ExportService(application)

    val recording: StateFlow<Recording?> = repository.getRecordingByIdFlow(recordingId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val segments: StateFlow<List<TranscriptSegment>> = repository.getSegmentsForRecording(recordingId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Live transcription progress (0-100) from WorkManager, null if not actively running
    val transcriptionProgress: StateFlow<Int?> =
        WorkManager.getInstance(application)
            .getWorkInfosByTagFlow("${TranscriptionWorker.TAG_RECORDING_PREFIX}$recordingId")
            .map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?.progress?.getInt(TranscriptionWorker.KEY_PROGRESS, 0)
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val currentPosition: StateFlow<Long> = audioPlayer.currentPosition
    val duration: StateFlow<Long> = audioPlayer.duration
    val playbackSpeed: StateFlow<PlaybackSpeed> = audioPlayer.playbackSpeed

    val activeSegment: StateFlow<TranscriptSegment?> = combine(
        segments,
        currentPosition
    ) { segs, position ->
        segs.find { position >= it.startTimeMs && position < it.endTimeMs }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch {
            recording.filterNotNull().first().let { rec ->
                audioPlayer.load(rec.audioFilePath)
            }
        }
    }

    fun togglePlayPause() {
        audioPlayer.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun seekToSegment(segment: TranscriptSegment) {
        audioPlayer.seekTo(segment.startTimeMs)
        if (!isPlaying.value) {
            audioPlayer.play()
        }
    }

    fun skipForward() {
        audioPlayer.skipForward()
    }

    fun skipBackward() {
        audioPlayer.skipBackward()
    }

    fun setPlaybackSpeed(speed: PlaybackSpeed) {
        audioPlayer.setPlaybackSpeed(speed)
    }

    fun cyclePlaybackSpeed() {
        audioPlayer.cyclePlaybackSpeed()
    }

    /** Returns all WhisperModels that are currently downloaded on device. */
    fun getDownloadedModels(): List<WhisperModel> {
        val modelManager = SecureVoxApp.instance.modelManager
        return WhisperModel.entries.filter { modelManager.isModelDownloaded(it) }
    }

    /**
     * Re-run transcription with a specific model.
     * Clears existing segments so the UI reflects fresh results.
     */
    fun retryTranscription(model: WhisperModel) {
        viewModelScope.launch {
            val rec = recording.value ?: return@launch
            repository.updateTranscriptionStatus(rec.id, com.securevox.app.data.model.TranscriptionStatus.PENDING, 0)
            repository.deleteSegmentsForRecording(rec.id)
            val workRequest = TranscriptionWorker.createWorkRequest(
                recordingId = rec.id,
                modelName = model.fileName,
                language = rec.language
            )
            WorkManager.getInstance(getApplication()).enqueue(workRequest)
        }
    }

    fun deleteRecording() {
        viewModelScope.launch {
            recording.value?.let { rec ->
                repository.deleteRecording(rec)
            }
        }
    }

    fun getFullTranscript(): String {
        return segments.value.joinToString(" ") { it.text }
    }

    fun exportTranscript(format: ExportFormat): Intent? {
        val rec = recording.value ?: return null
        val segs = segments.value
        if (segs.isEmpty()) return null
        return exportService.exportToFile(segs, format, rec.title)
    }

    override fun onCleared() {
        super.onCleared()
        // Don't release the audio player - it's a singleton that preserves playback
        // across navigation. The player will be released when a different recording
        // is loaded or when the app is destroyed.
    }
}

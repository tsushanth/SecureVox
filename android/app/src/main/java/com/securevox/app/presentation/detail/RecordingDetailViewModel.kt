package com.securevox.app.presentation.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.service.AudioPlayerService
import com.securevox.app.service.ExportFormat
import com.securevox.app.service.ExportService
import com.securevox.app.service.PlaybackSpeed
import android.content.Intent
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

package com.securevox.app.presentation.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.service.AudioPlayerService
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
    private val audioPlayer = AudioPlayerService(application)

    val recording: StateFlow<Recording?> = repository.getRecordingByIdFlow(recordingId)
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val segments: StateFlow<List<TranscriptSegment>> = repository.getSegmentsForRecording(recordingId)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val currentPosition: StateFlow<Long> = audioPlayer.currentPosition
    val duration: StateFlow<Long> = audioPlayer.duration

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

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}

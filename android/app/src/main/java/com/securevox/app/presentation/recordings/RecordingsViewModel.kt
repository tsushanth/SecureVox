package com.securevox.app.presentation.recordings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.service.AudioRecorderService
import com.securevox.app.service.TranscriptionWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = SecureVoxDatabase.getInstance(application)
    private val repository = RecordingRepository(
        database.recordingDao(),
        database.transcriptSegmentDao()
    )
    private val audioRecorder = AudioRecorderService(application)
    private val workManager = WorkManager.getInstance(application)

    val recordings: StateFlow<List<Recording>> = repository.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val isRecording: StateFlow<Boolean> = audioRecorder.isRecording
    val audioLevel: StateFlow<Float> = audioRecorder.audioLevel
    val recordingDuration: StateFlow<Long> = audioRecorder.recordingDuration

    private val _currentRecordingId = MutableStateFlow<String?>(null)

    private val recordingsDir: File by lazy {
        File(getApplication<Application>().filesDir, "recordings").also { it.mkdirs() }
    }

    fun startRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "recording_$timestamp.wav"
        val filePath = File(recordingsDir, fileName).absolutePath

        if (audioRecorder.startRecording(filePath)) {
            val recording = Recording(
                title = "Recording ${SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())}",
                audioFilePath = filePath,
                transcriptionStatus = TranscriptionStatus.PENDING
            )
            _currentRecordingId.value = recording.id

            viewModelScope.launch {
                repository.saveRecording(recording)
            }
        }
    }

    fun stopRecording() {
        val filePath = audioRecorder.stopRecording() ?: return
        val recordingId = _currentRecordingId.value ?: return

        viewModelScope.launch {
            val recording = repository.getRecordingById(recordingId)
            if (recording != null) {
                val file = File(filePath)
                val updatedRecording = recording.copy(
                    duration = audioRecorder.recordingDuration.value,
                    fileSize = file.length()
                )
                repository.updateRecording(updatedRecording)

                // Start transcription
                startTranscription(recordingId)
            }
        }

        _currentRecordingId.value = null
    }

    fun startTranscription(recordingId: String, language: String = "en") {
        val workRequest = TranscriptionWorker.createWorkRequest(
            recordingId = recordingId,
            language = language
        )
        workManager.enqueue(workRequest)
    }

    fun deleteRecording(recording: Recording) {
        viewModelScope.launch {
            repository.deleteRecording(recording)
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
    }
}

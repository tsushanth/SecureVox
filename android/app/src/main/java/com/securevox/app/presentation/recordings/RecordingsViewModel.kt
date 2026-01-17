package com.securevox.app.presentation.recordings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.service.AudioRecorderService
import com.securevox.app.service.ImportResult
import com.securevox.app.service.MediaImportService
import com.securevox.app.service.TranscriptionWorker
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Filter options for recordings list
 */
enum class RecordingsFilter {
    ALL,
    FAVORITES
}

class RecordingsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = SecureVoxDatabase.getInstance(application)
    private val repository = RecordingRepository(
        database.recordingDao(),
        database.transcriptSegmentDao()
    )
    private val audioRecorder = AudioRecorderService(application)
    private val mediaImportService = MediaImportService.getInstance(application)
    private val workManager = WorkManager.getInstance(application)

    // Import state
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    // Search and filter state
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _filter = MutableStateFlow(RecordingsFilter.ALL)
    val filter: StateFlow<RecordingsFilter> = _filter.asStateFlow()

    private val allRecordings: StateFlow<List<Recording>> = repository.getAllRecordings()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recordings: StateFlow<List<Recording>> = combine(
        allRecordings,
        _searchQuery,
        _filter
    ) { recordings, query, filter ->
        var filtered = recordings

        // Apply favorites filter
        if (filter == RecordingsFilter.FAVORITES) {
            filtered = filtered.filter { it.isFavorite }
        }

        // Apply search filter
        if (query.isNotBlank()) {
            filtered = filtered.filter {
                it.title.contains(query, ignoreCase = true)
            }
        }

        filtered
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

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

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: RecordingsFilter) {
        _filter.value = filter
    }

    fun toggleFavorite(recording: Recording) {
        viewModelScope.launch {
            repository.toggleFavorite(recording.id, !recording.isFavorite)
        }
    }

    /**
     * Import a media file from URI
     */
    fun importMedia(uri: Uri) {
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null

            when (val result = mediaImportService.importMedia(uri)) {
                is ImportResult.Success -> {
                    // Create recording entry
                    val title = result.originalFileName
                        .substringBeforeLast(".")
                        .replace("_", " ")
                        .replaceFirstChar { it.uppercase() }

                    val recording = Recording(
                        title = title,
                        audioFilePath = result.audioFilePath,
                        duration = result.duration,
                        fileSize = result.fileSize,
                        transcriptionStatus = TranscriptionStatus.PENDING
                    )

                    repository.saveRecording(recording)

                    // Start transcription
                    startTranscription(recording.id)
                }
                is ImportResult.Error -> {
                    _importError.value = result.message
                }
            }

            _isImporting.value = false
        }
    }

    /**
     * Clear import error
     */
    fun clearImportError() {
        _importError.value = null
    }

    override fun onCleared() {
        super.onCleared()
        audioRecorder.release()
    }
}

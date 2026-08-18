package com.securevox.app.presentation.recordings

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.presentation.settings.dataStore
import com.securevox.app.service.AudioRecorderService
import com.securevox.app.service.ImportResult
import com.securevox.app.service.MediaImportService
import com.securevox.app.service.TranscriptionWorker
import com.securevox.app.whisper.WhisperLanguage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.stringPreferencesKey
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
    private val dataStore = application.dataStore
    private val keySelectedLanguage = stringPreferencesKey("selected_language")
    private val keySelectedModel = stringPreferencesKey("selected_model")

    private val prefs = application.getSharedPreferences("securevox_prefs", Context.MODE_PRIVATE)

    private val _triggerReview = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val triggerReview: SharedFlow<Unit> = _triggerReview.asSharedFlow()

    private suspend fun getSelectedLanguageCode(): String {
        return dataStore.data.first()[keySelectedLanguage]
            ?.let { WhisperLanguage.fromCode(it).code }
            ?: WhisperLanguage.fromDeviceLocale().code
    }

    private suspend fun getSelectedModelName(): String {
        return dataStore.data.first()[keySelectedModel] ?: "ggml-tiny.bin"
    }

    // Import state
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    // Maps recordingId -> progress (0-100), only for actively running workers
    val transcriptionProgress: StateFlow<Map<String, Int>> =
        workManager.getWorkInfosByTagFlow(TranscriptionWorker.TAG_TRANSCRIPTION)
            .map { workInfos: List<WorkInfo> ->
                val result = mutableMapOf<String, Int>()
                for (info in workInfos) {
                    if (info.state == WorkInfo.State.RUNNING) {
                        val recordingId = info.tags
                            .firstOrNull { it.startsWith(TranscriptionWorker.TAG_RECORDING_PREFIX) }
                            ?.removePrefix(TranscriptionWorker.TAG_RECORDING_PREFIX)
                            ?: continue
                        val progress = info.progress.getInt(TranscriptionWorker.KEY_PROGRESS, 0)
                        result[recordingId] = progress
                    }
                }
                result as Map<String, Int>
            }
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

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

    init {
        viewModelScope.launch {
            var lastCompletedCount = -1
            allRecordings.collect { recordings ->
                val completedCount = recordings.count { it.transcriptionStatus == TranscriptionStatus.COMPLETED }
                if (lastCompletedCount >= 0 && completedCount > lastCompletedCount) {
                    // A new transcription just completed — check if we should prompt for review
                    maybeRequestReview(completedCount)
                }
                lastCompletedCount = completedCount
            }
        }
    }

    private fun maybeRequestReview(completedCount: Int) {
        val hasPrompted = prefs.getBoolean("review_prompted", false)
        if (!hasPrompted && completedCount >= 3) {
            prefs.edit().putBoolean("review_prompted", true).apply()
            _triggerReview.tryEmit(Unit)
        }
    }

    val isRecording: StateFlow<Boolean> = audioRecorder.isRecording
    val isPaused: StateFlow<Boolean> = audioRecorder.isPaused
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

    fun pauseRecording() {
        audioRecorder.pauseRecording()
    }

    fun resumeRecording() {
        audioRecorder.resumeRecording()
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

                // Start transcription with the user's selected language and model
                startTranscription(recordingId, getSelectedLanguageCode(), getSelectedModelName())
            }
        }

        _currentRecordingId.value = null
    }

    fun startTranscription(recordingId: String, language: String = WhisperLanguage.fromDeviceLocale().code, modelName: String = "ggml-tiny.bin") {
        val workRequest = TranscriptionWorker.createWorkRequest(
            recordingId = recordingId,
            modelName = modelName,
            language = language
        )
        workManager.enqueue(workRequest)
    }

    fun retryTranscription(recording: Recording) {
        viewModelScope.launch {
            repository.updateTranscriptionStatus(recording.id, TranscriptionStatus.PENDING, 0)
            startTranscription(recording.id, getSelectedLanguageCode(), getSelectedModelName())
        }
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
            _importProgress.value = 0f
            _importError.value = null

            when (val result = mediaImportService.importMedia(uri) { progress ->
                _importProgress.value = progress
            }) {
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

                    // Start transcription with the user's selected language and model
                    startTranscription(recording.id, getSelectedLanguageCode(), getSelectedModelName())
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

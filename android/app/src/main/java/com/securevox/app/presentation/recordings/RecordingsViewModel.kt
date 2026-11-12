package com.securevox.app.presentation.recordings

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import com.securevox.app.util.FailureReporter
import com.securevox.app.util.MemoryPolicy
import com.securevox.app.util.ReportConsent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.securevox.app.data.RecordingRecovery
import com.securevox.app.data.RecordingSearch
import com.securevox.app.service.TranscriptionRun
import com.securevox.app.service.TranscriptionSpeedStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import com.securevox.app.data.local.SecureVoxDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptionStatus
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.presentation.settings.dataStore
import com.securevox.app.service.AudioRecorderService
import com.securevox.app.service.ImportResult
import com.securevox.app.service.MediaImportService
import com.securevox.app.service.TranscriptionWorker
import com.securevox.app.whisper.ModelSelection
import com.securevox.app.whisper.WhisperLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import com.securevox.app.SecureVoxApp
import com.securevox.app.whisper.LiveCaption
import com.securevox.app.whisper.LiveTranscriber
import com.securevox.app.whisper.WhisperModel
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.securevox.app.whisper.DownloadState
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

/** Recording ids whose transcript matched [query]. */
private data class TranscriptHits(val query: String, val ids: Set<String>)

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

    /**
     * The saved model if it is installed, otherwise the installed model that suits the language best, so a phone
     * that only has Small (chosen at setup) never queues work for a Tiny it does not have.
     */
    private suspend fun getSelectedModelName(): String {
        val saved = dataStore.data.first()[keySelectedModel]?.let { WhisperModel.fromFileName(it) }
            ?: WhisperModel.DEFAULT
        val installed = WhisperModel.entries.filter { modelManager.isModelDownloaded(it) }.toSet()
        val language = WhisperLanguage.fromCode(getSelectedLanguageCode())
        return (ModelSelection.effective(saved, installed, language) ?: saved).fileName
    }

    // Import state
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    // Maps recordingId -> progress (0-100) and when the run began, only for actively running workers
    val transcriptionProgress: StateFlow<Map<String, TranscriptionRun>> =
        workManager.getWorkInfosByTagFlow(TranscriptionWorker.TAG_TRANSCRIPTION)
            .map { workInfos: List<WorkInfo> ->
                val result = mutableMapOf<String, TranscriptionRun>()
                for (info in workInfos) {
                    if (info.state == WorkInfo.State.RUNNING) {
                        val recordingId = info.tags
                            .firstOrNull { it.startsWith(TranscriptionWorker.TAG_RECORDING_PREFIX) }
                            ?.removePrefix(TranscriptionWorker.TAG_RECORDING_PREFIX)
                            ?: continue
                        val progress = info.progress.getInt(TranscriptionWorker.KEY_PROGRESS, 0)
                        val startedAt = info.progress.getLong(TranscriptionWorker.KEY_STARTED_AT, 0L).takeIf { it > 0L }
                        val model = info.progress.getString(TranscriptionWorker.KEY_MODEL)
                        result[recordingId] = TranscriptionRun(
                            progress = progress,
                            startedAtMs = startedAt,
                            speed = TranscriptionSpeedStore.get(application, model)
                        )
                    }
                }
                result as Map<String, TranscriptionRun>
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

    /**
     * Ids of recordings whose transcript contains the query. Empty while the query is blank (no work at
     * all then), refreshed when the query settles for a moment or the list of recordings changes.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val transcriptMatches: StateFlow<TranscriptHits> = _searchQuery
        .map { RecordingSearch.normalizeQuery(it) }
        .distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else 200L }
        .combine(allRecordings) { query, _ -> query }
        .flatMapLatest { query ->
            if (query.isEmpty()) flowOf(TranscriptHits("", emptySet()))
            else flow { emit(TranscriptHits(query, repository.recordingIdsWithTranscriptMatching(query))) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, TranscriptHits("", emptySet()))

    val recordings: StateFlow<List<Recording>> = combine(
        allRecordings,
        _searchQuery,
        _filter,
        transcriptMatches
    ) { recordings, query, filter, hits ->
        var filtered = recordings

        // Apply favorites filter
        if (filter == RecordingsFilter.FAVORITES) {
            filtered = filtered.filter { it.isFavorite }
        }

        // Apply search filter
        if (query.isNotBlank()) {
            filtered = filtered.filter {
                // Hits computed for an earlier query (typing is debounced) are ignored until they catch up.
                RecordingSearch.titleMatches(it.title, query) ||
                    (hits.query == RecordingSearch.normalizeQuery(query) && it.id in hits.ids)
            }
        }

        filtered
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch {
            var lastCompletedCount = -1
            var lastFailedCount = -1
            allRecordings.collect { recordings ->
                val completedCount = recordings.count { it.transcriptionStatus == TranscriptionStatus.COMPLETED }
                val failedCount = recordings.count { it.transcriptionStatus == TranscriptionStatus.FAILED }
                if (lastCompletedCount >= 0 && completedCount > lastCompletedCount) {
                    // Magic moment: a transcription just finished. RatingKit decides whether to ask (several moments
                    // and a few days of use first); the screen calls it once the finished transcript is on screen.
                    _triggerReview.tryEmit(Unit)
                }
                if (lastFailedCount >= 0 && failedCount > lastFailedCount) {
                    com.kreativekoala.ratingkit.RatingKit.reportError() // stay quiet for a while after a failure
                }
                lastCompletedCount = completedCount
                lastFailedCount = failedCount
            }
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

    // Offer Base (more accurate than the Tiny model installed at setup) once, to people still on Tiny.
    private val keyUpgradeDismissed = booleanPreferencesKey("base_upgrade_dismissed")
    private val modelManager get() = SecureVoxApp.instance.modelManager

    private val _upgradeInstalled = MutableStateFlow(modelManager.isModelDownloaded(WhisperModel.BASE))
    val downloadState: StateFlow<DownloadState> = modelManager.downloadState

    /** False until some model is installed (setup skipped, or still downloading): recording is held back. */
    val hasModel: StateFlow<Boolean> = modelManager.availableModels
        .map { models -> models.any { it.isDownloaded } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, modelManager.isAnyModelInstalled())

    val showModelUpgrade: StateFlow<Boolean> = combine(
        dataStore.data.map { it[keyUpgradeDismissed] ?: false },
        dataStore.data.map { it[keySelectedModel] ?: WhisperModel.TINY.fileName },
        _upgradeInstalled
    ) { dismissed, selected, baseInstalled ->
        !dismissed && !baseInstalled && selected == WhisperModel.TINY.fileName
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun dismissModelUpgrade() {
        viewModelScope.launch { dataStore.edit { it[keyUpgradeDismissed] = true } }
    }

    // One-time, non-blocking question about anonymous crash reports (off until the user says yes). It appears
    // only after the first finished transcription, and an unanswered card never counts as consent.
    private val _reportsAnswered = MutableStateFlow(FailureReporter.hasAnswered(application))
    val showReportsPrompt: StateFlow<Boolean> = combine(allRecordings, _reportsAnswered) { recordings, answered ->
        ReportConsent.shouldPrompt(answered, recordings.count { it.transcriptionStatus == TranscriptionStatus.COMPLETED })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun answerReportsPrompt(turnOn: Boolean) {
        FailureReporter.setEnabled(getApplication<Application>(), turnOn)
        _reportsAnswered.value = true
    }

    /** Downloads Base and, once it verifies, makes it the model used for new transcriptions. */
    fun upgradeToBase() {
        viewModelScope.launch {
            val result = modelManager.downloadModel(WhisperModel.BASE)
            if (result.isSuccess && modelManager.isModelDownloaded(WhisperModel.BASE)) {
                dataStore.edit { it[keySelectedModel] = WhisperModel.BASE.fileName }
                _upgradeInstalled.value = true
            }
        }
    }

    private var liveTranscriber: LiveTranscriber? = null
    private var liveCaptionJob: Job? = null
    private val _liveCaption = MutableStateFlow(LiveCaption())
    val liveCaption: StateFlow<LiveCaption> = _liveCaption.asStateFlow()

    /**
     * Starts rolling captions for the recording in progress. Uses Base when it is
     * downloaded and the phone has memory to spare, otherwise Tiny, or nothing when memory is
     * low; Small is too slow to keep up with speech on most phones.
     */
    private fun startLiveCaptions() {
        viewModelScope.launch {
            val enabled = dataStore.data.first()[LiveTranscriber.KEY_ENABLED] ?: true
            if (!enabled || !audioRecorder.isRecording.value) return@launch
            val models = SecureVoxApp.instance.modelManager
            // Live captions load a second Whisper context, so on a tight phone use Tiny only or none.
            val snapshot = MemoryPolicy.read(getApplication())
            val largest = MemoryPolicy.liveCaptionModel(snapshot)
            if (largest == null) {
                Log.i("RecordingsVM", "Live captions off for this recording: low memory " +
                    "(avail=${snapshot.availBytes / 1_000_000} MB, lowMemory=${snapshot.lowMemory})")
                return@launch
            }
            val model = listOf(WhisperModel.BASE, WhisperModel.TINY)
                .filter { it.sizeBytes <= largest.sizeBytes }
                .firstOrNull { models.isModelDownloaded(it) } ?: return@launch

            val transcriber = LiveTranscriber(
                context = getApplication(),
                modelPath = models.getModelPath(model),
                language = getSelectedLanguageCode(),
                audio = audioRecorder.liveAudio,
                scope = viewModelScope
            )
            liveTranscriber = transcriber
            liveCaptionJob = launch { transcriber.caption.collect { _liveCaption.value = it } }
            transcriber.start()
        }
    }

    private fun stopLiveCaptions() {
        val transcriber = liveTranscriber ?: return
        liveTranscriber = null
        liveCaptionJob?.cancel()
        liveCaptionJob = null
        _liveCaption.value = LiveCaption()
        // Freeing the model has to wait for the pass in flight, so keep it off the main thread.
        viewModelScope.launch { transcriber.stop() }
    }

    fun startRecording() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "recording_$timestamp.wav"
        val filePath = File(recordingsDir, fileName).absolutePath

        if (audioRecorder.startRecording(filePath)) {
            val recording = Recording(
                title = defaultTitle(),
                audioFilePath = filePath,
                transcriptionStatus = TranscriptionStatus.PENDING
            )
            _currentRecordingId.value = recording.id

            pendingRowSave = viewModelScope.launch {
                repository.saveRecording(recording)
            }
            startLiveCaptions()
        }
    }

    /** "Recording Nov 9, 3:41 PM", in the phone's language and date style. */
    private fun defaultTitle(): String {
        val locale = Locale.getDefault()
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdjm")
        val label = getApplication<Application>().getString(com.securevox.app.R.string.recording_label)
        return "$label ${SimpleDateFormat(pattern, locale).format(Date())}"
    }

    fun pauseRecording() {
        audioRecorder.pauseRecording()
    }

    fun resumeRecording() {
        audioRecorder.resumeRecording()
    }

    fun stopRecording() {
        stopLiveCaptions()
        val filePath = audioRecorder.stopRecording()
        val recordingId = _currentRecordingId.value ?: return
        _currentRecordingId.value = null

        viewModelScope.launch {
            // The row is saved when recording starts; wait for it so an instant stop cannot leave it behind.
            pendingRowSave?.join()
            when (stopper.onStopped(recordingId, filePath, audioRecorder.recordingDuration.value)) {
                RecordingStopper.Result.KEPT -> _transcriptionQueued.tryEmit(Unit)
                RecordingStopper.Result.DISCARDED -> _nothingRecorded.tryEmit(Unit)
                RecordingStopper.Result.IGNORED -> Unit
            }
        }
    }

    private val stopper = RecordingStopper(
        rows = object : RecordingRows {
            override suspend fun get(id: String) = repository.getRecordingById(id)
            override suspend fun update(recording: Recording) = repository.updateRecording(recording)
            override suspend fun delete(id: String) = repository.deleteRecordingById(id)
        },
        // Start transcription with the user's selected language and model
        startTranscription = { id -> startTranscription(id, getSelectedLanguageCode(), getSelectedModelName()) }
    )

    private var pendingRowSave: Job? = null

    /** Emitted when the user's recording or import was queued for transcription. */
    private val _transcriptionQueued = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val transcriptionQueued: SharedFlow<Unit> = _transcriptionQueued.asSharedFlow()

    /** Emitted when a recording ended with no audio and was discarded. */
    private val _nothingRecorded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val nothingRecorded: SharedFlow<Unit> = _nothingRecorded.asSharedFlow()

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
                    _transcriptionQueued.tryEmit(Unit)
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

    /**
     * Recordings cut short by the app being swiped away or killed are repaired (header, duration, size) and
     * transcribed. Declared after the properties it uses, since it starts working as soon as it is created.
     */
    init {
        viewModelScope.launch(Dispatchers.IO) {
            val candidates = repository.getAllRecordingsOnce().filter { it.id != _currentRecordingId.value }
            val recovered = RecordingRecovery.recover(candidates) { repository.updateRecording(it) }
            if (recovered.isEmpty()) return@launch
            Log.i("RecordingsVM", "Recovered ${recovered.size} interrupted recording(s)")
            if (modelManager.isAnyModelInstalled()) {
                val language = getSelectedLanguageCode()
                val model = getSelectedModelName()
                recovered.forEach { startTranscription(it.id, language, model) }
            } else {
                // No model to transcribe with: show it as failed so the retry button is there once one is installed.
                recovered.forEach { repository.updateTranscriptionStatus(it.id, TranscriptionStatus.FAILED, 0) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLiveCaptions()
        audioRecorder.release()
    }
}

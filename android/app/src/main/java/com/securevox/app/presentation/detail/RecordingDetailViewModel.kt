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
import com.securevox.app.data.model.WordTiming
import com.securevox.app.data.repository.RecordingRepository
import com.securevox.app.SecureVoxApp
import com.securevox.app.service.AudioPlayerService
import com.securevox.app.service.ExportFormat
import com.securevox.app.service.ExportService
import com.securevox.app.service.PlaybackSpeed
import com.securevox.app.service.TranscriptionWorker
import com.securevox.app.whisper.NonSpeech
import com.securevox.app.speakers.ModelDownloader
import com.securevox.app.speakers.SpeakerCount
import com.securevox.app.speakers.SpeakerLabels
import com.securevox.app.speakers.SpeakerModels
import com.securevox.app.service.SpeakerRelabelWorker
import com.securevox.app.util.MemoryPolicy
import com.securevox.app.whisper.WhisperModel
import com.securevox.app.whisper.WhisperLanguage
import androidx.datastore.preferences.core.stringPreferencesKey
import com.securevox.app.presentation.settings.dataStore
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.securevox.app.util.WaveformExtractor
import java.io.File

private const val WORD_SEEK_LEAD_IN_MS = 120L

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

    // ---- Speaker relabel -----------------------------------------------------------------------

    /** State of the user-triggered speaker relabel as WorkManager reports it. */
    sealed class RelabelState {
        object Idle : RelabelState()
        object Running : RelabelState()
        /** The last run asked for this session did not finish; [message] is shown, not red. */
        data class Failed(val message: String) : RelabelState()
    }

    private val relabelRequested = MutableStateFlow(false)
    private val _chosenCount = MutableStateFlow(SpeakerLabels.chosenCount(application, recordingId))
    /** The speaker count the user chose (1..8), or null for automatic. */
    val chosenCount: StateFlow<Int?> = _chosenCount.asStateFlow()

    private val relabelInfos = WorkManager.getInstance(application)
        .getWorkInfosByTagFlow(SpeakerRelabelWorker.uniqueName(recordingId))

    val relabelState: StateFlow<RelabelState> = combine(relabelInfos, relabelRequested) { infos, requested ->
        val running = infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        val failed = infos.firstOrNull { it.state == WorkInfo.State.FAILED }
        when {
            running -> RelabelState.Running
            requested && failed != null && failed.outputData.getString(SpeakerRelabelWorker.KEY_RESULT)
                != SpeakerRelabelWorker.RESULT_MODELS_MISSING ->
                RelabelState.Failed(failed.outputData.getString(SpeakerRelabelWorker.KEY_ERROR) ?: "Unknown error")
            else -> RelabelState.Idle
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, RelabelState.Idle)

    /** Why speaker labels were skipped for this recording (memory or length), or null. Re-read after each relabel. */
    val speakerSkip: StateFlow<MemoryPolicy.SpeakerSkip?> = combine(recording, relabelState) { _, _ ->
        SpeakerLabels.skipNote(getApplication(), recordingId)
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _modelProgress = MutableStateFlow<Float?>(null)
    /** 0..1 while the speaker models download, else null. */
    val modelProgress: StateFlow<Float?> = _modelProgress.asStateFlow()
    private val _modelError = MutableStateFlow<String?>(null)
    val modelError: StateFlow<String?> = _modelError.asStateFlow()

    fun speakerModelsReady(): Boolean =
        ModelDownloader.isReady(SpeakerLabels.modelDir(getApplication()), SpeakerModels.DIARIZATION)

    fun clearModelError() { _modelError.value = null }

    /** Starts the relabel. Returns false (and does nothing) when the speaker models are missing and [count] needs them. */
    fun startRelabel(count: Int?): Boolean {
        if (SpeakerCount.normalize(count) != 1 && !speakerModelsReady()) return false
        relabelRequested.value = true
        _chosenCount.value = SpeakerCount.normalize(count)
        SpeakerRelabelWorker.enqueue(getApplication(), recordingId, count)
        return true
    }

    /** Downloads the speaker models with the shared downloader, then starts the relabel. */
    fun downloadModelsAndRelabel(count: Int?) {
        if (_modelProgress.value != null) return
        viewModelScope.launch {
            _modelError.value = null
            _modelProgress.value = 0f
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ModelDownloader.ensure(SpeakerLabels.modelDir(getApplication()), SpeakerModels.DIARIZATION) {
                        _modelProgress.value = it
                    }
                }
            }
            _modelProgress.value = null
            if (result.isFailure) {
                _modelError.value = result.exceptionOrNull()?.message ?: "Download failed"
            } else {
                startRelabel(count)
            }
        }
    }

    // Older recordings were saved with Whisper's [BLANK_AUDIO]-style markers; hide them here so
    // the transcript, copy and export all agree, whatever version transcribed the recording.
    val segments: StateFlow<List<TranscriptSegment>> = repository.getSegmentsForRecording(recordingId)
        .map { list ->
            list.mapNotNull { segment ->
                NonSpeech.strip(segment.text).takeIf { it.isNotEmpty() }?.let { segment.copy(text = it) }
            }
        }
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

    private val _waveform = MutableStateFlow(FloatArray(0))
    val waveform: StateFlow<FloatArray> = _waveform.asStateFlow()

    init {
        viewModelScope.launch {
            recording.filterNotNull().first().let { rec ->
                audioPlayer.load(rec.audioFilePath)
                _waveform.value = withContext(Dispatchers.IO) {
                    runCatching { WaveformExtractor.extract(File(rec.audioFilePath)) }
                        .getOrDefault(FloatArray(0))
                }
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

    /**
     * Seeks to a tapped word. Word starts are approximate (token timing), so land slightly
     * before it rather than clipping its first sound.
     */
    fun seekToWord(word: WordTiming) {
        audioPlayer.seekTo((word.startMs - WORD_SEEK_LEAD_IN_MS).coerceAtLeast(0L))
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
     *
     * Uses the user's *currently selected* language preference (Settings), not the
     * recording's saved `language` field. Previously this passed `rec.language`, which
     * is stale the moment the user changes their language selection and doesn't
     * override it back to their new choice - and if `rec.language` was ever null,
     * TranscriptionWorker's `?: "en"` default silently forced English regardless of
     * what the user had picked. Confirmed via a real user report: switching from
     * Tiny to Base/Small flipped Japanese transcripts to English with no way back.
     */
    fun retryTranscription(model: WhisperModel) {
        viewModelScope.launch {
            val rec = recording.value ?: return@launch
            repository.updateTranscriptionStatus(rec.id, com.securevox.app.data.model.TranscriptionStatus.PENDING, 0)
            repository.deleteSegmentsForRecording(rec.id)
            val selectedLanguageKey = stringPreferencesKey("selected_language")
            val currentLanguageCode = getApplication<Application>().dataStore.data.first()[selectedLanguageKey]
                ?.let { WhisperLanguage.fromCode(it) }
                ?.takeIf { it != WhisperLanguage.AUTO }
                ?.code
                ?: rec.language
            val workRequest = TranscriptionWorker.createWorkRequest(
                recordingId = rec.id,
                modelName = model.fileName,
                language = currentLanguageCode
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

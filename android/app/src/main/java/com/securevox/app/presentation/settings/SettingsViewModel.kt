package com.securevox.app.presentation.settings

import android.app.Application
import android.content.Context
import com.securevox.app.AppLock
import com.securevox.app.speakers.ModelDownloader
import com.securevox.app.speakers.SpeakerLabels
import com.securevox.app.speakers.SpeakerModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.securevox.app.whisper.DownloadState
import com.securevox.app.whisper.ModelInfo
import com.securevox.app.whisper.LiveTranscriber
import com.securevox.app.SecureVoxApp
import com.securevox.app.whisper.LanguageQuality
import com.securevox.app.whisper.WhisperLanguage
import com.securevox.app.whisper.WhisperModel
import com.securevox.app.util.funnel.Buckets
import com.securevox.app.util.funnel.FunnelModel
import com.securevox.app.util.funnel.LangBucket
import com.securevox.app.util.funnel.SettingWhich
import com.securevox.app.util.funnel.Step
import com.securevox.app.util.funnel.funnel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Theme mode options matching iOS: System, Light, Dark
 */
enum class ThemeMode(val displayName: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark");

    companion object {
        fun fromString(value: String?): ThemeMode {
            return entries.find { it.name == value } ?: SYSTEM
        }
    }
}

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        // Existing keys
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")
        private val KEY_SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
    }

    private val dataStore = application.dataStore
    // The app-wide instance, so a download here is seen by the recordings screen (which hides Record until a
    // model exists) and two managers can never fight over the same partial file.
    val modelManager = SecureVoxApp.instance.modelManager

    val availableModels: StateFlow<List<ModelInfo>> = modelManager.availableModels
    val downloadState: StateFlow<DownloadState> = modelManager.downloadState

    val selectedModel: StateFlow<WhisperModel> = dataStore.data
        .map { prefs ->
            prefs[KEY_SELECTED_MODEL]?.let { WhisperModel.fromFileName(it) } ?: WhisperModel.DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WhisperModel.DEFAULT)

    val selectedLanguage: StateFlow<WhisperLanguage> = dataStore.data
        .map { prefs ->
            prefs[KEY_SELECTED_LANGUAGE]?.let { WhisperLanguage.fromCode(it) } ?: WhisperLanguage.fromDeviceLocale()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WhisperLanguage.fromDeviceLocale())

    val themeMode: StateFlow<ThemeMode> = dataStore.data
        .map { prefs ->
            ThemeMode.fromString(prefs[KEY_THEME_MODE])
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val liveCaptionsEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[LiveTranscriber.KEY_ENABLED] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setLiveCaptionsEnabled(enabled: Boolean) {
        funnel().step(Step.SETTING_CHANGED, SettingWhich.LIVE_CAPTIONS, Buckets.toggle(enabled))
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[LiveTranscriber.KEY_ENABLED] = enabled }
        }
    }

    val labelSpeakersEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[SpeakerLabels.KEY_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** null = idle, otherwise download progress 0..1 of the speaker models. */
    private val _speakerModelProgress = MutableStateFlow<Float?>(null)
    val speakerModelProgress: StateFlow<Float?> = _speakerModelProgress.asStateFlow()
    private val _speakerModelError = MutableStateFlow<String?>(null)
    val speakerModelError: StateFlow<String?> = _speakerModelError.asStateFlow()

    /** Turning labels on downloads the two models first (about 35 MB); if that fails it stays off. */
    fun setLabelSpeakersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                dataStore.edit { it[SpeakerLabels.KEY_ENABLED] = false }
                funnel().step(Step.SETTING_CHANGED, SettingWhich.SPEAKERS, Buckets.toggle(false))
                return@launch
            }
            val dir = SpeakerLabels.modelDir(getApplication())
            if (!ModelDownloader.isReady(dir, SpeakerModels.DIARIZATION)) {
                _speakerModelError.value = null
                _speakerModelProgress.value = 0f
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        ModelDownloader.ensure(dir, SpeakerModels.DIARIZATION) { _speakerModelProgress.value = it }
                    }
                }
                _speakerModelProgress.value = null
                if (result.isFailure) {
                    _speakerModelError.value = result.exceptionOrNull()?.message ?: "Download failed"
                    return@launch
                }
            }
            dataStore.edit { it[SpeakerLabels.KEY_ENABLED] = true }
            funnel().step(Step.SETTING_CHANGED, SettingWhich.SPEAKERS, Buckets.toggle(true))
        }
    }

    val appLockEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[AppLock.KEY_ENABLED] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAppLockEnabled(enabled: Boolean) {
        funnel().step(Step.SETTING_CHANGED, SettingWhich.APP_LOCK, Buckets.toggle(enabled))
        viewModelScope.launch {
            dataStore.edit { prefs -> prefs[AppLock.KEY_ENABLED] = enabled }
        }
    }

    val storageUsed: StateFlow<Long> = availableModels
        .map { modelManager.getTotalStorageUsed() }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    fun selectModel(model: WhisperModel) {
        viewModelScope.launch {
            // Only allow selecting if model is downloaded
            if (modelManager.isModelDownloaded(model)) {
                dataStore.edit { prefs ->
                    prefs[KEY_SELECTED_MODEL] = model.fileName
                }
                funnel().step(Step.SETTING_CHANGED, SettingWhich.MODEL, FunnelModel.of(model))
            }
        }
    }

    fun selectLanguage(language: WhisperLanguage) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_SELECTED_LANGUAGE] = language.code
            }
            // Which language is never recorded, only how well the model in use handles it.
            funnel().step(Step.SETTING_CHANGED, SettingWhich.LANGUAGE, LangBucket.of(language, selectedModel.value))
            // Move off a model that is POOR for this language if a better one is already downloaded
            val downloaded = WhisperModel.entries.filter { modelManager.isModelDownloaded(it) }.toSet()
            LanguageQuality.autoUpgrade(language, selectedModel.value, downloaded)?.let { upgrade ->
                dataStore.edit { prefs -> prefs[KEY_SELECTED_MODEL] = upgrade.fileName }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_THEME_MODE] = mode.name
            }
        }
    }

    fun downloadModel(model: WhisperModel) {
        viewModelScope.launch {
            modelManager.downloadModel(model)
        }
    }

    fun deleteModel(model: WhisperModel) {
        funnel().step(Step.SETTING_CHANGED, SettingWhich.MODEL_DELETE, FunnelModel.of(model))
        // If deleting the currently selected model, switch to another
        if (selectedModel.value == model) {
            val alternative = WhisperModel.entries
                .filter { it != model && modelManager.isModelDownloaded(it) }
                .firstOrNull() ?: WhisperModel.TINY

            viewModelScope.launch {
                dataStore.edit { prefs ->
                    prefs[KEY_SELECTED_MODEL] = alternative.fileName
                }
            }
        }

        modelManager.deleteModel(model)
    }

    fun cancelDownload() {
        modelManager.cancelDownload()
    }

    fun refreshModels() {
        modelManager.refreshModelList()
    }
}

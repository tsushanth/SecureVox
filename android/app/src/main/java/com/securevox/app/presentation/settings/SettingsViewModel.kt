package com.securevox.app.presentation.settings

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.securevox.app.whisper.DownloadState
import com.securevox.app.whisper.ModelInfo
import com.securevox.app.whisper.ModelManager
import com.securevox.app.whisper.WhisperLanguage
import com.securevox.app.whisper.WhisperModel
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

/**
 * Recycle bin retention options
 */
enum class RecycleBinRetention(val days: Int, val displayName: String) {
    DISABLED(0, "Disabled"),
    DAYS_7(7, "7 Days"),
    DAYS_14(14, "14 Days"),
    DAYS_30(30, "30 Days");

    companion object {
        fun fromDays(days: Int): RecycleBinRetention {
            return entries.find { it.days == days } ?: DAYS_30
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

        // Recording settings
        private val KEY_SOUND_EFFECTS = booleanPreferencesKey("sound_effects_enabled")
        private val KEY_HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback_enabled")

        // Transcription settings
        private val KEY_AUTO_PUNCTUATION = booleanPreferencesKey("auto_punctuation_enabled")
        private val KEY_SMART_CAPITALIZATION = booleanPreferencesKey("smart_capitalization_enabled")

        // Data management settings
        private val KEY_RECYCLE_BIN_DAYS = intPreferencesKey("recycle_bin_retention_days")
        private val KEY_AUTO_DELETE_AUDIO = booleanPreferencesKey("auto_delete_audio")
        private val KEY_AUTO_COPY_CLIPBOARD = booleanPreferencesKey("auto_copy_clipboard")
    }

    private val dataStore = application.dataStore
    val modelManager = ModelManager(application)

    val availableModels: StateFlow<List<ModelInfo>> = modelManager.availableModels
    val downloadState: StateFlow<DownloadState> = modelManager.downloadState

    val selectedModel: StateFlow<WhisperModel> = dataStore.data
        .map { prefs ->
            prefs[KEY_SELECTED_MODEL]?.let { WhisperModel.fromFileName(it) } ?: WhisperModel.DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WhisperModel.DEFAULT)

    val selectedLanguage: StateFlow<WhisperLanguage> = dataStore.data
        .map { prefs ->
            prefs[KEY_SELECTED_LANGUAGE]?.let { WhisperLanguage.fromCode(it) } ?: WhisperLanguage.DEFAULT
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, WhisperLanguage.DEFAULT)

    val themeMode: StateFlow<ThemeMode> = dataStore.data
        .map { prefs ->
            ThemeMode.fromString(prefs[KEY_THEME_MODE])
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    // Recording settings
    val soundEffectsEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_SOUND_EFFECTS] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val hapticFeedbackEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_HAPTIC_FEEDBACK] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Transcription settings
    val autoPunctuationEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_AUTO_PUNCTUATION] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val smartCapitalizationEnabled: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_SMART_CAPITALIZATION] ?: true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // Data management settings
    val recycleBinRetention: StateFlow<RecycleBinRetention> = dataStore.data
        .map { prefs -> RecycleBinRetention.fromDays(prefs[KEY_RECYCLE_BIN_DAYS] ?: 30) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, RecycleBinRetention.DAYS_30)

    val autoDeleteAudio: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_AUTO_DELETE_AUDIO] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val autoCopyToClipboard: StateFlow<Boolean> = dataStore.data
        .map { prefs -> prefs[KEY_AUTO_COPY_CLIPBOARD] ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
            }
        }
    }

    fun selectLanguage(language: WhisperLanguage) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_SELECTED_LANGUAGE] = language.code
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

    // Recording settings setters
    fun setSoundEffectsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_SOUND_EFFECTS] = enabled
            }
        }
    }

    fun setHapticFeedbackEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_HAPTIC_FEEDBACK] = enabled
            }
        }
    }

    // Transcription settings setters
    fun setAutoPunctuationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_AUTO_PUNCTUATION] = enabled
            }
        }
    }

    fun setSmartCapitalizationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_SMART_CAPITALIZATION] = enabled
            }
        }
    }

    // Data management settings setters
    fun setRecycleBinRetention(retention: RecycleBinRetention) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_RECYCLE_BIN_DAYS] = retention.days
            }
        }
    }

    fun setAutoDeleteAudio(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_AUTO_DELETE_AUDIO] = enabled
            }
        }
    }

    fun setAutoCopyToClipboard(enabled: Boolean) {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[KEY_AUTO_COPY_CLIPBOARD] = enabled
            }
        }
    }

    fun downloadModel(model: WhisperModel) {
        viewModelScope.launch {
            modelManager.downloadModel(model)
        }
    }

    fun deleteModel(model: WhisperModel) {
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

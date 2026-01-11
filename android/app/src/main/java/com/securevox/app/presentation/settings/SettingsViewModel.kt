package com.securevox.app.presentation.settings

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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

private val Application.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val KEY_SELECTED_MODEL = stringPreferencesKey("selected_model")
        private val KEY_SELECTED_LANGUAGE = stringPreferencesKey("selected_language")
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

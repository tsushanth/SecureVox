package com.securevox.app.presentation.detail

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class RecordingDetailViewModelFactory(
    private val application: Application,
    private val recordingId: String
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(RecordingDetailViewModel::class.java)) {
            return RecordingDetailViewModel(application, recordingId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

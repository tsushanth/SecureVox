package com.securevox.app.service

import android.os.Bundle
import android.util.Log
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase

/**
 * Service for tracking analytics events using Firebase Analytics.
 * Enables Google Ads campaigns and event-based attribution.
 */
object AnalyticsService {

    private const val TAG = "AnalyticsService"

    private val analytics: FirebaseAnalytics by lazy { Firebase.analytics }

    // Event Names
    object Event {
        // Recording events
        const val RECORDING_STARTED = "recording_started"
        const val RECORDING_COMPLETED = "recording_completed"
        const val RECORDING_DELETED = "recording_deleted"

        // Transcription events
        const val TRANSCRIPTION_STARTED = "transcription_started"
        const val TRANSCRIPTION_COMPLETED = "transcription_completed"
        const val TRANSCRIPTION_FAILED = "transcription_failed"

        // Import/Export events
        const val MEDIA_IMPORTED = "media_imported"
        const val TRANSCRIPT_EXPORTED = "transcript_exported"

        // Feature usage events
        const val MODEL_DOWNLOAD_STARTED = "model_download_started"
        const val MODEL_DOWNLOAD_COMPLETED = "model_download_completed"
        const val LANGUAGE_CHANGED = "language_changed"
        const val CUSTOM_DICTIONARY_UPDATED = "custom_dictionary_updated"

        // User engagement events
        const val SETTINGS_OPENED = "settings_opened"
        const val FAQ_VIEWED = "faq_viewed"
        const val RECYCLE_BIN_OPENED = "recycle_bin_opened"
    }

    // Parameter Keys
    object Param {
        const val DURATION = "duration_seconds"
        const val SOURCE_TYPE = "source_type"
        const val EXPORT_FORMAT = "export_format"
        const val LANGUAGE = "language"
        const val MODEL_NAME = "model_name"
        const val WORD_COUNT = "word_count"
        const val SEGMENT_COUNT = "segment_count"
        const val ERROR_MESSAGE = "error_message"
    }

    /**
     * Log a simple event without parameters
     */
    fun log(event: String) {
        analytics.logEvent(event, null)
        Log.d(TAG, "Logged event: $event")
    }

    /**
     * Log an event with custom parameters
     */
    fun log(event: String, params: Bundle) {
        analytics.logEvent(event, params)
        Log.d(TAG, "Logged event: $event with parameters: $params")
    }

    /**
     * Set a user property for segmentation
     */
    fun setUserProperty(name: String, value: String?) {
        analytics.setUserProperty(name, value)
        Log.d(TAG, "Set user property: $name = $value")
    }

    // Convenience Methods

    /**
     * Log recording completed with duration
     */
    fun logRecordingCompleted(durationSeconds: Double) {
        log(Event.RECORDING_COMPLETED, Bundle().apply {
            putDouble(Param.DURATION, durationSeconds)
        })
    }

    /**
     * Log transcription completed with details
     */
    fun logTranscriptionCompleted(durationSeconds: Double, wordCount: Int, segmentCount: Int) {
        log(Event.TRANSCRIPTION_COMPLETED, Bundle().apply {
            putDouble(Param.DURATION, durationSeconds)
            putInt(Param.WORD_COUNT, wordCount)
            putInt(Param.SEGMENT_COUNT, segmentCount)
        })
    }

    /**
     * Log transcription failure
     */
    fun logTranscriptionFailed(errorMessage: String) {
        log(Event.TRANSCRIPTION_FAILED, Bundle().apply {
            putString(Param.ERROR_MESSAGE, errorMessage)
        })
    }

    /**
     * Log media import with source type
     */
    fun logMediaImported(sourceType: String) {
        log(Event.MEDIA_IMPORTED, Bundle().apply {
            putString(Param.SOURCE_TYPE, sourceType)
        })
    }

    /**
     * Log transcript export with format
     */
    fun logTranscriptExported(format: String) {
        log(Event.TRANSCRIPT_EXPORTED, Bundle().apply {
            putString(Param.EXPORT_FORMAT, format)
        })
    }

    /**
     * Log language change
     */
    fun logLanguageChanged(language: String) {
        log(Event.LANGUAGE_CHANGED, Bundle().apply {
            putString(Param.LANGUAGE, language)
        })
    }

    /**
     * Log model download completed
     */
    fun logModelDownloadCompleted(modelName: String) {
        log(Event.MODEL_DOWNLOAD_COMPLETED, Bundle().apply {
            putString(Param.MODEL_NAME, modelName)
        })
    }
}

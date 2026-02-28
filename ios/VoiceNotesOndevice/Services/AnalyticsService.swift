import Foundation
import FBSDKCoreKit
import os.log

private let analyticsLogger = os.Logger(subsystem: "com.voicenotes.ondevice", category: "Analytics")

/// Service for tracking analytics events using Facebook SDK
/// Enables Meta Ads attribution and CAPI for broader audience targeting
final class AnalyticsService {

    // MARK: - Singleton

    static let shared = AnalyticsService()

    private init() {}

    // MARK: - Event Names

    enum Event: String {
        // Recording events
        case recordingStarted = "recording_started"
        case recordingCompleted = "recording_completed"
        case recordingDeleted = "recording_deleted"

        // Transcription events
        case transcriptionStarted = "transcription_started"
        case transcriptionCompleted = "transcription_completed"
        case transcriptionFailed = "transcription_failed"

        // Import/Export events
        case mediaImported = "media_imported"
        case transcriptExported = "transcript_exported"

        // Feature usage events
        case modelDownloadStarted = "model_download_started"
        case modelDownloadCompleted = "model_download_completed"
        case languageChanged = "language_changed"
        case customDictionaryUpdated = "custom_dictionary_updated"

        // User engagement events
        case settingsOpened = "settings_opened"
        case faqViewed = "faq_viewed"
        case recycleBinOpened = "recycle_bin_opened"
    }

    // MARK: - Parameter Keys

    enum ParameterKey: String {
        case duration = "duration_seconds"
        case sourceType = "source_type"
        case exportFormat = "export_format"
        case language = "language"
        case modelName = "model_name"
        case wordCount = "word_count"
        case segmentCount = "segment_count"
        case errorMessage = "error_message"
    }

    // MARK: - Logging Methods

    /// Log a simple event without parameters
    func log(_ event: Event) {
        AppEvents.shared.logEvent(AppEvents.Name(event.rawValue))
        analyticsLogger.debug("Logged event: \(event.rawValue)")
    }

    /// Log an event with custom parameters
    func log(_ event: Event, parameters: [ParameterKey: Any]) {
        let appEventParams = parameters.reduce(into: [AppEvents.ParameterName: Any]()) { result, pair in
            result[AppEvents.ParameterName(pair.key.rawValue)] = pair.value
        }
        AppEvents.shared.logEvent(AppEvents.Name(event.rawValue), parameters: appEventParams)
        analyticsLogger.debug("Logged event: \(event.rawValue) with parameters: \(parameters)")
    }

    /// Log a custom event with string name and parameters
    func logCustom(_ eventName: String, parameters: [String: Any]? = nil) {
        if let parameters = parameters {
            let appEventParams = parameters.reduce(into: [AppEvents.ParameterName: Any]()) { result, pair in
                result[AppEvents.ParameterName(pair.key)] = pair.value
            }
            AppEvents.shared.logEvent(AppEvents.Name(eventName), parameters: appEventParams)
        } else {
            AppEvents.shared.logEvent(AppEvents.Name(eventName))
        }
        analyticsLogger.debug("Logged custom event: \(eventName)")
    }

    // MARK: - User Properties

    /// Set a user property for segmentation
    func setUserProperty(_ value: String?, forName name: String) {
        // Note: Facebook SDK uses setUserData for custom user properties
        // For user ID tracking, use AppEvents.shared.userID
        analyticsLogger.debug("Set user property: \(name) = \(value ?? "nil")")
    }

    // MARK: - Convenience Methods

    /// Log recording completed with duration
    func logRecordingCompleted(durationSeconds: Double) {
        log(.recordingCompleted, parameters: [
            .duration: durationSeconds
        ])
    }

    /// Log transcription completed with details
    func logTranscriptionCompleted(durationSeconds: Double, wordCount: Int, segmentCount: Int) {
        log(.transcriptionCompleted, parameters: [
            .duration: durationSeconds,
            .wordCount: wordCount,
            .segmentCount: segmentCount
        ])
    }

    /// Log transcription failure
    func logTranscriptionFailed(errorMessage: String) {
        log(.transcriptionFailed, parameters: [
            .errorMessage: errorMessage
        ])
    }

    /// Log media import with source type
    func logMediaImported(sourceType: String) {
        log(.mediaImported, parameters: [
            .sourceType: sourceType
        ])
    }

    /// Log transcript export with format
    func logTranscriptExported(format: String) {
        log(.transcriptExported, parameters: [
            .exportFormat: format
        ])
    }

    /// Log language change
    func logLanguageChanged(to language: String) {
        log(.languageChanged, parameters: [
            .language: language
        ])
    }

    /// Log model download
    func logModelDownloadCompleted(modelName: String) {
        log(.modelDownloadCompleted, parameters: [
            .modelName: modelName
        ])
    }
}

import Foundation
import SwiftUI
import Combine

/// ViewModel for app settings
@MainActor
final class SettingsViewModel: ObservableObject {

    // MARK: - Published State

    /// Selected Whisper model
    @Published var selectedModel: WhisperModel {
        didSet {
            UserDefaults.standard.set(selectedModel.rawValue, forKey: AppConstants.UserDefaultsKeys.selectedModel)
        }
    }

    /// Default language for transcription
    @Published var defaultLanguage: String {
        didSet {
            UserDefaults.standard.set(defaultLanguage, forKey: AppConstants.UserDefaultsKeys.defaultLanguage)
        }
    }

    /// Whether to auto-delete audio after transcription
    @Published var autoDeleteAudio: Bool {
        didSet {
            UserDefaults.standard.set(autoDeleteAudio, forKey: AppConstants.UserDefaultsKeys.autoDeleteAudio)
        }
    }

    /// Whether to auto-copy transcript to clipboard after transcription
    @Published var autoCopyToClipboard: Bool {
        didSet {
            UserDefaults.standard.set(autoCopyToClipboard, forKey: AppConstants.UserDefaultsKeys.autoCopyToClipboard)
        }
    }

    /// App theme preference
    @Published var appTheme: AppConstants.AppTheme {
        didSet {
            UserDefaults.standard.set(appTheme.rawValue, forKey: AppConstants.UserDefaultsKeys.appTheme)
        }
    }

    /// Whether sound effects are enabled for recording start/stop
    @Published var soundEffectsEnabled: Bool {
        didSet {
            UserDefaults.standard.set(soundEffectsEnabled, forKey: AppConstants.UserDefaultsKeys.soundEffectsEnabled)
        }
    }

    /// Whether haptic feedback is enabled for recording
    @Published var hapticFeedbackEnabled: Bool {
        didSet {
            UserDefaults.standard.set(hapticFeedbackEnabled, forKey: AppConstants.UserDefaultsKeys.hapticFeedbackEnabled)
        }
    }

    /// Whether auto-punctuation is enabled for transcription
    @Published var autoPunctuationEnabled: Bool {
        didSet {
            UserDefaults.standard.set(autoPunctuationEnabled, forKey: AppConstants.UserDefaultsKeys.autoPunctuationEnabled)
        }
    }

    /// Whether smart capitalization is enabled for transcription
    @Published var smartCapitalizationEnabled: Bool {
        didSet {
            UserDefaults.standard.set(smartCapitalizationEnabled, forKey: AppConstants.UserDefaultsKeys.smartCapitalizationEnabled)
        }
    }

    /// Recording quality setting
    @Published var recordingQuality: AppConstants.RecordingQuality {
        didSet {
            UserDefaults.standard.set(recordingQuality.rawValue, forKey: AppConstants.UserDefaultsKeys.recordingQuality)
        }
    }

    /// Input gain multiplier (0.5 to 2.0, 1.0 = normal)
    @Published var inputGain: Float {
        didSet {
            UserDefaults.standard.set(inputGain, forKey: AppConstants.UserDefaultsKeys.inputGain)
        }
    }

    /// Recycle bin retention days (0 = disabled, 7/14/30 days)
    @Published var recycleBinRetentionDays: Int {
        didSet {
            UserDefaults.standard.set(recycleBinRetentionDays, forKey: AppConstants.UserDefaultsKeys.recycleBinRetentionDays)
        }
    }

    /// Count of deleted recordings in recycle bin
    @Published private(set) var deletedRecordingsCount: Int?

    /// Storage usage information
    @Published private(set) var storageInfo: StorageInfo?

    /// Whether storage info is loading
    @Published private(set) var isLoadingStorage: Bool = false

    /// Download progress for models (model ID -> progress 0-1)
    @Published private(set) var modelDownloadProgress: [String: Double] = [:]

    /// Model availability cache
    @Published private(set) var modelAvailability: [String: Bool] = [:]

    /// Error message to display
    @Published var errorMessage: String? = nil

    // MARK: - Dependencies

    private let transcriber: CoreMLTranscriber
    private let recordingStore: RecordingStore?

    // MARK: - Whisper Model

    enum WhisperModel: String, CaseIterable, Identifiable {
        case tiny = "openai_whisper-tiny"
        case base = "openai_whisper-base"
        case small = "openai_whisper-small"

        var id: String { rawValue }

        /// Legacy raw value for UserDefaults compatibility
        var legacyRawValue: String {
            switch self {
            case .tiny: return "whisper-tiny"
            case .base: return "whisper-base"
            case .small: return "whisper-small"
            }
        }

        /// Initialize from legacy raw value (for UserDefaults migration)
        init?(legacyRawValue: String) {
            switch legacyRawValue {
            case "whisper-tiny": self = .tiny
            case "whisper-base": self = .base
            case "whisper-small": self = .small
            default: return nil
            }
        }

        var displayName: String {
            switch self {
            case .tiny: return "Fast"
            case .base: return "Balanced"
            case .small: return "Accurate"
            }
        }

        var subtitle: String {
            switch self {
            case .tiny: return "Quick results"
            case .base: return "2x slower"
            case .small: return "5x slower"
            }
        }

        var expectedRAM: String {
            switch self {
            case .tiny: return "~200 MB"
            case .base: return "~400 MB"
            case .small: return "~1 GB"
            }
        }

        var approximateSpeed: String {
            switch self {
            case .tiny: return "Fastest"
            case .base: return "Moderate"
            case .small: return "Slower"
            }
        }

        var modelSize: String {
            switch self {
            case .tiny: return "~40 MB"
            case .base: return "~140 MB"
            case .small: return "~460 MB"
            }
        }

        /// Tiny model is bundled with the app; larger models download on-demand
        var isBundled: Bool {
            self == .tiny
        }
    }

    // MARK: - Storage Info

    struct StorageInfo {
        let totalRecordings: Int
        let totalAudioSize: Int64
        let totalDuration: TimeInterval
        let availableSpace: Int64

        var formattedAudioSize: String {
            ByteCountFormatter.string(fromByteCount: totalAudioSize, countStyle: .file)
        }

        var formattedAvailableSpace: String {
            ByteCountFormatter.string(fromByteCount: availableSpace, countStyle: .file)
        }

        var formattedDuration: String {
            let hours = Int(totalDuration) / 3600
            let minutes = (Int(totalDuration) % 3600) / 60

            if hours > 0 {
                return "\(hours)h \(minutes)m"
            } else {
                return "\(minutes) min"
            }
        }
    }

    // MARK: - Initialization

    init(transcriber: CoreMLTranscriber = .shared, recordingStore: RecordingStore? = nil) {
        self.transcriber = transcriber
        self.recordingStore = recordingStore

        // Load saved preferences with migration support
        let savedModel = UserDefaults.standard.string(forKey: AppConstants.UserDefaultsKeys.selectedModel)

        if let savedModel = savedModel {
            // Try new format first, then legacy format
            if let model = WhisperModel(rawValue: savedModel) {
                self.selectedModel = model
            } else if let model = WhisperModel(legacyRawValue: savedModel) {
                self.selectedModel = model
                // Migrate to new format
                UserDefaults.standard.set(model.rawValue, forKey: AppConstants.UserDefaultsKeys.selectedModel)
            } else {
                self.selectedModel = .tiny
            }
        } else {
            self.selectedModel = .tiny
        }

        self.defaultLanguage = UserDefaults.standard.string(forKey: AppConstants.UserDefaultsKeys.defaultLanguage) ?? "auto"
        self.autoDeleteAudio = UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.autoDeleteAudio)
        self.autoCopyToClipboard = UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.autoCopyToClipboard)

        // Load theme preference
        if let savedTheme = UserDefaults.standard.string(forKey: AppConstants.UserDefaultsKeys.appTheme),
           let theme = AppConstants.AppTheme(rawValue: savedTheme) {
            self.appTheme = theme
        } else {
            self.appTheme = .system
        }

        // Load sound and haptic settings (default to enabled)
        // Check if key exists, if not default to true
        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.soundEffectsEnabled) != nil {
            self.soundEffectsEnabled = UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.soundEffectsEnabled)
        } else {
            self.soundEffectsEnabled = true
        }

        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.hapticFeedbackEnabled) != nil {
            self.hapticFeedbackEnabled = UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.hapticFeedbackEnabled)
        } else {
            self.hapticFeedbackEnabled = true
        }

        // Load punctuation settings (default to enabled)
        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.autoPunctuationEnabled) != nil {
            self.autoPunctuationEnabled = UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.autoPunctuationEnabled)
        } else {
            self.autoPunctuationEnabled = true
        }

        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.smartCapitalizationEnabled) != nil {
            self.smartCapitalizationEnabled = UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.smartCapitalizationEnabled)
        } else {
            self.smartCapitalizationEnabled = true
        }

        // Load recording quality (default to standard)
        if let savedQuality = UserDefaults.standard.string(forKey: AppConstants.UserDefaultsKeys.recordingQuality),
           let quality = AppConstants.RecordingQuality(rawValue: savedQuality) {
            self.recordingQuality = quality
        } else {
            self.recordingQuality = .standard
        }

        // Load input gain (default to 1.0 = normal)
        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.inputGain) != nil {
            self.inputGain = UserDefaults.standard.float(forKey: AppConstants.UserDefaultsKeys.inputGain)
        } else {
            self.inputGain = 1.0
        }

        // Load recycle bin retention (default to 30 days)
        if UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.recycleBinRetentionDays) != nil {
            self.recycleBinRetentionDays = UserDefaults.standard.integer(forKey: AppConstants.UserDefaultsKeys.recycleBinRetentionDays)
        } else {
            self.recycleBinRetentionDays = 30
        }
    }

    // MARK: - Public Methods

    /// Load storage usage information
    func loadStorageInfo() async {
        isLoadingStorage = true

        do {
            let info = try await calculateStorageInfo()
            storageInfo = info
        } catch {
            errorMessage = "Failed to load storage info: \(error.localizedDescription)"
        }

        isLoadingStorage = false
    }

    /// Check if a model is available locally (uses cached data)
    func isModelAvailable(_ model: WhisperModel) -> Bool {
        if model.isBundled {
            return true
        }
        return modelAvailability[model.rawValue] ?? false
    }

    /// Refresh model availability cache
    func refreshModelAvailability() async {
        for model in WhisperModel.allCases {
            if model.isBundled {
                modelAvailability[model.rawValue] = true
            } else {
                modelAvailability[model.rawValue] = await transcriber.isModelAvailable(model.rawValue)
            }
        }
    }

    /// Download a model
    func downloadModel(_ model: WhisperModel) async {
        guard !model.isBundled else { return }

        modelDownloadProgress[model.rawValue] = 0

        do {
            let stream = await transcriber.downloadModel(model.rawValue)
            for try await progress in stream {
                modelDownloadProgress[model.rawValue] = progress
            }
            modelDownloadProgress[model.rawValue] = nil
            modelAvailability[model.rawValue] = true

            // Auto-select the model after successful download
            selectedModel = model
        } catch {
            modelDownloadProgress[model.rawValue] = nil
            errorMessage = "Failed to download model: \(error.localizedDescription)"
        }
    }

    /// Delete all audio files (keep transcripts)
    func deleteAllAudioFiles() async {
        do {
            try await recordingStore?.deleteAllAudioFiles()
            await loadStorageInfo()
        } catch {
            errorMessage = "Failed to delete audio files: \(error.localizedDescription)"
        }
    }

    /// Delete all recordings (audio files and transcripts)
    func deleteAllRecordings() async {
        do {
            // Fetch all recordings and delete them
            try await recordingStore?.fetchRecordings()
            if let recordings = recordingStore?.recordings {
                try recordingStore?.deleteRecordings(Array(recordings))
            }
            await loadStorageInfo()
        } catch {
            errorMessage = "Failed to delete recordings: \(error.localizedDescription)"
        }
    }

    /// Get language display name
    func languageDisplayName(for code: String) -> String {
        if code == "auto" {
            return "Auto-Detect"
        }
        return Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    // MARK: - App Info

    var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }

    // MARK: - Private Methods

    private func calculateStorageInfo() async throws -> StorageInfo {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let recordingsURL = documentsURL.appendingPathComponent(AppConstants.Storage.recordingsDirectory)

        var totalSize: Int64 = 0
        var fileCount = 0

        if FileManager.default.fileExists(atPath: recordingsURL.path) {
            let contents = try FileManager.default.contentsOfDirectory(
                at: recordingsURL,
                includingPropertiesForKeys: [.fileSizeKey],
                options: .skipsHiddenFiles
            )

            for fileURL in contents {
                let attributes = try fileURL.resourceValues(forKeys: [.fileSizeKey])
                totalSize += Int64(attributes.fileSize ?? 0)
                fileCount += 1
            }
        }

        // Get available space
        let attributes = try FileManager.default.attributesOfFileSystem(forPath: documentsURL.path)
        let availableSpace = (attributes[.systemFreeSize] as? Int64) ?? 0

        // Get total duration from store
        let totalDuration = recordingStore?.totalDuration ?? 0

        return StorageInfo(
            totalRecordings: fileCount,
            totalAudioSize: totalSize,
            totalDuration: totalDuration,
            availableSpace: availableSpace
        )
    }
}

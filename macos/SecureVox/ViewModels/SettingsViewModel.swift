import Foundation
import SwiftData
import Combine
import AppKit
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "Settings")

/// ViewModel for settings
@MainActor
class SettingsViewModel: ObservableObject {

    // MARK: - General Settings

    @Published var launchAtLogin: Bool {
        didSet { UserDefaults.standard.set(launchAtLogin, forKey: "launchAtLogin") }
    }

    @Published var hideDockIcon: Bool {
        didSet { UserDefaults.standard.set(hideDockIcon, forKey: "hideDockIcon") }
    }

    // MARK: - Appearance

    @Published var appTheme: AppConstants.AppTheme {
        didSet {
            UserDefaults.standard.set(appTheme.rawValue, forKey: "appTheme")
            applyTheme()
        }
    }

    // MARK: - Transcription

    @Published var selectedModel: AppConstants.WhisperModel {
        didSet { UserDefaults.standard.set(selectedModel.rawValue, forKey: "selectedModel") }
    }

    @Published var defaultLanguage: AppConstants.WhisperLanguage {
        didSet { UserDefaults.standard.set(defaultLanguage.rawValue, forKey: "defaultLanguage") }
    }

    @Published var autoPunctuationEnabled: Bool {
        didSet { UserDefaults.standard.set(autoPunctuationEnabled, forKey: "autoPunctuationEnabled") }
    }

    @Published var smartCapitalizationEnabled: Bool {
        didSet { UserDefaults.standard.set(smartCapitalizationEnabled, forKey: "smartCapitalizationEnabled") }
    }

    // MARK: - Recording

    @Published var recordingQuality: AppConstants.RecordingQuality {
        didSet { UserDefaults.standard.set(recordingQuality.rawValue, forKey: "recordingQuality") }
    }

    @Published var inputGain: Float {
        didSet { UserDefaults.standard.set(inputGain, forKey: "inputGain") }
    }

    @Published var soundEffectsEnabled: Bool {
        didSet { UserDefaults.standard.set(soundEffectsEnabled, forKey: "soundEffectsEnabled") }
    }

    // MARK: - Behavior

    @Published var autoCopyToClipboard: Bool {
        didSet { UserDefaults.standard.set(autoCopyToClipboard, forKey: "autoCopyToClipboard") }
    }

    @Published var autoDeleteAudio: Bool {
        didSet { UserDefaults.standard.set(autoDeleteAudio, forKey: "autoDeleteAudio") }
    }

    // MARK: - Data Management

    @Published var recycleBinRetentionDays: Int {
        didSet { UserDefaults.standard.set(recycleBinRetentionDays, forKey: "recycleBinRetentionDays") }
    }

    // MARK: - Storage Info

    @Published var storageInfo: StorageInfo?
    @Published var isLoadingStorage = false
    @Published var deletedRecordingsCount: Int = 0

    // MARK: - Model Download

    @Published var modelDownloadProgress: [String: Double] = [:]
    @Published var availableModels: Set<String> = []

    // MARK: - Error Handling

    @Published var errorMessage: String?

    // MARK: - Private

    private var modelContext: ModelContext?

    // MARK: - Computed

    var appVersion: String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }

    // MARK: - Initialization

    init() {
        // Load saved settings
        self.launchAtLogin = UserDefaults.standard.bool(forKey: "launchAtLogin")
        self.hideDockIcon = UserDefaults.standard.bool(forKey: "hideDockIcon")

        if let themeRaw = UserDefaults.standard.string(forKey: "appTheme"),
           let theme = AppConstants.AppTheme(rawValue: themeRaw) {
            self.appTheme = theme
        } else {
            self.appTheme = .system
        }

        if let modelRaw = UserDefaults.standard.string(forKey: "selectedModel"),
           let model = AppConstants.WhisperModel(rawValue: modelRaw) {
            self.selectedModel = model
        } else {
            self.selectedModel = .tiny
        }

        if let langRaw = UserDefaults.standard.string(forKey: "defaultLanguage"),
           let lang = AppConstants.WhisperLanguage(rawValue: langRaw) {
            self.defaultLanguage = lang
        } else {
            self.defaultLanguage = .auto
        }

        self.autoPunctuationEnabled = UserDefaults.standard.object(forKey: "autoPunctuationEnabled") as? Bool ?? true
        self.smartCapitalizationEnabled = UserDefaults.standard.object(forKey: "smartCapitalizationEnabled") as? Bool ?? true

        if let qualityRaw = UserDefaults.standard.string(forKey: "recordingQuality"),
           let quality = AppConstants.RecordingQuality(rawValue: qualityRaw) {
            self.recordingQuality = quality
        } else {
            self.recordingQuality = .standard
        }

        self.inputGain = UserDefaults.standard.object(forKey: "inputGain") as? Float ?? 1.0
        self.soundEffectsEnabled = UserDefaults.standard.object(forKey: "soundEffectsEnabled") as? Bool ?? true
        self.autoCopyToClipboard = UserDefaults.standard.bool(forKey: "autoCopyToClipboard")
        self.autoDeleteAudio = UserDefaults.standard.bool(forKey: "autoDeleteAudio")
        self.recycleBinRetentionDays = UserDefaults.standard.object(forKey: "recycleBinRetentionDays") as? Int ?? 7

        // Check available models
        refreshModelAvailability()
    }

    func configure(with context: ModelContext) {
        self.modelContext = context
        Task {
            await loadStorageInfo()
            await loadDeletedCount()
        }
    }

    // MARK: - Theme

    private func applyTheme() {
        // macOS theme application would go here
        // For now, we rely on SwiftUI's preferredColorScheme
    }

    // MARK: - Storage

    func loadStorageInfo() async {
        isLoadingStorage = true
        defer { isLoadingStorage = false }

        guard let context = modelContext else { return }

        do {
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate { !$0.isDeleted }
            )
            let allRecordings = try context.fetch(descriptor)

            let audioSize = allRecordings.reduce(0) { $0 + $1.audioFileSize }
            let totalDuration = allRecordings.reduce(0) { $0 + $1.duration }

            let availableSpace = try? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage ?? 0

            storageInfo = StorageInfo(
                totalRecordings: allRecordings.count,
                audioSize: audioSize,
                totalDuration: totalDuration,
                availableSpace: availableSpace ?? 0
            )
        } catch {
            logger.error("Error loading storage info: \(error.localizedDescription)")
        }
    }

    func loadDeletedCount() async {
        guard let context = modelContext else { return }

        do {
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate { $0.isDeleted }
            )
            let deleted = try context.fetch(descriptor)
            deletedRecordingsCount = deleted.count
        } catch {
            logger.error("Error loading deleted count: \(error.localizedDescription)")
        }
    }

    // MARK: - Model Management

    func refreshModelAvailability() {
        availableModels.removeAll()

        for model in AppConstants.WhisperModel.allCases {
            if TranscriptionService.shared.isModelAvailable(model) {
                availableModels.insert(model.rawValue)
            }
        }

        // Tiny is always available (bundled)
        availableModels.insert(AppConstants.WhisperModel.tiny.rawValue)
    }

    func isModelAvailable(_ model: AppConstants.WhisperModel) -> Bool {
        availableModels.contains(model.rawValue)
    }

    func downloadModel(_ model: AppConstants.WhisperModel) async {
        do {
            try await TranscriptionService.shared.downloadModel(model)
            availableModels.insert(model.rawValue)
        } catch {
            errorMessage = "Failed to download model: \(error.localizedDescription)"
        }
    }

    // MARK: - Data Management

    func deleteAllAudioFiles() async {
        guard let context = modelContext else { return }

        do {
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate { !$0.isDeleted }
            )
            let recordings = try context.fetch(descriptor)

            for recording in recordings {
                if let audioURL = recording.audioURL {
                    try? FileManager.default.removeItem(at: audioURL)
                }
                recording.audioFileName = nil
                recording.audioFileSize = 0
            }

            try context.save()
            await loadStorageInfo()
        } catch {
            errorMessage = "Failed to delete audio files: \(error.localizedDescription)"
        }
    }

    func deleteAllRecordings() async {
        guard let context = modelContext else { return }

        do {
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate { !$0.isDeleted }
            )
            let recordings = try context.fetch(descriptor)

            for recording in recordings {
                if let audioURL = recording.audioURL {
                    try? FileManager.default.removeItem(at: audioURL)
                }
                context.delete(recording)
            }

            try context.save()
            await loadStorageInfo()
        } catch {
            errorMessage = "Failed to delete recordings: \(error.localizedDescription)"
        }
    }

    // MARK: - Support

    func contactSupport() {
        let subject = AppConstants.Support.emailSubject.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        if let url = URL(string: "mailto:\(AppConstants.Support.email)?subject=\(subject)") {
            NSWorkspace.shared.open(url)
        }
    }

    func openPrivacyPolicy() {
        if let url = URL(string: AppConstants.Support.privacyPolicyURL) {
            NSWorkspace.shared.open(url)
        }
    }

    func openAppStore() {
        if let url = URL(string: AppConstants.Support.appStoreURL) {
            NSWorkspace.shared.open(url)
        }
    }
}

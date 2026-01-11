import Foundation

/// Global app constants and configuration
enum AppConstants {

    // MARK: - App Info

    static let appName = "SecureVox"
    static let bundleIdentifier = "com.voicenotes.ondevice"

    // MARK: - Audio Configuration

    enum Audio {
        /// Sample rate for Whisper model (required: 16kHz)
        static let whisperSampleRate: Double = 16000

        /// Sample rate for recording (will be converted)
        static let recordingSampleRate: Double = 44100

        /// Audio file extension for recordings
        static let recordingFileExtension = "m4a"

        /// Approximate bytes per minute for AAC recording
        static let bytesPerMinuteAAC: Int64 = 1_000_000

        /// Maximum recording duration (4 hours)
        static let maxRecordingDuration: TimeInterval = 4 * 60 * 60

        /// Minimum audio duration for transcription
        static let minTranscriptionDuration: TimeInterval = 0.5
    }

    // MARK: - Whisper Configuration

    enum Whisper {
        /// Chunk duration in seconds
        static let chunkDuration: TimeInterval = 30.0

        /// Overlap between chunks to avoid word cutoff
        static let chunkOverlap: TimeInterval = 1.0

        /// Maximum file size for import (2 GB)
        static let maxImportFileSize: Int64 = 2 * 1024 * 1024 * 1024
    }

    // MARK: - Storage

    enum Storage {
        /// Directory name for audio recordings
        static let recordingsDirectory = "recordings"

        /// Directory name for temporary files
        static let tempDirectory = "temp"

        /// Minimum free space warning threshold (500 MB)
        static let lowSpaceWarningThreshold: Int64 = 500 * 1024 * 1024
    }

    // MARK: - UI

    enum UI {
        /// Debounce delay for search input
        static let searchDebounceDelay: TimeInterval = 0.3

        /// Audio level update frequency (Hz)
        static let audioLevelUpdateFrequency: Double = 60.0

        /// Animation duration for standard transitions
        static let standardAnimationDuration: TimeInterval = 0.25
    }

    // MARK: - User Defaults Keys

    enum UserDefaultsKeys {
        static let selectedModel = "selectedModel"
        static let defaultLanguage = "defaultLanguage"
        static let autoDeleteAudio = "autoDeleteAudio"
        static let autoCopyToClipboard = "autoCopyToClipboard"
        static let hasCompletedOnboarding = "hasCompletedOnboarding"
        static let hasShownRatingPrompt = "hasShownRatingPrompt"
        static let transcriptionCount = "transcriptionCount"

        // Rating prompt tracking
        static let ratingPromptLastShownDate = "ratingPromptLastShownDate"
        static let ratingPromptResponse = "ratingPromptResponse" // "yes", "no", "notNow", or nil
        static let ratingPromptNotNowCount = "ratingPromptNotNowCount"
        static let transcriptionsSinceLastPrompt = "transcriptionsSinceLastPrompt"
        static let appTheme = "appTheme"
        static let soundEffectsEnabled = "soundEffectsEnabled"
        static let hapticFeedbackEnabled = "hapticFeedbackEnabled"
        static let playbackSpeed = "playbackSpeed"
        static let autoPunctuationEnabled = "autoPunctuationEnabled"
        static let smartCapitalizationEnabled = "smartCapitalizationEnabled"
        static let recordingQuality = "recordingQuality"
        static let inputGain = "inputGain"
        static let recycleBinRetentionDays = "recycleBinRetentionDays"
        static let customDictionary = "customDictionary"
        static let customDictionaryEnabled = "customDictionaryEnabled"
        static let customDictionaryWords = "customDictionaryWords"
    }

    // MARK: - Recording Quality

    enum RecordingQuality: String, CaseIterable, Identifiable {
        case standard = "standard"
        case high = "high"
        case maximum = "maximum"

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .standard: return "Standard"
            case .high: return "High"
            case .maximum: return "Maximum"
            }
        }

        var description: String {
            switch self {
            case .standard: return "16-bit, smaller files"
            case .high: return "24-bit, better quality"
            case .maximum: return "32-bit, best quality"
            }
        }

        var bitDepth: Int {
            switch self {
            case .standard: return 16
            case .high: return 24
            case .maximum: return 32
            }
        }
    }

    // MARK: - App Store

    enum AppStore {
        static let appID = "6740696498"
        static var appStoreURL: URL {
            URL(string: "https://apps.apple.com/app/id\(appID)")!
        }
    }

    // MARK: - Theme

    enum AppTheme: String, CaseIterable, Identifiable {
        case system = "system"
        case light = "light"
        case dark = "dark"

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .system: return "System"
            case .light: return "Light"
            case .dark: return "Dark"
            }
        }

        var icon: String {
            switch self {
            case .system: return "circle.lefthalf.filled"
            case .light: return "sun.max.fill"
            case .dark: return "moon.fill"
            }
        }
    }

    // MARK: - Support

    enum Support {
        static let email = "support@kreativekoala.llc"
        static let emailSubject = "SecureVox Feedback"
    }

    // MARK: - Rating Prompt Configuration

    enum RatingPrompt {
        /// Minimum transcriptions before showing the first prompt
        static let minTranscriptionsForFirstPrompt = 3

        /// Transcriptions between prompts for users who chose "Not Now"
        static let transcriptionsBetweenPrompts = 10

        /// Minimum days between prompts for users who chose "Not Now"
        static let daysBetweenPrompts = 7

        /// Maximum times to show prompt to users who keep choosing "Not Now"
        static let maxNotNowPrompts = 3
    }
}

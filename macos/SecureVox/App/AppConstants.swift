import Foundation

/// App-wide constants and configuration
enum AppConstants {

    // MARK: - App Info

    static let appName = "SecureVox"
    static let bundleIdentifier = "com.voicenotes.ondevice.macos"

    // MARK: - Audio Configuration

    enum Audio {
        static let whisperSampleRate: Double = 16000
        static let recordingSampleRate: Double = 44100
        static let recordingFileExtension = "m4a"
        static let bytesPerMinute = 1_000_000 // ~1 MB per minute for AAC
        static let maxRecordingDuration: TimeInterval = 4 * 60 * 60 // 4 hours
        static let minTranscriptionDuration: TimeInterval = 0.5
        static let bufferSize: UInt32 = 4096
    }

    // MARK: - Whisper Configuration

    enum Whisper {
        static let chunkDuration: TimeInterval = 30
        static let chunkOverlap: TimeInterval = 1
        static let maxImportFileSize: Int64 = 2 * 1024 * 1024 * 1024 // 2 GB
        static let modelLoadTimeout: TimeInterval = 30
        static let modelDownloadTimeout: TimeInterval = 120
    }

    // MARK: - Storage

    enum Storage {
        static let recordingsDirectory = "recordings"
        static let tempDirectory = "temp"
        static let modelsDirectory = "models"
        static let lowSpaceWarning: Int64 = 500 * 1024 * 1024 // 500 MB
        static let minSpaceToStart: Int64 = 50 * 1024 * 1024 // 50 MB
        static let minSpaceToContinue: Int64 = 20 * 1024 * 1024 // 20 MB
        static let spaceCheckInterval: TimeInterval = 10
    }

    // MARK: - UI

    enum UI {
        static let searchDebounce: TimeInterval = 0.3
        static let audioLevelUpdateFrequency: Double = 60
        static let standardAnimationDuration: TimeInterval = 0.25
        static let displayLinkFrameRate: Int = 30
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

    // MARK: - App Theme

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
            case .light: return "sun.max"
            case .dark: return "moon"
            }
        }
    }

    // MARK: - Playback Speed

    enum PlaybackSpeed: Double, CaseIterable, Identifiable {
        case half = 0.5
        case threeQuarters = 0.75
        case normal = 1.0
        case oneAndQuarter = 1.25
        case oneAndHalf = 1.5
        case double = 2.0

        var id: Double { rawValue }

        var displayName: String {
            switch self {
            case .half: return "0.5x"
            case .threeQuarters: return "0.75x"
            case .normal: return "1x"
            case .oneAndQuarter: return "1.25x"
            case .oneAndHalf: return "1.5x"
            case .double: return "2x"
            }
        }
    }

    // MARK: - Export Formats

    enum ExportFormat: String, CaseIterable, Identifiable {
        case txt = "txt"
        case srt = "srt"
        case vtt = "vtt"
        case json = "json"

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .txt: return "Plain Text"
            case .srt: return "SubRip Subtitle"
            case .vtt: return "WebVTT"
            case .json: return "JSON"
            }
        }

        var description: String {
            switch self {
            case .txt: return "Simple text without timestamps"
            case .srt: return "Standard subtitle format for video editors"
            case .vtt: return "Web-compatible subtitle format"
            case .json: return "Structured data with full metadata"
            }
        }

        var fileExtension: String { rawValue }

        var mimeType: String {
            switch self {
            case .txt: return "text/plain"
            case .srt: return "application/x-subrip"
            case .vtt: return "text/vtt"
            case .json: return "application/json"
            }
        }
    }

    // MARK: - Recycle Bin

    enum RecycleBinRetention: Int, CaseIterable, Identifiable {
        case disabled = 0
        case sevenDays = 7
        case fourteenDays = 14
        case thirtyDays = 30

        var id: Int { rawValue }

        var displayName: String {
            switch self {
            case .disabled: return "Disabled"
            case .sevenDays: return "7 Days"
            case .fourteenDays: return "14 Days"
            case .thirtyDays: return "30 Days"
            }
        }
    }

    // MARK: - Support

    enum Support {
        static let email = "support@securevox.app"
        static let emailSubject = "SecureVox macOS Support"
        static let privacyPolicyURL = "https://kreativekoala.llc/privacy"
        static let appStoreURL = "https://apps.apple.com/app/securevox"
    }

    // MARK: - Rating Prompt

    enum RatingPrompt {
        static let initialTranscriptionCount = 3
        static let subsequentTranscriptionCount = 10
        static let daysBetweenPrompts = 7
        static let maxNotNowCount = 3
    }

    // MARK: - Whisper Models

    enum WhisperModel: String, CaseIterable, Identifiable {
        case tiny = "openai_whisper-tiny"
        case base = "openai_whisper-base"
        case small = "openai_whisper-small"
        case largeTurbo = "openai_whisper-large-v3-turbo"

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .tiny: return "Tiny"
            case .base: return "Base"
            case .small: return "Small"
            case .largeTurbo: return "Large V3 Turbo"
            }
        }

        var subtitle: String {
            switch self {
            case .tiny: return "Fastest"
            case .base: return "Fast"
            case .small: return "Balanced"
            case .largeTurbo: return "Most Accurate"
            }
        }

        var expectedRAM: String {
            switch self {
            case .tiny: return "~200 MB RAM"
            case .base: return "~400 MB RAM"
            case .small: return "~1 GB RAM"
            case .largeTurbo: return "~3.5 GB RAM"
            }
        }

        var approximateSpeed: String {
            switch self {
            case .tiny: return "~10x realtime"
            case .base: return "~5x realtime"
            case .small: return "~2x realtime"
            case .largeTurbo: return "~1.2x realtime"
            }
        }

        var downloadSize: String {
            switch self {
            case .tiny: return "~40 MB"
            case .base: return "~140 MB"
            case .small: return "~460 MB"
            case .largeTurbo: return "~3 GB"
            }
        }

        var isBundled: Bool {
            self == .tiny
        }
    }

    // MARK: - Languages

    enum WhisperLanguage: String, CaseIterable, Identifiable {
        case auto = "auto"
        case english = "en"
        case spanish = "es"
        case french = "fr"
        case german = "de"
        case italian = "it"
        case portuguese = "pt"
        case dutch = "nl"
        case russian = "ru"
        case chinese = "zh"
        case japanese = "ja"
        case korean = "ko"
        case arabic = "ar"
        case hindi = "hi"

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .auto: return "Auto-detect"
            case .english: return "English"
            case .spanish: return "Spanish"
            case .french: return "French"
            case .german: return "German"
            case .italian: return "Italian"
            case .portuguese: return "Portuguese"
            case .dutch: return "Dutch"
            case .russian: return "Russian"
            case .chinese: return "Chinese"
            case .japanese: return "Japanese"
            case .korean: return "Korean"
            case .arabic: return "Arabic"
            case .hindi: return "Hindi"
            }
        }
    }
}

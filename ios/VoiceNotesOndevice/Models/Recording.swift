import Foundation
import SwiftData

/// SwiftData model representing a recording with its transcription
@Model
final class Recording {

    // MARK: - Identity

    /// Unique identifier
    @Attribute(.unique)
    var id: UUID

    // MARK: - Metadata

    /// Display title for the recording
    var title: String

    /// Creation timestamp
    var createdAt: Date

    /// Last modification timestamp
    var updatedAt: Date

    // MARK: - Audio Properties

    /// Duration in seconds
    var duration: TimeInterval = 0

    /// Relative path from app's documents directory
    /// Example: "recordings/abc123.m4a"
    var audioFileName: String?

    /// File size in bytes (for storage management UI)
    var audioFileSize: Int64 = 0

    // MARK: - Source Information

    /// How this recording was created
    var sourceTypeRaw: String

    /// Original filename if imported
    var originalFileName: String?

    // MARK: - Transcription State

    /// Current transcription status
    var statusRaw: String

    /// ISO 639-1 language code (e.g., "en", "es", "ja")
    /// nil = auto-detect
    var language: String?

    /// Detected language after transcription completes
    var detectedLanguage: String?

    /// Error message if transcription failed
    var errorMessage: String?

    /// Progress 0.0 - 1.0 during transcription
    var transcriptionProgress: Double = 0

    /// Whether this recording is marked as favorite
    var isFavorite: Bool = false

    /// Date when recording was moved to recycle bin (nil = not deleted)
    var deletedAt: Date?

    /// The Whisper model used for transcription (e.g., "openai_whisper-tiny")
    /// nil if not yet transcribed or transcribed with legacy version
    var transcriptionModel: String?

    /// The transcription engine used (whisperKit or appleSpeech)
    /// nil if not yet transcribed or transcribed with legacy version
    var transcriptionEngine: String?

    // MARK: - Relationships

    /// Transcript segments with timestamps
    @Relationship(deleteRule: .cascade, inverse: \TranscriptSegment.recording)
    var segments: [TranscriptSegment]

    // MARK: - Computed Properties

    /// Source type enum wrapper
    var sourceType: SourceType {
        get { SourceType(rawValue: sourceTypeRaw) ?? .microphone }
        set { sourceTypeRaw = newValue.rawValue }
    }

    /// Status enum wrapper
    var status: TranscriptionStatus {
        get { TranscriptionStatus(rawValue: statusRaw) ?? .pending }
        set { statusRaw = newValue.rawValue }
    }

    /// Whether transcription is complete with segments
    var isTranscribed: Bool {
        status == .completed && !segments.isEmpty
    }

    /// Full URL to the audio file
    var audioFileURL: URL? {
        guard let fileName = audioFileName else { return nil }
        return FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(fileName)
    }

    /// Localized display name for the language
    var displayLanguage: String {
        let code = detectedLanguage ?? language ?? "en"
        return Locale.current.localizedString(forLanguageCode: code) ?? code
    }

    /// Formatted duration string (HH:MM:SS)
    var formattedDuration: String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60
        let seconds = Int(duration) % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%d:%02d", minutes, seconds)
        }
    }

    /// Cached sorted segments to avoid repeated sorting
    /// Note: SwiftData models can't have stored computed properties with caching,
    /// so we rely on the caller to cache if needed for performance-critical paths
    var sortedSegments: [TranscriptSegment] {
        segments.sorted { $0.startTime < $1.startTime }
    }

    /// Combined transcript text from all segments
    var fullTranscript: String {
        sortedSegments
            .map(\.text)
            .joined(separator: " ")
    }

    /// Formatted file size string
    var formattedFileSize: String {
        ByteCountFormatter.string(fromByteCount: audioFileSize, countStyle: .file)
    }

    /// Display name for the model used in transcription
    var transcriptionModelDisplayName: String? {
        guard let model = transcriptionModel else { return nil }
        switch model {
        case "openai_whisper-tiny": return "Fast"
        case "openai_whisper-base": return "Balanced"
        case "openai_whisper-small": return "Accurate"
        default: return model
        }
    }

    /// Whether Apple Speech fallback was used (less accurate than Whisper)
    var usedAppleSpeechFallback: Bool {
        transcriptionEngine == "appleSpeech"
    }

    /// Display name for the transcription engine
    var transcriptionEngineDisplayName: String? {
        guard let engine = transcriptionEngine else { return nil }
        switch engine {
        case "whisperKit": return "Whisper AI"
        case "appleSpeech": return "Apple Speech"
        default: return engine
        }
    }

    /// Check if re-transcription is available (audio file exists)
    var canRetranscribe: Bool {
        status == .completed && audioFileURL != nil
    }

    /// Check if a better model is available for re-transcription (for "Improve" banner)
    var canImproveTranscription: Bool {
        guard canRetranscribe else { return false }
        // Can improve if using Tiny or Base (Small is the best)
        guard let model = transcriptionModel else { return true } // Legacy transcription, can improve
        return model != "openai_whisper-small"
    }

    /// Get the next better model for improvement
    var nextBetterModel: String? {
        guard canImproveTranscription else { return nil }
        guard let model = transcriptionModel else { return "openai_whisper-base" }
        switch model {
        case "openai_whisper-tiny": return "openai_whisper-base"
        case "openai_whisper-base": return "openai_whisper-small"
        default: return nil
        }
    }

    /// Whether this recording is in the recycle bin
    var isDeleted: Bool {
        deletedAt != nil
    }

    /// Days remaining before permanent deletion (nil if not deleted or recycle bin disabled)
    var daysUntilPermanentDeletion: Int? {
        guard let deletedAt = deletedAt else { return nil }
        let retentionDays = UserDefaults.standard.integer(forKey: AppConstants.UserDefaultsKeys.recycleBinRetentionDays)
        guard retentionDays > 0 else { return nil }

        let expirationDate = Calendar.current.date(byAdding: .day, value: retentionDays, to: deletedAt) ?? deletedAt
        let daysRemaining = Calendar.current.dateComponents([.day], from: Date(), to: expirationDate).day ?? 0
        return max(0, daysRemaining)
    }

    // MARK: - Initialization

    init(
        title: String,
        duration: TimeInterval = 0,
        sourceType: SourceType = .microphone,
        language: String? = nil
    ) {
        self.id = UUID()
        self.title = title
        self.createdAt = Date()
        self.updatedAt = Date()
        self.duration = duration
        self.audioFileSize = 0
        self.sourceTypeRaw = sourceType.rawValue
        self.language = language
        self.statusRaw = TranscriptionStatus.pending.rawValue
        self.transcriptionProgress = 0
        self.isFavorite = false
        self.segments = []
    }
}

// MARK: - Hashable

extension Recording: Hashable {
    static func == (lhs: Recording, rhs: Recording) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

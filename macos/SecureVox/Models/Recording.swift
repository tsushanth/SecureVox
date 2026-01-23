import Foundation
import SwiftData

/// A voice recording with optional transcription
@Model
final class Recording {

    // MARK: - Properties

    @Attribute(.unique) var id: UUID
    var title: String
    var createdAt: Date
    var updatedAt: Date
    var duration: TimeInterval
    var audioFileName: String?
    var audioFileSize: Int64
    var transcriptionStatus: TranscriptionStatus
    var transcriptionProgress: Double
    var sourceType: SourceType
    var isFavorite: Bool
    var isDeleted: Bool
    var deletedAt: Date?

    // Transcription metadata
    var transcriptionModel: String?
    var transcriptionEngine: String?
    var detectedLanguage: String?
    var transcriptionError: String?
    var originalFileName: String?
    var language: String?

    // MARK: - Relationships

    @Relationship(deleteRule: .cascade, inverse: \TranscriptSegment.recording)
    var segments: [TranscriptSegment]?

    // MARK: - Computed Properties

    var fullTranscript: String {
        guard let segments = segments, !segments.isEmpty else { return "" }
        return segments
            .sorted { $0.startTime < $1.startTime }
            .map { $0.text }
            .joined(separator: " ")
    }

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

    var formattedDate: String {
        let formatter = DateFormatter()
        let calendar = Calendar.current

        if calendar.isDateInToday(createdAt) {
            formatter.dateFormat = "'Today' HH:mm"
        } else if calendar.isDateInYesterday(createdAt) {
            formatter.dateFormat = "'Yesterday' HH:mm"
        } else if calendar.isDate(createdAt, equalTo: Date(), toGranularity: .year) {
            formatter.dateFormat = "MMM d, HH:mm"
        } else {
            formatter.dateFormat = "MMM d, yyyy HH:mm"
        }

        return formatter.string(from: createdAt)
    }

    var formattedFileSize: String {
        ByteCountFormatter.string(fromByteCount: audioFileSize, countStyle: .file)
    }

    var hasAudio: Bool {
        audioFileName != nil && audioFileSize > 0
    }

    var hasTranscript: Bool {
        guard let segments = segments else { return false }
        return !segments.isEmpty
    }

    var daysUntilPermanentDeletion: Int? {
        guard isDeleted, let deletedAt = deletedAt else { return nil }
        // Read directly from UserDefaults to avoid MainActor isolation issues
        let retentionDays = UserDefaults.standard.integer(forKey: "recycleBinRetentionDays")
        guard retentionDays > 0 else { return nil }

        let expirationDate = Calendar.current.date(byAdding: .day, value: retentionDays, to: deletedAt) ?? Date()
        let days = Calendar.current.dateComponents([.day], from: Date(), to: expirationDate).day ?? 0
        return max(0, days)
    }

    var audioURL: URL? {
        guard let audioFileName = audioFileName else { return nil }
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documentsURL
            .appendingPathComponent(AppConstants.Storage.recordingsDirectory)
            .appendingPathComponent(audioFileName)
    }

    // MARK: - Initialization

    init(
        id: UUID = UUID(),
        title: String,
        createdAt: Date = Date(),
        updatedAt: Date = Date(),
        duration: TimeInterval = 0,
        audioFileName: String? = nil,
        audioFileSize: Int64 = 0,
        transcriptionStatus: TranscriptionStatus = .pending,
        transcriptionProgress: Double = 0,
        sourceType: SourceType = .recorded,
        isFavorite: Bool = false,
        isDeleted: Bool = false,
        deletedAt: Date? = nil,
        language: String? = nil,
        segments: [TranscriptSegment]? = nil
    ) {
        self.id = id
        self.title = title
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.duration = duration
        self.audioFileName = audioFileName
        self.audioFileSize = audioFileSize
        self.transcriptionStatus = transcriptionStatus
        self.transcriptionProgress = transcriptionProgress
        self.sourceType = sourceType
        self.isFavorite = isFavorite
        self.isDeleted = isDeleted
        self.deletedAt = deletedAt
        self.language = language
        self.segments = segments
    }
}

// MARK: - Transcription Status

enum TranscriptionStatus: String, Codable {
    case pending = "pending"
    case inProgress = "in_progress"
    case completed = "completed"
    case failed = "failed"

    var displayName: String {
        switch self {
        case .pending: return "Pending"
        case .inProgress: return "Transcribing..."
        case .completed: return "Completed"
        case .failed: return "Failed"
        }
    }

    var icon: String {
        switch self {
        case .pending: return "clock"
        case .inProgress: return "waveform"
        case .completed: return "checkmark.circle"
        case .failed: return "exclamationmark.triangle"
        }
    }
}

// MARK: - Source Type

enum SourceType: String, Codable {
    case recorded = "recorded"
    case imported = "imported"
    case meeting = "meeting"
    case quick = "quick"

    var displayName: String {
        switch self {
        case .recorded: return "Recorded"
        case .imported: return "Imported"
        case .meeting: return "Meeting"
        case .quick: return "Quick"
        }
    }

    var icon: String {
        switch self {
        case .recorded: return "mic"
        case .imported: return "square.and.arrow.down"
        case .meeting: return "video"
        case .quick: return "bolt"
        }
    }
}

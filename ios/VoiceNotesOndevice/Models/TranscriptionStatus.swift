import Foundation

/// Status of a recording's transcription process
enum TranscriptionStatus: String, Codable, CaseIterable {
    case pending = "pending"
    case processing = "processing"
    case completed = "completed"
    case failed = "failed"

    /// Human-readable display name
    var displayName: String {
        switch self {
        case .pending: return "Waiting"
        case .processing: return "Transcribing"
        case .completed: return "Completed"
        case .failed: return "Failed"
        }
    }

    /// SF Symbol icon name
    var iconName: String {
        switch self {
        case .pending: return "clock"
        case .processing: return "arrow.triangle.2.circlepath"
        case .completed: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        }
    }

    /// Icon color
    var iconColorName: String {
        switch self {
        case .pending: return "secondary"
        case .processing: return "blue"
        case .completed: return "green"
        case .failed: return "red"
        }
    }
}

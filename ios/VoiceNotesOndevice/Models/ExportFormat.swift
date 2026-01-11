import Foundation

/// Available export formats for transcripts
enum ExportFormat: String, CaseIterable, Identifiable {
    case txt = "txt"
    case srt = "srt"
    case vtt = "vtt"

    var id: String { rawValue }

    /// Human-readable display name
    var displayName: String {
        switch self {
        case .txt: return "Plain Text (.txt)"
        case .srt: return "SubRip Subtitle (.srt)"
        case .vtt: return "WebVTT (.vtt)"
        }
    }

    /// Short name for UI
    var shortName: String {
        rawValue.uppercased()
    }

    /// File extension
    var fileExtension: String { rawValue }

    /// MIME type for sharing
    var mimeType: String {
        switch self {
        case .txt: return "text/plain"
        case .srt: return "application/x-subrip"
        case .vtt: return "text/vtt"
        }
    }

    /// Description of the format
    var formatDescription: String {
        switch self {
        case .txt:
            return "Simple text without timestamps. Best for reading and editing."
        case .srt:
            return "Industry-standard subtitle format. Compatible with most video editors."
        case .vtt:
            return "Web-compatible subtitle format. Works with HTML5 video players."
        }
    }
}

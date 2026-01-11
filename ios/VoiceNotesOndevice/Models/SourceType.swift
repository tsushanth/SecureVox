import Foundation

/// How a recording was created
enum SourceType: String, Codable, CaseIterable {
    case microphone = "microphone"
    case audioImport = "audio_import"
    case videoImport = "video_import"

    /// Human-readable display name
    var displayName: String {
        switch self {
        case .microphone: return "Recording"
        case .audioImport: return "Audio Import"
        case .videoImport: return "Video Import"
        }
    }

    /// SF Symbol icon name
    var iconName: String {
        switch self {
        case .microphone: return "mic.fill"
        case .audioImport: return "waveform"
        case .videoImport: return "video.fill"
        }
    }
}

import Foundation
import SwiftData

/// A segment of transcribed text with timing information
@Model
final class TranscriptSegment {

    // MARK: - Properties

    @Attribute(.unique) var id: UUID
    var text: String
    var startTime: TimeInterval
    var endTime: TimeInterval
    var confidence: Float
    var speakerLabel: String?

    // MARK: - Relationships

    var recording: Recording?

    // MARK: - Computed Properties

    var duration: TimeInterval {
        endTime - startTime
    }

    var formattedStartTime: String {
        formatTime(startTime)
    }

    var formattedEndTime: String {
        formatTime(endTime)
    }

    var formattedTimeRange: String {
        "\(formattedStartTime) - \(formattedEndTime)"
    }

    /// SRT format timestamp for start time (00:00:00,000)
    var srtStartTimestamp: String {
        formatSRTTime(startTime)
    }

    /// SRT format timestamp for end time (00:00:00,000)
    var srtEndTimestamp: String {
        formatSRTTime(endTime)
    }

    /// VTT format timestamp for start time (00:00:00.000)
    var vttStartTimestamp: String {
        formatVTTTime(startTime)
    }

    /// VTT format timestamp for end time (00:00:00.000)
    var vttEndTimestamp: String {
        formatVTTTime(endTime)
    }

    /// Whether this segment has valid timing information
    var hasValidTiming: Bool {
        startTime >= 0 && endTime >= startTime
    }

    // MARK: - Initialization

    init(
        id: UUID = UUID(),
        text: String,
        startTime: TimeInterval,
        endTime: TimeInterval,
        confidence: Float = 1.0,
        speakerLabel: String? = nil,
        recording: Recording? = nil
    ) {
        self.id = id
        self.text = text
        self.startTime = startTime
        self.endTime = endTime
        self.confidence = confidence
        self.speakerLabel = speakerLabel
        self.recording = recording
    }

    // MARK: - Helpers

    private func formatTime(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    /// Format time for SRT subtitle format (00:00:00,000)
    private func formatSRTTime(_ time: TimeInterval) -> String {
        let normalizedTime = max(0, time)
        let hours = Int(normalizedTime) / 3600
        let minutes = (Int(normalizedTime) % 3600) / 60
        let seconds = Int(normalizedTime) % 60
        let milliseconds = Int((normalizedTime.truncatingRemainder(dividingBy: 1)) * 1000)
        return String(format: "%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds)
    }

    /// Format time for VTT subtitle format (00:00:00.000)
    private func formatVTTTime(_ time: TimeInterval) -> String {
        let normalizedTime = max(0, time)
        let hours = Int(normalizedTime) / 3600
        let minutes = (Int(normalizedTime) % 3600) / 60
        let seconds = Int(normalizedTime) % 60
        let milliseconds = Int((normalizedTime.truncatingRemainder(dividingBy: 1)) * 1000)
        return String(format: "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
    }
}

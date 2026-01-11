import Foundation
import SwiftData

/// SwiftData model representing a single timestamped segment of transcription
@Model
final class TranscriptSegment {

    // MARK: - Identity

    /// Unique identifier
    @Attribute(.unique)
    var id: UUID

    // MARK: - Timing

    /// Start time in seconds from beginning of audio
    var startTime: TimeInterval

    /// End time in seconds from beginning of audio
    var endTime: TimeInterval

    // MARK: - Content

    /// Transcribed text for this segment
    var text: String

    /// Confidence score 0.0 - 1.0 (if available from Whisper)
    var confidence: Double?

    // MARK: - Relationship

    /// Parent recording
    var recording: Recording?

    // MARK: - Computed Properties

    /// Duration of this segment
    var duration: TimeInterval {
        endTime - startTime
    }

    /// Formatted start timestamp for display (MM:SS)
    var formattedStartTime: String {
        formatTimestamp(startTime)
    }

    /// Formatted as "00:00:00,000" for SRT
    var srtStartTimestamp: String {
        formatSRTTimestamp(startTime)
    }

    /// Formatted as "00:00:00,000" for SRT
    var srtEndTimestamp: String {
        formatSRTTimestamp(endTime)
    }

    /// Formatted as "00:00:00.000" for VTT
    var vttStartTimestamp: String {
        formatVTTTimestamp(startTime)
    }

    /// Formatted as "00:00:00.000" for VTT
    var vttEndTimestamp: String {
        formatVTTTimestamp(endTime)
    }

    // MARK: - Initialization

    init(
        startTime: TimeInterval,
        endTime: TimeInterval,
        text: String,
        confidence: Double? = nil
    ) {
        self.id = UUID()
        self.startTime = startTime
        self.endTime = endTime
        self.text = text
        self.confidence = confidence
    }

    // MARK: - Private Helpers

    private func formatTimestamp(_ time: TimeInterval) -> String {
        let minutes = Int(time) / 60
        let seconds = Int(time) % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    private func formatSRTTimestamp(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60
        let milliseconds = Int((time.truncatingRemainder(dividingBy: 1)) * 1000)
        return String(format: "%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds)
    }

    private func formatVTTTimestamp(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60
        let milliseconds = Int((time.truncatingRemainder(dividingBy: 1)) * 1000)
        return String(format: "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
    }
}

// MARK: - Hashable

extension TranscriptSegment: Hashable {
    static func == (lhs: TranscriptSegment, rhs: TranscriptSegment) -> Bool {
        lhs.id == rhs.id
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
    }
}

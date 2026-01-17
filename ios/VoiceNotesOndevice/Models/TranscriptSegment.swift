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

    /// Creates a new transcript segment with validated timing
    /// - Parameters:
    ///   - startTime: Start time in seconds (must be >= 0)
    ///   - endTime: End time in seconds (must be >= startTime)
    ///   - text: Transcribed text for this segment
    ///   - confidence: Optional confidence score (0.0 - 1.0)
    /// - Note: If endTime < startTime, values will be swapped to ensure validity
    init(
        startTime: TimeInterval,
        endTime: TimeInterval,
        text: String,
        confidence: Double? = nil
    ) {
        self.id = UUID()

        // Validate and normalize times - ensure non-negative
        let validStartTime = max(0, startTime)
        let validEndTime = max(0, endTime)

        // Ensure startTime <= endTime by swapping if necessary
        if validStartTime <= validEndTime {
            self.startTime = validStartTime
            self.endTime = validEndTime
        } else {
            // Swap times if endTime is before startTime
            self.startTime = validEndTime
            self.endTime = validStartTime
        }

        self.text = text

        // Validate confidence is in valid range (0.0 - 1.0)
        if let conf = confidence {
            self.confidence = min(1.0, max(0.0, conf))
        } else {
            self.confidence = nil
        }
    }

    // MARK: - Validation

    /// Check if the segment has valid timing
    var hasValidTiming: Bool {
        startTime >= 0 && endTime >= startTime
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

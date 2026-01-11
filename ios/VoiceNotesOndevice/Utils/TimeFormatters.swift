import Foundation

/// Utilities for formatting time values
enum TimeFormatters {

    // MARK: - Duration Formatting

    /// Format duration as "MM:SS" or "H:MM:SS"
    static func formatDuration(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60
        let seconds = Int(duration) % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%d:%02d", minutes, seconds)
        }
    }

    /// Format duration as "X min" or "X hr X min"
    static func formatDurationText(_ duration: TimeInterval) -> String {
        let hours = Int(duration) / 3600
        let minutes = (Int(duration) % 3600) / 60

        if hours > 0 {
            if minutes > 0 {
                return "\(hours) hr \(minutes) min"
            } else {
                return "\(hours) hr"
            }
        } else if minutes > 0 {
            return "\(minutes) min"
        } else {
            return "< 1 min"
        }
    }

    // MARK: - Timestamp Formatting

    /// Format timestamp for SRT files (HH:MM:SS,mmm)
    static func formatSRTTimestamp(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60
        let milliseconds = Int((time.truncatingRemainder(dividingBy: 1)) * 1000)

        return String(format: "%02d:%02d:%02d,%03d", hours, minutes, seconds, milliseconds)
    }

    /// Format timestamp for VTT files (HH:MM:SS.mmm)
    static func formatVTTTimestamp(_ time: TimeInterval) -> String {
        let hours = Int(time) / 3600
        let minutes = (Int(time) % 3600) / 60
        let seconds = Int(time) % 60
        let milliseconds = Int((time.truncatingRemainder(dividingBy: 1)) * 1000)

        return String(format: "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds)
    }

    /// Format timestamp for display (M:SS or H:MM:SS)
    static func formatTimestamp(_ time: TimeInterval) -> String {
        formatDuration(time)
    }

    // MARK: - Relative Time

    /// Format date as relative time ("Just now", "5 min ago", "Yesterday", etc.)
    static func formatRelativeTime(_ date: Date) -> String {
        let now = Date()
        let interval = now.timeIntervalSince(date)

        if interval < 60 {
            return "Just now"
        } else if interval < 3600 {
            let minutes = Int(interval / 60)
            return "\(minutes) min ago"
        } else if interval < 86400 {
            let hours = Int(interval / 3600)
            return hours == 1 ? "1 hour ago" : "\(hours) hours ago"
        } else if interval < 172800 {
            return "Yesterday"
        } else {
            let formatter = DateFormatter()
            formatter.dateStyle = .medium
            formatter.timeStyle = .none
            return formatter.string(from: date)
        }
    }
}

// MARK: - TimeInterval Extensions

extension TimeInterval {

    /// Formatted as "MM:SS" or "H:MM:SS"
    var formattedDuration: String {
        TimeFormatters.formatDuration(self)
    }

    /// Formatted as "X min" or "X hr X min"
    var formattedDurationText: String {
        TimeFormatters.formatDurationText(self)
    }

    /// Formatted as SRT timestamp
    var srtTimestamp: String {
        TimeFormatters.formatSRTTimestamp(self)
    }

    /// Formatted as VTT timestamp
    var vttTimestamp: String {
        TimeFormatters.formatVTTTimestamp(self)
    }
}

// MARK: - Date Extensions

extension Date {

    /// Formatted as relative time
    var relativeTime: String {
        TimeFormatters.formatRelativeTime(self)
    }
}

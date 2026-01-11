import Foundation

/// Manager for App Group shared data between main app and extensions
/// This is a copy of the main app's AppGroupManager for the widget extension
enum AppGroupManager {

    // MARK: - Constants

    /// App Group identifier - must match the identifier in entitlements
    static let appGroupIdentifier = "group.com.voicenotes.ondevice"

    // MARK: - Shared Defaults

    /// UserDefaults shared between app and extensions
    static var sharedDefaults: UserDefaults? {
        UserDefaults(suiteName: appGroupIdentifier)
    }

    // MARK: - Keys

    enum Keys {
        static let recentRecordingsCount = "recentRecordingsCount"
        static let totalRecordingsCount = "totalRecordingsCount"
        static let totalDuration = "totalDuration"
        static let lastRecordingTitle = "lastRecordingTitle"
        static let lastRecordingDate = "lastRecordingDate"
        static let lastRecordingDuration = "lastRecordingDuration"
        static let isRecording = "isRecording"
        static let currentRecordingDuration = "currentRecordingDuration"
    }

    // MARK: - Widget Data

    /// Data structure for widget display
    struct WidgetData: Codable {
        let totalRecordings: Int
        let totalDuration: TimeInterval
        let lastRecordingTitle: String?
        let lastRecordingDate: Date?
        let lastRecordingDuration: TimeInterval?
        let isRecording: Bool
        let currentRecordingDuration: TimeInterval?

        static var empty: WidgetData {
            WidgetData(
                totalRecordings: 0,
                totalDuration: 0,
                lastRecordingTitle: nil,
                lastRecordingDate: nil,
                lastRecordingDuration: nil,
                isRecording: false,
                currentRecordingDuration: nil
            )
        }
    }

    // MARK: - Read Methods

    /// Read widget data (for widget extension)
    static func readWidgetData() -> WidgetData {
        guard let defaults = sharedDefaults else { return .empty }

        return WidgetData(
            totalRecordings: defaults.integer(forKey: Keys.totalRecordingsCount),
            totalDuration: defaults.double(forKey: Keys.totalDuration),
            lastRecordingTitle: defaults.string(forKey: Keys.lastRecordingTitle),
            lastRecordingDate: defaults.object(forKey: Keys.lastRecordingDate) as? Date,
            lastRecordingDuration: defaults.object(forKey: Keys.lastRecordingDuration) as? TimeInterval,
            isRecording: defaults.bool(forKey: Keys.isRecording),
            currentRecordingDuration: defaults.object(forKey: Keys.currentRecordingDuration) as? TimeInterval
        )
    }
}

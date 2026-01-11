import Foundation

/// Manager for App Group shared data between main app and extensions
enum AppGroupManager {

    // MARK: - Constants

    /// App Group identifier - must match the identifier in entitlements
    static let appGroupIdentifier = "group.com.voicenotes.ondevice"

    // MARK: - Shared Defaults

    /// UserDefaults shared between app and extensions
    static var sharedDefaults: UserDefaults? {
        UserDefaults(suiteName: appGroupIdentifier)
    }

    // MARK: - Shared Container

    /// URL for the shared container directory
    static var sharedContainerURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)
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

    // MARK: - Read/Write Methods

    /// Update widget data from main app
    static func updateWidgetData(_ data: WidgetData) {
        guard let defaults = sharedDefaults else { return }

        defaults.set(data.totalRecordings, forKey: Keys.totalRecordingsCount)
        defaults.set(data.totalDuration, forKey: Keys.totalDuration)
        defaults.set(data.lastRecordingTitle, forKey: Keys.lastRecordingTitle)
        defaults.set(data.lastRecordingDate, forKey: Keys.lastRecordingDate)
        defaults.set(data.lastRecordingDuration, forKey: Keys.lastRecordingDuration)
        defaults.set(data.isRecording, forKey: Keys.isRecording)
        defaults.set(data.currentRecordingDuration, forKey: Keys.currentRecordingDuration)
    }

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

    /// Update recording state (called frequently during recording)
    static func updateRecordingState(isRecording: Bool, duration: TimeInterval?) {
        guard let defaults = sharedDefaults else { return }

        defaults.set(isRecording, forKey: Keys.isRecording)
        if let duration = duration {
            defaults.set(duration, forKey: Keys.currentRecordingDuration)
        }
    }
}

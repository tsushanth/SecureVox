import AppIntents
import SwiftUI

// MARK: - Start Recording Intent

/// Siri Shortcut to start a new voice recording
struct StartRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Start Recording"
    static var description = IntentDescription("Start a new voice recording in SecureVox")

    static var openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        // Post notification to start recording when app opens
        NotificationCenter.default.post(name: .startRecordingFromIntent, object: nil)

        return .result(dialog: "Starting recording...")
    }
}

// MARK: - Show Recordings Intent

/// Siri Shortcut to show all recordings
struct ShowRecordingsIntent: AppIntent {
    static var title: LocalizedStringResource = "Show Recordings"
    static var description = IntentDescription("Open SecureVox and show your recordings")

    static var openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        // Post notification to show recordings
        NotificationCenter.default.post(name: .showRecordingsFromIntent, object: nil)

        return .result(dialog: "Opening your recordings...")
    }
}

// MARK: - Show Recent Recording Intent

/// Siri Shortcut to show the most recent recording
struct ShowRecentRecordingIntent: AppIntent {
    static var title: LocalizedStringResource = "Show Recent Recording"
    static var description = IntentDescription("Open SecureVox and show your most recent recording")

    static var openAppWhenRun: Bool = true

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        // Post notification to show recent recording
        NotificationCenter.default.post(name: .showRecentRecordingFromIntent, object: nil)

        return .result(dialog: "Opening your recent recording...")
    }
}

// MARK: - App Shortcuts Provider

/// Provides app shortcuts for Siri and Shortcuts app
struct SecureVoxShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: StartRecordingIntent(),
            phrases: [
                "Start recording in \(.applicationName)",
                "Record with \(.applicationName)",
                "New recording in \(.applicationName)",
                "Start \(.applicationName) recording",
                "Record voice note in \(.applicationName)"
            ],
            shortTitle: "Start Recording",
            systemImageName: "mic.fill"
        )

        AppShortcut(
            intent: ShowRecordingsIntent(),
            phrases: [
                "Show my recordings in \(.applicationName)",
                "Open \(.applicationName) recordings",
                "Show \(.applicationName) notes",
                "My voice notes in \(.applicationName)"
            ],
            shortTitle: "Show Recordings",
            systemImageName: "list.bullet"
        )

        AppShortcut(
            intent: ShowRecentRecordingIntent(),
            phrases: [
                "Show recent recording in \(.applicationName)",
                "Last recording in \(.applicationName)",
                "Open last note in \(.applicationName)"
            ],
            shortTitle: "Recent Recording",
            systemImageName: "clock"
        )
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let startRecordingFromIntent = Notification.Name("startRecordingFromIntent")
    static let showRecordingsFromIntent = Notification.Name("showRecordingsFromIntent")
    static let showRecentRecordingFromIntent = Notification.Name("showRecentRecordingFromIntent")
}

import Foundation
import ServiceManagement
import AppKit
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "LaunchAtLogin")

/// Service for managing Launch at Login functionality using SMAppService
@MainActor
class LaunchAtLoginService: ObservableObject {

    // MARK: - Singleton

    static let shared = LaunchAtLoginService()

    // MARK: - Published Properties

    @Published private(set) var isEnabled: Bool = false
    @Published private(set) var status: SMAppService.Status = .notRegistered

    // MARK: - Initialization

    private init() {
        updateStatus()
    }

    // MARK: - Public Methods

    /// Enable or disable launch at login
    func setEnabled(_ enabled: Bool) {
        do {
            if enabled {
                try SMAppService.mainApp.register()
            } else {
                try SMAppService.mainApp.unregister()
            }
        } catch {
            logger.error("Failed to \(enabled ? "enable" : "disable") launch at login: \(error.localizedDescription)")
        }

        updateStatus()
    }

    /// Toggle launch at login
    func toggle() {
        setEnabled(!isEnabled)
    }

    /// Refresh the current status
    func updateStatus() {
        status = SMAppService.mainApp.status
        isEnabled = (status == .enabled)
    }

    // MARK: - Status Description

    var statusDescription: String {
        switch status {
        case .notRegistered:
            return "Not registered"
        case .enabled:
            return "Enabled"
        case .requiresApproval:
            return "Requires approval in System Settings"
        case .notFound:
            return "App not found"
        @unknown default:
            return "Unknown status"
        }
    }

    var requiresUserAction: Bool {
        status == .requiresApproval
    }

    /// Open System Settings to the Login Items pane
    func openLoginItemsSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.LoginItems-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
    }
}

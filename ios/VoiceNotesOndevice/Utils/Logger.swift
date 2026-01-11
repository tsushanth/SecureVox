import Foundation
import os.log

/// Centralized logging utility using OSLog
enum Logger {

    // MARK: - Log Categories

    private static let subsystem = Bundle.main.bundleIdentifier ?? "com.voicenotes.ondevice"

    /// General app logs
    static let general = os.Logger(subsystem: subsystem, category: "general")

    /// Audio recording logs
    static let audio = os.Logger(subsystem: subsystem, category: "audio")

    /// Transcription/CoreML logs
    static let transcription = os.Logger(subsystem: subsystem, category: "transcription")

    /// Data/persistence logs
    static let data = os.Logger(subsystem: subsystem, category: "data")

    /// Import/export logs
    static let io = os.Logger(subsystem: subsystem, category: "io")

    /// UI/View logs
    static let ui = os.Logger(subsystem: subsystem, category: "ui")

    // MARK: - Convenience Methods

    /// Log info message
    static func info(_ message: String, category: os.Logger = general) {
        category.info("\(message)")
    }

    /// Log debug message
    static func debug(_ message: String, category: os.Logger = general) {
        category.debug("\(message)")
    }

    /// Log warning message
    static func warning(_ message: String, category: os.Logger = general) {
        category.warning("\(message)")
    }

    /// Log error message
    static func error(_ message: String, category: os.Logger = general) {
        category.error("\(message)")
    }

    /// Log error with Error object
    static func error(_ error: Error, context: String = "", category: os.Logger = general) {
        if context.isEmpty {
            category.error("Error: \(error.localizedDescription)")
        } else {
            category.error("\(context): \(error.localizedDescription)")
        }
    }

    // MARK: - Signpost Support

    /// Begin a signpost interval for performance tracking
    static func signpostBegin(
        _ name: StaticString,
        category: os.Logger = general
    ) -> OSSignpostID {
        let id = OSSignpostID(log: OSLog(subsystem: subsystem, category: "performance"))
        os_signpost(.begin, log: OSLog(subsystem: subsystem, category: "performance"), name: name, signpostID: id)
        return id
    }

    /// End a signpost interval
    static func signpostEnd(
        _ name: StaticString,
        id: OSSignpostID
    ) {
        os_signpost(.end, log: OSLog(subsystem: subsystem, category: "performance"), name: name, signpostID: id)
    }
}

// MARK: - Usage Examples
/*
 Logger.info("App launched", category: Logger.general)
 Logger.debug("Processing chunk 5 of 10", category: Logger.transcription)
 Logger.warning("Low storage space", category: Logger.data)
 Logger.error("Failed to save recording", category: Logger.audio)
 Logger.error(someError, context: "Recording failed", category: Logger.audio)

 // Performance tracking
 let id = Logger.signpostBegin("Transcription")
 // ... do work ...
 Logger.signpostEnd("Transcription", id: id)
 */

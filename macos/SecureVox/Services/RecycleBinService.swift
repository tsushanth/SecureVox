import Foundation
import SwiftData
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "RecycleBin")

/// Service for managing the recycle bin
@MainActor
class RecycleBinService {

    // MARK: - Singleton

    static let shared = RecycleBinService()

    private var modelContext: ModelContext?

    private init() {
        // Set default retention if not set
        if UserDefaults.standard.object(forKey: "recycleBinRetentionDays") == nil {
            UserDefaults.standard.set(30, forKey: "recycleBinRetentionDays")
        }
    }

    // MARK: - Configuration

    func configure(with modelContext: ModelContext) {
        self.modelContext = modelContext
    }

    // MARK: - Retention

    var retentionDays: Int {
        get {
            UserDefaults.standard.integer(forKey: "recycleBinRetentionDays")
        }
        set {
            UserDefaults.standard.set(newValue, forKey: "recycleBinRetentionDays")
        }
    }

    var isEnabled: Bool {
        retentionDays > 0
    }

    // MARK: - Cleanup

    /// Clean up expired recordings from recycle bin
    func cleanupExpiredRecordings() {
        guard let modelContext = modelContext, isEnabled else { return }

        let now = Date()

        do {
            // Fetch all deleted recordings
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate<Recording> { $0.isDeleted }
            )
            let deletedRecordings = try modelContext.fetch(descriptor)

            for recording in deletedRecordings {
                guard let deletedAt = recording.deletedAt else { continue }

                let expirationDate = Calendar.current.date(byAdding: .day, value: retentionDays, to: deletedAt) ?? now

                if now >= expirationDate {
                    // Delete the audio file if it exists
                    if let audioURL = recording.audioURL {
                        try? FileManager.default.removeItem(at: audioURL)
                    }

                    // Delete the recording from the database
                    modelContext.delete(recording)
                }
            }

            try modelContext.save()
        } catch {
            logger.error("Error cleaning up expired recordings: \(error.localizedDescription)")
        }
    }

    /// Permanently delete a recording immediately
    func permanentlyDelete(_ recording: Recording) {
        guard let modelContext = modelContext else { return }

        // Delete the audio file if it exists
        if let audioURL = recording.audioURL {
            try? FileManager.default.removeItem(at: audioURL)
        }

        // Delete the recording from the database
        modelContext.delete(recording)

        do {
            try modelContext.save()
        } catch {
            logger.error("Error permanently deleting recording: \(error.localizedDescription)")
        }
    }

    /// Empty the entire recycle bin
    func emptyRecycleBin() {
        guard let modelContext = modelContext else { return }

        do {
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate<Recording> { $0.isDeleted }
            )
            let deletedRecordings = try modelContext.fetch(descriptor)

            for recording in deletedRecordings {
                // Delete the audio file if it exists
                if let audioURL = recording.audioURL {
                    try? FileManager.default.removeItem(at: audioURL)
                }

                modelContext.delete(recording)
            }

            try modelContext.save()
        } catch {
            logger.error("Error emptying recycle bin: \(error.localizedDescription)")
        }
    }

    /// Restore a recording from the recycle bin
    func restore(_ recording: Recording) {
        recording.isDeleted = false
        recording.deletedAt = nil
        recording.updatedAt = Date()

        if let modelContext = modelContext {
            do {
                try modelContext.save()
            } catch {
                logger.error("Error restoring recording: \(error.localizedDescription)")
            }
        }
    }

    // MARK: - Calculations

    func daysUntilPermanentDeletion(deletedAt: Date?) -> Int? {
        guard isEnabled, let deletedAt = deletedAt else { return nil }

        let expirationDate = Calendar.current.date(byAdding: .day, value: retentionDays, to: deletedAt) ?? Date()
        let days = Calendar.current.dateComponents([.day], from: Date(), to: expirationDate).day ?? 0

        return max(0, days)
    }

    func formattedDaysRemaining(deletedAt: Date?) -> String {
        guard let days = daysUntilPermanentDeletion(deletedAt: deletedAt) else {
            return "Will be deleted immediately"
        }

        if days == 0 {
            return "Will be deleted today"
        } else if days == 1 {
            return "1 day remaining"
        } else {
            return "\(days) days remaining"
        }
    }

    // MARK: - Statistics

    func getRecycleBinCount() -> Int {
        guard let modelContext = modelContext else { return 0 }

        do {
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate<Recording> { $0.isDeleted }
            )
            return try modelContext.fetchCount(descriptor)
        } catch {
            return 0
        }
    }
}

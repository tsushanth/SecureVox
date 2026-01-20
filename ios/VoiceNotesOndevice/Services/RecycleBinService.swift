import Foundation
import SwiftData
import os.log

private let recycleBinLogger = os.Logger(subsystem: "com.voicenotes.ondevice", category: "RecycleBinService")

/// Service for managing the recycle bin and automatic cleanup of expired recordings
@MainActor
final class RecycleBinService {

    // MARK: - Singleton

    static let shared = RecycleBinService()

    private init() {}

    // MARK: - Cleanup

    /// Clean up expired recordings from the recycle bin
    /// - Parameters:
    ///   - modelContext: The SwiftData model context
    /// - Returns: Number of recordings permanently deleted
    @discardableResult
    func cleanupExpiredRecordings(modelContext: ModelContext) -> Int {
        let retentionDays = UserDefaults.standard.integer(forKey: AppConstants.UserDefaultsKeys.recycleBinRetentionDays)

        // If retention is 0 (disabled) or negative, don't auto-cleanup
        guard retentionDays > 0 else {
            recycleBinLogger.debug("Recycle bin is disabled (retention: \(retentionDays)), skipping cleanup")
            return 0
        }

        // Calculate expiration date
        guard let expirationDate = Calendar.current.date(byAdding: .day, value: -retentionDays, to: Date()) else {
            return 0
        }

        // Fetch expired recordings
        let descriptor = FetchDescriptor<Recording>(
            predicate: #Predicate<Recording> { recording in
                recording.deletedAt != nil
            }
        )

        do {
            let deletedRecordings = try modelContext.fetch(descriptor)

            var cleanedCount = 0
            for recording in deletedRecordings {
                // Check if this recording has expired
                if let deletedAt = recording.deletedAt, deletedAt < expirationDate {
                    // Delete audio file
                    if let audioURL = recording.audioFileURL {
                        do {
                            try FileManager.default.removeItem(at: audioURL)
                            recycleBinLogger.debug("Deleted audio file: \(audioURL.lastPathComponent)")
                        } catch {
                            recycleBinLogger.warning("Failed to delete audio file: \(error.localizedDescription)")
                        }
                    }

                    // Delete from database
                    modelContext.delete(recording)
                    cleanedCount += 1
                    recycleBinLogger.info("Permanently deleted expired recording: \(recording.title)")
                }
            }

            if cleanedCount > 0 {
                try modelContext.save()
                recycleBinLogger.info("Cleaned up \(cleanedCount) expired recording(s)")
            }

            return cleanedCount

        } catch {
            recycleBinLogger.error("Error during cleanup: \(error.localizedDescription)")
            return 0
        }
    }

    /// Move a recording to the recycle bin (soft delete)
    /// - Parameters:
    ///   - recording: The recording to delete
    ///   - modelContext: The SwiftData model context
    ///   - permanently: If true, delete permanently instead of moving to recycle bin
    func deleteRecording(_ recording: Recording, modelContext: ModelContext, permanently: Bool = false) throws {
        let retentionDays = UserDefaults.standard.integer(forKey: AppConstants.UserDefaultsKeys.recycleBinRetentionDays)

        // If recycle bin is disabled or permanently requested, delete immediately
        if retentionDays <= 0 || permanently {
            // Delete audio file
            if let audioURL = recording.audioFileURL {
                do {
                    try FileManager.default.removeItem(at: audioURL)
                } catch {
                    recycleBinLogger.warning("Failed to delete audio file: \(error.localizedDescription)")
                }
            }

            modelContext.delete(recording)
            try modelContext.save()
            recycleBinLogger.info("Permanently deleted recording: \(recording.title)")
        } else {
            // Soft delete - move to recycle bin
            recording.deletedAt = Date()
            recording.updatedAt = Date()
            try modelContext.save()
            recycleBinLogger.info("Moved recording to recycle bin: \(recording.title)")
        }
    }

    /// Restore a recording from the recycle bin
    func restoreRecording(_ recording: Recording, modelContext: ModelContext) throws {
        recording.deletedAt = nil
        recording.updatedAt = Date()
        try modelContext.save()
        recycleBinLogger.info("Restored recording: \(recording.title)")
    }

    /// Get count of recordings in recycle bin
    func getDeletedRecordingsCount(modelContext: ModelContext) -> Int {
        let descriptor = FetchDescriptor<Recording>(
            predicate: #Predicate<Recording> { $0.deletedAt != nil }
        )

        do {
            return try modelContext.fetchCount(descriptor)
        } catch {
            return 0
        }
    }

    /// Empty the entire recycle bin
    func emptyRecycleBin(modelContext: ModelContext) throws -> Int {
        let descriptor = FetchDescriptor<Recording>(
            predicate: #Predicate<Recording> { $0.deletedAt != nil }
        )

        let deletedRecordings = try modelContext.fetch(descriptor)
        var count = 0

        for recording in deletedRecordings {
            // Delete audio file
            if let audioURL = recording.audioFileURL {
                try? FileManager.default.removeItem(at: audioURL)
            }

            modelContext.delete(recording)
            count += 1
        }

        try modelContext.save()
        recycleBinLogger.info("Emptied recycle bin: \(count) recording(s) permanently deleted")
        return count
    }
}

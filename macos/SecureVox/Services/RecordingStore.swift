import Foundation
import SwiftData
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "RecordingStore")

/// Central data access layer for recordings
@MainActor
class RecordingStore: ObservableObject {

    // MARK: - Singleton

    static let shared = RecordingStore()

    // MARK: - Published Properties

    @Published private(set) var recordings: [Recording] = []
    @Published private(set) var deletedRecordings: [Recording] = []
    @Published var searchQuery = ""
    @Published var sortOption: SortOption = .newest
    @Published var statusFilter: TranscriptionStatus? = nil

    // MARK: - Private Properties

    private var modelContext: ModelContext?

    // MARK: - Initialization

    private init() {}

    func configure(with context: ModelContext) {
        self.modelContext = context
        Task {
            await fetchRecordings()
            await cleanupRecycleBin()
        }
    }

    // MARK: - Fetch

    func fetchRecordings() async {
        guard let context = modelContext else { return }

        do {
            var descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate { !$0.isDeleted }
            )

            // Apply sort
            switch sortOption {
            case .newest:
                descriptor.sortBy = [SortDescriptor(\.createdAt, order: .reverse)]
            case .oldest:
                descriptor.sortBy = [SortDescriptor(\.createdAt)]
            case .longest:
                descriptor.sortBy = [SortDescriptor(\.duration, order: .reverse)]
            case .shortest:
                descriptor.sortBy = [SortDescriptor(\.duration)]
            case .alphabetical:
                descriptor.sortBy = [SortDescriptor(\.title)]
            }

            var fetched = try context.fetch(descriptor)

            // Apply search filter (in-memory since transcript is computed)
            if !searchQuery.isEmpty {
                let query = searchQuery.lowercased()
                fetched = fetched.filter { recording in
                    recording.title.lowercased().contains(query) ||
                    recording.fullTranscript.lowercased().contains(query)
                }
            }

            // Apply status filter
            if let status = statusFilter {
                fetched = fetched.filter { $0.transcriptionStatus == status }
            }

            recordings = fetched

            // Fetch deleted recordings
            let deletedDescriptor = FetchDescriptor<Recording>(
                predicate: #Predicate { $0.isDeleted },
                sortBy: [SortDescriptor(\.deletedAt, order: .reverse)]
            )
            deletedRecordings = try context.fetch(deletedDescriptor)

        } catch {
            logger.error("Error fetching recordings: \(error.localizedDescription)")
        }
    }

    // MARK: - CRUD Operations

    func saveRecording(_ recording: Recording) async {
        guard let context = modelContext else { return }

        context.insert(recording)

        do {
            try context.save()
            await fetchRecordings()
        } catch {
            logger.error("Error saving recording: \(error.localizedDescription)")
        }
    }

    func updateRecording(_ recording: Recording) async {
        guard let context = modelContext else { return }

        do {
            try context.save()
            await fetchRecordings()
        } catch {
            logger.error("Error updating recording: \(error.localizedDescription)")
        }
    }

    func deleteRecording(_ recording: Recording, permanent: Bool = false) async {
        guard let context = modelContext else { return }

        if permanent {
            // Delete audio file
            deleteAudioFile(for: recording)
            context.delete(recording)
        } else {
            // Soft delete
            recording.isDeleted = true
            recording.deletedAt = Date()
        }

        do {
            try context.save()
            await fetchRecordings()
        } catch {
            logger.error("Error deleting recording: \(error.localizedDescription)")
        }
    }

    func restoreRecording(_ recording: Recording) async {
        recording.isDeleted = false
        recording.deletedAt = nil
        await updateRecording(recording)
    }

    func deleteAudioOnly(_ recording: Recording) async {
        deleteAudioFile(for: recording)
        recording.audioFileName = nil
        recording.audioFileSize = 0
        await updateRecording(recording)
    }

    // MARK: - Batch Operations

    func deleteAllRecordings() async {
        for recording in recordings {
            await deleteRecording(recording, permanent: true)
        }
    }

    func deleteAllAudioFiles() async {
        for recording in recordings {
            await deleteAudioOnly(recording)
        }
    }

    func emptyRecycleBin() async {
        for recording in deletedRecordings {
            await deleteRecording(recording, permanent: true)
        }
    }

    // MARK: - Recycle Bin Cleanup

    func cleanupRecycleBin() async {
        let retentionDays = UserDefaults.standard.integer(forKey: "recycleBinRetentionDays")
        guard retentionDays > 0 else { return }

        let cutoffDate = Calendar.current.date(byAdding: .day, value: -retentionDays, to: Date()) ?? Date()

        for recording in deletedRecordings {
            if let deletedAt = recording.deletedAt, deletedAt < cutoffDate {
                await deleteRecording(recording, permanent: true)
            }
        }
    }

    // MARK: - Helpers

    private func deleteAudioFile(for recording: Recording) {
        guard let audioFileName = recording.audioFileName else { return }

        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let audioURL = documentsURL
            .appendingPathComponent(AppConstants.Storage.recordingsDirectory)
            .appendingPathComponent(audioFileName)

        try? FileManager.default.removeItem(at: audioURL)
    }

    // MARK: - Storage Info

    func getStorageInfo() async -> StorageInfo {
        guard let context = modelContext else {
            return StorageInfo(totalRecordings: 0, audioSize: 0, totalDuration: 0, availableSpace: 0)
        }

        do {
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate { !$0.isDeleted }
            )
            let allRecordings = try context.fetch(descriptor)

            let audioSize = allRecordings.reduce(0) { $0 + $1.audioFileSize }
            let totalDuration = allRecordings.reduce(0) { $0 + $1.duration }

            let availableSpace = try? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
                .volumeAvailableCapacityForImportantUsage ?? 0

            return StorageInfo(
                totalRecordings: allRecordings.count,
                audioSize: audioSize,
                totalDuration: totalDuration,
                availableSpace: availableSpace ?? 0
            )
        } catch {
            return StorageInfo(totalRecordings: 0, audioSize: 0, totalDuration: 0, availableSpace: 0)
        }
    }
}

// MARK: - Sort Option

enum SortOption: String, CaseIterable, Identifiable {
    case newest = "newest"
    case oldest = "oldest"
    case longest = "longest"
    case shortest = "shortest"
    case alphabetical = "alphabetical"

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .newest: return "Newest First"
        case .oldest: return "Oldest First"
        case .longest: return "Longest First"
        case .shortest: return "Shortest First"
        case .alphabetical: return "Alphabetical"
        }
    }
}

// MARK: - Storage Info

struct StorageInfo {
    let totalRecordings: Int
    let audioSize: Int64
    let totalDuration: TimeInterval
    let availableSpace: Int64

    var formattedAudioSize: String {
        ByteCountFormatter.string(fromByteCount: audioSize, countStyle: .file)
    }

    var formattedAvailableSpace: String {
        ByteCountFormatter.string(fromByteCount: availableSpace, countStyle: .file)
    }

    var formattedDuration: String {
        let hours = Int(totalDuration) / 3600
        let minutes = (Int(totalDuration) % 3600) / 60

        if hours > 0 {
            return "\(hours)h \(minutes)m"
        } else {
            return "\(minutes) minutes"
        }
    }
}

import Foundation
import SwiftData
import Combine

/// Central data access layer for Recording entities
@MainActor
final class RecordingStore: ObservableObject {

    // MARK: - Types

    enum SortOrder: String, CaseIterable {
        case newestFirst = "newest"
        case oldestFirst = "oldest"
        case longestFirst = "longest"
        case shortestFirst = "shortest"
        case alphabetical = "alphabetical"

        var displayName: String {
            switch self {
            case .newestFirst: return "Newest First"
            case .oldestFirst: return "Oldest First"
            case .longestFirst: return "Longest First"
            case .shortestFirst: return "Shortest First"
            case .alphabetical: return "A to Z"
            }
        }
    }

    struct StorageInfo {
        let totalRecordings: Int
        let totalAudioSize: Int64
        let totalDuration: TimeInterval
        let availableSpace: Int64

        var formattedAudioSize: String {
            ByteCountFormatter.string(fromByteCount: totalAudioSize, countStyle: .file)
        }

        var formattedAvailableSpace: String {
            ByteCountFormatter.string(fromByteCount: availableSpace, countStyle: .file)
        }
    }

    enum StoreError: Error, LocalizedError {
        case recordingNotFound(UUID)
        case saveFailed(Error)
        case deleteFailed(Error)
        case audioFileNotFound

        var errorDescription: String? {
            switch self {
            case .recordingNotFound(let id):
                return "Recording not found: \(id)"
            case .saveFailed(let error):
                return "Save failed: \(error.localizedDescription)"
            case .deleteFailed(let error):
                return "Delete failed: \(error.localizedDescription)"
            case .audioFileNotFound:
                return "Audio file not found"
            }
        }
    }

    // MARK: - Published Properties

    @Published private(set) var recordings: [Recording] = []
    @Published var searchText: String = ""
    @Published var sortOrder: SortOrder = .newestFirst
    @Published var filterStatus: TranscriptionStatus? = nil

    // MARK: - Properties

    private let modelContext: ModelContext

    var totalDuration: TimeInterval {
        recordings.reduce(0) { $0 + $1.duration }
    }

    // MARK: - Initialization

    init(modelContext: ModelContext) {
        self.modelContext = modelContext
    }

    // MARK: - CRUD Operations

    /// Fetch all recordings with current filters and sort order
    /// Uses SwiftData predicates for efficient database-level filtering
    func fetchRecordings() async throws {
        var descriptor = FetchDescriptor<Recording>()

        // Apply sort order
        switch sortOrder {
        case .newestFirst:
            descriptor.sortBy = [SortDescriptor(\.createdAt, order: .reverse)]
        case .oldestFirst:
            descriptor.sortBy = [SortDescriptor(\.createdAt, order: .forward)]
        case .longestFirst:
            descriptor.sortBy = [SortDescriptor(\.duration, order: .reverse)]
        case .shortestFirst:
            descriptor.sortBy = [SortDescriptor(\.duration, order: .forward)]
        case .alphabetical:
            descriptor.sortBy = [SortDescriptor(\.title, order: .forward)]
        }

        // Build predicate for database-level filtering
        descriptor.predicate = buildFilterPredicate()

        var fetchedRecordings = try modelContext.fetch(descriptor)

        // Full-text search on transcript must be done in-memory since it's a computed property
        // Only apply if there's a search query and the title predicate didn't match
        if !searchText.isEmpty {
            let query = searchText.lowercased()
            fetchedRecordings = fetchedRecordings.filter { recording in
                // Title is already filtered by predicate, but we need to include
                // transcript matches. Re-check title to avoid duplicating logic.
                recording.title.localizedCaseInsensitiveContains(query) ||
                recording.fullTranscript.localizedCaseInsensitiveContains(query)
            }
        }

        recordings = fetchedRecordings
    }

    /// Build a SwiftData predicate for filtering recordings at the database level
    private func buildFilterPredicate() -> Predicate<Recording>? {
        let hasStatusFilter = filterStatus != nil
        let hasSearchFilter = !searchText.isEmpty

        // No filters - return nil to fetch all
        if !hasStatusFilter && !hasSearchFilter {
            return nil
        }

        // Status filter only
        if hasStatusFilter && !hasSearchFilter {
            let statusRawValue = filterStatus!.rawValue
            return #Predicate<Recording> { recording in
                recording.statusRaw == statusRawValue
            }
        }

        // Search filter only (title search at DB level, transcript search in-memory)
        if !hasStatusFilter && hasSearchFilter {
            let query = searchText
            return #Predicate<Recording> { recording in
                recording.title.localizedStandardContains(query)
            }
        }

        // Both filters
        let statusRawValue = filterStatus!.rawValue
        let query = searchText
        return #Predicate<Recording> { recording in
            recording.statusRaw == statusRawValue &&
            recording.title.localizedStandardContains(query)
        }
    }

    /// Get a single recording by ID
    func recording(withID id: UUID) -> Recording? {
        recordings.first { $0.id == id }
    }

    /// Create a new recording entry
    @discardableResult
    func createRecording(
        title: String,
        audioFileURL: URL,
        duration: TimeInterval,
        sourceType: SourceType,
        language: String?
    ) throws -> Recording {

        let recording = Recording(
            title: title,
            duration: duration,
            sourceType: sourceType,
            language: language
        )

        // Set audio file info
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let relativePath = audioFileURL.path.replacingOccurrences(of: documentsURL.path + "/", with: "")
        recording.audioFileName = relativePath

        let attributes = try? FileManager.default.attributesOfItem(atPath: audioFileURL.path)
        recording.audioFileSize = attributes?[.size] as? Int64 ?? 0

        modelContext.insert(recording)
        try modelContext.save()

        return recording
    }

    /// Update recording metadata
    func updateRecording(_ recording: Recording) throws {
        recording.updatedAt = Date()
        try modelContext.save()
    }

    /// Update recording with transcription results
    func updateWithTranscription(
        recordingID: UUID,
        segments: [TranscriptSegment],
        detectedLanguage: String?
    ) throws {
        guard let recording = recording(withID: recordingID) else {
            throw StoreError.recordingNotFound(recordingID)
        }

        recording.segments = segments
        recording.detectedLanguage = detectedLanguage
        recording.status = .completed
        recording.transcriptionProgress = 1.0
        recording.updatedAt = Date()

        try modelContext.save()
    }

    /// Update transcription progress
    func updateProgress(recordingID: UUID, progress: Double) throws {
        guard let recording = recording(withID: recordingID) else {
            throw StoreError.recordingNotFound(recordingID)
        }

        recording.transcriptionProgress = progress
        recording.status = .processing

        try modelContext.save()
    }

    /// Mark recording as failed
    func markAsFailed(recordingID: UUID, error: String) throws {
        guard let recording = recording(withID: recordingID) else {
            throw StoreError.recordingNotFound(recordingID)
        }

        recording.status = .failed
        recording.errorMessage = error
        recording.updatedAt = Date()

        try modelContext.save()
    }

    /// Delete a single recording and its audio file
    func deleteRecording(_ recording: Recording) throws {
        // Delete audio file
        if let audioURL = recording.audioFileURL {
            do {
                try FileManager.default.removeItem(at: audioURL)
            } catch {
                // Log but continue - we still want to delete the database record
                print("[RecordingStore] Warning: Failed to delete audio file at \(audioURL.path): \(error.localizedDescription)")
            }
        }

        modelContext.delete(recording)
        try modelContext.save()
    }

    /// Delete multiple recordings
    func deleteRecordings(_ recordings: [Recording]) throws {
        for recording in recordings {
            if let audioURL = recording.audioFileURL {
                do {
                    try FileManager.default.removeItem(at: audioURL)
                } catch {
                    // Log but continue - we still want to delete the database record
                    print("[RecordingStore] Warning: Failed to delete audio file at \(audioURL.path): \(error.localizedDescription)")
                }
            }
            modelContext.delete(recording)
        }

        try modelContext.save()
    }

    /// Delete only the audio file, keep transcript
    func deleteAudioFile(for recording: Recording) throws {
        guard let audioURL = recording.audioFileURL else {
            throw StoreError.audioFileNotFound
        }

        try FileManager.default.removeItem(at: audioURL)
        recording.audioFileName = nil
        recording.audioFileSize = 0
        recording.updatedAt = Date()

        try modelContext.save()
    }

    /// Delete all audio files
    func deleteAllAudioFiles() async throws {
        var failedDeletions: [String] = []

        for recording in recordings {
            if let audioURL = recording.audioFileURL {
                do {
                    try FileManager.default.removeItem(at: audioURL)
                } catch {
                    // Log but continue - we still want to update the database records
                    print("[RecordingStore] Warning: Failed to delete audio file at \(audioURL.path): \(error.localizedDescription)")
                    failedDeletions.append(audioURL.lastPathComponent)
                }
                recording.audioFileName = nil
                recording.audioFileSize = 0
            }
        }

        try modelContext.save()

        if !failedDeletions.isEmpty {
            print("[RecordingStore] Warning: Failed to delete \(failedDeletions.count) audio file(s)")
        }
    }

    // MARK: - Utilities

    /// Generate automatic title based on date/time
    func generateDefaultTitle() -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return "Recording - \(formatter.string(from: Date()))"
    }

    /// Get storage usage information
    func getStorageInfo() throws -> StorageInfo {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let recordingsURL = documentsURL.appendingPathComponent(AppConstants.Storage.recordingsDirectory)

        var totalSize: Int64 = 0
        var fileCount = 0

        if FileManager.default.fileExists(atPath: recordingsURL.path) {
            let contents = try FileManager.default.contentsOfDirectory(
                at: recordingsURL,
                includingPropertiesForKeys: [.fileSizeKey],
                options: .skipsHiddenFiles
            )

            for fileURL in contents {
                let attributes = try fileURL.resourceValues(forKeys: [.fileSizeKey])
                totalSize += Int64(attributes.fileSize ?? 0)
                fileCount += 1
            }
        }

        let attributes = try FileManager.default.attributesOfFileSystem(forPath: documentsURL.path)
        let availableSpace = (attributes[.systemFreeSize] as? Int64) ?? 0

        return StorageInfo(
            totalRecordings: fileCount,
            totalAudioSize: totalSize,
            totalDuration: totalDuration,
            availableSpace: availableSpace
        )
    }

    /// Export transcript in specified format
    func exportTranscript(_ recording: Recording, format: ExportFormat) throws -> URL {
        let content = generateExportContent(recording: recording, format: format)
        let fileName = "\(recording.title).\(format.fileExtension)"
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        try content.write(to: tempURL, atomically: true, encoding: .utf8)

        return tempURL
    }

    // MARK: - Private Methods

    private func generateExportContent(recording: Recording, format: ExportFormat) -> String {
        let segments = recording.sortedSegments

        switch format {
        case .txt:
            return segments.map(\.text).joined(separator: "\n\n")

        case .srt:
            return segments.enumerated().map { index, segment in
                """
                \(index + 1)
                \(segment.srtStartTimestamp) --> \(segment.srtEndTimestamp)
                \(segment.text)
                """
            }.joined(separator: "\n\n")

        case .vtt:
            let header = "WEBVTT\n\n"
            let cues = segments.map { segment in
                """
                \(segment.vttStartTimestamp) --> \(segment.vttEndTimestamp)
                \(segment.text)
                """
            }.joined(separator: "\n\n")
            return header + cues
        }
    }
}

// MARK: - Preview Support

extension RecordingStore {
    static var preview: RecordingStore {
        do {
            let schema = Schema([Recording.self, TranscriptSegment.self])
            let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
            let container = try ModelContainer(for: schema, configurations: [config])
            return RecordingStore(modelContext: container.mainContext)
        } catch {
            // Fallback: Create a minimal in-memory container
            // This should never fail, but provides safety for previews
            fatalError("Failed to create preview RecordingStore: \(error.localizedDescription)")
        }
    }
}

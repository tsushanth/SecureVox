import Foundation
import Combine
import SwiftData
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "BatchTranscription")

/// Manager for batch transcription of multiple recordings sequentially
@MainActor
final class BatchTranscriptionManager: ObservableObject {

    // MARK: - Singleton

    static let shared = BatchTranscriptionManager()

    // MARK: - Types

    enum BatchState: Equatable {
        case idle
        case processing
        case completed
        case cancelled
        case failed(String)

        static func == (lhs: BatchState, rhs: BatchState) -> Bool {
            switch (lhs, rhs) {
            case (.idle, .idle), (.processing, .processing),
                 (.completed, .completed), (.cancelled, .cancelled):
                return true
            case (.failed(let lhsMsg), .failed(let rhsMsg)):
                return lhsMsg == rhsMsg
            default:
                return false
            }
        }

        var isProcessing: Bool {
            if case .processing = self { return true }
            return false
        }
    }

    struct BatchProgress {
        let currentIndex: Int
        let totalCount: Int
        let currentRecordingID: UUID?
        let currentRecordingProgress: Double
        let currentRecordingTitle: String?

        var completedCount: Int {
            currentIndex
        }

        var remainingCount: Int {
            max(0, totalCount - currentIndex)
        }

        var overallProgress: Double {
            guard totalCount > 0 else { return 0 }
            let completed = Double(currentIndex)
            let current = currentRecordingProgress
            return (completed + current) / Double(totalCount)
        }

        var statusText: String {
            guard totalCount > 0 else { return "" }
            if currentIndex >= totalCount {
                return "Completed \(totalCount) of \(totalCount)"
            }
            return "Transcribing \(currentIndex + 1) of \(totalCount)"
        }

        var detailedStatusText: String {
            guard totalCount > 0 else { return "" }
            if let title = currentRecordingTitle {
                return "\(statusText): \(title)"
            }
            return statusText
        }

        static let initial = BatchProgress(
            currentIndex: 0,
            totalCount: 0,
            currentRecordingID: nil,
            currentRecordingProgress: 0,
            currentRecordingTitle: nil
        )
    }

    struct BatchResult {
        let totalCount: Int
        let successCount: Int
        let failedCount: Int
        let failedRecordingIDs: Set<UUID>

        var allSucceeded: Bool {
            failedCount == 0
        }
    }

    // MARK: - Published Properties

    @Published private(set) var state: BatchState = .idle
    @Published private(set) var progress: BatchProgress = .initial
    @Published private(set) var failedRecordingIDs: Set<UUID> = []
    @Published private(set) var completedRecordingIDs: Set<UUID> = []

    // MARK: - Private Properties

    private var recordingQueue: [Recording] = []
    private var currentTask: Task<Void, Never>?
    private var modelContext: ModelContext?
    private let transcriptionService = TranscriptionService.shared

    // MARK: - Callbacks

    var onRecordingStarted: ((Recording) -> Void)?
    var onRecordingProgress: ((Recording, Double, String?) -> Void)?
    var onRecordingCompleted: ((Recording, [TranscriptSegment]) -> Void)?
    var onRecordingFailed: ((Recording, Error) -> Void)?
    var onBatchCompleted: ((BatchResult) -> Void)?

    // MARK: - Initialization

    private init() {}

    // MARK: - Configuration

    func configure(with modelContext: ModelContext) {
        self.modelContext = modelContext
    }

    // MARK: - Public Methods

    /// Start batch transcription for a list of recordings
    func startBatch(recordings: [Recording]) {
        guard state != .processing else { return }
        guard !recordings.isEmpty else { return }

        // Filter to only pending/failed recordings with audio
        let toTranscribe = recordings.filter { recording in
            recording.hasAudio &&
            (recording.transcriptionStatus == .pending || recording.transcriptionStatus == .failed)
        }

        guard !toTranscribe.isEmpty else { return }

        recordingQueue = toTranscribe
        failedRecordingIDs.removeAll()
        completedRecordingIDs.removeAll()
        state = .processing

        progress = BatchProgress(
            currentIndex: 0,
            totalCount: toTranscribe.count,
            currentRecordingID: toTranscribe.first?.id,
            currentRecordingProgress: 0,
            currentRecordingTitle: toTranscribe.first?.title
        )

        currentTask = Task {
            await processBatch()
        }
    }

    /// Start batch transcription for recordings matching a predicate
    func startBatch(filter: ((Recording) -> Bool)? = nil) {
        guard let modelContext = modelContext else { return }

        do {
            let descriptor = FetchDescriptor<Recording>(
                predicate: #Predicate<Recording> { !$0.isDeleted }
            )
            var recordings = try modelContext.fetch(descriptor)

            // Apply additional filter if provided
            if let filter = filter {
                recordings = recordings.filter(filter)
            }

            startBatch(recordings: recordings)
        } catch {
            state = .failed("Failed to fetch recordings: \(error.localizedDescription)")
        }
    }

    /// Add a recording to the current queue (if processing) or start a new batch
    func addToQueue(_ recording: Recording) {
        guard recording.hasAudio else { return }

        if state == .processing {
            // Add to existing queue
            if !recordingQueue.contains(where: { $0.id == recording.id }) {
                recordingQueue.append(recording)
                // Update total count in progress
                progress = BatchProgress(
                    currentIndex: progress.currentIndex,
                    totalCount: recordingQueue.count,
                    currentRecordingID: progress.currentRecordingID,
                    currentRecordingProgress: progress.currentRecordingProgress,
                    currentRecordingTitle: progress.currentRecordingTitle
                )
            }
        } else {
            // Start new batch with single recording
            startBatch(recordings: [recording])
        }
    }

    /// Cancel the current batch operation
    func cancel() {
        guard state == .processing else { return }

        currentTask?.cancel()
        currentTask = nil

        transcriptionService.cancelTranscription()

        state = .cancelled
        recordingQueue.removeAll()

        progress = BatchProgress(
            currentIndex: progress.currentIndex,
            totalCount: progress.totalCount,
            currentRecordingID: nil,
            currentRecordingProgress: 0,
            currentRecordingTitle: nil
        )
    }

    /// Reset the manager to idle state
    func reset() {
        cancel()
        state = .idle
        recordingQueue.removeAll()
        failedRecordingIDs.removeAll()
        completedRecordingIDs.removeAll()
        progress = .initial
    }

    /// Check if a specific recording is in the queue
    func isInQueue(_ recording: Recording) -> Bool {
        recordingQueue.contains { $0.id == recording.id }
    }

    /// Get the position of a recording in the queue (1-based)
    func queuePosition(for recording: Recording) -> Int? {
        guard let index = recordingQueue.firstIndex(where: { $0.id == recording.id }) else {
            return nil
        }
        return index + 1
    }

    // MARK: - Private Methods

    private func processBatch() async {
        var successCount = 0
        var currentIndex = 0

        for recording in recordingQueue {
            // Check for cancellation
            if Task.isCancelled {
                break
            }

            // Update progress for current recording
            progress = BatchProgress(
                currentIndex: currentIndex,
                totalCount: recordingQueue.count,
                currentRecordingID: recording.id,
                currentRecordingProgress: 0,
                currentRecordingTitle: recording.title
            )

            // Update recording status
            recording.transcriptionStatus = .inProgress
            recording.transcriptionProgress = 0
            recording.updatedAt = Date()
            saveContext()

            onRecordingStarted?(recording)

            // Get audio URL
            guard let audioURL = recording.audioURL else {
                failedRecordingIDs.insert(recording.id)
                recording.transcriptionStatus = .failed
                recording.transcriptionError = "Audio file not found"
                recording.updatedAt = Date()
                saveContext()
                onRecordingFailed?(recording, BatchError.audioFileNotFound)
                currentIndex += 1
                continue
            }

            do {
                // Get language preference
                let language: AppConstants.WhisperLanguage?
                if let langCode = recording.language,
                   let lang = AppConstants.WhisperLanguage(rawValue: langCode) {
                    language = lang
                } else {
                    language = nil
                }

                // Transcribe
                let segments = try await transcriptionService.transcribe(
                    audioURL: audioURL,
                    language: language
                )

                // Check for cancellation after transcription
                if Task.isCancelled {
                    recording.transcriptionStatus = .pending
                    recording.updatedAt = Date()
                    saveContext()
                    break
                }

                // Update recording with segments
                recording.segments = segments
                recording.transcriptionStatus = .completed
                recording.transcriptionProgress = 1.0
                recording.transcriptionError = nil
                recording.transcriptionEngine = transcriptionService.getActiveEngine().rawValue
                recording.updatedAt = Date()

                // Link segments to recording
                for segment in segments {
                    segment.recording = recording
                }

                saveContext()

                completedRecordingIDs.insert(recording.id)
                successCount += 1
                onRecordingCompleted?(recording, segments)

            } catch {
                if Task.isCancelled {
                    recording.transcriptionStatus = .pending
                    recording.updatedAt = Date()
                    saveContext()
                    break
                }

                failedRecordingIDs.insert(recording.id)
                recording.transcriptionStatus = .failed
                recording.transcriptionError = error.localizedDescription
                recording.transcriptionProgress = 0
                recording.updatedAt = Date()
                saveContext()

                onRecordingFailed?(recording, error)
            }

            currentIndex += 1
        }

        // Update final state
        if Task.isCancelled {
            state = .cancelled
        } else {
            state = .completed
            progress = BatchProgress(
                currentIndex: recordingQueue.count,
                totalCount: recordingQueue.count,
                currentRecordingID: nil,
                currentRecordingProgress: 1.0,
                currentRecordingTitle: nil
            )

            let result = BatchResult(
                totalCount: recordingQueue.count,
                successCount: successCount,
                failedCount: failedRecordingIDs.count,
                failedRecordingIDs: failedRecordingIDs
            )
            onBatchCompleted?(result)
        }
    }

    private func saveContext() {
        guard let modelContext = modelContext else { return }
        do {
            try modelContext.save()
        } catch {
            logger.error("Error saving context: \(error.localizedDescription)")
        }
    }

    // MARK: - Errors

    enum BatchError: LocalizedError {
        case audioFileNotFound
        case noRecordingsSelected
        case alreadyProcessing
        case modelContextNotConfigured

        var errorDescription: String? {
            switch self {
            case .audioFileNotFound:
                return "Audio file not found"
            case .noRecordingsSelected:
                return "No recordings selected for transcription"
            case .alreadyProcessing:
                return "Batch processing already in progress"
            case .modelContextNotConfigured:
                return "Database context not configured"
            }
        }
    }
}

// MARK: - Convenience Extensions

extension BatchTranscriptionManager {

    /// Transcribe all pending recordings
    func transcribeAllPending() {
        startBatch { recording in
            recording.transcriptionStatus == .pending && recording.hasAudio
        }
    }

    /// Retry all failed recordings
    func retryAllFailed() {
        startBatch { recording in
            recording.transcriptionStatus == .failed && recording.hasAudio
        }
    }

    /// Get a summary string for the current state
    var statusSummary: String {
        switch state {
        case .idle:
            return "Ready"
        case .processing:
            return progress.statusText
        case .completed:
            let failed = failedRecordingIDs.count
            if failed > 0 {
                return "Completed with \(failed) error(s)"
            }
            return "Completed successfully"
        case .cancelled:
            return "Cancelled"
        case .failed(let message):
            return "Failed: \(message)"
        }
    }
}

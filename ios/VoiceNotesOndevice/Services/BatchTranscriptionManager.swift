import Foundation
import Combine

/// Manager for batch transcription of multiple recordings sequentially
@MainActor
final class BatchTranscriptionManager: ObservableObject {

    // MARK: - Types

    enum BatchState: Equatable {
        case idle
        case processing
        case completed
        case cancelled

        static func == (lhs: BatchState, rhs: BatchState) -> Bool {
            switch (lhs, rhs) {
            case (.idle, .idle), (.processing, .processing),
                 (.completed, .completed), (.cancelled, .cancelled):
                return true
            default:
                return false
            }
        }
    }

    struct BatchProgress {
        let currentIndex: Int
        let totalCount: Int
        let currentRecordingID: UUID?
        let currentRecordingProgress: Double

        var completedCount: Int {
            currentIndex
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
            return "\(currentIndex + 1) of \(totalCount)"
        }
    }

    // MARK: - Published Properties

    @Published private(set) var state: BatchState = .idle
    @Published private(set) var progress: BatchProgress = BatchProgress(
        currentIndex: 0,
        totalCount: 0,
        currentRecordingID: nil,
        currentRecordingProgress: 0
    )
    @Published private(set) var failedRecordingIDs: Set<UUID> = []

    // MARK: - Private Properties

    private var recordingQueue: [UUID] = []
    private var currentTask: Task<Void, Never>?

    // MARK: - Callbacks

    var onRecordingProgress: ((UUID, Double, String?) -> Void)?
    var onRecordingCompleted: ((UUID, [TranscriptSegment]) -> Void)?
    var onRecordingFailed: ((UUID, Error) -> Void)?
    var onBatchCompleted: (() -> Void)?

    // MARK: - Initialization

    init() {}

    // MARK: - Public Methods

    /// Start batch transcription for a list of recordings
    /// - Parameters:
    ///   - recordingIDs: IDs of recordings to transcribe
    ///   - getAudioURL: Closure to get audio URL for a recording ID
    ///   - getLanguage: Closure to get language preference for a recording ID
    func startBatch(
        recordingIDs: [UUID],
        getAudioURL: @escaping (UUID) -> URL?,
        getLanguage: @escaping (UUID) -> String?
    ) {
        guard state != .processing else { return }
        guard !recordingIDs.isEmpty else { return }

        recordingQueue = recordingIDs
        failedRecordingIDs.removeAll()
        state = .processing
        progress = BatchProgress(
            currentIndex: 0,
            totalCount: recordingIDs.count,
            currentRecordingID: recordingIDs.first,
            currentRecordingProgress: 0
        )

        currentTask = Task {
            await processBatch(getAudioURL: getAudioURL, getLanguage: getLanguage)
        }
    }

    /// Cancel the current batch operation
    func cancel() {
        guard state == .processing else { return }

        currentTask?.cancel()
        currentTask = nil

        Task {
            await CoreMLTranscriber.shared.cancelTranscription()
        }

        state = .cancelled
        recordingQueue.removeAll()

        // Reset progress
        progress = BatchProgress(
            currentIndex: progress.currentIndex,
            totalCount: progress.totalCount,
            currentRecordingID: nil,
            currentRecordingProgress: 0
        )
    }

    /// Reset the manager to idle state
    func reset() {
        cancel()
        state = .idle
        recordingQueue.removeAll()
        failedRecordingIDs.removeAll()
        progress = BatchProgress(
            currentIndex: 0,
            totalCount: 0,
            currentRecordingID: nil,
            currentRecordingProgress: 0
        )
    }

    // MARK: - Private Methods

    private func processBatch(
        getAudioURL: @escaping (UUID) -> URL?,
        getLanguage: @escaping (UUID) -> String?
    ) async {
        for (index, recordingID) in recordingQueue.enumerated() {
            // Check for cancellation
            if Task.isCancelled {
                break
            }

            // Update progress for current recording
            progress = BatchProgress(
                currentIndex: index,
                totalCount: recordingQueue.count,
                currentRecordingID: recordingID,
                currentRecordingProgress: 0
            )

            // Get audio URL
            guard let audioURL = getAudioURL(recordingID) else {
                failedRecordingIDs.insert(recordingID)
                onRecordingFailed?(recordingID, BatchError.audioFileNotFound)
                continue
            }

            let language = getLanguage(recordingID)

            do {
                // Transcribe
                let segments = try await transcribeRecording(
                    recordingID: recordingID,
                    audioURL: audioURL,
                    language: language
                )

                // Check for cancellation after transcription
                if Task.isCancelled {
                    break
                }

                // Notify completion
                onRecordingCompleted?(recordingID, segments)

            } catch {
                if Task.isCancelled {
                    break
                }

                failedRecordingIDs.insert(recordingID)
                onRecordingFailed?(recordingID, error)
            }
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
                currentRecordingProgress: 1.0
            )
            onBatchCompleted?()
        }
    }

    /// Get the user's selected Whisper model from settings
    private func getSelectedModel() -> CoreMLTranscriber.WhisperModel {
        let savedModel = UserDefaults.standard.string(forKey: AppConstants.UserDefaultsKeys.selectedModel)

        if let savedModel = savedModel {
            // Try new format first, then legacy format
            if let model = CoreMLTranscriber.WhisperModel(rawValue: savedModel) {
                return model
            } else if let model = CoreMLTranscriber.WhisperModel(legacyRawValue: savedModel) {
                return model
            }
        }
        return .tiny // Default fallback
    }

    private func transcribeRecording(
        recordingID: UUID,
        audioURL: URL,
        language: String?
    ) async throws -> [TranscriptSegment] {
        // Ensure the selected model is loaded before transcribing
        let selectedModel = getSelectedModel()
        let transcriber = CoreMLTranscriber.shared

        let currentlyLoaded = await transcriber.loadedModel
        if currentlyLoaded != selectedModel {
            try await transcriber.loadModel(selectedModel)
        }

        return try await withCheckedThrowingContinuation { continuation in
            Task {
                await transcriber.transcribe(
                    audioURL: audioURL,
                    languageCode: language,
                    onPartial: { [weak self] partialProgress, partialText in
                        Task { @MainActor in
                            guard let self = self else { return }

                            // Update progress
                            self.progress = BatchProgress(
                                currentIndex: self.progress.currentIndex,
                                totalCount: self.progress.totalCount,
                                currentRecordingID: recordingID,
                                currentRecordingProgress: partialProgress
                            )

                            self.onRecordingProgress?(recordingID, partialProgress, partialText)
                        }
                    },
                    completion: { result in
                        switch result {
                        case .success(let segments):
                            continuation.resume(returning: segments)
                        case .failure(let error):
                            continuation.resume(throwing: error)
                        }
                    }
                )
            }
        }
    }

    // MARK: - Errors

    enum BatchError: LocalizedError {
        case audioFileNotFound
        case noRecordingsSelected
        case alreadyProcessing

        var errorDescription: String? {
            switch self {
            case .audioFileNotFound:
                return "Audio file not found"
            case .noRecordingsSelected:
                return "No recordings selected"
            case .alreadyProcessing:
                return "Batch processing already in progress"
            }
        }
    }
}

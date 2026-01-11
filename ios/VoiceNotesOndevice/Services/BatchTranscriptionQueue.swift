import Foundation

/// Actor for managing batch transcription queue
actor BatchTranscriptionQueue {

    // MARK: - Types

    struct QueueItem: Identifiable {
        let id: UUID
        let recordingID: UUID
        var status: ItemStatus
        var progress: Double

        enum ItemStatus {
            case pending
            case processing
            case completed
            case failed(Error)
        }
    }

    // MARK: - Properties

    private(set) var queue: [QueueItem] = []
    private(set) var isProcessing: Bool = false

    private let transcriber: CoreMLTranscriber
    private let store: RecordingStore

    // MARK: - Callbacks

    var onProgressUpdate: ((UUID, Double) -> Void)?
    var onItemCompleted: ((UUID) -> Void)?
    var onItemFailed: ((UUID, Error) -> Void)?

    // MARK: - Initialization

    init(transcriber: CoreMLTranscriber, store: RecordingStore) {
        self.transcriber = transcriber
        self.store = store
    }

    // MARK: - Queue Management

    /// Add recording to queue
    func enqueue(recordingID: UUID) {
        let item = QueueItem(
            id: UUID(),
            recordingID: recordingID,
            status: .pending,
            progress: 0
        )
        queue.append(item)

        // Start processing if not already
        if !isProcessing {
            Task {
                await processQueue()
            }
        }
    }

    /// Add multiple recordings to queue
    func enqueue(recordingIDs: [UUID]) {
        for id in recordingIDs {
            enqueue(recordingID: id)
        }
    }

    /// Remove item from queue
    func remove(itemID: UUID) {
        queue.removeAll { $0.id == itemID }
    }

    /// Clear all pending items
    func clearPending() {
        queue.removeAll { item in
            if case .pending = item.status { return true }
            return false
        }
    }

    /// Cancel current processing
    func cancel() async {
        await transcriber.cancelTranscription()
        isProcessing = false
    }

    // MARK: - Queue Processing

    private func processQueue() async {
        isProcessing = true

        while let index = queue.firstIndex(where: { item in
            if case .pending = item.status { return true }
            return false
        }) {
            var item = queue[index]
            item.status = .processing
            queue[index] = item

            do {
                try await processItem(item)
                item.status = .completed
                item.progress = 1.0
                queue[index] = item
                onItemCompleted?(item.recordingID)

            } catch {
                item.status = .failed(error)
                queue[index] = item
                onItemFailed?(item.recordingID, error)
            }
        }

        isProcessing = false
    }

    private func processItem(_ item: QueueItem) async throws {
        // Get recording from store
        guard let recording = await MainActor.run(body: {
            store.recording(withID: item.recordingID)
        }) else {
            throw TranscriptionError.recordingNotFound
        }

        guard let audioURL = recording.audioFileURL else {
            throw TranscriptionError.audioFileNotFound
        }

        // Transcribe
        let result = try await transcriber.transcribe(
            audioURL: audioURL,
            language: recording.language
        ) { [weak self] progress in
            Task {
                await self?.updateProgress(
                    itemID: item.id,
                    progress: progress.fractionCompleted
                )
            }
        }

        // Convert results to segments
        let segments = result.segments.map { segmentResult in
            TranscriptSegment(
                startTime: segmentResult.startTime,
                endTime: segmentResult.endTime,
                text: segmentResult.text,
                confidence: segmentResult.confidence
            )
        }

        // Update store
        try await MainActor.run {
            try store.updateWithTranscription(
                recordingID: item.recordingID,
                segments: segments,
                detectedLanguage: result.detectedLanguage
            )
        }
    }

    private func updateProgress(itemID: UUID, progress: Double) {
        guard let index = queue.firstIndex(where: { $0.id == itemID }) else { return }
        queue[index].progress = progress
        onProgressUpdate?(queue[index].recordingID, progress)
    }

    // MARK: - Errors

    enum TranscriptionError: LocalizedError {
        case recordingNotFound
        case audioFileNotFound

        var errorDescription: String? {
            switch self {
            case .recordingNotFound:
                return "Recording not found"
            case .audioFileNotFound:
                return "Audio file not found"
            }
        }
    }
}

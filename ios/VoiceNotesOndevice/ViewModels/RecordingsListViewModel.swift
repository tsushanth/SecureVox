import Foundation
import SwiftUI
import Combine

/// ViewModel managing the recordings list in memory
@MainActor
final class RecordingsListViewModel: ObservableObject {

    // MARK: - Published Properties

    /// All recordings
    @Published private(set) var recordings: [RecordingItem] = []

    /// Whether a recording is in progress
    @Published private(set) var isRecording: Bool = false

    /// Current recording duration
    @Published private(set) var recordingDuration: TimeInterval = 0

    /// Audio level for visualization (0.0 - 1.0)
    @Published private(set) var audioLevel: Float = 0

    /// Error message to display
    @Published var errorMessage: String?

    /// Search text for filtering
    @Published var searchText: String = ""

    // MARK: - Batch Selection Properties

    /// Whether multi-select mode is active
    @Published var isSelectionMode: Bool = false

    /// Set of selected recording IDs
    @Published var selectedRecordingIDs: Set<UUID> = []

    /// Batch transcription manager
    @Published private(set) var batchManager = BatchTranscriptionManager()

    /// Whether batch transcription is in progress
    var isBatchTranscribing: Bool {
        batchManager.state == .processing
    }

    /// Number of selected recordings
    var selectedCount: Int {
        selectedRecordingIDs.count
    }

    /// Number of pending recordings in selection
    var pendingSelectedCount: Int {
        selectedRecordingIDs.filter { id in
            recordings.first { $0.id == id }?.transcriptionStatus == .pending
        }.count
    }

    // MARK: - Recording Item Model (In-Memory)

    struct RecordingItem: Identifiable, Hashable {
        let id: UUID
        var title: String
        let createdAt: Date
        var duration: TimeInterval
        let audioFileURL: URL?
        var transcriptionStatus: TranscriptionStatus
        var transcriptionProgress: Double
        var transcript: String

        var formattedDuration: String {
            let hours = Int(duration) / 3600
            let minutes = (Int(duration) % 3600) / 60
            let seconds = Int(duration) % 60

            if hours > 0 {
                return String(format: "%d:%02d:%02d", hours, minutes, seconds)
            } else {
                return String(format: "%d:%02d", minutes, seconds)
            }
        }

        var formattedDate: String {
            let formatter = DateFormatter()
            let calendar = Calendar.current

            if calendar.isDateInToday(createdAt) {
                formatter.dateFormat = "h:mm a"
                return "Today, \(formatter.string(from: createdAt))"
            } else if calendar.isDateInYesterday(createdAt) {
                formatter.dateFormat = "h:mm a"
                return "Yesterday, \(formatter.string(from: createdAt))"
            } else {
                formatter.dateStyle = .medium
                formatter.timeStyle = .short
                return formatter.string(from: createdAt)
            }
        }

        enum TranscriptionStatus: String {
            case pending = "pending"
            case transcribing = "transcribing"
            case completed = "completed"
            case failed = "failed"
        }
    }

    // MARK: - Private Properties

    private var audioRecorder: AudioRecorderService?
    private var cancellables = Set<AnyCancellable>()
    private var currentRecordingURL: URL?

    // MARK: - Computed Properties

    var filteredRecordings: [RecordingItem] {
        if searchText.isEmpty {
            return recordings
        }
        return recordings.filter {
            $0.title.localizedCaseInsensitiveContains(searchText) ||
            $0.transcript.localizedCaseInsensitiveContains(searchText)
        }
    }

    var hasRecordings: Bool {
        !recordings.isEmpty
    }

    var formattedRecordingDuration: String {
        let hours = Int(recordingDuration) / 3600
        let minutes = (Int(recordingDuration) % 3600) / 60
        let seconds = Int(recordingDuration) % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            return String(format: "%d:%02d", minutes, seconds)
        }
    }

    // MARK: - Initialization

    init() {
        loadSampleRecordings()
    }

    // MARK: - Recording Methods

    func startRecording() {
        guard !isRecording else { return }

        let recorder = AudioRecorderService()
        self.audioRecorder = recorder

        // Observe recorder state
        recorder.$isRecording
            .receive(on: DispatchQueue.main)
            .sink { [weak self] value in
                self?.isRecording = value
            }
            .store(in: &cancellables)

        recorder.$duration
            .receive(on: DispatchQueue.main)
            .sink { [weak self] value in
                self?.recordingDuration = value
            }
            .store(in: &cancellables)

        recorder.$audioLevel
            .receive(on: DispatchQueue.main)
            .sink { [weak self] value in
                self?.audioLevel = value
            }
            .store(in: &cancellables)

        recorder.$errorMessage
            .receive(on: DispatchQueue.main)
            .sink { [weak self] value in
                self?.errorMessage = value
            }
            .store(in: &cancellables)

        // Start recording
        do {
            let url = try recorder.startRecording()
            currentRecordingURL = url
        } catch {
            errorMessage = error.localizedDescription
            audioRecorder = nil
        }
    }

    func stopRecording() {
        guard isRecording, let recorder = audioRecorder else { return }

        do {
            let result = try recorder.stopRecording()

            // Create new recording item
            let title = generateRecordingTitle()
            let newRecording = RecordingItem(
                id: UUID(),
                title: title,
                createdAt: Date(),
                duration: result.duration,
                audioFileURL: result.fileURL,
                transcriptionStatus: .pending,
                transcriptionProgress: 0,
                transcript: ""
            )

            // Add to list at the beginning
            recordings.insert(newRecording, at: 0)

        } catch {
            errorMessage = error.localizedDescription
        }

        // Reset state
        cancellables.removeAll()
        audioRecorder = nil
        recordingDuration = 0
        audioLevel = 0
        currentRecordingURL = nil
    }

    func cancelRecording() {
        audioRecorder?.cancelRecording()
        cancellables.removeAll()
        audioRecorder = nil
        recordingDuration = 0
        audioLevel = 0
        currentRecordingURL = nil
    }

    // MARK: - List Management

    func deleteRecording(_ recording: RecordingItem) {
        // Delete audio file
        if let url = recording.audioFileURL {
            try? FileManager.default.removeItem(at: url)
        }

        // Remove from list
        recordings.removeAll { $0.id == recording.id }
    }

    func deleteRecordings(at offsets: IndexSet) {
        let recordingsToDelete = offsets.map { filteredRecordings[$0] }
        for recording in recordingsToDelete {
            deleteRecording(recording)
        }
    }

    func renameRecording(_ recording: RecordingItem, newTitle: String) {
        guard let index = recordings.firstIndex(where: { $0.id == recording.id }) else { return }
        recordings[index].title = newTitle
    }

    func recording(withID id: UUID) -> RecordingItem? {
        recordings.first { $0.id == id }
    }

    func updateRecording(_ recording: RecordingItem) {
        guard let index = recordings.firstIndex(where: { $0.id == recording.id }) else { return }
        recordings[index] = recording
    }

    /// Refresh recordings list (placeholder for future SwiftData integration)
    func refreshRecordings() {
        // In-memory implementation - no refresh needed
        // This will be implemented when SwiftData integration is added
    }

    // MARK: - Transcription (Stub)

    func startTranscription(for recordingID: UUID) {
        guard let index = recordings.firstIndex(where: { $0.id == recordingID }) else { return }

        recordings[index].transcriptionStatus = .transcribing
        recordings[index].transcriptionProgress = 0

        // Simulate transcription progress (stub)
        simulateTranscription(for: recordingID)
    }

    private func simulateTranscription(for recordingID: UUID) {
        var progress: Double = 0

        Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] timer in
            guard let self = self else {
                timer.invalidate()
                return
            }

            progress += 0.01

            Task { @MainActor in
                guard let index = self.recordings.firstIndex(where: { $0.id == recordingID }) else {
                    timer.invalidate()
                    return
                }

                if progress >= 1.0 {
                    timer.invalidate()
                    self.recordings[index].transcriptionStatus = .completed
                    self.recordings[index].transcriptionProgress = 1.0
                    self.recordings[index].transcript = """
                    This is a sample transcription generated by the Whisper model.

                    The actual transcription will appear here once the CoreML model processes the audio file. The text will be broken into segments with timestamps for easy navigation.

                    Each segment can be tapped to seek to that position in the audio playback.
                    """
                } else {
                    self.recordings[index].transcriptionProgress = progress
                }
            }
        }
    }

    // MARK: - Private Methods

    private func generateRecordingTitle() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, yyyy 'at' h:mm a"
        return "Recording - \(formatter.string(from: Date()))"
    }

    private func loadSampleRecordings() {
        let samples: [(String, TimeInterval, Date, RecordingItem.TranscriptionStatus, String)] = [
            ("Team Standup Meeting", 245, Date().addingTimeInterval(-3600), .completed,
             "Good morning everyone. Let's go around and share our updates. I finished the authentication module yesterday and will be working on the dashboard today."),
            ("Voice Memo", 62, Date().addingTimeInterval(-86400), .pending, ""),
            ("Product Interview", 1847, Date().addingTimeInterval(-172800), .completed,
             "Thank you for joining us today. Can you tell us about your experience with our product? What features do you find most valuable?"),
            ("Quick Reminder", 15, Date().addingTimeInterval(-259200), .pending, ""),
        ]

        recordings = samples.map { title, duration, date, status, transcript in
            RecordingItem(
                id: UUID(),
                title: title,
                createdAt: date,
                duration: duration,
                audioFileURL: nil,
                transcriptionStatus: status,
                transcriptionProgress: status == .completed ? 1.0 : 0,
                transcript: transcript
            )
        }
    }

    // MARK: - Selection Mode Methods

    /// Toggle selection mode on/off
    func toggleSelectionMode() {
        isSelectionMode.toggle()
        if !isSelectionMode {
            selectedRecordingIDs.removeAll()
        }
    }

    /// Exit selection mode
    func exitSelectionMode() {
        isSelectionMode = false
        selectedRecordingIDs.removeAll()
    }

    /// Toggle selection for a recording
    func toggleSelection(for recordingID: UUID) {
        if selectedRecordingIDs.contains(recordingID) {
            selectedRecordingIDs.remove(recordingID)
        } else {
            selectedRecordingIDs.insert(recordingID)
        }
    }

    /// Check if a recording is selected
    func isSelected(_ recordingID: UUID) -> Bool {
        selectedRecordingIDs.contains(recordingID)
    }

    /// Select all recordings
    func selectAll() {
        selectedRecordingIDs = Set(filteredRecordings.map(\.id))
    }

    /// Deselect all recordings
    func deselectAll() {
        selectedRecordingIDs.removeAll()
    }

    /// Select all pending recordings
    func selectAllPending() {
        selectedRecordingIDs = Set(
            filteredRecordings
                .filter { $0.transcriptionStatus == .pending }
                .map(\.id)
        )
    }

    // MARK: - Batch Transcription Methods

    /// Start batch transcription for selected recordings
    func startBatchTranscription() {
        // Filter to only pending recordings
        let pendingIDs = selectedRecordingIDs.filter { id in
            recordings.first { $0.id == id }?.transcriptionStatus == .pending
        }

        guard !pendingIDs.isEmpty else {
            errorMessage = "No pending recordings selected"
            return
        }

        // Set up callbacks
        setupBatchCallbacks()

        // Mark selected recordings as transcribing
        for id in pendingIDs {
            if let index = recordings.firstIndex(where: { $0.id == id }) {
                recordings[index].transcriptionStatus = .transcribing
                recordings[index].transcriptionProgress = 0
            }
        }

        // Start batch processing
        batchManager.startBatch(
            recordingIDs: Array(pendingIDs),
            getAudioURL: { [weak self] id in
                self?.recordings.first { $0.id == id }?.audioFileURL
            },
            getLanguage: { _ in nil } // Auto-detect language
        )
    }

    /// Cancel batch transcription
    func cancelBatchTranscription() {
        batchManager.cancel()

        // Reset transcribing recordings to pending
        for id in selectedRecordingIDs {
            if let index = recordings.firstIndex(where: { $0.id == id }),
               recordings[index].transcriptionStatus == .transcribing {
                recordings[index].transcriptionStatus = .pending
                recordings[index].transcriptionProgress = 0
            }
        }
    }

    private func setupBatchCallbacks() {
        batchManager.onRecordingProgress = { [weak self] recordingID, progress, partialText in
            guard let self = self else { return }

            if let index = self.recordings.firstIndex(where: { $0.id == recordingID }) {
                self.recordings[index].transcriptionProgress = progress
            }
        }

        batchManager.onRecordingCompleted = { [weak self] recordingID, segments in
            guard let self = self else { return }

            if let index = self.recordings.firstIndex(where: { $0.id == recordingID }) {
                self.recordings[index].transcriptionStatus = .completed
                self.recordings[index].transcriptionProgress = 1.0
                self.recordings[index].transcript = segments.map(\.text).joined(separator: " ")
            }
        }

        batchManager.onRecordingFailed = { [weak self] recordingID, error in
            guard let self = self else { return }

            if let index = self.recordings.firstIndex(where: { $0.id == recordingID }) {
                self.recordings[index].transcriptionStatus = .failed
                self.recordings[index].transcriptionProgress = 0
            }
        }

        batchManager.onBatchCompleted = { [weak self] in
            guard let self = self else { return }

            // Exit selection mode after batch completes
            self.exitSelectionMode()
        }
    }

    /// Delete selected recordings
    func deleteSelectedRecordings() {
        for id in selectedRecordingIDs {
            if let recording = recordings.first(where: { $0.id == id }) {
                deleteRecording(recording)
            }
        }
        selectedRecordingIDs.removeAll()
    }
}

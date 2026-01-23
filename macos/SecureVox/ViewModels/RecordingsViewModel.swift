import Foundation
import SwiftData
import Combine
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "Recordings")

/// ViewModel for the recordings list
@MainActor
class RecordingsViewModel: ObservableObject {

    // MARK: - Published Properties

    @Published var recordings: [Recording] = []
    @Published var searchQuery = ""
    @Published var sortOption: SortOption = .newest
    @Published var statusFilter: TranscriptionStatus? = nil
    @Published var sourceFilter: SourceType? = nil
    @Published var isRecording = false
    @Published var recordingDuration: TimeInterval = 0
    @Published var audioLevel: Float = 0
    @Published var isImporting = false
    @Published var errorMessage: String?
    @Published var selectedRecordings: Set<UUID> = []
    @Published var isSelectionMode = false

    // MARK: - Services

    private let recorderService = AudioRecorderService()
    private var modelContext: ModelContext?
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Initialization

    init() {
        setupBindings()
    }

    func configure(with context: ModelContext) {
        self.modelContext = context
        Task {
            await fetchRecordings()
        }
    }

    private func setupBindings() {
        // Observe recorder state
        recorderService.$isRecording
            .receive(on: DispatchQueue.main)
            .assign(to: &$isRecording)

        recorderService.$recordingDuration
            .receive(on: DispatchQueue.main)
            .assign(to: &$recordingDuration)

        recorderService.$audioLevel
            .receive(on: DispatchQueue.main)
            .assign(to: &$audioLevel)

        recorderService.$errorMessage
            .receive(on: DispatchQueue.main)
            .assign(to: &$errorMessage)

        // Debounce search
        $searchQuery
            .debounce(for: .seconds(AppConstants.UI.searchDebounce), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in
                Task {
                    await self?.fetchRecordings()
                }
            }
            .store(in: &cancellables)

        // Refresh on sort/filter changes
        $sortOption
            .sink { [weak self] _ in
                Task {
                    await self?.fetchRecordings()
                }
            }
            .store(in: &cancellables)

        $statusFilter
            .sink { [weak self] _ in
                Task {
                    await self?.fetchRecordings()
                }
            }
            .store(in: &cancellables)
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

            // Apply search filter
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

            // Apply source filter
            if let source = sourceFilter {
                fetched = fetched.filter { $0.sourceType == source }
            }

            recordings = fetched

        } catch {
            logger.error("Error fetching recordings: \(error.localizedDescription)")
        }
    }

    // MARK: - Recording

    func startRecording() async {
        guard let url = await recorderService.startRecording() else { return }
        // URL is stored internally by the recorder
    }

    func stopRecording() async {
        guard let result = recorderService.stopRecording() else { return }

        // Create recording entry
        let title = generateRecordingTitle()
        let fileName = result.url.lastPathComponent
        let fileSize = (try? FileManager.default.attributesOfItem(atPath: result.url.path)[.size] as? Int64) ?? 0

        let recording = Recording(
            title: title,
            duration: result.duration,
            audioFileName: fileName,
            audioFileSize: fileSize,
            transcriptionStatus: .pending,
            sourceType: .recorded
        )

        // Save to database
        guard let context = modelContext else { return }
        context.insert(recording)

        do {
            try context.save()
            await fetchRecordings()

            // Start transcription
            await transcribe(recording)
        } catch {
            errorMessage = "Failed to save recording: \(error.localizedDescription)"
        }
    }

    func cancelRecording() {
        recorderService.cancelRecording()
    }

    private func generateRecordingTitle() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: Date())
    }

    // MARK: - Import

    func importFile() async {
        guard let url = MediaImportService.shared.showOpenPanel() else { return }

        isImporting = true
        defer { isImporting = false }

        do {
            let result = try await MediaImportService.shared.importFile(url: url)

            let title = result.originalFileName
                .replacingOccurrences(of: "_", with: " ")
                .components(separatedBy: ".").dropLast().joined(separator: ".")

            let recording = Recording(
                title: title.isEmpty ? "Imported Recording" : title,
                duration: result.duration,
                audioFileName: result.audioURL.lastPathComponent,
                audioFileSize: result.fileSize,
                transcriptionStatus: .pending,
                sourceType: .imported
            )
            recording.originalFileName = result.originalFileName

            guard let context = modelContext else { return }
            context.insert(recording)

            try context.save()
            await fetchRecordings()

            // Start transcription
            await transcribe(recording)

        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Transcription

    func transcribe(_ recording: Recording) async {
        guard let audioURL = recording.audioURL else {
            recording.transcriptionStatus = .failed
            recording.transcriptionError = "Audio file not found"
            return
        }

        recording.transcriptionStatus = .inProgress

        do {
            try modelContext?.save()
        } catch {
            logger.error("Error saving transcription status: \(error.localizedDescription)")
        }

        do {
            let segments = try await TranscriptionService.shared.transcribe(audioURL: audioURL)

            // Add segments to recording
            for segment in segments {
                segment.recording = recording
                modelContext?.insert(segment)
            }

            recording.transcriptionStatus = .completed
            recording.transcriptionModel = TranscriptionService.shared.selectedModel.rawValue

            try modelContext?.save()

            // Auto-copy if enabled
            if UserDefaults.standard.bool(forKey: "autoCopyToClipboard") {
                ExportService.shared.copyToClipboard(segments: segments)
            }

            // Auto-delete audio if enabled
            if UserDefaults.standard.bool(forKey: "autoDeleteAudio") {
                await deleteAudio(for: recording)
            }

        } catch {
            recording.transcriptionStatus = .failed
            recording.transcriptionError = error.localizedDescription
            try? modelContext?.save()
        }

        await fetchRecordings()
    }

    // MARK: - CRUD Operations

    func toggleFavorite(_ recording: Recording) {
        recording.isFavorite.toggle()
        try? modelContext?.save()
    }

    func updateTitle(_ recording: Recording, title: String) {
        recording.title = title
        try? modelContext?.save()
    }

    func deleteRecording(_ recording: Recording) async {
        let retentionDays = UserDefaults.standard.integer(forKey: "recycleBinRetentionDays")

        if retentionDays > 0 {
            // Soft delete
            recording.isDeleted = true
            recording.deletedAt = Date()
        } else {
            // Permanent delete
            if let audioURL = recording.audioURL {
                try? FileManager.default.removeItem(at: audioURL)
            }
            modelContext?.delete(recording)
        }

        try? modelContext?.save()
        await fetchRecordings()
    }

    func deleteAudio(for recording: Recording) async {
        if let audioURL = recording.audioURL {
            try? FileManager.default.removeItem(at: audioURL)
        }
        recording.audioFileName = nil
        recording.audioFileSize = 0
        try? modelContext?.save()
        await fetchRecordings()
    }

    // MARK: - Batch Operations

    func toggleSelectionMode() {
        isSelectionMode.toggle()
        if !isSelectionMode {
            selectedRecordings.removeAll()
        }
    }

    func toggleSelection(_ recording: Recording) {
        if selectedRecordings.contains(recording.id) {
            selectedRecordings.remove(recording.id)
        } else {
            selectedRecordings.insert(recording.id)
        }
    }

    func selectAll() {
        selectedRecordings = Set(recordings.map { $0.id })
    }

    func deselectAll() {
        selectedRecordings.removeAll()
    }

    func selectAllPending() {
        selectedRecordings = Set(recordings.filter { $0.transcriptionStatus == .pending }.map { $0.id })
    }

    func transcribeSelected() async {
        let selectedList = recordings.filter { selectedRecordings.contains($0.id) }
        for recording in selectedList where recording.transcriptionStatus == .pending {
            await transcribe(recording)
        }
        toggleSelectionMode()
    }

    func deleteSelected() async {
        let selectedList = recordings.filter { selectedRecordings.contains($0.id) }
        for recording in selectedList {
            await deleteRecording(recording)
        }
        toggleSelectionMode()
    }
}

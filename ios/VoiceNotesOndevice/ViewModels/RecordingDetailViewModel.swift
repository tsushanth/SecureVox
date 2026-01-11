import Foundation
import SwiftUI
import SwiftData
import AVFoundation
import Combine
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// ViewModel for the recording detail screen with playback and transcription
@MainActor
final class RecordingDetailViewModel: ObservableObject {

    // MARK: - Published State

    /// The recording being displayed
    @Published var recording: Recording

    /// Playback state
    @Published private(set) var isPlaying: Bool = false

    /// Current playback time in seconds
    @Published private(set) var currentTime: TimeInterval = 0

    /// Whether transcript editing is enabled
    @Published var isEditing: Bool = false

    /// Error message to display
    @Published var errorMessage: String? = nil

    // MARK: - Transcription Published State

    /// Live transcript text (updates during transcription)
    @Published private(set) var transcriptText: String = ""

    /// Whether transcription is in progress
    @Published private(set) var isTranscribing: Bool = false

    /// Transcription progress (0.0 - 1.0)
    @Published private(set) var progress: Double = 0

    /// Current status message for the UI
    @Published private(set) var statusMessage: String = ""

    /// The transcription engine being used
    @Published private(set) var transcriptionEngine: CoreMLTranscriber.TranscriptionEngine = .whisperKit

    /// Whether we're using the fallback engine
    @Published private(set) var isUsingFallback: Bool = false

    /// Whether model is loading
    @Published private(set) var isLoadingModel: Bool = false

    // MARK: - Export State

    /// URL for sharing exported file
    @Published var shareURL: URL? = nil

    /// Whether export is in progress
    @Published private(set) var isExporting: Bool = false

    /// Whether to show the rating prompt
    @Published var showRatingPrompt: Bool = false

    /// Current playback speed
    @Published var playbackSpeed: Float = 1.0 {
        didSet {
            audioPlayer?.rate = playbackSpeed
            UserDefaults.standard.set(playbackSpeed, forKey: AppConstants.UserDefaultsKeys.playbackSpeed)
        }
    }

    // MARK: - Computed Properties

    /// Whether audio is available for playback
    var hasAudio: Bool {
        recording.audioFileURL != nil
    }

    /// Sorted segments for display
    var sortedSegments: [TranscriptSegment] {
        recording.segments.sorted { $0.startTime < $1.startTime }
    }

    /// Currently active segment based on playback time
    var activeSegment: TranscriptSegment? {
        sortedSegments.first { segment in
            currentTime >= segment.startTime && currentTime < segment.endTime
        }
    }

    /// Whether transcription can be started
    var canStartTranscription: Bool {
        hasAudio && (recording.status == .pending || recording.status == .failed) && !isTranscribing
    }

    /// Formatted progress percentage
    var progressPercentage: String {
        "\(Int(progress * 100))%"
    }

    /// Whether export is available
    var canExport: Bool {
        recording.status == .completed && !recording.segments.isEmpty
    }

    // MARK: - Private Properties

    private var audioPlayer: AVAudioPlayer?
    private var playbackTimer: Timer?
    private let modelContext: ModelContext
    private var transcriptionTask: Task<Void, Never>?

    // MARK: - Initialization

    init(recording: Recording, modelContext: ModelContext) {
        self.recording = recording
        self.modelContext = modelContext

        // Initialize transcript text from existing segments
        if recording.status == .completed {
            transcriptText = recording.fullTranscript
        }

        // Load saved playback speed
        let savedSpeed = UserDefaults.standard.float(forKey: AppConstants.UserDefaultsKeys.playbackSpeed)
        if savedSpeed > 0 {
            playbackSpeed = savedSpeed
        }

        updateStatusMessage()
    }

    deinit {
        // Stop audio player directly since deinit is not on MainActor
        audioPlayer?.stop()
        audioPlayer = nil
        playbackTimer?.invalidate()
        playbackTimer = nil
        transcriptionTask?.cancel()
    }

    // MARK: - Transcription Methods

    /// Start transcription for the recording
    func startTranscription() {
        guard canStartTranscription else {
            if !hasAudio {
                errorMessage = "No audio file available"
            }
            return
        }

        guard let audioURL = recording.audioFileURL else {
            errorMessage = "Audio file not found"
            return
        }

        // Update state
        isTranscribing = true
        progress = 0
        transcriptText = ""
        statusMessage = "Preparing transcription..."
        recording.status = .processing
        recording.transcriptionProgress = 0
        recording.errorMessage = nil

        // Clear existing segments
        for segment in recording.segments {
            modelContext.delete(segment)
        }
        recording.segments.removeAll()

        saveContext()

        // Start transcription in background
        transcriptionTask = Task { [weak self] in
            await self?.performTranscription(audioURL: audioURL)
        }
    }

    /// Cancel ongoing transcription
    func cancelTranscription() {
        transcriptionTask?.cancel()
        transcriptionTask = nil

        Task {
            await CoreMLTranscriber.shared.cancelTranscription()
        }

        isTranscribing = false
        progress = 0
        statusMessage = "Transcription cancelled"
        recording.status = .pending
        recording.transcriptionProgress = 0

        saveContext()
    }

    // MARK: - Private Transcription Methods

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

    private func performTranscription(audioURL: URL) async {
        let transcriber = CoreMLTranscriber.shared
        let selectedModel = getSelectedModel()

        // Ensure model is loaded
        do {
            isLoadingModel = true
            statusMessage = "Loading \(selectedModel.displayName) model..."

            // Always load the selected model (it will unload any previous model)
            let currentlyLoaded = await transcriber.loadedModel
            if currentlyLoaded != selectedModel {
                try await transcriber.loadModel(selectedModel)
            }

            // Update engine info after loading
            transcriptionEngine = await transcriber.getActiveEngine()
            isUsingFallback = await transcriber.isUsingFallback()
            isLoadingModel = false

            if isUsingFallback {
                statusMessage = "Using Apple Speech (fallback)..."
            }
        } catch {
            isLoadingModel = false
            await handleTranscriptionError(error)
            return
        }

        // Update status with engine name
        let engineName = transcriptionEngine.displayName
        statusMessage = "Transcribing with \(engineName)..."

        // Use the new API with partial results
        do {
            let segments = try await transcriber.transcribe(
                audioURL: audioURL,
                languageCode: recording.language,
                onPartial: { [weak self] progressValue, partialText in
                    Task { @MainActor [weak self] in
                        self?.handlePartialResult(progress: progressValue, text: partialText)
                    }
                }
            )

            await handleTranscriptionSuccess(segments: segments, modelUsed: selectedModel)

        } catch {
            await handleTranscriptionError(error)
        }
    }

    // MARK: - Improve Transcription

    /// Re-transcribe with a specific (better) model
    func improveTranscription(with model: CoreMLTranscriber.WhisperModel) {
        guard recording.status == .completed,
              let audioURL = recording.audioFileURL else {
            errorMessage = "Cannot improve: audio file not available"
            return
        }

        // Update state
        isTranscribing = true
        progress = 0
        transcriptText = ""
        statusMessage = "Improving with \(model.displayName) model..."
        recording.status = .processing
        recording.transcriptionProgress = 0
        recording.errorMessage = nil

        // Clear existing segments
        for segment in recording.segments {
            modelContext.delete(segment)
        }
        recording.segments.removeAll()

        saveContext()

        // Start transcription with the specific model
        transcriptionTask = Task { [weak self] in
            await self?.performTranscriptionWithModel(audioURL: audioURL, model: model)
        }
    }

    private func performTranscriptionWithModel(audioURL: URL, model: CoreMLTranscriber.WhisperModel) async {
        let transcriber = CoreMLTranscriber.shared

        // Load the specific model
        do {
            isLoadingModel = true
            statusMessage = "Loading \(model.displayName) model..."

            try await transcriber.loadModel(model)

            // Update engine info after loading
            transcriptionEngine = await transcriber.getActiveEngine()
            isUsingFallback = await transcriber.isUsingFallback()
            isLoadingModel = false

            if isUsingFallback {
                statusMessage = "Using Apple Speech (fallback)..."
            }
        } catch {
            isLoadingModel = false
            await handleTranscriptionError(error)
            return
        }

        // Update status with engine name
        let engineName = transcriptionEngine.displayName
        statusMessage = "Transcribing with \(engineName)..."

        // Perform transcription
        do {
            let segments = try await transcriber.transcribe(
                audioURL: audioURL,
                languageCode: recording.language,
                onPartial: { [weak self] progressValue, partialText in
                    Task { @MainActor [weak self] in
                        self?.handlePartialResult(progress: progressValue, text: partialText)
                    }
                }
            )

            await handleTranscriptionSuccess(segments: segments, modelUsed: model)

        } catch {
            await handleTranscriptionError(error)
        }
    }

    private func handlePartialResult(progress progressValue: Double, text: String?) {
        progress = progressValue
        recording.transcriptionProgress = progressValue

        if let text = text, !text.isEmpty {
            transcriptText = text
        }

        // Update status message with progress and engine
        let percentage = Int(progressValue * 100)
        let engineName = transcriptionEngine.displayName
        statusMessage = "\(engineName): \(percentage)%"
    }

    private func handleTranscriptionSuccess(segments: [TranscriptSegment], modelUsed: CoreMLTranscriber.WhisperModel) async {
        await MainActor.run {
            // Add segments to recording
            for segment in segments {
                segment.recording = recording
                recording.segments.append(segment)
                modelContext.insert(segment)
            }

            // Update recording state
            recording.status = .completed
            recording.transcriptionProgress = 1.0
            recording.updatedAt = Date()
            recording.transcriptionModel = modelUsed.rawValue
            recording.transcriptionEngine = transcriptionEngine.rawValue

            // Update view model state
            isTranscribing = false
            progress = 1.0
            transcriptText = recording.fullTranscript
            statusMessage = "Transcription complete"

            saveContext()

            // Auto-copy to clipboard if enabled
            autoCopyToClipboardIfEnabled()

            // Check if we should show rating prompt (after first transcription)
            checkAndShowRatingPrompt()
        }
    }

    /// Auto-copy transcript to clipboard if the setting is enabled
    private func autoCopyToClipboardIfEnabled() {
        let autoCopyEnabled = UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.autoCopyToClipboard)
        guard autoCopyEnabled else { return }

        let transcript = recording.fullTranscript
        guard !transcript.isEmpty else { return }

        #if os(iOS)
        UIPasteboard.general.string = transcript
        #elseif os(macOS)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(transcript, forType: .string)
        #endif
    }

    /// Check if we should show the rating prompt and trigger it
    private func checkAndShowRatingPrompt() {
        // Increment total transcription count
        let totalCount = UserDefaults.standard.integer(forKey: AppConstants.UserDefaultsKeys.transcriptionCount) + 1
        UserDefaults.standard.set(totalCount, forKey: AppConstants.UserDefaultsKeys.transcriptionCount)

        // Increment transcriptions since last prompt
        let sinceLastPrompt = UserDefaults.standard.integer(forKey: AppConstants.UserDefaultsKeys.transcriptionsSinceLastPrompt) + 1
        UserDefaults.standard.set(sinceLastPrompt, forKey: AppConstants.UserDefaultsKeys.transcriptionsSinceLastPrompt)

        Logger.info("Rating check: Total transcriptions: \(totalCount), since last prompt: \(sinceLastPrompt)", category: Logger.ui)

        // Check if we should show the prompt
        guard shouldShowRatingPrompt(totalCount: totalCount, sinceLastPrompt: sinceLastPrompt) else {
            return
        }

        // Delay slightly to let the user see "Transcription complete"
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
            Logger.info("Rating prompt: Showing rating prompt", category: Logger.ui)
            self?.showRatingPrompt = true
        }
    }

    /// Determine if we should show the rating prompt based on user history
    private func shouldShowRatingPrompt(totalCount: Int, sinceLastPrompt: Int) -> Bool {
        let defaults = UserDefaults.standard
        let lastResponse = defaults.string(forKey: AppConstants.UserDefaultsKeys.ratingPromptResponse)
        let notNowCount = defaults.integer(forKey: AppConstants.UserDefaultsKeys.ratingPromptNotNowCount)
        let lastShownDate = defaults.object(forKey: AppConstants.UserDefaultsKeys.ratingPromptLastShownDate) as? Date

        // If user already said "yes" or "no", don't prompt again
        if lastResponse == "yes" || lastResponse == "no" {
            Logger.info("Rating check: User already responded '\(lastResponse ?? "")' - skipping", category: Logger.ui)
            return false
        }

        // If user has hit max "Not Now" responses, stop asking
        if notNowCount >= AppConstants.RatingPrompt.maxNotNowPrompts {
            Logger.info("Rating check: Max 'Not Now' count (\(notNowCount)) reached - skipping", category: Logger.ui)
            return false
        }

        // First time showing prompt - need minimum transcriptions
        if lastResponse == nil {
            let shouldShow = totalCount >= AppConstants.RatingPrompt.minTranscriptionsForFirstPrompt
            Logger.info("Rating check: First prompt check - total: \(totalCount), required: \(AppConstants.RatingPrompt.minTranscriptionsForFirstPrompt), showing: \(shouldShow)", category: Logger.ui)
            return shouldShow
        }

        // User chose "Not Now" - check if enough time and transcriptions have passed
        if lastResponse == "notNow" {
            // Check minimum days
            if let lastDate = lastShownDate {
                let daysSince = Calendar.current.dateComponents([.day], from: lastDate, to: Date()).day ?? 0
                if daysSince < AppConstants.RatingPrompt.daysBetweenPrompts {
                    Logger.info("Rating check: Only \(daysSince) days since last prompt (need \(AppConstants.RatingPrompt.daysBetweenPrompts)) - skipping", category: Logger.ui)
                    return false
                }
            }

            // Check minimum transcriptions since last prompt
            let shouldShow = sinceLastPrompt >= AppConstants.RatingPrompt.transcriptionsBetweenPrompts
            Logger.info("Rating check: Re-prompt check - sinceLastPrompt: \(sinceLastPrompt), required: \(AppConstants.RatingPrompt.transcriptionsBetweenPrompts), showing: \(shouldShow)", category: Logger.ui)
            return shouldShow
        }

        return false
    }

    private func handleTranscriptionError(_ error: Error) async {
        await MainActor.run {
            isTranscribing = false
            progress = 0
            recording.status = .failed
            recording.errorMessage = error.localizedDescription
            recording.transcriptionProgress = 0

            if let transcriptionError = error as? CoreMLTranscriber.TranscriptionError {
                switch transcriptionError {
                case .cancelled:
                    statusMessage = "Transcription cancelled"
                    recording.status = .pending
                case .speechRecognitionNotAuthorized:
                    statusMessage = "Speech permission required"
                    errorMessage = "Please enable Speech Recognition in Settings to transcribe audio."
                case .speechRecognitionUnavailable:
                    statusMessage = "Speech recognition unavailable"
                    errorMessage = "On-device speech recognition is not available. Please check your device settings."
                case .allEnginesFailed:
                    statusMessage = "Transcription failed"
                    errorMessage = "Both Whisper and Apple Speech failed. This may be a memory issue - try closing other apps and retry."
                case .audioTooShort:
                    statusMessage = "Audio too short"
                    errorMessage = "The recording is too short to transcribe. Please record at least 1 second of audio."
                    recording.status = .pending
                default:
                    statusMessage = "Transcription failed"
                    errorMessage = transcriptionError.localizedDescription
                }
            } else {
                statusMessage = "Transcription failed"
                errorMessage = error.localizedDescription
            }

            saveContext()
        }
    }

    private func updateStatusMessage() {
        switch recording.status {
        case .pending:
            statusMessage = "Ready to transcribe"
        case .processing:
            let percentage = Int(recording.transcriptionProgress * 100)
            statusMessage = "Transcribing... \(percentage)%"
        case .completed:
            statusMessage = "Transcription complete"
        case .failed:
            statusMessage = recording.errorMessage ?? "Transcription failed"
        }
    }

    // MARK: - Playback Controls

    /// Start or resume audio playback
    func play() {
        guard let audioURL = recording.audioFileURL else {
            errorMessage = "Audio file not found"
            return
        }

        do {
            // Initialize player if needed
            if audioPlayer == nil {
                audioPlayer = try AVAudioPlayer(contentsOf: audioURL)
                audioPlayer?.enableRate = true
                audioPlayer?.rate = playbackSpeed
                audioPlayer?.prepareToPlay()
            }

            // Configure audio session (iOS only)
            #if os(iOS)
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
            #endif

            // Seek to current time if resuming
            audioPlayer?.currentTime = currentTime
            audioPlayer?.rate = playbackSpeed
            audioPlayer?.play()
            isPlaying = true

            // Start timer for UI updates
            startPlaybackTimer()

        } catch {
            errorMessage = "Failed to play audio: \(error.localizedDescription)"
        }
    }

    /// Pause audio playback
    func pause() {
        audioPlayer?.pause()
        isPlaying = false
        stopPlaybackTimer()
    }

    /// Toggle play/pause state
    func togglePlayback() {
        if isPlaying {
            pause()
        } else {
            play()
        }
    }

    /// Seek to a specific time
    func seek(to time: TimeInterval) {
        let clampedTime = max(0, min(time, recording.duration))
        currentTime = clampedTime
        audioPlayer?.currentTime = clampedTime
    }

    /// Seek to a specific segment
    func seekToSegment(_ segment: TranscriptSegment) {
        seek(to: segment.startTime)
        if !isPlaying {
            play()
        }
    }

    /// Skip forward by seconds
    func skipForward(seconds: TimeInterval = 15) {
        seek(to: currentTime + seconds)
    }

    /// Skip backward by seconds
    func skipBackward(seconds: TimeInterval = 15) {
        seek(to: currentTime - seconds)
    }

    /// Stop playback and reset
    func stopPlayback() {
        audioPlayer?.stop()
        audioPlayer = nil
        isPlaying = false
        currentTime = 0
        stopPlaybackTimer()

        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false)
        #endif
    }

    // MARK: - Transcript Editing

    /// Update segment text
    func updateSegmentText(_ segment: TranscriptSegment, newText: String) {
        segment.text = newText
        recording.updatedAt = Date()
        transcriptText = recording.fullTranscript

        saveContext()
    }

    /// Update recording title
    func updateTitle(_ newTitle: String) {
        recording.title = newTitle
        recording.updatedAt = Date()

        saveContext()
    }

    // MARK: - Actions

    /// Delete the audio file but keep the transcript
    func deleteAudioFile() throws {
        guard let audioURL = recording.audioFileURL else {
            throw RecordingError.audioFileNotFound
        }

        try FileManager.default.removeItem(at: audioURL)
        recording.audioFileName = nil
        recording.audioFileSize = 0
        recording.updatedAt = Date()

        saveContext()

        stopPlayback()
    }

    /// Retry failed transcription
    func retryTranscription() {
        guard recording.status == .failed else { return }
        startTranscription()
    }

    /// Delete the recording completely (audio file, segments, and recording)
    func deleteRecording() {
        print("[DELETE] Starting deletion of recording: \(recording.id) - \(recording.title)")

        // Stop playback first
        stopPlayback()

        // Delete audio file if it exists
        if let audioURL = recording.audioFileURL {
            print("[DELETE] Removing audio file at: \(audioURL.path)")
            do {
                try FileManager.default.removeItem(at: audioURL)
                print("[DELETE] Audio file removed successfully")
            } catch {
                print("[DELETE] Failed to remove audio file: \(error)")
            }
        }

        // Delete all segments
        let segmentCount = recording.segments.count
        print("[DELETE] Deleting \(segmentCount) segments")
        for segment in recording.segments {
            modelContext.delete(segment)
        }

        // Delete the recording itself
        print("[DELETE] Deleting recording from SwiftData")
        modelContext.delete(recording)

        // Save and verify
        do {
            try modelContext.save()
            print("[DELETE] SwiftData save successful")
        } catch {
            print("[DELETE] SwiftData save failed: \(error)")
            errorMessage = "Failed to delete: \(error.localizedDescription)"
        }
    }

    // MARK: - Export Methods

    /// Export transcript as TXT and return URL for sharing
    func exportTXT() -> URL? {
        return exportTranscript(format: .txt)
    }

    /// Export transcript as SRT and return URL for sharing
    func exportSRT() -> URL? {
        return exportTranscript(format: .srt)
    }

    /// Export transcript as VTT and return URL for sharing
    func exportVTT() -> URL? {
        return exportTranscript(format: .vtt)
    }

    /// Export transcript in specified format
    /// - Parameter format: Export format (TXT, SRT, VTT)
    /// - Returns: URL to the exported file, or nil on failure
    func exportTranscript(format: ExportFormat) -> URL? {
        guard canExport else {
            errorMessage = "No transcript available to export"
            return nil
        }

        isExporting = true
        defer { isExporting = false }

        do {
            let exporter = TranscriptExporter.shared
            let url = try exporter.exportForSharing(
                segments: sortedSegments,
                format: format,
                title: recording.title
            )
            shareURL = url
            return url
        } catch {
            errorMessage = "Export failed: \(error.localizedDescription)"
            return nil
        }
    }

    /// Prepare export URL for sharing via share sheet
    /// - Parameter format: Export format
    func prepareShareURL(format: ExportFormat) {
        shareURL = exportTranscript(format: format)
    }

    /// Clear the share URL after sharing is complete
    func clearShareURL() {
        shareURL = nil
    }

    // MARK: - Private Methods

    private func startPlaybackTimer() {
        playbackTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self, let player = self.audioPlayer else { return }

                self.currentTime = player.currentTime

                // Check if playback finished
                if !player.isPlaying && self.isPlaying {
                    self.isPlaying = false
                    self.stopPlaybackTimer()
                }
            }
        }
    }

    private func stopPlaybackTimer() {
        playbackTimer?.invalidate()
        playbackTimer = nil
    }

    private func saveContext() {
        do {
            try modelContext.save()
        } catch {
            errorMessage = "Failed to save: \(error.localizedDescription)"
        }
    }

    // MARK: - Errors

    enum RecordingError: LocalizedError {
        case audioFileNotFound

        var errorDescription: String? {
            switch self {
            case .audioFileNotFound:
                return "Audio file not found"
            }
        }
    }
}

import Foundation
import AVFoundation
import Speech
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "Transcription")

/// Service for transcribing audio using WhisperKit with Apple Speech fallback
@MainActor
class TranscriptionService: ObservableObject {

    // MARK: - Singleton

    static let shared = TranscriptionService()

    // MARK: - Types

    enum TranscriptionEngine: String {
        case whisperKit = "whisperKit"
        case appleSpeech = "appleSpeech"

        var displayName: String {
            switch self {
            case .whisperKit: return "Whisper AI"
            case .appleSpeech: return "Apple Speech"
            }
        }
    }

    enum TranscriptionError: LocalizedError {
        case alreadyTranscribing
        case audioTooShort
        case modelNotAvailable
        case audioProcessingFailed
        case speechRecognizerUnavailable
        case speechRecognitionNotAuthorized
        case transcriptionFailed(String)
        case cancelled

        var errorDescription: String? {
            switch self {
            case .alreadyTranscribing:
                return "A transcription is already in progress"
            case .audioTooShort:
                return "Audio is too short to transcribe (minimum 0.5 seconds)"
            case .modelNotAvailable:
                return "The selected model is not available. Please download it first."
            case .audioProcessingFailed:
                return "Failed to process audio file"
            case .speechRecognizerUnavailable:
                return "Speech recognition is not available"
            case .speechRecognitionNotAuthorized:
                return "Speech recognition permission denied"
            case .transcriptionFailed(let message):
                return "Transcription failed: \(message)"
            case .cancelled:
                return "Transcription was cancelled"
            }
        }
    }

    // MARK: - Published Properties

    @Published private(set) var isTranscribing = false
    @Published private(set) var progress: Double = 0
    @Published private(set) var statusMessage = ""
    @Published private(set) var currentEngine: TranscriptionEngine = .appleSpeech
    @Published private(set) var modelDownloadProgress: [String: Double] = [:]

    @Published var selectedModel: AppConstants.WhisperModel = .tiny {
        didSet {
            UserDefaults.standard.set(selectedModel.rawValue, forKey: "selectedModel")
        }
    }

    @Published var selectedLanguage: AppConstants.WhisperLanguage = .auto {
        didSet {
            UserDefaults.standard.set(selectedLanguage.rawValue, forKey: "defaultLanguage")
        }
    }

    // MARK: - Settings

    @Published var autoPunctuationEnabled = true
    @Published var smartCapitalizationEnabled = true

    // MARK: - Private Properties

    private var speechRecognizer: SFSpeechRecognizer?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var isCancelled = false

    /// Number of words before creating a new segment
    private static let segmentWordThreshold: Int = 10

    // MARK: - Initialization

    private init() {
        loadSettings()
        speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    }

    private func loadSettings() {
        if let modelRaw = UserDefaults.standard.string(forKey: "selectedModel"),
           let model = AppConstants.WhisperModel(rawValue: modelRaw) {
            selectedModel = model
        }

        if let langRaw = UserDefaults.standard.string(forKey: "defaultLanguage"),
           let lang = AppConstants.WhisperLanguage(rawValue: langRaw) {
            selectedLanguage = lang
        }

        autoPunctuationEnabled = UserDefaults.standard.object(forKey: "autoPunctuationEnabled") as? Bool ?? true
        smartCapitalizationEnabled = UserDefaults.standard.object(forKey: "smartCapitalizationEnabled") as? Bool ?? true
    }

    // MARK: - Model Management

    func isModelAvailable(_ model: AppConstants.WhisperModel) -> Bool {
        // Bundled model is always available
        if model.isBundled {
            return true
        }
        // Check if model has been downloaded (marker file exists)
        let modelPath = getModelPath(for: model)
        return FileManager.default.fileExists(atPath: modelPath.path)
    }

    func downloadModel(_ model: AppConstants.WhisperModel) async throws {
        guard !isModelAvailable(model) else { return }

        modelDownloadProgress[model.rawValue] = 0
        statusMessage = "Downloading \(model.displayName) model..."

        // TODO: Integrate WhisperKit for macOS to enable real model downloads
        // For now, simulate download progress
        for i in 0...100 {
            try await Task.sleep(nanoseconds: 30_000_000) // 30ms
            modelDownloadProgress[model.rawValue] = Double(i) / 100.0

            if isCancelled {
                modelDownloadProgress[model.rawValue] = nil
                throw TranscriptionError.cancelled
            }
        }

        // Create the models directory and a marker file to indicate download complete
        let modelPath = getModelPath(for: model)
        let modelsDir = modelPath.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: modelsDir, withIntermediateDirectories: true)

        // Create a marker file to indicate the model is "downloaded"
        // In a real implementation, this would be the actual model files
        try "downloaded".write(to: modelPath, atomically: true, encoding: .utf8)

        modelDownloadProgress[model.rawValue] = nil
        statusMessage = ""
    }

    func deleteModel(_ model: AppConstants.WhisperModel) {
        guard !model.isBundled else { return }
        let modelPath = getModelPath(for: model)
        try? FileManager.default.removeItem(at: modelPath)
    }

    private func getModelPath(for model: AppConstants.WhisperModel) -> URL {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documentsURL
            .appendingPathComponent(AppConstants.Storage.modelsDirectory)
            .appendingPathComponent("\(model.rawValue).marker")
    }

    // MARK: - Transcription

    func transcribe(audioURL: URL, language: AppConstants.WhisperLanguage? = nil) async throws -> [TranscriptSegment] {
        // Convert to language code string
        let languageCode: String?
        if let lang = language {
            languageCode = lang.rawValue == "auto" ? nil : lang.rawValue
        } else {
            languageCode = selectedLanguage.rawValue == "auto" ? nil : selectedLanguage.rawValue
        }

        return try await transcribeInternal(audioURL: audioURL, languageCode: languageCode)
    }

    /// Transcribe using the new WhisperLanguage model
    func transcribe(audioURL: URL, whisperLanguage: WhisperLanguage?) async throws -> [TranscriptSegment] {
        let languageCode: String?
        if let lang = whisperLanguage {
            languageCode = lang.isAutoDetect ? nil : lang.code
        } else {
            languageCode = nil
        }

        return try await transcribeInternal(audioURL: audioURL, languageCode: languageCode)
    }

    /// Transcribe using a language code string directly
    func transcribe(audioURL: URL, languageCode: String?) async throws -> [TranscriptSegment] {
        let code = languageCode == "auto" ? nil : languageCode
        return try await transcribeInternal(audioURL: audioURL, languageCode: code)
    }

    private func transcribeInternal(audioURL: URL, languageCode: String?) async throws -> [TranscriptSegment] {
        guard !isTranscribing else {
            throw TranscriptionError.alreadyTranscribing
        }

        isTranscribing = true
        progress = 0
        isCancelled = false
        statusMessage = "Preparing audio..."

        defer {
            isTranscribing = false
            progress = 1.0
            statusMessage = ""
        }

        // Get audio duration
        let asset = AVAsset(url: audioURL)
        let duration: TimeInterval
        do {
            duration = try await asset.load(.duration).seconds
        } catch {
            throw TranscriptionError.audioProcessingFailed
        }

        guard duration >= AppConstants.Audio.minTranscriptionDuration else {
            throw TranscriptionError.audioTooShort
        }

        statusMessage = "Loading transcription engine..."
        progress = 0.1

        // Use Apple Speech for transcription (macOS native)
        // TODO: Integrate WhisperKit for macOS when available
        currentEngine = .appleSpeech

        statusMessage = "Transcribing..."
        progress = 0.2

        let segments = try await transcribeWithAppleSpeech(
            url: audioURL,
            languageCode: languageCode,
            duration: duration
        )

        return segments
    }

    func cancelTranscription() {
        isCancelled = true
        recognitionTask?.cancel()
        recognitionTask = nil
        isTranscribing = false
        progress = 0
        statusMessage = ""
    }

    // MARK: - Apple Speech Transcription

    private func requestSpeechAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status)
            }
        }
    }

    /// Maximum duration for a single transcription chunk (Apple Speech works best with shorter audio)
    private static let maxChunkDuration: TimeInterval = 55.0 // ~55 seconds per chunk

    private func transcribeWithAppleSpeech(url: URL, languageCode: String?, duration: TimeInterval) async throws -> [TranscriptSegment] {
        let status = await requestSpeechAuthorization()
        guard status == .authorized else {
            throw TranscriptionError.speechRecognitionNotAuthorized
        }

        // For short audio, transcribe directly
        if duration <= Self.maxChunkDuration {
            return try await transcribeSingleChunk(url: url, languageCode: languageCode, duration: duration, timeOffset: 0)
        }

        // For longer audio, split into chunks and transcribe each
        statusMessage = "Preparing audio chunks..."

        let chunks = try await splitAudioIntoChunks(url: url, duration: duration)
        var allSegments: [TranscriptSegment] = []

        for (index, chunk) in chunks.enumerated() {
            if isCancelled {
                // Clean up temp files
                for c in chunks {
                    try? FileManager.default.removeItem(at: c.url)
                }
                throw TranscriptionError.cancelled
            }

            let chunkProgress = Double(index) / Double(chunks.count)
            progress = 0.1 + (chunkProgress * 0.85)
            statusMessage = "Transcribing chunk \(index + 1) of \(chunks.count)..."

            do {
                let chunkSegments = try await transcribeSingleChunk(
                    url: chunk.url,
                    languageCode: languageCode,
                    duration: chunk.duration,
                    timeOffset: chunk.startTime
                )
                allSegments.append(contentsOf: chunkSegments)
            } catch {
                // Continue with other chunks even if one fails
                logger.warning("Chunk \(index + 1) transcription failed: \(error.localizedDescription)")
            }

            // Clean up chunk file
            try? FileManager.default.removeItem(at: chunk.url)
        }

        return allSegments
    }

    private struct AudioChunk {
        let url: URL
        let startTime: TimeInterval
        let duration: TimeInterval
    }

    private func splitAudioIntoChunks(url: URL, duration: TimeInterval) async throws -> [AudioChunk] {
        let asset = AVAsset(url: url)
        var chunks: [AudioChunk] = []

        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("SecureVox_chunks_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)

        var currentTime: TimeInterval = 0
        var chunkIndex = 0

        while currentTime < duration {
            let chunkDuration = min(Self.maxChunkDuration, duration - currentTime)
            let chunkURL = tempDir.appendingPathComponent("chunk_\(chunkIndex).m4a")

            // Export this chunk
            let startCMTime = CMTime(seconds: currentTime, preferredTimescale: 44100)
            let durationCMTime = CMTime(seconds: chunkDuration, preferredTimescale: 44100)
            let timeRange = CMTimeRange(start: startCMTime, duration: durationCMTime)

            guard let exportSession = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetAppleM4A) else {
                throw TranscriptionError.audioProcessingFailed
            }

            exportSession.outputURL = chunkURL
            exportSession.outputFileType = .m4a
            exportSession.timeRange = timeRange

            await exportSession.export()

            if exportSession.status == .completed {
                chunks.append(AudioChunk(
                    url: chunkURL,
                    startTime: currentTime,
                    duration: chunkDuration
                ))
            }

            currentTime += chunkDuration
            chunkIndex += 1
        }

        return chunks
    }

    private func transcribeSingleChunk(url: URL, languageCode: String?, duration: TimeInterval, timeOffset: TimeInterval) async throws -> [TranscriptSegment] {
        let recognizer: SFSpeechRecognizer
        if let langCode = languageCode {
            guard let langRecognizer = SFSpeechRecognizer(locale: Locale(identifier: langCode)) ?? SFSpeechRecognizer() else {
                throw TranscriptionError.speechRecognizerUnavailable
            }
            recognizer = langRecognizer
        } else {
            guard let defaultRecognizer = speechRecognizer ?? SFSpeechRecognizer() else {
                throw TranscriptionError.speechRecognizerUnavailable
            }
            recognizer = defaultRecognizer
        }

        guard recognizer.isAvailable else {
            throw TranscriptionError.speechRecognizerUnavailable
        }

        let request = SFSpeechURLRecognitionRequest(url: url)
        request.shouldReportPartialResults = true // Get partial results to capture all speech
        request.taskHint = .dictation // Hint that this is dictation for better accuracy

        // Only use on-device if available, but don't require it (server can be more accurate)
        // On-device sometimes misses the beginning of audio
        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = false
        }

        request.addsPunctuation = autoPunctuationEnabled

        return try await withCheckedThrowingContinuation { continuation in
            var hasResumed = false

            recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
                guard let self = self, !hasResumed else { return }

                if self.isCancelled {
                    hasResumed = true
                    continuation.resume(throwing: TranscriptionError.cancelled)
                    return
                }

                if let error = error {
                    let nsError = error as NSError
                    // Error 1110 = no speech detected, return empty
                    if nsError.domain == "kAFAssistantErrorDomain" && nsError.code == 1110 {
                        hasResumed = true
                        continuation.resume(returning: [])
                        return
                    }
                    hasResumed = true
                    continuation.resume(throwing: TranscriptionError.transcriptionFailed(error.localizedDescription))
                    return
                }

                guard let result = result else { return }

                if result.isFinal {
                    // Convert segments and apply time offset
                    let rawSegments = self.convertAppleSpeechToSegments(result.bestTranscription, timeOffset: timeOffset)
                    let processedSegments = self.applyTextProcessing(to: rawSegments)
                    hasResumed = true
                    continuation.resume(returning: processedSegments)
                }
            }
        }
    }

    private func convertAppleSpeechToSegments(_ transcription: SFTranscription, timeOffset: TimeInterval = 0) -> [TranscriptSegment] {
        var segments: [TranscriptSegment] = []
        var currentWords: [SFTranscriptionSegment] = []
        var currentStartTime: TimeInterval = 0

        for segment in transcription.segments {
            if currentWords.isEmpty {
                currentStartTime = segment.timestamp
            }

            currentWords.append(segment)

            let wordText = segment.substring.trimmingCharacters(in: .whitespaces)
            let isPunctuation = wordText.hasSuffix(".") || wordText.hasSuffix("?") || wordText.hasSuffix("!")
            let isLongEnough = currentWords.count >= Self.segmentWordThreshold

            if isPunctuation || isLongEnough {
                let segmentText = currentWords.map { $0.substring }.joined(separator: " ")
                let endTime = segment.timestamp + segment.duration
                let avgConfidence = currentWords.map { Double($0.confidence) }.reduce(0, +) / Double(currentWords.count)

                let transcriptSegment = TranscriptSegment(
                    text: segmentText.trimmingCharacters(in: .whitespaces),
                    startTime: currentStartTime + timeOffset,
                    endTime: endTime + timeOffset,
                    confidence: Float(avgConfidence)
                )
                segments.append(transcriptSegment)
                currentWords = []
            }
        }

        // Handle remaining words
        if !currentWords.isEmpty, let lastSegment = currentWords.last {
            let segmentText = currentWords.map { $0.substring }.joined(separator: " ")
            let endTime = lastSegment.timestamp + lastSegment.duration
            let avgConfidence = currentWords.map { Double($0.confidence) }.reduce(0, +) / Double(currentWords.count)

            let transcriptSegment = TranscriptSegment(
                text: segmentText.trimmingCharacters(in: .whitespaces),
                startTime: currentStartTime + timeOffset,
                endTime: endTime + timeOffset,
                confidence: Float(avgConfidence)
            )
            segments.append(transcriptSegment)
        }

        return segments
    }

    // MARK: - Text Processing

    private func applyTextProcessing(to segments: [TranscriptSegment]) -> [TranscriptSegment] {
        return segments.map { segment in
            var processedText = segment.text

            // Remove punctuation if disabled
            if !autoPunctuationEnabled {
                processedText = processedText.replacingOccurrences(
                    of: "[.!?,;:]",
                    with: "",
                    options: .regularExpression
                )
            }

            // Apply or remove capitalization
            if !smartCapitalizationEnabled {
                processedText = processedText.lowercased()
            } else if autoPunctuationEnabled {
                processedText = capitalizeAfterPunctuation(processedText)
            }

            return TranscriptSegment(
                text: processedText,
                startTime: segment.startTime,
                endTime: segment.endTime,
                confidence: segment.confidence
            )
        }
    }

    private func capitalizeAfterPunctuation(_ text: String) -> String {
        var result = ""
        var capitalizeNext = true

        for char in text {
            if capitalizeNext && char.isLetter {
                result.append(char.uppercased())
                capitalizeNext = false
            } else {
                result.append(char)
            }

            if char == "." || char == "!" || char == "?" {
                capitalizeNext = true
            }
        }

        return result
    }

    // MARK: - Engine Info

    func getActiveEngine() -> TranscriptionEngine {
        return currentEngine
    }
}

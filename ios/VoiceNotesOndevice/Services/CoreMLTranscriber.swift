import Foundation
import WhisperKit
import Speech
import AVFoundation

/// Actor responsible for running on-device speech transcription.
/// Uses WhisperKit as primary engine with Apple Speech as fallback for memory-constrained devices.
actor CoreMLTranscriber {

    // MARK: - Types

    enum WhisperModel: String, CaseIterable {
        case tiny = "openai_whisper-tiny"
        case base = "openai_whisper-base"
        case small = "openai_whisper-small"

        var displayName: String {
            switch self {
            case .tiny: return "Fast"
            case .base: return "Balanced"
            case .small: return "Accurate"
            }
        }

        /// Legacy raw value for compatibility
        var legacyRawValue: String {
            switch self {
            case .tiny: return "whisper-tiny"
            case .base: return "whisper-base"
            case .small: return "whisper-small"
            }
        }

        init?(legacyRawValue: String) {
            switch legacyRawValue {
            case "whisper-tiny": self = .tiny
            case "whisper-base": self = .base
            case "whisper-small": self = .small
            default: return nil
            }
        }
    }

    enum TranscriptionEngine: String {
        case whisperKit = "whisperKit"
        case appleSpeech = "appleSpeech"

        var displayName: String {
            switch self {
            case .whisperKit: return "Whisper AI"
            case .appleSpeech: return "Apple Speech"
            }
        }

        var icon: String {
            switch self {
            case .whisperKit: return "waveform.circle.fill"
            case .appleSpeech: return "apple.logo"
            }
        }
    }

    struct TranscriptionResult {
        let segments: [SegmentResult]
        let detectedLanguage: String?
        let processingTime: TimeInterval
    }

    struct SegmentResult {
        let startTime: TimeInterval
        let endTime: TimeInterval
        let text: String
        let confidence: Double?
    }

    struct TranscriptionProgress {
        let fractionCompleted: Double
        let currentChunk: Int
        let totalChunks: Int
        let estimatedTimeRemaining: TimeInterval?
    }

    enum TranscriptionError: Error, LocalizedError {
        case modelNotLoaded
        case modelLoadFailed(Error)
        case audioPreprocessingFailed(Error)
        case inferenceFailed(Error)
        case cancelled
        case unsupportedAudioFormat
        case audioTooShort
        case whisperKitError(String)
        case speechRecognitionUnavailable
        case speechRecognitionNotAuthorized
        case allEnginesFailed

        var errorDescription: String? {
            switch self {
            case .modelNotLoaded:
                return "Transcription model is not loaded"
            case .modelLoadFailed(let error):
                return "Failed to load model: \(error.localizedDescription)"
            case .audioPreprocessingFailed(let error):
                return "Audio processing error: \(error.localizedDescription)"
            case .inferenceFailed(let error):
                return "Transcription error: \(error.localizedDescription)"
            case .cancelled:
                return "Transcription was cancelled"
            case .unsupportedAudioFormat:
                return "Unsupported audio format"
            case .audioTooShort:
                return "Audio is too short to transcribe"
            case .whisperKitError(let message):
                return "WhisperKit error: \(message)"
            case .speechRecognitionUnavailable:
                return "Speech recognition is not available"
            case .speechRecognitionNotAuthorized:
                return "Speech recognition permission denied"
            case .allEnginesFailed:
                return "All transcription engines failed. Please try again."
            }
        }
    }

    // MARK: - Device Capability

    /// Determines if the device has Neural Engine support (A12+, iPhone XS/XR and later)
    /// These devices can efficiently run larger Whisper models with GPU/ANE acceleration
    static var hasNeuralEngineSupport: Bool {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machineMirror = Mirror(reflecting: systemInfo.machine)
        let identifier = machineMirror.children.reduce("") { identifier, element in
            guard let value = element.value as? Int8, value != 0 else { return identifier }
            return identifier + String(UnicodeScalar(UInt8(value)))
        }

        // Parse device identifier (e.g., "iPhone14,5" -> major version 14)
        // iPhone 11 = iPhone12,x (A13)
        // iPhone 12 = iPhone13,x (A14)
        // iPhone 13 = iPhone14,x (A15)
        // iPhone 14 = iPhone15,x (A15/A16)
        // iPhone 15 = iPhone16,x (A16/A17)
        // We want iPhone 12+ (iPhone13,x and later) for best Neural Engine performance

        if identifier.hasPrefix("iPhone") {
            let versionString = identifier.dropFirst(6) // Remove "iPhone"
            if let commaIndex = versionString.firstIndex(of: ","),
               let majorVersion = Int(versionString[..<commaIndex]) {
                // iPhone13,x = iPhone 12, which has A14 with excellent ANE
                return majorVersion >= 13
            }
        }

        // iPad with A12+ also has good ANE support
        if identifier.hasPrefix("iPad") {
            // Most recent iPads have good ANE, be permissive
            return true
        }

        // Simulator - allow for testing
        if identifier.contains("arm64") || identifier == "x86_64" {
            return true
        }

        return false
    }

    /// Returns recommended compute options based on device capability and model size
    static func computeOptions(for model: WhisperModel) -> ModelComputeOptions {
        if hasNeuralEngineSupport {
            // Device has Neural Engine - use automatic selection for best performance
            // WhisperKit will choose between CPU, GPU, and ANE based on what's optimal
            print("[CoreMLTranscriber] Device has Neural Engine support, using automatic compute selection")
            return ModelComputeOptions(
                audioEncoderCompute: .all,
                textDecoderCompute: .all
            )
        } else {
            // Older device - use CPU only to avoid memory issues
            print("[CoreMLTranscriber] Device lacks Neural Engine, using CPU-only mode")
            return ModelComputeOptions(
                audioEncoderCompute: .cpuOnly,
                textDecoderCompute: .cpuOnly
            )
        }
    }

    // MARK: - Properties

    private(set) var loadedModel: WhisperModel?
    private(set) var isProcessing: Bool = false
    private(set) var currentEngine: TranscriptionEngine = .whisperKit
    private var whisperKit: WhisperKit?
    private var speechRecognizer: SFSpeechRecognizer?
    private var recognitionTask: SFSpeechRecognitionTask?
    private var isCancelled: Bool = false
    private var whisperKitFailed: Bool = false

    /// Tracks number of concurrent transcription operations to prevent race conditions
    private var activeTranscriptionCount: Int = 0

    /// Model loading progress (0.0 - 1.0) for UI indication
    /// - 0.0: Not started
    /// - 0.1-0.3: Downloading model (if needed)
    /// - 0.3-0.9: Loading/compiling model
    /// - 1.0: Complete
    private(set) var modelLoadProgress: Double = 0

    /// Whether a model download is in progress
    private(set) var isDownloadingModel: Bool = false

    // MARK: - Transcription Configuration

    /// Number of words before creating a new segment (configurable threshold)
    static let segmentWordThreshold: Int = 10

    // MARK: - Singleton

    static let shared = CoreMLTranscriber()

    private init() {
        // Initialize Apple Speech as fallback
        speechRecognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US"))
    }

    // MARK: - Public Methods

    /// Load a Whisper model into memory
    func loadModel(_ model: WhisperModel) async throws {
        // Unload any existing model first to free memory
        unloadModel()

        // Reset whisperKitFailed when explicitly loading a new model
        // This gives WhisperKit another chance if user selects a different model
        whisperKitFailed = false

        // Try WhisperKit first
        do {
            try await loadWhisperKit(model)
            currentEngine = .whisperKit
            loadedModel = model
            print("[CoreMLTranscriber] Successfully loaded WhisperKit with model: \(model.rawValue)")
            return
        } catch {
            print("[CoreMLTranscriber] WhisperKit failed to load: \(error.localizedDescription)")
            print("[CoreMLTranscriber] Falling back to Apple Speech...")
            whisperKitFailed = true
        }

        // Fall back to Apple Speech
        try await loadAppleSpeech()
        currentEngine = .appleSpeech
        loadedModel = model
        print("[CoreMLTranscriber] Using Apple Speech fallback")
    }

    /// Timeout duration for model loading (2 minutes for downloads, 30 seconds for bundled)
    private static let modelLoadTimeoutBundled: TimeInterval = 30
    private static let modelLoadTimeoutDownload: TimeInterval = 120

    private func loadWhisperKit(_ model: WhisperModel) async throws {
        print("[CoreMLTranscriber] Loading WhisperKit model: \(model.rawValue)")
        print("[CoreMLTranscriber] Device Neural Engine support: \(Self.hasNeuralEngineSupport)")

        // Reset progress tracking
        modelLoadProgress = 0
        isDownloadingModel = false

        // Get compute options based on device capability
        let computeOptions = Self.computeOptions(for: model)

        // Check if model is bundled with the app
        let modelFolderPath: String?
        if model == .tiny, let bundledPath = Bundle.main.path(forResource: "openai_whisper-tiny", ofType: nil, inDirectory: "Models/whisperkit-temp") {
            // Use bundled model folder directly - WhisperKit expects the folder containing .mlmodelc files
            modelFolderPath = bundledPath
            print("[CoreMLTranscriber] Using bundled model at: \(bundledPath)")
            modelLoadProgress = 0.3 // Skip download phase
        } else {
            // Download model on-demand
            modelFolderPath = nil
            isDownloadingModel = true
            print("[CoreMLTranscriber] Model not bundled, will download: \(model.rawValue)")
            modelLoadProgress = 0.05
        }

        // Determine timeout based on whether we need to download
        let timeout = modelFolderPath != nil ? Self.modelLoadTimeoutBundled : Self.modelLoadTimeoutDownload

        // Initialize WhisperKit with timeout
        print("[CoreMLTranscriber] Initializing WhisperKit (download=\(modelFolderPath == nil), timeout=\(timeout)s)...")

        do {
            // Start progress simulation task (will be cancelled when model loads)
            let progressTask = Task { [weak self] in
                let progressIntervalNs: UInt64 = 2_000_000_000 // 2 seconds
                let maxIntervals = Int(timeout / 2)
                let isDownloading = await self?.isDownloadingModel ?? false

                for i in 1...maxIntervals {
                    try Task.checkCancellation()
                    try await Task.sleep(nanoseconds: progressIntervalNs)
                    // Update progress on main actor
                    let baseProgress = isDownloading ? 0.05 : 0.3
                    let progressRange = 0.6
                    let simulatedProgress = baseProgress + (Double(i) / Double(maxIntervals)) * progressRange
                    await self?.updateModelLoadProgress(min(0.9, simulatedProgress))
                }
            }

            whisperKit = try await withThrowingTaskGroup(of: WhisperKit.self) { group in
                // Task to load the model
                group.addTask {
                    try await WhisperKit(
                        model: model.rawValue,
                        modelFolder: modelFolderPath,
                        computeOptions: computeOptions,
                        verbose: true,
                        prewarm: false,
                        download: modelFolderPath == nil
                    )
                }

                // Task to enforce timeout
                group.addTask {
                    try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                    throw TranscriptionError.modelLoadFailed(
                        NSError(domain: "CoreMLTranscriber", code: -1, userInfo: [
                            NSLocalizedDescriptionKey: "Model loading timed out after \(Int(timeout)) seconds"
                        ])
                    )
                }

                // Return first completed result (either success or timeout)
                guard let result = try await group.next() else {
                    throw TranscriptionError.modelNotLoaded
                }

                // Cancel remaining tasks
                group.cancelAll()
                return result
            }

            // Cancel progress task and mark loading complete
            progressTask.cancel()
            modelLoadProgress = 1.0
            isDownloadingModel = false
            print("[CoreMLTranscriber] WhisperKit initialized successfully")
        } catch is CancellationError {
            modelLoadProgress = 0
            isDownloadingModel = false
            throw TranscriptionError.cancelled
        } catch {
            modelLoadProgress = 0
            isDownloadingModel = false
            throw error
        }
    }

    /// Helper to update model load progress from non-isolated context
    private func updateModelLoadProgress(_ progress: Double) {
        self.modelLoadProgress = progress
    }

    private func loadAppleSpeech() async throws {
        let status = await requestSpeechAuthorization()

        guard status == .authorized else {
            throw TranscriptionError.speechRecognitionNotAuthorized
        }

        guard let recognizer = speechRecognizer, recognizer.isAvailable else {
            throw TranscriptionError.speechRecognitionUnavailable
        }
    }

    /// Unload current model to free memory
    func unloadModel() {
        whisperKit = nil
        recognitionTask?.cancel()
        recognitionTask = nil
        loadedModel = nil
    }

    /// Transcribe audio file with callback-based API
    func transcribe(
        audioURL: URL,
        languageCode: String?,
        onPartial: @escaping (Double, String?) -> Void,
        completion: @escaping (Result<[TranscriptSegment], Error>) -> Void
    ) {
        Task {
            do {
                let segments = try await performTranscription(
                    audioURL: audioURL,
                    languageCode: languageCode,
                    onPartial: onPartial
                )
                await MainActor.run {
                    completion(.success(segments))
                }
            } catch {
                await MainActor.run {
                    completion(.failure(error))
                }
            }
        }
    }

    /// Async version of transcribe for modern Swift concurrency
    func transcribe(
        audioURL: URL,
        languageCode: String?,
        onPartial: @escaping (Double, String?) -> Void
    ) async throws -> [TranscriptSegment] {
        return try await performTranscription(
            audioURL: audioURL,
            languageCode: languageCode,
            onPartial: onPartial
        )
    }

    /// Transcribe with progress callback (legacy API)
    func transcribe(
        audioURL: URL,
        language: String?,
        progress: @escaping (TranscriptionProgress) -> Void
    ) async throws -> TranscriptionResult {

        if loadedModel == nil {
            try await loadModel(getSelectedModel())
        }

        let startTime = Date()
        var allSegments: [SegmentResult] = []
        var detectedLanguage: String? = language

        let segments = try await performTranscription(
            audioURL: audioURL,
            languageCode: language
        ) { fractionCompleted, partialText in
            progress(TranscriptionProgress(
                fractionCompleted: fractionCompleted,
                currentChunk: Int(fractionCompleted * 10),
                totalChunks: 10,
                estimatedTimeRemaining: nil
            ))
        }

        for segment in segments {
            allSegments.append(SegmentResult(
                startTime: segment.startTime,
                endTime: segment.endTime,
                text: segment.text,
                confidence: segment.confidence
            ))
        }

        if detectedLanguage == nil && !segments.isEmpty {
            detectedLanguage = "en"
        }

        return TranscriptionResult(
            segments: allSegments,
            detectedLanguage: detectedLanguage,
            processingTime: Date().timeIntervalSince(startTime)
        )
    }

    /// Cancel any in-progress transcription
    func cancelTranscription() {
        isCancelled = true
        recognitionTask?.cancel()
        recognitionTask = nil
    }

    /// Get the current transcription engine being used
    func getActiveEngine() -> TranscriptionEngine {
        return currentEngine
    }

    /// Check if WhisperKit failed and we're using fallback
    func isUsingFallback() -> Bool {
        return whisperKitFailed
    }

    /// Reset WhisperKit failed flag to retry WhisperKit on next load
    func resetWhisperKitStatus() {
        whisperKitFailed = false
    }

    /// Check if a model is available (downloaded)
    func isModelAvailable(_ modelName: String) -> Bool {
        // Check if WhisperKit model exists in cache
        let fileManager = FileManager.default
        let cacheDir = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first

        if let cacheDir = cacheDir {
            let modelPath = cacheDir.appendingPathComponent("huggingface/models/argmaxinc/whisperkit-coreml/\(modelName)")
            if fileManager.fileExists(atPath: modelPath.path) {
                return true
            }
        }

        // Apple Speech is always available as fallback
        return speechRecognizer?.isAvailable ?? false
    }

    /// Download a model
    func downloadModel(_ modelName: String) -> AsyncThrowingStream<Double, Error> {
        AsyncThrowingStream { continuation in
            Task {
                do {
                    // Convert legacy model name if needed
                    let whisperKitModelName: String
                    if let model = WhisperModel(legacyRawValue: modelName) {
                        whisperKitModelName = model.rawValue
                    } else {
                        whisperKitModelName = modelName
                    }

                    continuation.yield(0.1)

                    // Try to download WhisperKit model
                    let computeOptions = ModelComputeOptions(
                        audioEncoderCompute: .cpuOnly,
                        textDecoderCompute: .cpuOnly
                    )

                    let tempKit = try await WhisperKit(
                        model: whisperKitModelName,
                        downloadBase: nil,
                        modelFolder: nil,
                        computeOptions: computeOptions,
                        verbose: false,
                        prewarm: false
                    )

                    _ = tempKit

                    continuation.yield(1.0)
                    continuation.finish()

                } catch {
                    // If WhisperKit fails, just verify Apple Speech is available
                    let status = await self.requestSpeechAuthorization()
                    if status == .authorized {
                        continuation.yield(1.0)
                        continuation.finish()
                    } else {
                        continuation.finish(throwing: error)
                    }
                }
            }
        }
    }

    // MARK: - Private Transcription Implementation

    /// Get the user's selected Whisper model from settings
    private func getSelectedModel() -> WhisperModel {
        let savedModel = UserDefaults.standard.string(forKey: AppConstants.UserDefaultsKeys.selectedModel)

        if let savedModel = savedModel {
            // Try new format first, then legacy format
            if let model = WhisperModel(rawValue: savedModel) {
                return model
            } else if let model = WhisperModel(legacyRawValue: savedModel) {
                return model
            }
        }
        return .tiny // Default fallback
    }

    private func performTranscription(
        audioURL: URL,
        languageCode: String?,
        onPartial: @escaping (Double, String?) -> Void
    ) async throws -> [TranscriptSegment] {

        // Ensure model is loaded (use selected model from settings)
        if loadedModel == nil {
            try await loadModel(getSelectedModel())
        }

        // Track concurrent transcriptions
        activeTranscriptionCount += 1
        isProcessing = true
        isCancelled = false

        defer {
            activeTranscriptionCount -= 1
            // Only mark as not processing if no other transcriptions are active
            if activeTranscriptionCount == 0 {
                isProcessing = false
            }
        }

        // Get audio duration
        let asset = AVAsset(url: audioURL)
        let duration = try await asset.load(.duration).seconds

        guard duration >= AppConstants.Audio.minTranscriptionDuration else {
            throw TranscriptionError.audioTooShort
        }

        // Capture engine state at the start to avoid race conditions
        // Each transcription uses the engine state from when it started
        let engineToUse = currentEngine
        let whisperKitInstance = whisperKit
        let wasWhisperKitAlreadyFailed = whisperKitFailed

        // Try WhisperKit first if available and not already failed
        if engineToUse == .whisperKit, let kit = whisperKitInstance {
            do {
                return try await transcribeWithWhisperKit(
                    whisperKit: kit,
                    audioURL: audioURL,
                    languageCode: languageCode,
                    duration: duration,
                    onPartial: onPartial
                )
            } catch {
                print("[CoreMLTranscriber] WhisperKit transcription failed: \(error.localizedDescription)")
                print("[CoreMLTranscriber] Falling back to Apple Speech...")

                // Only update shared state if no other transcription is using WhisperKit
                // This prevents one failing transcription from affecting concurrent ones
                if activeTranscriptionCount == 1 {
                    whisperKitFailed = true
                    currentEngine = .appleSpeech
                }
                // Continue to Apple Speech fallback for this transcription
            }
        }

        // Fall back to Apple Speech
        do {
            return try await transcribeWithAppleSpeech(
                audioURL: audioURL,
                languageCode: languageCode,
                duration: duration,
                onPartial: onPartial
            )
        } catch {
            // If WhisperKit already failed (either before or during this transcription)
            // and Apple Speech also fails, throw allEnginesFailed
            if wasWhisperKitAlreadyFailed || engineToUse == .whisperKit {
                print("[CoreMLTranscriber] Apple Speech also failed: \(error.localizedDescription)")
                throw TranscriptionError.allEnginesFailed
            }
            // Otherwise just propagate the Apple Speech error
            throw error
        }
    }

    // MARK: - WhisperKit Transcription

    private func transcribeWithWhisperKit(
        whisperKit: WhisperKit,
        audioURL: URL,
        languageCode: String?,
        duration: TimeInterval,
        onPartial: @escaping (Double, String?) -> Void
    ) async throws -> [TranscriptSegment] {

        // Get custom dictionary prompt if enabled
        let customPrompt = await MainActor.run {
            CustomDictionaryService.shared.promptString
        }

        if let prompt = customPrompt {
            print("[CoreMLTranscriber] Using custom dictionary prompt: \(prompt.prefix(100))...")
        }

        // Configure decoding options with more lenient thresholds for better fallback handling
        let options = DecodingOptions(
            task: .transcribe,
            language: languageCode,
            temperatureFallbackCount: 5,           // Allow more temperature fallbacks
            sampleLength: 224,
            usePrefillPrompt: true,
            usePrefillCache: true,
            skipSpecialTokens: true,
            withoutTimestamps: false,
            wordTimestamps: true,
            promptTokens: nil,                     // Let WhisperKit handle tokenization
            prefixTokens: nil,
            suppressBlank: true,
            supressTokens: nil,
            compressionRatioThreshold: 2.8,        // More lenient compression threshold (default is 2.4)
            logProbThreshold: -1.2,                // More lenient log probability threshold (default is -1.0)
            firstTokenLogProbThreshold: -1.5,      // More lenient first token threshold (default is -1.5)
            noSpeechThreshold: 0.4                 // Lower threshold to detect speech (default is 0.6)
        )

        // Report initial progress
        await MainActor.run { onPartial(0.05, nil) }

        // Use streaming callback for real-time progress
        var accumulatedText = ""
        var lastReportedProgress: Double = 0.05

        let results = try await whisperKit.transcribe(
            audioPath: audioURL.path,
            decodeOptions: options,
            callback: { progress in
                // WhisperKit provides progress through the callback
                let currentProgress = min(0.95, 0.05 + (Double(progress.timings.pipelineStart) / max(duration, 1.0)) * 0.9)
                if currentProgress > lastReportedProgress + 0.05 {
                    lastReportedProgress = currentProgress
                    Task { @MainActor in
                        onPartial(currentProgress, progress.text)
                    }
                }
                return !self.isCancelled // Return false to stop
            }
        )

        if isCancelled {
            throw TranscriptionError.cancelled
        }

        // Log transcription results for debugging
        print("[CoreMLTranscriber] Transcription completed with \(results.count) result(s)")
        for (index, result) in results.enumerated() {
            print("[CoreMLTranscriber] Result \(index): \(result.segments.count) segments, text: \(result.text.prefix(100))...")
        }

        var segments: [TranscriptSegment] = []

        for result in results {
            for resultSegment in result.segments {
                if let words = resultSegment.words, !words.isEmpty {
                    var currentSegmentWords: [WordTiming] = []
                    var currentSegmentStart: Float = 0

                    for word in words {
                        if currentSegmentWords.isEmpty {
                            currentSegmentStart = word.start
                        }

                        currentSegmentWords.append(word)

                        let wordText = word.word.trimmingCharacters(in: CharacterSet.whitespaces)
                        let isPunctuation = wordText.hasSuffix(".") || wordText.hasSuffix("?") || wordText.hasSuffix("!")
                        let isLongEnough = currentSegmentWords.count >= Self.segmentWordThreshold

                        if isPunctuation || isLongEnough {
                            let segmentText = currentSegmentWords.map { $0.word }.joined()
                            let avgConfidence = currentSegmentWords.map { Double($0.probability) }.reduce(0, +) / Double(currentSegmentWords.count)
                            let segment = TranscriptSegment(
                                startTime: TimeInterval(currentSegmentStart),
                                endTime: TimeInterval(word.end),
                                text: segmentText.trimmingCharacters(in: CharacterSet.whitespaces),
                                confidence: avgConfidence
                            )
                            segments.append(segment)
                            currentSegmentWords = []
                        }
                    }

                    if !currentSegmentWords.isEmpty {
                        let segmentText = currentSegmentWords.map { $0.word }.joined()
                        let avgConfidence = currentSegmentWords.map { Double($0.probability) }.reduce(0, +) / Double(currentSegmentWords.count)
                        let lastEnd = currentSegmentWords.last?.end ?? currentSegmentStart
                        let segment = TranscriptSegment(
                            startTime: TimeInterval(currentSegmentStart),
                            endTime: TimeInterval(lastEnd),
                            text: segmentText.trimmingCharacters(in: CharacterSet.whitespaces),
                            confidence: avgConfidence
                        )
                        segments.append(segment)
                    }
                } else {
                    let segment = TranscriptSegment(
                        startTime: TimeInterval(resultSegment.start),
                        endTime: TimeInterval(resultSegment.end),
                        text: resultSegment.text.trimmingCharacters(in: CharacterSet.whitespaces),
                        confidence: nil
                    )
                    segments.append(segment)
                }
            }
        }

        // Apply punctuation and capitalization settings
        let processedSegments = applyPunctuationSettings(to: segments)

        // Report completion with full text from all segments
        let fullText = processedSegments.map { $0.text }.joined(separator: " ")
        await MainActor.run { onPartial(1.0, fullText) }

        return processedSegments
    }

    // MARK: - Text Processing

    /// Apply user punctuation, capitalization, and custom dictionary settings to transcribed segments
    private func applyPunctuationSettings(to segments: [TranscriptSegment]) -> [TranscriptSegment] {
        let autoPunctuationEnabled = UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.autoPunctuationEnabled) == nil ||
            UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.autoPunctuationEnabled)
        let smartCapitalizationEnabled = UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.smartCapitalizationEnabled) == nil ||
            UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.smartCapitalizationEnabled)

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
                // Ensure sentences start with capital letters when both settings are enabled
                processedText = capitalizeAfterPunctuation(processedText)
            }

            return TranscriptSegment(
                startTime: segment.startTime,
                endTime: segment.endTime,
                text: processedText,
                confidence: segment.confidence
            )
        }
    }

    /// Capitalize the first letter of sentences (after . ! ?)
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

    // MARK: - Apple Speech Transcription

    private func requestSpeechAuthorization() async -> SFSpeechRecognizerAuthorizationStatus {
        await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status)
            }
        }
    }

    private func transcribeWithAppleSpeech(
        audioURL: URL,
        languageCode: String?,
        duration: TimeInterval,
        onPartial: @escaping (Double, String?) -> Void
    ) async throws -> [TranscriptSegment] {

        let status = await requestSpeechAuthorization()
        guard status == .authorized else {
            throw TranscriptionError.speechRecognitionNotAuthorized
        }

        let recognizer: SFSpeechRecognizer
        if let langCode = languageCode, langCode != "auto" {
            guard let langRecognizer = SFSpeechRecognizer(locale: Locale(identifier: langCode)) ?? SFSpeechRecognizer() else {
                throw TranscriptionError.speechRecognitionUnavailable
            }
            recognizer = langRecognizer
        } else {
            guard let defaultRecognizer = speechRecognizer ?? SFSpeechRecognizer() else {
                throw TranscriptionError.speechRecognitionUnavailable
            }
            recognizer = defaultRecognizer
        }

        guard recognizer.isAvailable else {
            throw TranscriptionError.speechRecognitionUnavailable
        }

        let request = SFSpeechURLRecognitionRequest(url: audioURL)
        request.shouldReportPartialResults = true

        if recognizer.supportsOnDeviceRecognition {
            request.requiresOnDeviceRecognition = true
        }

        // Apply punctuation settings from user preferences
        let autoPunctuationEnabled = UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.autoPunctuationEnabled) == nil ||
            UserDefaults.standard.bool(forKey: AppConstants.UserDefaultsKeys.autoPunctuationEnabled)
        request.addsPunctuation = autoPunctuationEnabled

        await MainActor.run { onPartial(0.05, nil) }

        return try await withCheckedThrowingContinuation { continuation in
            var lastProcessedText = ""
            var hasResumed = false
            var updateCount = 0
            let startTime = Date()

            recognitionTask = recognizer.recognitionTask(with: request) { [weak self] result, error in
                guard let self = self, !hasResumed else { return }

                if self.isCancelled {
                    hasResumed = true
                    continuation.resume(throwing: TranscriptionError.cancelled)
                    return
                }

                if let error = error {
                    let nsError = error as NSError
                    if nsError.domain == "kAFAssistantErrorDomain" && nsError.code == 1110 {
                        hasResumed = true
                        continuation.resume(returning: [])
                        return
                    }
                    hasResumed = true
                    continuation.resume(throwing: TranscriptionError.inferenceFailed(error))
                    return
                }

                guard let result = result else { return }

                let currentText = result.bestTranscription.formattedString

                if currentText != lastProcessedText {
                    lastProcessedText = currentText
                    updateCount += 1

                    // Calculate progress based on elapsed time vs audio duration
                    let elapsedTime = Date().timeIntervalSince(startTime)
                    // Apple Speech typically processes faster than real-time
                    // Estimate progress based on time elapsed relative to expected processing time
                    let estimatedProgress: Double
                    if result.isFinal {
                        estimatedProgress = 1.0
                    } else {
                        // Use a combination of time-based and text-length based progress
                        let timeProgress = min(0.9, elapsedTime / max(duration * 0.5, 1.0))
                        estimatedProgress = max(0.1, min(0.95, timeProgress))
                    }

                    Task { @MainActor in
                        onPartial(estimatedProgress, currentText)
                    }
                }

                if result.isFinal {
                    let rawSegments = self.convertAppleSpeechToSegments(result.bestTranscription, duration: duration)
                    let processedSegments = self.applyPunctuationSettings(to: rawSegments)
                    Task { @MainActor in
                        onPartial(1.0, currentText)
                    }
                    hasResumed = true
                    continuation.resume(returning: processedSegments)
                }
            }
        }
    }

    private func convertAppleSpeechToSegments(_ transcription: SFTranscription, duration: TimeInterval) -> [TranscriptSegment] {
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
                    startTime: currentStartTime,
                    endTime: endTime,
                    text: segmentText.trimmingCharacters(in: .whitespaces),
                    confidence: avgConfidence
                )
                segments.append(transcriptSegment)
                currentWords = []
            }
        }

        if !currentWords.isEmpty, let lastSegment = currentWords.last {
            let segmentText = currentWords.map { $0.substring }.joined(separator: " ")
            let endTime = lastSegment.timestamp + lastSegment.duration
            let avgConfidence = currentWords.map { Double($0.confidence) }.reduce(0, +) / Double(currentWords.count)

            let transcriptSegment = TranscriptSegment(
                startTime: currentStartTime,
                endTime: endTime,
                text: segmentText.trimmingCharacters(in: .whitespaces),
                confidence: avgConfidence
            )
            segments.append(transcriptSegment)
        }

        return segments
    }
}

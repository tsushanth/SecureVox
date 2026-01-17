import AVFoundation
import Combine
import Foundation

/// Audio recording service optimized for Whisper transcription
/// Records mono audio at 16kHz (Whisper's native sample rate)
/// Supports unlimited recording length (limited only by disk space)
final class AudioRecorderService: NSObject, ObservableObject {

    // MARK: - Published Properties

    /// Whether recording is currently in progress
    @Published private(set) var isRecording: Bool = false

    /// Current recording duration in seconds
    @Published private(set) var duration: TimeInterval = 0

    /// Current audio level (0.0 - 1.0) for UI visualization
    @Published private(set) var audioLevel: Float = 0

    /// Error message if something goes wrong
    @Published private(set) var errorMessage: String?

    // MARK: - Types

    enum RecorderError: Error, LocalizedError {
        case microphonePermissionDenied
        case microphonePermissionRestricted
        case audioSessionSetupFailed(Error)
        case engineStartFailed(Error)
        case fileCreationFailed(Error)
        case notRecording
        case alreadyRecording
        case interruptionNotRecoverable
        case noOutputFile
        case insufficientDiskSpace(available: Int64, required: Int64)
        case diskSpaceRunningLow(available: Int64)

        var errorDescription: String? {
            switch self {
            case .microphonePermissionDenied:
                return "Microphone access was denied. Please enable it in Settings."
            case .microphonePermissionRestricted:
                return "Microphone access is restricted on this device."
            case .audioSessionSetupFailed(let error):
                return "Audio session setup failed: \(error.localizedDescription)"
            case .engineStartFailed(let error):
                return "Could not start recording: \(error.localizedDescription)"
            case .fileCreationFailed(let error):
                return "Could not create audio file: \(error.localizedDescription)"
            case .notRecording:
                return "No active recording to stop."
            case .alreadyRecording:
                return "Recording is already in progress."
            case .interruptionNotRecoverable:
                return "Recording was interrupted and could not be resumed."
            case .noOutputFile:
                return "No output file was created."
            case .insufficientDiskSpace(let available, _):
                let formatted = ByteCountFormatter.string(fromByteCount: available, countStyle: .file)
                return "Not enough disk space to start recording. Only \(formatted) available."
            case .diskSpaceRunningLow(let available):
                let formatted = ByteCountFormatter.string(fromByteCount: available, countStyle: .file)
                return "Recording stopped: disk space running low (\(formatted) remaining)."
            }
        }
    }

    /// Minimum disk space required to start recording (50 MB)
    private static let minimumDiskSpaceToStart: Int64 = 50 * 1024 * 1024

    /// Disk space threshold to stop recording (20 MB)
    private static let minimumDiskSpaceToContinue: Int64 = 20 * 1024 * 1024

    /// How often to check disk space during recording (seconds)
    private static let diskSpaceCheckInterval: TimeInterval = 10

    struct RecordingResult {
        /// URL to the recorded audio file
        let fileURL: URL
        /// Duration of the recording in seconds
        let duration: TimeInterval
        /// File size in bytes
        let fileSize: Int64
        /// Sample rate used (should be 16000 for Whisper)
        let sampleRate: Double
    }

    // MARK: - Constants

    /// Whisper's native sample rate
    static let whisperSampleRate: Double = 16000

    /// Buffer size for audio processing
    private static let bufferSize: AVAudioFrameCount = 4096

    // MARK: - Private Properties

    private var audioEngine: AVAudioEngine?
    private var audioFile: AVAudioFile?
    private var currentRecordingURL: URL?
    private var recordingStartTime: Date?
    private var durationTimer: Timer?
    private var diskSpaceTimer: Timer?

    private var isInterrupted: Bool = false
    private var wasRecordingBeforeInterruption: Bool = false

    // MARK: - Initialization

    override init() {
        super.init()
        setupNotifications()
    }

    deinit {
        removeNotifications()
        stopRecordingInternal(cancelled: true)
    }

    // MARK: - Public Methods

    /// Check current microphone permission status
    #if os(iOS)
    var microphonePermissionStatus: AVAudioSession.RecordPermission {
        AVAudioSession.sharedInstance().recordPermission
    }
    #else
    var microphonePermissionStatus: AVAudioApplication.RecordPermission {
        AVAudioApplication.shared.recordPermission
    }
    #endif

    /// Request microphone permission
    /// - Returns: true if permission was granted
    func requestMicrophonePermission() async -> Bool {
        #if os(iOS)
        await withCheckedContinuation { continuation in
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
        #else
        await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { granted in
                continuation.resume(returning: granted)
            }
        }
        #endif
    }

    /// Start recording audio
    /// - Returns: URL where the audio file is being saved
    /// - Throws: RecorderError if recording cannot start
    @discardableResult
    func startRecording() throws -> URL {
        // Check if already recording
        guard !isRecording else {
            throw RecorderError.alreadyRecording
        }

        // Check microphone permission
        switch microphonePermissionStatus {
        case .denied:
            throw RecorderError.microphonePermissionDenied
        case .undetermined:
            // Permission not yet requested - this shouldn't happen if UI flow is correct
            throw RecorderError.microphonePermissionDenied
        case .granted:
            break
        @unknown default:
            throw RecorderError.microphonePermissionRestricted
        }

        // Check available disk space before starting
        let availableSpace = availableDiskSpace()
        if availableSpace < Self.minimumDiskSpaceToStart {
            throw RecorderError.insufficientDiskSpace(
                available: availableSpace,
                required: Self.minimumDiskSpaceToStart
            )
        }

        // Generate output file URL
        let outputURL = generateOutputURL()

        // Configure and start recording
        try configureAudioSession()
        try setupAudioEngine(outputURL: outputURL)
        try startEngine()

        // Update state
        currentRecordingURL = outputURL
        recordingStartTime = Date()
        isRecording = true
        isInterrupted = false
        errorMessage = nil

        // Start duration timer and disk space monitoring
        startDurationTimer()
        startDiskSpaceMonitoring()

        return outputURL
    }

    /// Stop recording and finalize the audio file
    /// - Returns: RecordingResult with file info
    /// - Throws: RecorderError if not recording
    @discardableResult
    func stopRecording() throws -> RecordingResult {
        guard isRecording else {
            throw RecorderError.notRecording
        }

        guard let result = stopRecordingInternal(cancelled: false) else {
            throw RecorderError.noOutputFile
        }
        return result
    }

    /// Cancel recording and delete the partial file
    func cancelRecording() {
        stopRecordingInternal(cancelled: true)
    }

    // MARK: - Private Methods

    private func generateOutputURL() -> URL {
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let recordingsFolder = documentsPath.appendingPathComponent("recordings", isDirectory: true)

        // Create recordings folder if needed
        try? FileManager.default.createDirectory(at: recordingsFolder, withIntermediateDirectories: true)

        let filename = "recording_\(UUID().uuidString).wav"
        return recordingsFolder.appendingPathComponent(filename)
    }

    private func configureAudioSession() throws {
        #if os(iOS)
        let session = AVAudioSession.sharedInstance()

        do {
            // Configure for recording with options for interruption handling
            try session.setCategory(
                .playAndRecord,
                mode: .measurement,  // Best for voice recording
                options: [.defaultToSpeaker, .allowBluetooth, .duckOthers]
            )

            // Set preferred sample rate to Whisper's native rate
            try session.setPreferredSampleRate(Self.whisperSampleRate)

            // Set preferred buffer duration for low latency
            try session.setPreferredIOBufferDuration(0.01)

            // Activate the session
            try session.setActive(true, options: .notifyOthersOnDeactivation)

        } catch {
            throw RecorderError.audioSessionSetupFailed(error)
        }
        #endif
        // macOS doesn't require AVAudioSession configuration
    }

    private func setupAudioEngine(outputURL: URL) throws {
        let engine = AVAudioEngine()
        let inputNode = engine.inputNode

        // Get the hardware input format
        let hardwareFormat = inputNode.outputFormat(forBus: 0)

        // Create the output format (16kHz mono for Whisper)
        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Self.whisperSampleRate,
            channels: 1,
            interleaved: false
        ) else {
            throw RecorderError.fileCreationFailed(NSError(domain: "AudioRecorder", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Could not create output format"]))
        }

        // Create format converter if sample rates differ
        var converter: AVAudioConverter?
        if hardwareFormat.sampleRate != Self.whisperSampleRate || hardwareFormat.channelCount != 1 {
            converter = AVAudioConverter(from: hardwareFormat, to: outputFormat)
        }

        // Create the audio file for writing
        do {
            let fileSettings: [String: Any] = [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: Self.whisperSampleRate,
                AVNumberOfChannelsKey: 1,
                AVLinearPCMBitDepthKey: 32,
                AVLinearPCMIsFloatKey: true,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false
            ]

            audioFile = try AVAudioFile(forWriting: outputURL, settings: fileSettings)
        } catch {
            throw RecorderError.fileCreationFailed(error)
        }

        // Install tap on input node
        let tapFormat = converter != nil ? hardwareFormat : outputFormat

        inputNode.installTap(onBus: 0, bufferSize: Self.bufferSize, format: tapFormat) { [weak self] buffer, _ in
            self?.processAudioBuffer(buffer, converter: converter, outputFormat: outputFormat)
        }

        self.audioEngine = engine
    }

    private func startEngine() throws {
        guard let engine = audioEngine else {
            throw RecorderError.engineStartFailed(NSError(domain: "AudioRecorder", code: -1,
                userInfo: [NSLocalizedDescriptionKey: "Audio engine not configured"]))
        }

        do {
            try engine.start()
        } catch {
            // Clean up on failure
            engine.inputNode.removeTap(onBus: 0)
            self.audioEngine = nil
            self.audioFile = nil
            throw RecorderError.engineStartFailed(error)
        }
    }

    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer, converter: AVAudioConverter?, outputFormat: AVAudioFormat) {
        guard let audioFile = audioFile else { return }

        // Apply input gain from user settings
        let inputGain = UserDefaults.standard.object(forKey: AppConstants.UserDefaultsKeys.inputGain) == nil
            ? 1.0
            : UserDefaults.standard.float(forKey: AppConstants.UserDefaultsKeys.inputGain)

        if inputGain != 1.0, let channelData = buffer.floatChannelData {
            let frameLength = Int(buffer.frameLength)
            for channel in 0..<Int(buffer.format.channelCount) {
                for frame in 0..<frameLength {
                    channelData[channel][frame] *= inputGain
                }
            }
        }

        var bufferToWrite: AVAudioPCMBuffer

        // Convert if necessary
        if let converter = converter {
            let frameCapacity = AVAudioFrameCount(
                Double(buffer.frameLength) * outputFormat.sampleRate / buffer.format.sampleRate
            )

            guard let convertedBuffer = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: frameCapacity) else {
                return
            }

            var error: NSError?
            let inputBlock: AVAudioConverterInputBlock = { _, outStatus in
                outStatus.pointee = .haveData
                return buffer
            }

            converter.convert(to: convertedBuffer, error: &error, withInputFrom: inputBlock)

            if error != nil { return }

            bufferToWrite = convertedBuffer
        } else {
            bufferToWrite = buffer
        }

        // Write to file
        do {
            try audioFile.write(from: bufferToWrite)
        } catch {
            DispatchQueue.main.async {
                self.errorMessage = "Error writing audio: \(error.localizedDescription)"
            }
        }

        // Calculate audio level for UI
        updateAudioLevel(from: bufferToWrite)
    }

    private func updateAudioLevel(from buffer: AVAudioPCMBuffer) {
        guard let channelData = buffer.floatChannelData?[0] else { return }

        let frameCount = Int(buffer.frameLength)
        guard frameCount > 0 else { return }

        // Calculate RMS (root mean square) for audio level
        var sum: Float = 0
        for i in 0..<frameCount {
            let sample = channelData[i]
            sum += sample * sample
        }

        let rms = sqrt(sum / Float(frameCount))

        // Convert to decibels and normalize to 0-1
        let db = 20 * log10(max(rms, 0.000001))
        let normalized = max(0, min(1, (db + 60) / 60))  // Assuming -60 to 0 dB range

        DispatchQueue.main.async {
            self.audioLevel = normalized
        }
    }

    @discardableResult
    private func stopRecordingInternal(cancelled: Bool) -> RecordingResult? {
        // Stop timers
        durationTimer?.invalidate()
        durationTimer = nil
        stopDiskSpaceMonitoring()

        // Stop and clean up audio engine
        if let engine = audioEngine {
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
        }
        audioEngine = nil

        // Close audio file
        audioFile = nil

        // Calculate final duration
        let finalDuration: TimeInterval
        if let startTime = recordingStartTime {
            finalDuration = Date().timeIntervalSince(startTime)
        } else {
            finalDuration = duration
        }

        // Get file info
        var result: RecordingResult?
        if let url = currentRecordingURL {
            if cancelled {
                // Delete the file if cancelled
                try? FileManager.default.removeItem(at: url)
            } else {
                // Get file size
                let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
                let fileSize = attributes?[.size] as? Int64 ?? 0

                result = RecordingResult(
                    fileURL: url,
                    duration: finalDuration,
                    fileSize: fileSize,
                    sampleRate: Self.whisperSampleRate
                )
            }
        }

        // Reset state
        currentRecordingURL = nil
        recordingStartTime = nil
        isRecording = false
        duration = 0
        audioLevel = 0
        isInterrupted = false

        // Deactivate audio session
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif

        return result
    }

    private func startDurationTimer() {
        durationTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self = self, let startTime = self.recordingStartTime else { return }
            DispatchQueue.main.async {
                self.duration = Date().timeIntervalSince(startTime)
            }
        }
    }

    private func startDiskSpaceMonitoring() {
        diskSpaceTimer = Timer.scheduledTimer(withTimeInterval: Self.diskSpaceCheckInterval, repeats: true) { [weak self] _ in
            self?.checkDiskSpaceDuringRecording()
        }
    }

    private func stopDiskSpaceMonitoring() {
        diskSpaceTimer?.invalidate()
        diskSpaceTimer = nil
    }

    private func checkDiskSpaceDuringRecording() {
        guard isRecording else { return }

        let available = availableDiskSpace()
        if available < Self.minimumDiskSpaceToContinue {
            // Stop recording due to low disk space
            DispatchQueue.main.async { [weak self] in
                self?.errorMessage = RecorderError.diskSpaceRunningLow(available: available).localizedDescription
                self?.stopRecordingInternal(cancelled: false)
            }
        }
    }

    /// Get available disk space in bytes
    private func availableDiskSpace() -> Int64 {
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        do {
            let values = try documentsURL.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            return values.volumeAvailableCapacityForImportantUsage ?? 0
        } catch {
            // Fallback to older API
            do {
                let attributes = try FileManager.default.attributesOfFileSystem(forPath: documentsURL.path)
                return attributes[.systemFreeSize] as? Int64 ?? 0
            } catch {
                return 0
            }
        }
    }

    // MARK: - Interruption Handling

    private func setupNotifications() {
        #if os(iOS)
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleInterruption),
            name: AVAudioSession.interruptionNotification,
            object: AVAudioSession.sharedInstance()
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleRouteChange),
            name: AVAudioSession.routeChangeNotification,
            object: AVAudioSession.sharedInstance()
        )

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleMediaServicesReset),
            name: AVAudioSession.mediaServicesWereResetNotification,
            object: AVAudioSession.sharedInstance()
        )
        #endif
    }

    private func removeNotifications() {
        NotificationCenter.default.removeObserver(self)
    }

    #if os(iOS)
    @objc private func handleInterruption(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let typeValue = userInfo[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue) else {
            return
        }

        switch type {
        case .began:
            // Interruption began (e.g., phone call)
            wasRecordingBeforeInterruption = isRecording
            if isRecording {
                isInterrupted = true
                audioEngine?.pause()
                durationTimer?.invalidate()
            }

        case .ended:
            // Interruption ended
            guard wasRecordingBeforeInterruption else { return }

            let optionsValue = userInfo[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)

            if options.contains(.shouldResume) {
                // Can resume recording
                do {
                    try AVAudioSession.sharedInstance().setActive(true)
                    try audioEngine?.start()
                    startDurationTimer()
                    isInterrupted = false
                } catch {
                    errorMessage = "Could not resume recording after interruption"
                    stopRecordingInternal(cancelled: false)
                }
            } else {
                // Cannot resume - stop recording
                errorMessage = "Recording stopped due to interruption"
                stopRecordingInternal(cancelled: false)
            }

        @unknown default:
            break
        }
    }

    @objc private func handleRouteChange(_ notification: Notification) {
        guard let userInfo = notification.userInfo,
              let reasonValue = userInfo[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let reason = AVAudioSession.RouteChangeReason(rawValue: reasonValue) else {
            return
        }

        switch reason {
        case .oldDeviceUnavailable:
            // Headphones unplugged, etc.
            if isRecording {
                // Continue recording with new route (built-in mic)
                // No action needed - audio session handles this
            }

        case .newDeviceAvailable:
            // New device connected
            break

        default:
            break
        }
    }

    @objc private func handleMediaServicesReset(_ notification: Notification) {
        // Media services were reset - need to reconfigure everything
        if isRecording {
            errorMessage = "Audio system was reset. Recording stopped."
            stopRecordingInternal(cancelled: false)
        }

        // Reset audio engine
        audioEngine = nil
        audioFile = nil
    }
    #endif
}

// MARK: - Convenience Extensions

extension AudioRecorderService {

    /// Formatted duration string (MM:SS or HH:MM:SS)
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

    /// Check if microphone permission has been granted
    var hasMicrophonePermission: Bool {
        microphonePermissionStatus == .granted
    }
}

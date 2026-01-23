import Foundation
import AVFoundation
import Combine
import AppKit

/// Error types for recording operations
enum RecorderError: LocalizedError {
    case microphonePermissionDenied
    case insufficientDiskSpace
    case failedToPrepare
    case failedToStart
    case encodingError(String)
    case interrupted
    case deviceDisconnected
    case unknown(String)

    var errorDescription: String? {
        switch self {
        case .microphonePermissionDenied:
            return "Microphone permission denied. Please enable in System Settings."
        case .insufficientDiskSpace:
            return "Not enough disk space to start recording."
        case .failedToPrepare:
            return "Failed to prepare recorder"
        case .failedToStart:
            return "Failed to start recording"
        case .encodingError(let message):
            return "Recording encode error: \(message)"
        case .interrupted:
            return "Recording was interrupted"
        case .deviceDisconnected:
            return "Audio device was disconnected"
        case .unknown(let message):
            return "Recording error: \(message)"
        }
    }
}

/// Result of a successful recording
struct RecordingResult {
    let url: URL
    let duration: TimeInterval
    let fileSize: Int64
    let sampleRate: Double
}

/// Service for recording audio on macOS
@MainActor
class AudioRecorderService: NSObject, ObservableObject {

    // MARK: - Published Properties

    @Published private(set) var isRecording = false
    @Published private(set) var isPaused = false
    @Published private(set) var recordingDuration: TimeInterval = 0
    @Published private(set) var audioLevel: Float = 0
    @Published private(set) var errorMessage: String?
    @Published private(set) var lastError: RecorderError?

    // MARK: - Private Properties

    private var audioRecorder: AVAudioRecorder?
    private var levelTimer: Timer?
    private var durationTimer: Timer?
    private var recordingStartTime: Date?
    private var pausedDuration: TimeInterval = 0
    private var currentFilePath: URL?
    private var deviceObserver: NSObjectProtocol?

    // MARK: - Settings

    @Published var soundEffectsEnabled = true
    @Published var inputGain: Float = 1.0
    @Published var recordingQuality: AppConstants.RecordingQuality = .standard

    // MARK: - Initialization

    override init() {
        super.init()
        setupAudioSession()
        setupDeviceNotifications()
    }

    deinit {
        if let observer = deviceObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    // MARK: - Audio Session Setup

    private func setupAudioSession() {
        // On macOS, we don't need to configure audio session like iOS
        // But we should check microphone permission
    }

    private func setupDeviceNotifications() {
        // Monitor for audio device changes
        deviceObserver = NotificationCenter.default.addObserver(
            forName: NSNotification.Name.AVCaptureDeviceWasDisconnected,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            Task { @MainActor in
                guard let self = self, self.isRecording else { return }
                // Device disconnected during recording
                self.handleDeviceDisconnected()
            }
        }
    }

    private func handleDeviceDisconnected() {
        guard isRecording else { return }

        // Pause recording and notify user
        pauseRecording()
        lastError = .deviceDisconnected
        errorMessage = RecorderError.deviceDisconnected.localizedDescription
    }

    // MARK: - Permission

    func checkMicrophonePermission() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            return true
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: .audio)
        case .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }

    // MARK: - Recording Control

    func startRecording() async -> URL? {
        guard !isRecording else { return nil }

        // Check permission
        guard await checkMicrophonePermission() else {
            errorMessage = "Microphone permission denied. Please enable in System Settings."
            return nil
        }

        // Check disk space
        guard hasSufficientDiskSpace() else {
            errorMessage = "Not enough disk space to start recording."
            return nil
        }

        // Generate file path
        let fileName = "recording_\(Int(Date().timeIntervalSince1970)).m4a"
        let documentsURL = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let recordingsURL = documentsURL.appendingPathComponent(AppConstants.Storage.recordingsDirectory)

        // Create recordings directory if needed
        try? FileManager.default.createDirectory(at: recordingsURL, withIntermediateDirectories: true)

        let fileURL = recordingsURL.appendingPathComponent(fileName)
        currentFilePath = fileURL

        // Configure audio settings
        let settings = audioSettings()

        do {
            audioRecorder = try AVAudioRecorder(url: fileURL, settings: settings)
            audioRecorder?.delegate = self
            audioRecorder?.isMeteringEnabled = true

            guard audioRecorder?.prepareToRecord() == true else {
                errorMessage = "Failed to prepare recorder"
                return nil
            }

            guard audioRecorder?.record() == true else {
                errorMessage = "Failed to start recording"
                return nil
            }

            isRecording = true
            isPaused = false
            recordingStartTime = Date()
            recordingDuration = 0

            // Play start sound
            if soundEffectsEnabled {
                NSSound.beep()
            }

            // Start timers
            startTimers()

            return fileURL

        } catch {
            errorMessage = "Recording error: \(error.localizedDescription)"
            return nil
        }
    }

    func stopRecording() -> (url: URL, duration: TimeInterval)? {
        guard let result = stopRecordingWithResult() else {
            return nil
        }
        return (result.url, result.duration)
    }

    func stopRecordingWithResult() -> RecordingResult? {
        guard isRecording, let recorder = audioRecorder, let filePath = currentFilePath else {
            return nil
        }

        let duration = recordingDuration

        recorder.stop()
        stopTimers()

        isRecording = false
        isPaused = false
        recordingDuration = 0
        pausedDuration = 0
        audioLevel = 0
        lastError = nil

        // Play stop sound
        if soundEffectsEnabled {
            NSSound.beep()
        }

        audioRecorder = nil

        // Get file size
        let fileSize: Int64
        if let attrs = try? FileManager.default.attributesOfItem(atPath: filePath.path),
           let size = attrs[.size] as? Int64 {
            fileSize = size
        } else {
            fileSize = 0
        }

        return RecordingResult(
            url: filePath,
            duration: duration,
            fileSize: fileSize,
            sampleRate: AppConstants.Audio.recordingSampleRate
        )
    }

    func pauseRecording() {
        guard isRecording, !isPaused else { return }
        audioRecorder?.pause()
        isPaused = true
        stopTimers()
    }

    func resumeRecording() {
        guard isRecording, isPaused else { return }
        audioRecorder?.record()
        isPaused = false
        startTimers()
    }

    func cancelRecording() {
        guard isRecording else { return }

        audioRecorder?.stop()
        stopTimers()

        // Delete the file
        if let filePath = currentFilePath {
            try? FileManager.default.removeItem(at: filePath)
        }

        isRecording = false
        isPaused = false
        recordingDuration = 0
        audioLevel = 0
        audioRecorder = nil
        currentFilePath = nil
    }

    // MARK: - Audio Settings

    private func audioSettings() -> [String: Any] {
        let bitDepth: Int
        switch recordingQuality {
        case .standard:
            bitDepth = 16
        case .high:
            bitDepth = 24
        case .maximum:
            bitDepth = 32
        }

        return [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: AppConstants.Audio.recordingSampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            AVLinearPCMBitDepthKey: bitDepth
        ]
    }

    // MARK: - Timers

    private func startTimers() {
        // Audio level timer
        levelTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / AppConstants.UI.audioLevelUpdateFrequency, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.updateAudioLevel()
            }
        }

        // Duration timer
        durationTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.updateDuration()
            }
        }
    }

    private func stopTimers() {
        levelTimer?.invalidate()
        levelTimer = nil
        durationTimer?.invalidate()
        durationTimer = nil
    }

    private func updateAudioLevel() {
        guard let recorder = audioRecorder, isRecording, !isPaused else {
            audioLevel = 0
            return
        }

        recorder.updateMeters()
        let db = recorder.averagePower(forChannel: 0)

        // Convert dB to linear scale (0-1)
        // dB range is typically -160 to 0
        let minDb: Float = -60
        let normalizedDb = max(0, (db - minDb) / (-minDb))
        audioLevel = normalizedDb * inputGain
    }

    private func updateDuration() {
        guard let startTime = recordingStartTime, isRecording, !isPaused else { return }
        recordingDuration = Date().timeIntervalSince(startTime)

        // Check max duration
        if recordingDuration >= AppConstants.Audio.maxRecordingDuration {
            _ = stopRecording()
            errorMessage = "Maximum recording duration reached (4 hours)"
        }

        // Check disk space periodically
        if Int(recordingDuration) % Int(AppConstants.Storage.spaceCheckInterval) == 0 {
            if !hasSufficientDiskSpaceToContinue() {
                _ = stopRecording()
                errorMessage = "Recording stopped due to low disk space"
            }
        }
    }

    // MARK: - Disk Space

    private func hasSufficientDiskSpace() -> Bool {
        guard let availableSpace = try? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage else {
            return true // Assume OK if can't check
        }
        return availableSpace > AppConstants.Storage.minSpaceToStart
    }

    private func hasSufficientDiskSpaceToContinue() -> Bool {
        guard let availableSpace = try? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
            .volumeAvailableCapacityForImportantUsage else {
            return true
        }
        return availableSpace > AppConstants.Storage.minSpaceToContinue
    }
}

// MARK: - AVAudioRecorderDelegate

extension AudioRecorderService: AVAudioRecorderDelegate {
    nonisolated func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        Task { @MainActor in
            if !flag {
                errorMessage = "Recording finished unexpectedly"
            }
        }
    }

    nonisolated func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        Task { @MainActor in
            errorMessage = "Recording encode error: \(error?.localizedDescription ?? "Unknown")"
        }
    }
}

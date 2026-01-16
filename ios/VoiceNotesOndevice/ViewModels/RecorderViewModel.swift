import Foundation
import SwiftUI
import Combine
#if os(iOS)
import UIKit
#endif

/// ViewModel for active recording session
@MainActor
final class RecorderViewModel: ObservableObject {

    // MARK: - Published State

    /// Current recording state
    @Published private(set) var state: RecordingState = .idle

    /// Recording duration in seconds
    @Published private(set) var duration: TimeInterval = 0

    /// Normalized audio level (0.0 - 1.0) for waveform display
    @Published private(set) var audioLevel: Float = 0

    /// Error message to display
    @Published var errorMessage: String? = nil

    /// Whether microphone permission is granted
    @Published private(set) var hasPermission: Bool = false

    /// Whether to show the settings prompt for permission denied
    @Published var showPermissionDeniedAlert: Bool = false

    // MARK: - Recording State

    enum RecordingState: Equatable {
        case idle
        case recording
        case saving
    }

    // MARK: - Private Properties

    private let audioRecorder: AudioRecorderService
    private var cancellables = Set<AnyCancellable>()

    // MARK: - Computed Properties

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

    var canRecord: Bool {
        hasPermission && state == .idle
    }

    var canStop: Bool {
        state == .recording
    }

    // MARK: - Initialization

    init(audioRecorder: AudioRecorderService = AudioRecorderService()) {
        self.audioRecorder = audioRecorder
        setupBindings()
        checkPermission()
    }

    // MARK: - Public Methods

    /// Request microphone permission
    func requestPermission() async {
        // First check current status
        let currentStatus = audioRecorder.microphonePermissionStatus

        switch currentStatus {
        case .granted:
            hasPermission = true
        case .denied:
            hasPermission = false
            // Show alert that guides user to Settings
            showPermissionDeniedAlert = true
        case .undetermined:
            // Request permission
            let granted = await audioRecorder.requestMicrophonePermission()
            hasPermission = granted
            if !granted {
                // User just denied, show the settings prompt
                showPermissionDeniedAlert = true
            }
        @unknown default:
            hasPermission = false
        }
    }

    /// Open the app's Settings page where user can enable microphone permission
    func openSettings() {
        #if os(iOS)
        if let settingsURL = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(settingsURL)
        }
        #endif
    }

    /// Check current permission status
    func checkPermission() {
        hasPermission = audioRecorder.hasMicrophonePermission
    }

    /// Start a new recording
    func startRecording() {
        // Re-check permission before starting
        checkPermission()

        guard hasPermission else {
            // Show the settings prompt instead of just an error message
            showPermissionDeniedAlert = true
            return
        }

        guard state == .idle else { return }

        do {
            _ = try audioRecorder.startRecording()
            state = .recording

            // Provide feedback
            FeedbackService.shared.playRecordingStartSound()
            FeedbackService.shared.triggerRecordingStartHaptic()

        } catch {
            errorMessage = "Failed to start recording: \(error.localizedDescription)"
        }
    }

    /// Stop recording and return the result
    func stopRecording() -> RecordingResult? {
        guard state == .recording else { return nil }

        state = .saving

        // Provide feedback
        FeedbackService.shared.playRecordingStopSound()
        FeedbackService.shared.triggerRecordingStopHaptic()

        do {
            let result = try audioRecorder.stopRecording()

            state = .idle
            duration = 0
            audioLevel = 0

            return RecordingResult(
                fileURL: result.fileURL,
                duration: result.duration,
                fileSize: result.fileSize
            )

        } catch {
            errorMessage = "Failed to save recording: \(error.localizedDescription)"
            state = .idle
            return nil
        }
    }

    /// Cancel recording and delete any partial file
    func cancelRecording() {
        audioRecorder.cancelRecording()
        state = .idle
        duration = 0
        audioLevel = 0
    }

    // MARK: - Private Methods

    private func setupBindings() {
        // Bind to audioRecorder's published properties
        audioRecorder.$audioLevel
            .receive(on: RunLoop.main)
            .sink { [weak self] level in
                self?.audioLevel = level
            }
            .store(in: &cancellables)

        audioRecorder.$duration
            .receive(on: RunLoop.main)
            .sink { [weak self] duration in
                self?.duration = duration
            }
            .store(in: &cancellables)

        audioRecorder.$isRecording
            .receive(on: RunLoop.main)
            .sink { [weak self] isRecording in
                guard let self = self else { return }
                if !isRecording && self.state == .recording {
                    // Recording stopped externally (e.g., interruption)
                    self.state = .idle
                }
            }
            .store(in: &cancellables)

        audioRecorder.$errorMessage
            .receive(on: RunLoop.main)
            .compactMap { $0 }
            .sink { [weak self] error in
                self?.errorMessage = error
            }
            .store(in: &cancellables)
    }

    // MARK: - Result Type

    struct RecordingResult {
        let fileURL: URL
        let duration: TimeInterval
        let fileSize: Int64
    }
}

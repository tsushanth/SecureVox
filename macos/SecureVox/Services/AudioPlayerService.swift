import Foundation
import AVFoundation
import Combine
import os.log

private let logger = Logger(subsystem: "com.voicenotes.ondevice.macos", category: "AudioPlayer")

/// Service for playing audio recordings on macOS
@MainActor
class AudioPlayerService: ObservableObject {

    // MARK: - Singleton

    static let shared = AudioPlayerService()

    // MARK: - Published Properties

    @Published private(set) var isPlaying = false
    @Published private(set) var isPaused = false
    @Published private(set) var currentTime: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var audioLevel: Float = 0
    @Published var playbackSpeed: AppConstants.PlaybackSpeed = .normal {
        didSet {
            audioPlayer?.rate = Float(playbackSpeed.rawValue)
            UserDefaults.standard.set(playbackSpeed.rawValue, forKey: "playbackSpeed")
        }
    }

    // MARK: - Private Properties

    private var audioPlayer: AVAudioPlayer?
    private var displayLink: CVDisplayLink?
    private var updateTimer: Timer?
    private var currentFilePath: String?

    // MARK: - Constants

    private let skipInterval: TimeInterval = 15

    // MARK: - Initialization

    private init() {
        // Load saved playback speed
        if let savedSpeed = UserDefaults.standard.object(forKey: "playbackSpeed") as? Double,
           let speed = AppConstants.PlaybackSpeed(rawValue: savedSpeed) {
            playbackSpeed = speed
        }
    }

    // MARK: - Loading

    func load(url: URL) -> Bool {
        // If same file is already loaded, don't reload
        if currentFilePath == url.path, audioPlayer != nil {
            return true
        }

        stop()

        do {
            audioPlayer = try AVAudioPlayer(contentsOf: url)
            audioPlayer?.prepareToPlay()
            audioPlayer?.enableRate = true
            audioPlayer?.rate = Float(playbackSpeed.rawValue)

            duration = audioPlayer?.duration ?? 0
            currentTime = 0
            currentFilePath = url.path

            return true
        } catch {
            logger.error("Error loading audio: \(error.localizedDescription)")
            return false
        }
    }

    func load(filePath: String) -> Bool {
        let url = URL(fileURLWithPath: filePath)
        return load(url: url)
    }

    // MARK: - Playback Control

    func play() {
        guard let player = audioPlayer else { return }

        player.rate = Float(playbackSpeed.rawValue)
        player.play()
        isPlaying = true
        isPaused = false

        startProgressUpdates()
    }

    func pause() {
        audioPlayer?.pause()
        isPlaying = false
        isPaused = true

        stopProgressUpdates()
    }

    func togglePlayPause() {
        if isPlaying {
            pause()
        } else {
            play()
        }
    }

    func stop() {
        audioPlayer?.stop()
        audioPlayer?.currentTime = 0
        isPlaying = false
        isPaused = false
        currentTime = 0

        stopProgressUpdates()
    }

    func seek(to time: TimeInterval) {
        guard let player = audioPlayer else { return }

        let clampedTime = max(0, min(time, duration))
        player.currentTime = clampedTime
        currentTime = clampedTime
    }

    func seekToPercentage(_ percentage: Double) {
        let time = duration * percentage
        seek(to: time)
    }

    func skipForward() {
        seek(to: currentTime + skipInterval)
    }

    func skipBackward() {
        seek(to: currentTime - skipInterval)
    }

    // MARK: - Speed Control

    func setSpeed(_ speed: AppConstants.PlaybackSpeed) {
        playbackSpeed = speed
        if isPlaying {
            audioPlayer?.rate = Float(speed.rawValue)
        }
    }

    func cycleSpeed() {
        let speeds = AppConstants.PlaybackSpeed.allCases
        guard let currentIndex = speeds.firstIndex(of: playbackSpeed) else { return }

        let nextIndex = (currentIndex + 1) % speeds.count
        playbackSpeed = speeds[nextIndex]
    }

    // MARK: - Progress Updates

    private func startProgressUpdates() {
        stopProgressUpdates()

        updateTimer = Timer.scheduledTimer(withTimeInterval: 1.0 / 30.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.updateProgress()
            }
        }
    }

    private func stopProgressUpdates() {
        updateTimer?.invalidate()
        updateTimer = nil
    }

    private func updateProgress() {
        guard let player = audioPlayer else { return }

        currentTime = player.currentTime

        // Update audio level
        player.updateMeters()
        let db = player.averagePower(forChannel: 0)
        let minDb: Float = -60
        audioLevel = max(0, (db - minDb) / (-minDb))

        // Check if finished
        if !player.isPlaying && isPlaying && currentTime >= duration - 0.1 {
            isPlaying = false
            isPaused = false
            currentTime = 0
            audioLevel = 0
            stopProgressUpdates()
        }
    }

    // MARK: - Cleanup

    func release() {
        stop()
        audioPlayer = nil
        currentFilePath = nil
        duration = 0
        audioLevel = 0
    }
}

import AVFoundation
import Combine

/// Service for audio playback
class AudioPlayerService: NSObject, ObservableObject {

    // MARK: - Published Properties

    @Published private(set) var isPlaying: Bool = false
    @Published private(set) var currentTime: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0

    // MARK: - Private Properties

    private var audioPlayer: AVAudioPlayer?
    private var displayLink: CADisplayLink?
    private var audioURL: URL?

    // MARK: - Initialization

    override init() {
        super.init()
    }

    deinit {
        stop()
    }

    // MARK: - Public Methods

    /// Load audio file for playback
    func load(url: URL) throws {
        stop()

        audioPlayer = try AVAudioPlayer(contentsOf: url)
        audioPlayer?.delegate = self
        audioPlayer?.prepareToPlay()

        audioURL = url
        duration = audioPlayer?.duration ?? 0
        currentTime = 0
    }

    /// Start or resume playback
    func play() {
        guard let player = audioPlayer else { return }

        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            return
        }

        player.play()
        isPlaying = true
        startDisplayLink()
    }

    /// Pause playback
    func pause() {
        audioPlayer?.pause()
        isPlaying = false
        stopDisplayLink()
    }

    /// Toggle play/pause
    func togglePlayback() {
        if isPlaying {
            pause()
        } else {
            play()
        }
    }

    /// Stop playback and reset to beginning
    func stop() {
        audioPlayer?.stop()
        audioPlayer = nil
        isPlaying = false
        currentTime = 0
        stopDisplayLink()

        try? AVAudioSession.sharedInstance().setActive(false)
    }

    /// Seek to specific time
    func seek(to time: TimeInterval) {
        let clampedTime = max(0, min(time, duration))
        audioPlayer?.currentTime = clampedTime
        currentTime = clampedTime
    }

    /// Skip forward by seconds
    func skipForward(seconds: TimeInterval = 15) {
        seek(to: currentTime + seconds)
    }

    /// Skip backward by seconds
    func skipBackward(seconds: TimeInterval = 15) {
        seek(to: currentTime - seconds)
    }

    /// Set playback rate
    func setRate(_ rate: Float) {
        audioPlayer?.enableRate = true
        audioPlayer?.rate = rate
    }

    // MARK: - Private Methods

    private func startDisplayLink() {
        displayLink = CADisplayLink(target: self, selector: #selector(updateTime))
        displayLink?.preferredFrameRateRange = CAFrameRateRange(minimum: 15, maximum: 30)
        displayLink?.add(to: .main, forMode: .common)
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func updateTime() {
        guard let player = audioPlayer else { return }
        currentTime = player.currentTime
    }
}

// MARK: - AVAudioPlayerDelegate

extension AudioPlayerService: AVAudioPlayerDelegate {

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        isPlaying = false
        currentTime = 0
        stopDisplayLink()
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        isPlaying = false
        stopDisplayLink()
    }
}

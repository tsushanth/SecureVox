import AVFoundation
import Combine

/// Service for audio playback
class AudioPlayerService: NSObject, ObservableObject {

    // MARK: - Playback Speed

    enum PlaybackSpeed: Double, CaseIterable, Identifiable {
        case half = 0.5
        case threeQuarters = 0.75
        case normal = 1.0
        case oneAndQuarter = 1.25
        case oneAndHalf = 1.5
        case double = 2.0

        var id: Double { rawValue }

        var displayName: String {
            switch self {
            case .half: return "0.5x"
            case .threeQuarters: return "0.75x"
            case .normal: return "1x"
            case .oneAndQuarter: return "1.25x"
            case .oneAndHalf: return "1.5x"
            case .double: return "2x"
            }
        }
    }

    // MARK: - Published Properties

    @Published private(set) var isPlaying: Bool = false
    @Published private(set) var currentTime: TimeInterval = 0
    @Published private(set) var duration: TimeInterval = 0
    @Published private(set) var isPaused: Bool = false
    @Published var playbackSpeed: PlaybackSpeed = .normal {
        didSet {
            audioPlayer?.rate = Float(playbackSpeed.rawValue)
            UserDefaults.standard.set(playbackSpeed.rawValue, forKey: "playbackSpeed")
        }
    }

    // MARK: - Private Properties

    private var audioPlayer: AVAudioPlayer?
    private var displayLink: CADisplayLink?
    private var displayLinkTarget: DisplayLinkTarget?
    private var audioURL: URL?

    // MARK: - Initialization

    override init() {
        super.init()
        // Load saved playback speed
        if let savedSpeed = UserDefaults.standard.object(forKey: "playbackSpeed") as? Double,
           let speed = PlaybackSpeed(rawValue: savedSpeed) {
            playbackSpeed = speed
        }
    }

    deinit {
        // Explicitly invalidate display link to break any remaining references
        displayLink?.invalidate()
        displayLink = nil
        displayLinkTarget = nil
        audioPlayer?.stop()
        audioPlayer = nil
    }

    // MARK: - Public Methods

    /// Load audio file for playback
    func load(url: URL) throws {
        stop()

        audioPlayer = try AVAudioPlayer(contentsOf: url)
        audioPlayer?.delegate = self
        audioPlayer?.enableRate = true
        audioPlayer?.rate = Float(playbackSpeed.rawValue)
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

        player.rate = Float(playbackSpeed.rawValue)
        player.play()
        isPlaying = true
        isPaused = false
        startDisplayLink()
    }

    /// Pause playback
    func pause() {
        audioPlayer?.pause()
        isPlaying = false
        isPaused = true
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

    /// Set playback speed using the enum
    func setSpeed(_ speed: PlaybackSpeed) {
        playbackSpeed = speed
    }

    /// Cycle through available playback speeds
    func cycleSpeed() {
        let speeds = PlaybackSpeed.allCases
        guard let currentIndex = speeds.firstIndex(of: playbackSpeed) else { return }

        let nextIndex = (currentIndex + 1) % speeds.count
        playbackSpeed = speeds[nextIndex]
    }

    // MARK: - Private Methods

    private func startDisplayLink() {
        stopDisplayLink() // Ensure any existing link is stopped first

        // Use a weak wrapper to avoid retain cycle between CADisplayLink -> self
        let target = DisplayLinkTarget { [weak self] in
            self?.updateTime()
        }
        displayLinkTarget = target

        displayLink = CADisplayLink(target: target, selector: #selector(DisplayLinkTarget.tick))
        displayLink?.preferredFrameRateRange = CAFrameRateRange(minimum: 15, maximum: 30)
        displayLink?.add(to: .main, forMode: .common)
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
        displayLinkTarget = nil
    }

    private func updateTime() {
        guard let player = audioPlayer else { return }
        currentTime = player.currentTime
    }
}

// MARK: - Display Link Target

/// A helper class that breaks the retain cycle between CADisplayLink and its target.
/// CADisplayLink retains its target strongly, so using `self` directly would create a retain cycle.
/// This wrapper holds a weak reference via closure capture, allowing proper deallocation.
private final class DisplayLinkTarget: NSObject {
    private let handler: () -> Void

    init(handler: @escaping () -> Void) {
        self.handler = handler
        super.init()
    }

    @objc func tick() {
        handler()
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

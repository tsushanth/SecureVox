import SwiftUI
import AVFoundation

/// Bottom sheet that synthesizes a transcript via Kokoro on-device and plays it
/// back. Self-contained — owns its own AVAudioPlayer so it doesn't fight with
/// the recording's PlaybackBar.
struct ReadAloudSheet: View {

    let text: String

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var modelManager = KokoroModelManager.shared
    @StateObject private var controller = ReadAloudController()
    @State private var selectedVoice: String = KokoroModelManager.preferredVoiceID

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                voicePicker
                stateView
                Spacer()
                primaryButton
            }
            .padding(24)
            .navigationTitle("Read Aloud")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .onDisappear { controller.stop() }
        }
        .presentationDetents([.medium, .large])
    }

    private var voicePicker: some View {
        Picker("Voice", selection: $selectedVoice) {
            ForEach(KokoroVoiceCatalog.entries) { entry in
                Text(entry.displayName).tag(entry.id)
            }
        }
        .pickerStyle(.menu)
        .onChange(of: selectedVoice) { _, newValue in
            KokoroModelManager.preferredVoiceID = newValue
            // If we already synthesized with the old voice, drop the cache so
            // the next play picks up the new selection.
            controller.invalidate()
        }
    }

    @ViewBuilder
    private var stateView: some View {
        switch controller.state {
        case .idle:
            VStack(spacing: 8) {
                Image(systemName: "speaker.wave.2")
                    .font(.system(size: 48))
                    .foregroundColor(.accentColor)
                Text(modelStatusText)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        case .synthesizing(let fraction):
            // While the model is preparing (downloading / compiling), the
            // synthesizer hasn't started yet so its fraction is 0. Show the
            // model-prep state instead — that's the long-running work.
            if modelManager.state == .preparing {
                preparingView
            } else {
                VStack(spacing: 12) {
                    ProgressView(value: fraction)
                        .progressViewStyle(.linear)
                    Text("Synthesizing… \(Int((fraction * 100).rounded()))%")
                        .font(.subheadline)
                        .foregroundColor(.secondary)
                }
            }
        case .playing(let progress, let duration), .paused(let progress, let duration):
            VStack(spacing: 12) {
                ProgressView(value: progress, total: max(0.01, duration))
                    .progressViewStyle(.linear)
                HStack {
                    Text(format(progress))
                    Spacer()
                    Text(format(duration))
                }
                .font(.caption.monospacedDigit())
                .foregroundColor(.secondary)
            }
        case .failed(let message):
            VStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 36))
                    .foregroundColor(.orange)
                Text(message)
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    @ViewBuilder
    private var primaryButton: some View {
        switch controller.state {
        case .idle, .failed:
            Button {
                Task { await controller.startSynthesis(text: text, voiceID: selectedVoice) }
            } label: {
                Label("Start Reading", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

        case .synthesizing:
            Button(role: .destructive) {
                controller.stop()
            } label: {
                Label("Cancel", systemImage: "xmark")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)

        case .playing:
            Button {
                controller.pause()
            } label: {
                Label("Pause", systemImage: "pause.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)

        case .paused:
            Button {
                controller.resume()
            } label: {
                Label("Resume", systemImage: "play.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
    }

    /// Rich, observable view for the prep phase. Shown whenever
    /// `KokoroModelManager.state == .preparing` — covers download (~250 MB),
    /// Core ML compile, and warm-up. Polls the manager every 0.5 s so the
    /// caption stays alive even if FluidAudio's `fractionCompleted` is silent.
    @ViewBuilder
    private var preparingView: some View {
        VStack(spacing: 12) {
            if modelManager.downloadProgress > 0 {
                ProgressView(value: modelManager.downloadProgress)
                    .progressViewStyle(.linear)
            } else {
                ProgressView()
                    .progressViewStyle(.linear)
            }
            Text(preparingCaption)
                .font(.subheadline)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
            Text("First run only — about 250 MB. Future runs are instant.")
                .font(.caption)
                .foregroundColor(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var preparingCaption: String {
        // Prefer the rich phase string from FluidAudio when present
        // ("Downloading model… 12/33 files", "Compiling kokoro_21_5s…", etc.).
        if !modelManager.phaseDescription.isEmpty {
            return modelManager.phaseDescription
        }
        let pct = Int((modelManager.downloadProgress * 100).rounded())
        if modelManager.downloadProgress >= 1 { return "Loading voice model…" }
        if modelManager.downloadProgress > 0 { return "Downloading voice model… \(pct)%" }
        return "Preparing voice model…"
    }

    private var modelStatusText: String {
        switch modelManager.state {
        case .ready: return "Tap Start Reading to synthesize on-device."
        case .preparing:
            let pct = Int((modelManager.downloadProgress * 100).rounded())
            return "Downloading model… \(pct)%"
        case .failed(let m): return m
        case .notReady: return "First use will download a ~250 MB voice model."
        }
    }

    private func format(_ seconds: Double) -> String {
        let s = max(0, Int(seconds.rounded()))
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: - Controller

@MainActor
final class ReadAloudController: NSObject, ObservableObject, AVAudioPlayerDelegate {

    enum State: Equatable {
        case idle
        case synthesizing(fraction: Double)
        case playing(progress: Double, duration: Double)
        case paused(progress: Double, duration: Double)
        case failed(message: String)
    }

    @Published var state: State = .idle

    private var player: AVAudioPlayer?
    private var cachedFileURL: URL?
    private var cachedVoice: String?
    private var cachedTextHash: Int?
    private var displayLink: CADisplayLink?
    private var synthesisTask: Task<Void, Never>?

    func startSynthesis(text: String, voiceID: String) async {
        synthesisTask?.cancel()
        let textHash = text.hashValue

        // Reuse cached WAV if same text + same voice (fast re-play scenario).
        if let url = cachedFileURL,
           cachedVoice == voiceID,
           cachedTextHash == textHash,
           FileManager.default.fileExists(atPath: url.path) {
            await play(url: url)
            return
        }

        state = .synthesizing(fraction: 0)

        let task = Task { @MainActor in
            do {
                let result = try await KokoroTTSService.shared.synthesize(
                    text: text,
                    voiceID: voiceID,
                    progress: { [weak self] progress in
                        Task { @MainActor in
                            self?.state = .synthesizing(fraction: progress.fraction)
                        }
                    }
                )
                if Task.isCancelled { return }
                cachedFileURL = result.fileURL
                cachedVoice = voiceID
                cachedTextHash = textHash
                await play(url: result.fileURL)
            } catch is CancellationError {
                state = .idle
            } catch {
                state = .failed(message: error.localizedDescription)
            }
        }
        synthesisTask = task
        await task.value
    }

    func pause() {
        player?.pause()
        if let player {
            state = .paused(progress: player.currentTime, duration: player.duration)
        }
        stopDisplayLink()
    }

    func resume() {
        guard let player else { return }
        if player.play() {
            state = .playing(progress: player.currentTime, duration: player.duration)
            startDisplayLink()
        }
    }

    func stop() {
        synthesisTask?.cancel()
        synthesisTask = nil
        player?.stop()
        player = nil
        stopDisplayLink()
        state = .idle
    }

    /// Drop cached audio so a new voice selection will trigger fresh synthesis.
    func invalidate() {
        cachedFileURL = nil
        cachedVoice = nil
        cachedTextHash = nil
    }

    private func play(url: URL) async {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
            try AVAudioSession.sharedInstance().setActive(true)
            let p = try AVAudioPlayer(contentsOf: url)
            p.delegate = self
            p.prepareToPlay()
            player = p
            if p.play() {
                state = .playing(progress: 0, duration: p.duration)
                startDisplayLink()
            } else {
                state = .failed(message: "Couldn't start playback")
            }
        } catch {
            state = .failed(message: error.localizedDescription)
        }
    }

    private func startDisplayLink() {
        stopDisplayLink()
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 5, maximum: 30, preferred: 10)
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    private func stopDisplayLink() {
        displayLink?.invalidate()
        displayLink = nil
    }

    @objc private func tick() {
        guard let player, player.isPlaying else { return }
        state = .playing(progress: player.currentTime, duration: player.duration)
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in
            self.stopDisplayLink()
            self.state = .idle
        }
    }
}

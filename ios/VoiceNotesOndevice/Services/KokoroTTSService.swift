import Foundation
import AVFoundation
import UIKit

#if canImport(FluidAudio)
import FluidAudio
import CoreML
#endif

private let kokoroOnDeviceToggleKey = "securevox.kokoro.onDeviceEnabled"
private let kokoroVoiceKey = "securevox.kokoro.voice"

// MARK: - Model manager (eligibility + state for UI)

@MainActor
final class KokoroModelManager: ObservableObject {

    static let shared = KokoroModelManager()

    enum State: Equatable {
        case notReady
        case preparing
        case ready
        case failed(message: String)
    }

    @Published private(set) var state: State = .notReady
    @Published private(set) var lastError: String? = nil
    @Published private(set) var downloadProgress: Double = 0
    /// Human-readable phase from FluidAudio (e.g. "Downloading 12/33 files",
    /// "Compiling kokoro_21_5s"). Updated on the main actor.
    @Published private(set) var phaseDescription: String = ""

    static let estimatedDownloadBytes: Int64 = 250 * 1_024 * 1_024

    /// iOS 17+ on any device with ≥3 GB RAM. iPhone XR (3 GB, A12) is the
    /// floor — older devices won't have iOS 17 so the OS check covers them.
    /// If the device can't load the model in practice, FluidAudio surfaces
    /// a clear error at synthesis time and we display it in the sheet.
    nonisolated static var isDeviceEligible: Bool {
        let totalRAM = ProcessInfo.processInfo.physicalMemory
        let ramGB = Double(totalRAM) / 1_000_000_000
        let osOK: Bool
        if #available(iOS 17.0, *) { osOK = true } else { osOK = false }
        let eligible = osOK && totalRAM >= 2_700_000_000
        if !eligible {
            print("[Kokoro] Device ineligible — iOS17+:\(osOK) RAM:\(String(format: "%.1f", ramGB))GB")
        }
        return eligible
    }

    nonisolated static var isOnDeviceEnabledByUser: Bool {
        get {
            if UserDefaults.standard.object(forKey: kokoroOnDeviceToggleKey) == nil { return true }
            return UserDefaults.standard.bool(forKey: kokoroOnDeviceToggleKey)
        }
        set { UserDefaults.standard.set(newValue, forKey: kokoroOnDeviceToggleKey) }
    }

    nonisolated static var preferredVoiceID: String {
        get { UserDefaults.standard.string(forKey: kokoroVoiceKey) ?? "af_bella" }
        set { UserDefaults.standard.set(newValue, forKey: kokoroVoiceKey) }
    }

    func markReady() {
        state = .ready
        lastError = nil
        downloadProgress = 1
    }
    func markPreparing() {
        state = .preparing
        downloadProgress = 0
        phaseDescription = "Preparing voice model…"
    }
    func updateProgress(_ fraction: Double) {
        downloadProgress = max(downloadProgress, min(1, fraction))
    }
    func updatePhase(_ description: String) {
        phaseDescription = description
    }
    func markFailed(_ message: String) {
        state = .failed(message: message)
        lastError = message
    }

    var isReady: Bool {
        if case .ready = state { return true }
        return false
    }

    private init() {}
}

// MARK: - Voice catalog

enum KokoroVoiceCatalog {
    struct Entry: Identifiable, Hashable {
        let id: String
        let displayName: String
        let language: String
        let isFemale: Bool
    }

    static let entries: [Entry] = [
        .init(id: "af_bella",   displayName: "Bella (US, Female)",   language: "en-US", isFemale: true),
        .init(id: "am_michael", displayName: "Michael (US, Male)",   language: "en-US", isFemale: false),
        .init(id: "bf_emma",    displayName: "Emma (UK, Female)",    language: "en-GB", isFemale: true),
        .init(id: "bm_george",  displayName: "George (UK, Male)",    language: "en-GB", isFemale: false),
    ]

    static let allIDs: Set<String> = Set(entries.map(\.id))
}

// MARK: - Errors

enum KokoroError: LocalizedError {
    case deviceIneligible
    case modelNotReady
    case inferenceFailed(String)
    case textEmpty

    var errorDescription: String? {
        switch self {
        case .deviceIneligible: return "On-device Read Aloud requires iOS 17+ and at least 4 GB of memory."
        case .modelNotReady:    return "On-device model has not finished downloading yet."
        case .inferenceFailed(let detail): return "On-device synthesis failed: \(detail)"
        case .textEmpty:        return "Nothing to read."
        }
    }
}

// MARK: - TTS service
//
// Synthesizes a single block of text to a WAV file using Kokoro 82M via
// FluidAudio. Pre-chunks input to ≤70-character windows so each FluidAudio
// call fits the 5-second short-variant Core ML graph.

actor KokoroTTSService {

    static let shared = KokoroTTSService()

    struct Progress: Sendable {
        let fraction: Double
    }

    struct Result: Sendable {
        let fileURL: URL
        let durationSeconds: Double
    }

    private var manager: KokoroManagerHandle?

    func synthesize(
        text: String,
        voiceID: String,
        speed: Float = 1.0,
        progress: @escaping @Sendable (Progress) -> Void = { _ in }
    ) async throws -> Result {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw KokoroError.textEmpty }

        try await ensureReady()
        guard let handle = manager else { throw KokoroError.modelNotReady }

        let chunks = Self.splitIntoSafeChunks(trimmed, maxChars: 70)
        var allSamples: [Int16] = []
        let sampleRate = 24_000
        let voice = KokoroVoiceCatalog.allIDs.contains(voiceID) ? voiceID : "af_bella"

        for (idx, chunk) in chunks.enumerated() {
            try Task.checkCancellation()
            let pcm = try await handle.synthesize(text: chunk, voice: voice, speed: speed)
            allSamples.append(contentsOf: pcm.samples)
            progress(Progress(fraction: Double(idx + 1) / Double(chunks.count)))
        }

        let url = try writeWAV(samples: allSamples, sampleRate: sampleRate)
        let duration = Double(allSamples.count) / Double(sampleRate)
        return Result(fileURL: url, durationSeconds: duration)
    }

    // MARK: - Setup

    private func ensureReady() async throws {
        guard KokoroModelManager.isDeviceEligible else {
            await MainActor.run {
                KokoroModelManager.shared.markFailed("Device not eligible (needs iOS 17+ and ≥4 GB RAM)")
            }
            throw KokoroError.deviceIneligible
        }
        if manager != nil { return }

        await MainActor.run { KokoroModelManager.shared.markPreparing() }
        do {
            manager = try await KokoroManagerHandle.create()
            await MainActor.run { KokoroModelManager.shared.markReady() }
        } catch {
            await MainActor.run { KokoroModelManager.shared.markFailed(error.localizedDescription) }
            throw error
        }
    }

    // MARK: - Chunking (sentence-aware, ≤maxChars windows)

    nonisolated static func splitIntoSafeChunks(_ text: String, maxChars: Int) -> [String] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        if trimmed.count <= maxChars { return [trimmed] }

        var sentences: [String] = []
        var current = ""
        for ch in trimmed {
            current.append(ch)
            if ch == "." || ch == "!" || ch == "?" || ch == "\n" {
                let s = current.trimmingCharacters(in: .whitespacesAndNewlines)
                if !s.isEmpty { sentences.append(s) }
                current = ""
            }
        }
        let tail = current.trimmingCharacters(in: .whitespacesAndNewlines)
        if !tail.isEmpty { sentences.append(tail) }

        var chunks: [String] = []
        var buf = ""
        for s in sentences {
            if s.count > maxChars {
                if !buf.isEmpty { chunks.append(buf); buf = "" }
                chunks.append(contentsOf: hardSplit(s, maxChars: maxChars))
                continue
            }
            if buf.isEmpty {
                buf = s
            } else if buf.count + 1 + s.count <= maxChars {
                buf += " " + s
            } else {
                chunks.append(buf)
                buf = s
            }
        }
        if !buf.isEmpty { chunks.append(buf) }
        return chunks
    }

    nonisolated private static func hardSplit(_ s: String, maxChars: Int) -> [String] {
        var out: [String] = []
        var buf = ""
        for word in s.split(separator: " ") {
            let w = String(word)
            if w.count > maxChars {
                if !buf.isEmpty { out.append(buf); buf = "" }
                var idx = w.startIndex
                while idx < w.endIndex {
                    let next = w.index(idx, offsetBy: maxChars, limitedBy: w.endIndex) ?? w.endIndex
                    out.append(String(w[idx..<next]))
                    idx = next
                }
                continue
            }
            if buf.isEmpty {
                buf = w
            } else if buf.count + 1 + w.count <= maxChars {
                buf += " " + w
            } else {
                out.append(buf)
                buf = w
            }
        }
        if !buf.isEmpty { out.append(buf) }
        return out
    }

    // MARK: - WAV writer

    private func writeWAV(samples: [Int16], sampleRate: Int) throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("securevox-readaloud-\(UUID().uuidString).wav")

        var data = Data()
        let dataSize = samples.count * 2
        let chunkSize = 36 + dataSize
        data.append(contentsOf: Array("RIFF".utf8))
        data.append(contentsOf: UInt32(chunkSize).leBytes)
        data.append(contentsOf: Array("WAVE".utf8))
        data.append(contentsOf: Array("fmt ".utf8))
        data.append(contentsOf: UInt32(16).leBytes)
        data.append(contentsOf: UInt16(1).leBytes)
        data.append(contentsOf: UInt16(1).leBytes)
        data.append(contentsOf: UInt32(sampleRate).leBytes)
        data.append(contentsOf: UInt32(sampleRate * 2).leBytes)
        data.append(contentsOf: UInt16(2).leBytes)
        data.append(contentsOf: UInt16(16).leBytes)
        data.append(contentsOf: Array("data".utf8))
        data.append(contentsOf: UInt32(dataSize).leBytes)
        samples.withUnsafeBufferPointer { ptr in
            data.append(UnsafeBufferPointer(start: ptr.baseAddress, count: ptr.count))
        }
        try data.write(to: url)
        return url
    }
}

// MARK: - FluidAudio wrapper

struct PCMResult: Sendable {
    let samples: [Int16]
    let sampleRate: Int
}

actor KokoroManagerHandle {

    #if canImport(FluidAudio)

    private let manager: KokoroTtsManager

    init(manager: KokoroTtsManager) {
        self.manager = manager
    }

    static func create() async throws -> KokoroManagerHandle {
        // iOS 26 ANE compiler regression workaround per FluidAudio docs.
        let computeUnits: MLComputeUnits
        if #available(iOS 26.0, *) {
            computeUnits = .cpuAndGPU
        } else {
            computeUnits = .all
        }

        let progressHandler: DownloadUtils.ProgressHandler = { snapshot in
            let description: String
            switch snapshot.phase {
            case .listing:
                description = "Listing model files…"
            case .downloading(let completed, let total):
                if total > 0 {
                    description = "Downloading model… \(completed)/\(total) files"
                } else {
                    description = "Downloading model…"
                }
            case .compiling(let modelName):
                description = modelName.isEmpty
                    ? "Compiling for Neural Engine…"
                    : "Compiling \(modelName)…"
            }
            Task { @MainActor in
                KokoroModelManager.shared.updateProgress(snapshot.fractionCompleted)
                KokoroModelManager.shared.updatePhase(description)
            }
        }
        let models = try await TtsModels.download(
            variants: [.fiveSecond],
            computeUnits: computeUnits,
            progressHandler: progressHandler
        )
        let m = KokoroTtsManager(computeUnits: computeUnits)
        try await m.initialize(models: models)
        return KokoroManagerHandle(manager: m)
    }

    func synthesize(text: String, voice: String, speed: Float) async throws -> PCMResult {
        let audioData: Data = try await manager.synthesize(
            text: text,
            voice: voice,
            voiceSpeed: speed
        )
        let samples = audioData.toInt16PCMSamples()
        return PCMResult(samples: samples, sampleRate: 24_000)
    }

    #else

    static func create() async throws -> KokoroManagerHandle {
        throw KokoroError.inferenceFailed("FluidAudio SwiftPM package not linked")
    }

    func synthesize(text: String, voice: String, speed: Float) async throws -> PCMResult {
        throw KokoroError.inferenceFailed("FluidAudio SwiftPM package not linked")
    }

    #endif
}

// MARK: - Helpers

private extension UInt32 {
    var leBytes: [UInt8] {
        withUnsafeBytes(of: self.littleEndian) { Array($0) }
    }
}

private extension UInt16 {
    var leBytes: [UInt8] {
        withUnsafeBytes(of: self.littleEndian) { Array($0) }
    }
}

#if canImport(FluidAudio)
private extension Data {
    func toInt16PCMSamples() -> [Int16] {
        var payload = self
        if payload.count > 44, payload.starts(with: Array("RIFF".utf8)) {
            payload = payload.dropFirst(44)
        }
        let count = payload.count / 2
        var samples = [Int16](repeating: 0, count: count)
        payload.withUnsafeBytes { rawBuf in
            guard let base = rawBuf.bindMemory(to: Int16.self).baseAddress else { return }
            for i in 0..<count { samples[i] = Int16(littleEndian: base[i]) }
        }
        return samples
    }
}
#endif

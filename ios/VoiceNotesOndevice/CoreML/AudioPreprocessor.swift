import AVFoundation
import Accelerate

/// Preprocesses audio files for Whisper model input
/// Converts to 16kHz mono PCM format required by Whisper
final class AudioPreprocessor {

    // MARK: - Types

    struct ProcessedAudio {
        /// Audio samples as Float array
        let samples: [Float]

        /// Sample rate (should be 16000)
        let sampleRate: Double

        /// Duration in seconds
        let duration: TimeInterval

        /// Number of samples
        var sampleCount: Int { samples.count }
    }

    enum ProcessingError: Error, LocalizedError {
        case fileNotFound
        case invalidFormat
        case readFailed(Error)
        case conversionFailed

        var errorDescription: String? {
            switch self {
            case .fileNotFound:
                return "Audio file not found"
            case .invalidFormat:
                return "Invalid audio format"
            case .readFailed(let error):
                return "Failed to read audio: \(error.localizedDescription)"
            case .conversionFailed:
                return "Failed to convert audio format"
            }
        }
    }

    // MARK: - Constants

    /// Whisper requires 16kHz sample rate
    static let targetSampleRate: Double = 16000

    /// Whisper requires mono audio
    static let targetChannels: AVAudioChannelCount = 1

    // MARK: - Public Methods

    /// Process audio file to Whisper-compatible format
    func process(url: URL) async throws -> ProcessedAudio {
        guard FileManager.default.fileExists(atPath: url.path) else {
            throw ProcessingError.fileNotFound
        }

        // Load audio file
        let audioFile: AVAudioFile
        do {
            audioFile = try AVAudioFile(forReading: url)
        } catch {
            throw ProcessingError.readFailed(error)
        }

        let sourceFormat = audioFile.processingFormat
        let sourceFrameCount = AVAudioFrameCount(audioFile.length)

        // Create target format
        guard let targetFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Self.targetSampleRate,
            channels: Self.targetChannels,
            interleaved: false
        ) else {
            throw ProcessingError.invalidFormat
        }

        // Read source audio
        guard let sourceBuffer = AVAudioPCMBuffer(
            pcmFormat: sourceFormat,
            frameCapacity: sourceFrameCount
        ) else {
            throw ProcessingError.conversionFailed
        }

        do {
            try audioFile.read(into: sourceBuffer)
        } catch {
            throw ProcessingError.readFailed(error)
        }

        // Convert to target format
        let convertedBuffer = try convert(
            buffer: sourceBuffer,
            to: targetFormat
        )

        // Extract samples
        guard let channelData = convertedBuffer.floatChannelData?[0] else {
            throw ProcessingError.conversionFailed
        }

        let frameCount = Int(convertedBuffer.frameLength)
        let samples = Array(UnsafeBufferPointer(start: channelData, count: frameCount))

        let duration = Double(frameCount) / Self.targetSampleRate

        return ProcessedAudio(
            samples: samples,
            sampleRate: Self.targetSampleRate,
            duration: duration
        )
    }

    /// Process a time range of audio
    func process(
        url: URL,
        startTime: TimeInterval,
        endTime: TimeInterval
    ) async throws -> ProcessedAudio {

        let fullAudio = try await process(url: url)

        let startSample = Int(startTime * Self.targetSampleRate)
        let endSample = min(Int(endTime * Self.targetSampleRate), fullAudio.sampleCount)

        guard startSample < endSample else {
            throw ProcessingError.invalidFormat
        }

        let samples = Array(fullAudio.samples[startSample..<endSample])
        let duration = Double(samples.count) / Self.targetSampleRate

        return ProcessedAudio(
            samples: samples,
            sampleRate: Self.targetSampleRate,
            duration: duration
        )
    }

    // MARK: - Private Methods

    private func convert(
        buffer: AVAudioPCMBuffer,
        to targetFormat: AVAudioFormat
    ) throws -> AVAudioPCMBuffer {

        guard let converter = AVAudioConverter(from: buffer.format, to: targetFormat) else {
            throw ProcessingError.conversionFailed
        }

        // Calculate target frame count
        let ratio = targetFormat.sampleRate / buffer.format.sampleRate
        let targetFrameCount = AVAudioFrameCount(Double(buffer.frameLength) * ratio)

        guard let targetBuffer = AVAudioPCMBuffer(
            pcmFormat: targetFormat,
            frameCapacity: targetFrameCount
        ) else {
            throw ProcessingError.conversionFailed
        }

        var error: NSError?
        let inputBlock: AVAudioConverterInputBlock = { _, outStatus in
            outStatus.pointee = .haveData
            return buffer
        }

        converter.convert(to: targetBuffer, error: &error, withInputFrom: inputBlock)

        if let error = error {
            throw ProcessingError.readFailed(error)
        }

        return targetBuffer
    }
}

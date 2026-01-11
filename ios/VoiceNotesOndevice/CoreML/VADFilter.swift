import Foundation
import Accelerate

/// Voice Activity Detection filter
/// Identifies speech vs silence to optimize transcription
final class VADFilter {

    // MARK: - Types

    struct SpeechSegment {
        let startTime: TimeInterval
        let endTime: TimeInterval

        var duration: TimeInterval {
            endTime - startTime
        }
    }

    struct VADResult {
        /// Detected speech segments
        let segments: [SpeechSegment]

        /// Percentage of audio containing speech
        let speechRatio: Double

        /// Total speech duration
        let totalSpeechDuration: TimeInterval
    }

    // MARK: - Configuration

    struct Configuration {
        /// Energy threshold for speech detection (dB)
        var energyThreshold: Float = -35

        /// Minimum silence duration to split segments (seconds)
        var minSilenceDuration: TimeInterval = 0.5

        /// Minimum speech duration to keep (seconds)
        var minSpeechDuration: TimeInterval = 0.3

        /// Frame size for analysis (samples)
        var frameSize: Int = 512

        /// Hop size between frames (samples)
        var hopSize: Int = 256

        static let `default` = Configuration()
    }

    // MARK: - Properties

    let sampleRate: Double
    let config: Configuration

    // MARK: - Initialization

    init(
        sampleRate: Double = AudioPreprocessor.targetSampleRate,
        config: Configuration = .default
    ) {
        self.sampleRate = sampleRate
        self.config = config
    }

    // MARK: - Public Methods

    /// Analyze audio for speech activity
    func analyze(samples: [Float]) -> VADResult {
        let frameDuration = Double(config.hopSize) / sampleRate
        var speechFrames: [Bool] = []

        // Process frames
        var frameStart = 0
        while frameStart + config.frameSize <= samples.count {
            let frameEnd = frameStart + config.frameSize
            let frame = Array(samples[frameStart..<frameEnd])

            let energy = calculateEnergy(frame)
            let isSpeech = energy > config.energyThreshold

            speechFrames.append(isSpeech)
            frameStart += config.hopSize
        }

        // Convert frame-level detection to segments
        let segments = extractSegments(
            speechFrames: speechFrames,
            frameDuration: frameDuration
        )

        // Calculate statistics
        let totalDuration = Double(samples.count) / sampleRate
        let totalSpeechDuration = segments.reduce(0) { $0 + $1.duration }
        let speechRatio = totalDuration > 0 ? totalSpeechDuration / totalDuration : 0

        return VADResult(
            segments: segments,
            speechRatio: speechRatio,
            totalSpeechDuration: totalSpeechDuration
        )
    }

    /// Filter samples to keep only speech regions
    func filterSilence(samples: [Float]) -> [Float] {
        let result = analyze(samples: samples)

        var filtered: [Float] = []

        for segment in result.segments {
            let startSample = Int(segment.startTime * sampleRate)
            let endSample = min(Int(segment.endTime * sampleRate), samples.count)

            if startSample < endSample {
                filtered.append(contentsOf: samples[startSample..<endSample])
            }
        }

        return filtered
    }

    /// Check if audio contains mostly silence
    func isMostlySilence(samples: [Float], threshold: Double = 0.1) -> Bool {
        let result = analyze(samples: samples)
        return result.speechRatio < threshold
    }

    // MARK: - Private Methods

    private func calculateEnergy(_ frame: [Float]) -> Float {
        var sum: Float = 0

        // Calculate RMS
        vDSP_svesq(frame, 1, &sum, vDSP_Length(frame.count))
        let rms = sqrt(sum / Float(frame.count))

        // Convert to dB
        let db = 20 * log10(max(rms, 1e-10))
        return db
    }

    private func extractSegments(
        speechFrames: [Bool],
        frameDuration: TimeInterval
    ) -> [SpeechSegment] {

        var segments: [SpeechSegment] = []
        var segmentStart: Int?

        let minSilenceFrames = Int(config.minSilenceDuration / frameDuration)
        let minSpeechFrames = Int(config.minSpeechDuration / frameDuration)

        var silenceCount = 0

        for (index, isSpeech) in speechFrames.enumerated() {
            if isSpeech {
                if segmentStart == nil {
                    segmentStart = index
                }
                silenceCount = 0
            } else {
                silenceCount += 1

                if let start = segmentStart, silenceCount >= minSilenceFrames {
                    let segmentLength = index - silenceCount - start

                    if segmentLength >= minSpeechFrames {
                        let segment = SpeechSegment(
                            startTime: Double(start) * frameDuration,
                            endTime: Double(index - silenceCount) * frameDuration
                        )
                        segments.append(segment)
                    }

                    segmentStart = nil
                }
            }
        }

        // Handle final segment
        if let start = segmentStart {
            let segmentLength = speechFrames.count - start

            if segmentLength >= minSpeechFrames {
                let segment = SpeechSegment(
                    startTime: Double(start) * frameDuration,
                    endTime: Double(speechFrames.count) * frameDuration
                )
                segments.append(segment)
            }
        }

        return segments
    }
}

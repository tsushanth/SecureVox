import Foundation

/// Handles chunking of audio for Whisper processing
/// Whisper processes 30-second chunks with overlap for continuity
final class ChunkProcessor {

    // MARK: - Types

    struct AudioChunk {
        /// Chunk index (0-based)
        let index: Int

        /// Start time in seconds
        let startTime: TimeInterval

        /// End time in seconds
        let endTime: TimeInterval

        /// Audio samples for this chunk
        let samples: [Float]

        /// Whether this is the last chunk
        let isLast: Bool

        var duration: TimeInterval {
            endTime - startTime
        }
    }

    // MARK: - Constants

    /// Maximum chunk duration (Whisper limit)
    static let maxChunkDuration: TimeInterval = 30.0

    /// Overlap between chunks to avoid word cutoff
    static let overlapDuration: TimeInterval = 1.0

    /// Minimum chunk duration to process
    static let minChunkDuration: TimeInterval = 0.5

    // MARK: - Properties

    let sampleRate: Double

    // MARK: - Initialization

    init(sampleRate: Double = AudioPreprocessor.targetSampleRate) {
        self.sampleRate = sampleRate
    }

    // MARK: - Public Methods

    /// Split audio samples into chunks
    func createChunks(from samples: [Float], duration: TimeInterval) -> [AudioChunk] {
        var chunks: [AudioChunk] = []

        let chunkDuration = Self.maxChunkDuration
        let overlap = Self.overlapDuration
        let stepDuration = chunkDuration - overlap

        var currentTime: TimeInterval = 0
        var chunkIndex = 0

        while currentTime < duration {
            let chunkStart = currentTime
            let chunkEnd = min(currentTime + chunkDuration, duration)
            let isLast = chunkEnd >= duration

            // Calculate sample indices
            let startSample = Int(chunkStart * sampleRate)
            let endSample = min(Int(chunkEnd * sampleRate), samples.count)

            guard endSample > startSample else { break }

            let chunkSamples = Array(samples[startSample..<endSample])

            // Skip very short final chunks
            if isLast && chunkEnd - chunkStart < Self.minChunkDuration && !chunks.isEmpty {
                break
            }

            let chunk = AudioChunk(
                index: chunkIndex,
                startTime: chunkStart,
                endTime: chunkEnd,
                samples: chunkSamples,
                isLast: isLast
            )

            chunks.append(chunk)

            currentTime += stepDuration
            chunkIndex += 1

            if isLast { break }
        }

        return chunks
    }

    /// Calculate total number of chunks for duration
    func calculateChunkCount(for duration: TimeInterval) -> Int {
        let chunkDuration = Self.maxChunkDuration
        let overlap = Self.overlapDuration
        let stepDuration = chunkDuration - overlap

        if duration <= chunkDuration {
            return 1
        }

        return Int(ceil((duration - overlap) / stepDuration))
    }

    /// Merge overlapping segments from adjacent chunks
    func mergeSegments(
        _ segments: [[TranscriptSegmentData]],
        chunks: [AudioChunk]
    ) -> [TranscriptSegmentData] {

        guard !segments.isEmpty else { return [] }

        var merged: [TranscriptSegmentData] = []

        for (chunkIndex, chunkSegments) in segments.enumerated() {
            let chunk = chunks[chunkIndex]

            for segment in chunkSegments {
                // Adjust timestamps to absolute time
                var adjustedSegment = segment
                adjustedSegment.startTime += chunk.startTime
                adjustedSegment.endTime += chunk.startTime

                // Check for overlap with previous segment
                if let lastSegment = merged.last {
                    let overlapThreshold = Self.overlapDuration / 2

                    if adjustedSegment.startTime < lastSegment.endTime - overlapThreshold {
                        // Segments overlap - merge text if similar
                        continue // Skip duplicate
                    }
                }

                merged.append(adjustedSegment)
            }
        }

        return merged
    }

    // MARK: - Supporting Types

    struct TranscriptSegmentData {
        var startTime: TimeInterval
        var endTime: TimeInterval
        var text: String
        var confidence: Double?
    }
}

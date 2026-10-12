package com.securevox.app.service

/**
 * Time arithmetic for the chunked transcription loop. Chunk N (N > 0) starts [overlapSamples]
 * before its nominal start, and whisper reports times relative to the first sample it was given,
 * so a time inside the chunk maps to the recording as chunkOffsetMs + localMs.
 */
object ChunkTiming {
    private const val SAMPLE_RATE = 16000L

    /** First sample of chunk [chunkIdx], including the overlap with the previous chunk. */
    fun chunkStartSample(chunkIdx: Int, chunkSamples: Int, overlapSamples: Int): Int =
        (chunkIdx * chunkSamples - if (chunkIdx > 0) overlapSamples else 0).coerceAtLeast(0)

    /** Where chunk audio begins in the recording, in ms. */
    fun chunkOffsetMs(startSample: Int): Long = startSample.toLong() * 1000L / SAMPLE_RATE

    /** Length of the part of this chunk that the previous chunk already transcribed, in ms. */
    fun overlapMs(chunkIdx: Int, overlapSamples: Int): Long =
        if (chunkIdx > 0) overlapSamples.toLong() * 1000L / SAMPLE_RATE else 0L

    /** True when a segment starting at [localStartMs] (chunk-relative) is new, not overlap. */
    fun isNewSegment(localStartMs: Long, overlapMs: Long): Boolean = localStartMs >= overlapMs

    /** Recording time for a chunk-relative time. Do NOT subtract the overlap again. */
    fun toRecordingMs(localMs: Long, chunkOffsetMs: Long): Long = localMs + chunkOffsetMs
}

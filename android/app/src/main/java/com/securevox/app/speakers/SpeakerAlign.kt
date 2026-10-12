package com.securevox.app.speakers

import com.securevox.app.data.model.TranscriptSegment

/** A span of audio attributed to one speaker, in milliseconds. */
data class SpeakerSpan(val startMs: Long, val endMs: Long, val speaker: Int)

/**
 * Labels each transcript segment with the speaker who talks for most of it, or the nearest
 * speaker when it falls in a gap. Whisper segments are sentence-sized, so a segment where the
 * speaker changes mid-way gets one label: the dominant voice. Speaker numbers are renumbered by
 * first appearance, so the first voice heard is always Speaker 1 (stored as 0).
 */
fun assignSpeakers(segments: List<TranscriptSegment>, spans: List<SpeakerSpan>): List<TranscriptSegment> {
    if (spans.isEmpty() || segments.isEmpty()) return segments
    val raw = segments.map { dominantSpeaker(it.startTimeMs, it.endTimeMs, spans) }
    val order = LinkedHashMap<Int, Int>()
    raw.forEach { order.getOrPut(it) { order.size } }
    return segments.mapIndexed { i, s -> s.copy(speaker = order.getValue(raw[i])) }
}

internal fun dominantSpeaker(start: Long, end: Long, spans: List<SpeakerSpan>): Int {
    val overlap = HashMap<Int, Long>()
    for (span in spans) {
        val shared = minOf(end, span.endMs) - maxOf(start, span.startMs)
        if (shared > 0) overlap.merge(span.speaker, shared, Long::plus)
    }
    overlap.maxByOrNull { it.value }?.let { return it.key }
    val mid = (start + end) / 2
    return spans.minByOrNull { distanceTo(it, mid) }!!.speaker
}

internal fun distanceTo(span: SpeakerSpan, ms: Long): Long = when {
    ms < span.startMs -> span.startMs - ms
    ms > span.endMs -> ms - span.endMs
    else -> 0L
}

/** True when the transcript has more than one distinct speaker worth labelling. */
fun hasMultipleSpeakers(segments: List<TranscriptSegment>): Boolean =
    segments.map { it.speaker }.filter { it >= 0 }.toSet().size > 1

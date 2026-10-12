package com.securevox.app.speakers

import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.model.WordCodec
import com.securevox.app.data.model.timedWords
import com.securevox.app.whisper.joinWords

/**
 * Re-labelling an already transcribed recording without touching its text or timing.
 *
 * How merging back works: the first labelling splits a Whisper segment where the speaker changes.
 * The first piece keeps the original id; every later piece is flagged [TranscriptSegment.speakerSplit].
 * [mergeSpeakerSplits] glues each flagged row onto the row before it (text from the joined words,
 * words concatenated, start of the first, end of the last, id of the first), which restores exactly
 * the original segments (pieces are contiguous by construction). The restored segments then go
 * through the same [assignSpeakersByWord] as the first labelling, so new turns are split at the same
 * places. Rows without the flag are never merged, even if neighbours share a speaker.
 */
object SpeakerRelabel {

    /** Restores the pre-split segments. */
    fun mergeSpeakerSplits(segments: List<TranscriptSegment>): List<TranscriptSegment> {
        val out = ArrayList<TranscriptSegment>()
        var group = ArrayList<TranscriptSegment>()
        fun flush() {
            if (group.isEmpty()) return
            out.add(if (group.size == 1) group[0] else mergeGroup(group))
            group = ArrayList()
        }
        for (seg in segments.sortedBy { it.segmentIndex }) {
            if (seg.speakerSplit && group.isNotEmpty()) group.add(seg) else { flush(); group.add(seg) }
        }
        flush()
        return out
    }

    private fun mergeGroup(parts: List<TranscriptSegment>): TranscriptSegment {
        val first = parts.first()
        val timed = parts.map { it.timedWords() }
        val allTimed = timed.all { it.isNotEmpty() }
        val text = if (allTimed) joinWords(timed.flatten().map { it.text })
        else parts.joinToString(" ") { it.text.trim() }.trim()
        return first.copy(
            text = text,
            endTimeMs = parts.last().endTimeMs,
            words = if (allTimed) WordCodec.encode(timed.flatten()) else "",
            speakerSplit = false
        )
    }

    /**
     * New segment list for [numSpeakers] (null = automatic) from the diarizer's [spans], with
     * segmentIndex renumbered 0..n-1. A single speaker (requested as 1, or found) removes the
     * labels, as the first-time pass does.
     */
    fun rebuild(current: List<TranscriptSegment>, spans: List<SpeakerSpan>, numSpeakers: Int?): List<TranscriptSegment> {
        val merged = mergeSpeakerSplits(current)
        if (SpeakerCount.normalize(numSpeakers) == 1 || spans.isEmpty()) return unlabelled(merged)
        val labelled = assignSpeakersByWord(merged, spans)
        return if (hasMultipleSpeakers(labelled)) labelled else unlabelled(merged)
    }

    private fun unlabelled(merged: List<TranscriptSegment>): List<TranscriptSegment> =
        merged.mapIndexed { i, s -> s.copy(speaker = -1, segmentIndex = i, speakerSplit = false) }

    fun speakerCount(segments: List<TranscriptSegment>): Int =
        segments.map { it.speaker }.filter { it >= 0 }.toSet().size

    private fun squash(s: String) = s.filterNot { it.isWhitespace() }

    /** Safety check before any write: the transcript text must be unchanged by a relabel. */
    fun textPreserved(old: List<TranscriptSegment>, new: List<TranscriptSegment>): Boolean =
        squash(old.sortedBy { it.segmentIndex }.joinToString("") { it.text }) ==
            squash(new.sortedBy { it.segmentIndex }.joinToString("") { it.text })
}

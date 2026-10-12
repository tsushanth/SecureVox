package com.securevox.app.speakers

import com.securevox.app.data.model.TranscriptSegment
import com.securevox.app.data.model.WordCodec
import com.securevox.app.data.model.WordTiming
import com.securevox.app.data.model.timedWords
import com.securevox.app.whisper.joinWords
import java.util.UUID
import kotlin.math.abs

// Word-level speaker assignment. A Whisper segment is sentence-sized but can still hold a turn
// change ("Yes. I think so."), which a whole-segment label gets wrong. With word timings we label
// each word, smooth the labels at sentence ends, and split the segment where the speaker changes.

/** A sentence-internal speaker change must last this long ... */
private const val MIN_INTERNAL_TURN_MS = 2_000L

/** ... and cover at least this many words to be kept as a real turn instead of timing noise. */
private const val MIN_INTERNAL_TURN_WORDS = 4

/**
 * The raw speaker of each word: the span containing the word's midpoint (the closest centre when
 * spans overlap), or the nearest span when it falls in a gap. Raw ids are the diarizer's own.
 */
fun wordSpeakers(words: List<WordTiming>, spans: List<SpeakerSpan>): IntArray {
    if (spans.isEmpty()) return IntArray(words.size) { -1 }
    return IntArray(words.size) { i ->
        val mid = (words[i].startMs + words[i].endMs) / 2
        val inside = spans.filter { mid >= it.startMs && mid <= it.endMs }
        if (inside.isNotEmpty()) {
            inside.minByOrNull { abs((it.startMs + it.endMs) / 2 - mid) }!!.speaker
        } else {
            spans.minByOrNull { distanceTo(it, mid) }!!.speaker
        }
    }
}

private fun endsSentence(word: String): Boolean {
    val t = word.trimEnd('"', '\'', ')', '”', '’')
    return t.endsWith(".") || t.endsWith("?") || t.endsWith("!") || t.endsWith("…") ||
        t.endsWith("。") || t.endsWith("？") || t.endsWith("！")
}

/**
 * Whisper's word times run a little late, so the diarizer's turn can end a word or two before the
 * sentence does. Give each sentence the speaker who talks most of it, so turns break at sentence
 * ends and a sentence is never cut in two by timing noise. A real change inside one sentence (an
 * interruption, or a run-on Whisper wrote without punctuation) is kept when the new voice holds the
 * floor for at least [MIN_INTERNAL_TURN_MS] and [MIN_INTERNAL_TURN_WORDS] words; shorter blips
 * inside it are absorbed into the neighbouring run.
 */
fun smoothSpeakersBySentence(words: List<WordTiming>, raw: IntArray): IntArray {
    val out = raw.copyOf()
    var start = 0
    for (i in words.indices) {
        if (!endsSentence(words[i].text) && i != words.lastIndex) continue
        smoothSentence(words, raw, out, start, i)
        start = i + 1
    }
    return out
}

private class Run(val first: Int, val last: Int, val speaker: Int)

private fun smoothSentence(words: List<WordTiming>, raw: IntArray, out: IntArray, from: Int, to: Int) {
    val runs = ArrayList<Run>()
    var s = from
    for (k in from + 1..to + 1) {
        if (k > to || raw[k] != raw[s]) {
            runs.add(Run(s, k - 1, raw[s]))
            s = k
        }
    }
    if (runs.size == 1) return
    fun isTurn(r: Run) =
        words[r.last].endMs - words[r.first].startMs >= MIN_INTERNAL_TURN_MS &&
            r.last - r.first + 1 >= MIN_INTERNAL_TURN_WORDS

    val weight = HashMap<Int, Long>()
    for (j in from..to) weight.merge(raw[j], maxOf(words[j].endMs - words[j].startMs, 1L), Long::plus)
    val winner = weight.maxByOrNull { it.value }!!.key

    val turns = runs.filter { isTurn(it) }
    if (turns.none { it.speaker != runs[0].speaker }) {
        // Nobody took over from the opening voice for long: one speaker for the whole sentence.
        for (j in from..to) out[j] = winner
        return
    }
    // A real mid-sentence turn: keep the long runs, hand each short run to the run before it
    // (or, at the start, to the first long run).
    val speakers = IntArray(runs.size)
    for (r in runs.indices) {
        speakers[r] = when {
            isTurn(runs[r]) -> runs[r].speaker
            r > 0 -> speakers[r - 1]
            // The sentence opener: two or more words are the previous speaker finishing their
            // thought; a single word is more likely timing noise from the turn after it.
            runs[r].last > runs[r].first -> runs[r].speaker
            else -> turns.first().speaker
        }
    }
    runs.forEachIndexed { r, run -> for (j in run.first..run.last) out[j] = speakers[r] }
}

/**
 * Labels speakers at word level and splits segments where the speaker changes. Segments without
 * usable words (recordings from before word timing) are labelled whole, exactly as [assignSpeakers]
 * does. A segment with one speaker comes back unchanged apart from its label. Split pieces keep
 * contiguous times (a piece starts at its first word, or at the segment start for the first piece,
 * and ends where the next begins, or at the segment end for the last), carry their own words, and
 * the first piece keeps the original id so saving replaces the old row. Speaker numbers are
 * renumbered by first appearance over the whole recording and segmentIndex is renumbered.
 */
fun assignSpeakersByWord(segments: List<TranscriptSegment>, spans: List<SpeakerSpan>): List<TranscriptSegment> {
    if (spans.isEmpty() || segments.isEmpty()) return segments
    val pieces = ArrayList<Pair<TranscriptSegment, Int>>()
    for (seg in segments) {
        val words = seg.timedWords()
        if (words.isEmpty()) {
            pieces.add(seg to dominantSpeaker(seg.startTimeMs, seg.endTimeMs, spans))
            continue
        }
        val speakers = smoothSpeakersBySentence(words, wordSpeakers(words, spans))
        if (speakers.all { it == speakers[0] }) {
            pieces.add(seg to speakers[0])
            continue
        }
        var first = 0
        for (k in 1..words.size) {
            if (k < words.size && speakers[k] == speakers[first]) continue
            val sub = words.subList(first, k)
            val isFirst = first == 0
            val isLast = k == words.size
            val piece = seg.copy(
                id = if (isFirst) seg.id else UUID.randomUUID().toString(),
                text = joinWords(sub.map { it.text }),
                startTimeMs = if (isFirst) seg.startTimeMs else sub.first().startMs,
                endTimeMs = if (isLast) seg.endTimeMs else words[k].startMs,
                words = WordCodec.encode(sub)
            )
            pieces.add(piece to speakers[first])
            first = k
        }
    }
    val order = LinkedHashMap<Int, Int>()
    pieces.forEach { order.getOrPut(it.second) { order.size } }
    return pieces.mapIndexed { i, (seg, raw) -> seg.copy(speaker = order.getValue(raw), segmentIndex = i) }
}

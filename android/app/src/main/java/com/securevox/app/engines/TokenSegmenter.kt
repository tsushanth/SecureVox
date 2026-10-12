package com.securevox.app.engines

import com.securevox.app.whisper.TranscriptionSegment
import kotlin.math.log10
import kotlin.math.sqrt

/*
 * Turns the token stream of a sherpa-onnx recognizer (NeMo FastConformer, SenseVoice) into the sentence-sized
 * segments the app stores. Ported from ClearVoice Android (WordTiming.kt, TranscriptAlign.kt). All times are
 * milliseconds from the start of the audio handed to the engine; the worker adds the chunk offset.
 */

/** A stretch of speech inside a window, in milliseconds from the window start. */
data class SpeechSpan(val startMs: Long, val endMs: Long)

/** Scripts written without spaces between words: Han, Hiragana, Katakana and their punctuation. */
fun isNoSpaceScript(c: Char): Boolean =
    c in '぀'..'ヿ' || c in '㐀'..'䶿' || c in '一'..'鿿' ||
        c in '　'..'〿' || c in '＀'..'￯' || c in 'ㇰ'..'ㇿ'

private fun isPunctuation(c: Char): Boolean =
    !c.isLetterOrDigit() && !c.isWhitespace() && (c in '　'..'〿' || c in '＀'..'／' ||
        c in '：'..'＠' || Character.getType(c).let {
            it == Character.CONNECTOR_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
                it == Character.START_PUNCTUATION.toInt() || it == Character.END_PUNCTUATION.toInt() ||
                it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                it == Character.OTHER_PUNCTUATION.toInt()
        })

/** True when a space belongs between two adjacent words (never next to Chinese or Japanese text). */
fun needsSpace(prev: String, next: String): Boolean {
    if (prev.isEmpty() || next.isEmpty()) return false
    return !isNoSpaceScript(prev.last()) && !isNoSpaceScript(next.first())
}

/** Joins words with spaces, except around scripts that are written without them. */
fun joinWords(words: List<String>): String = buildString {
    words.forEachIndexed { i, w ->
        if (i > 0 && needsSpace(words[i - 1], w)) append(' ')
        append(w)
    }
}

/** A word, the index of the first and last token it was built from. */
data class TokenGroup(val text: String, val firstToken: Int, val lastToken: Int)

/**
 * Groups recognizer tokens into words. A token starts a new word when it begins with a space, when it is the
 * first token, or at every Chinese/Japanese character (no spaces in those scripts, so each character is a
 * word). Punctuation stays with the word before it; special tokens (`<|...|>`) are dropped.
 */
fun groupTokens(tokens: Array<String>): List<TokenGroup> {
    val groups = mutableListOf<TokenGroup>()
    var text = StringBuilder()
    var first = 0
    var last = 0
    fun flush() {
        val w = text.toString().trim()
        if (w.isNotEmpty()) groups += TokenGroup(w, first, last)
        text = StringBuilder()
    }
    for (i in tokens.indices) {
        val token = tokens[i]
        if (token.isEmpty() || (token.startsWith("<|") && token.endsWith("|>"))) continue
        val core = token.trimStart()
        if (core.isEmpty()) { flush(); continue }
        val c = core.first()
        val startsNew = token.startsWith(" ") || text.isEmpty() ||
            (isNoSpaceScript(c) && !isPunctuation(c)) ||
            (isNoSpaceScript(text.last()) && !isPunctuation(c))
        if (startsNew) { flush(); first = i }
        text.append(core)
        last = i
    }
    flush()
    return groups
}

/** One word with its time span. */
data class TimedWord(val text: String, val startMs: Long, val endMs: Long)

/** Tuning for [segmentWords]. */
object SegmentRules {
    /** A segment is never longer than this; the worker's chunks are 30 s so this keeps the UI readable. */
    const val MAX_SEGMENT_MS = 15_000L
    /** Pause (end of one word to start of the next) that always ends a segment. */
    const val LONG_PAUSE_MS = 900L
    /** A shorter pause ends a segment only once it has at least [MIN_MS_BEFORE_SHORT_PAUSE_SPLIT] of speech. */
    const val SHORT_PAUSE_MS = 150L
    const val MIN_MS_BEFORE_SHORT_PAUSE_SPLIT = 3_000L
    /**
     * The recognizers give start times but no durations. A word is taken to last one token (3 frames of 80 ms)
     * past its last token's start, or until the next word if sooner, so a pause shows up as a gap between
     * words. In real output, subwords and Chinese characters are 0.08 to 0.3 s apart in continuous speech.
     */
    const val WORD_TAIL_MS = 240L
    private val SENTENCE_END = setOf('.', '?', '!', '。', '？', '！', '…')
    private val CLAUSE_END = setOf(',', ';', ':', '、', '，', '；', '：')
    private val ABBREVIATIONS = setOf(
        "dr.", "mr.", "mrs.", "ms.", "prof.", "st.", "no.", "vs.", "etc.", "z.b.", "d.h.", "usw.", "bzw.", "ca.",
        "nr.", "sr.", "sra.", "m.", "mme.", "p.ex.", "env."
    )

    fun endsSentence(word: String): Boolean {
        val trimmed = word.trimEnd('"', '\'', ')', '”', '」', '』')
        val c = trimmed.lastOrNull() ?: return false
        if (c !in SENTENCE_END) return false
        if (c == '.' && (trimmed.lowercase() in ABBREVIATIONS || trimmed.length == 2 && trimmed[0].isLetter())) return false
        // "3.5" is not a sentence end, but "in 2020." is: only a bare trailing dot after digits matters.
        return true
    }

    fun endsClause(word: String): Boolean = word.lastOrNull()?.let { it in CLAUSE_END } == true
}

/**
 * Words with start times from recognizer tokens. A word ends where the next starts, but no later than
 * [SegmentRules.WORD_TAIL_MS] after its last token starts. Returns empty when the timestamps do not line up
 * with the tokens.
 */
fun tokensToTimedWords(tokens: Array<String>, timestampsSec: FloatArray): List<TimedWord> {
    if (tokens.isEmpty() || timestampsSec.size != tokens.size) return emptyList()
    val groups = groupTokens(tokens)
    return groups.mapIndexed { gi, g ->
        val start = Math.round(timestampsSec[g.firstToken] * 1000f).toLong()
        val lastTokenStart = Math.round(timestampsSec[g.lastToken] * 1000f).toLong()
        val tail = lastTokenStart + SegmentRules.WORD_TAIL_MS
        val end = if (gi + 1 < groups.size) minOf(Math.round(timestampsSec[groups[gi + 1].firstToken] * 1000f).toLong(), tail) else tail
        TimedWord(g.text, start, maxOf(end, start))
    }
}

/**
 * Recognizer word times can start before anyone speaks: NeMo stamps its first token at 0.00 in most clips,
 * 1 to 2.5 s ahead of the speech. No word can start before speech does, so move earlier starts up to the first
 * speech span ([spans] are relative to the same origin as the words).
 */
fun clampStartsToSpeech(words: List<TimedWord>, spans: List<SpeechSpan>): List<TimedWord> {
    val speechStart = spans.firstOrNull()?.startMs ?: return words
    return words.map { if (it.startMs < speechStart) it.copy(startMs = speechStart, endMs = maxOf(it.endMs, speechStart)) else it }
}

/**
 * Groups timed words into sentence-sized segments. A segment ends after sentence-ending punctuation, at a long
 * pause, at a shorter pause once it holds 3 s of speech, at [SegmentRules.MAX_SEGMENT_MS], and at
 * [leadingOverlapMs] (the worker drops segments that start inside the overlap it re-reads from the previous
 * chunk, so none may straddle that point or the part after it would be lost).
 */
fun segmentWords(words: List<TimedWord>, leadingOverlapMs: Long = 0L): List<TranscriptionSegment> {
    if (words.isEmpty()) return emptyList()
    val out = mutableListOf<TranscriptionSegment>()
    var cur = mutableListOf<TimedWord>()

    fun flush(upTo: Int = cur.size) {
        if (upTo <= 0) return
        val part = cur.subList(0, upTo)
        val text = joinWords(part.map { it.text })
        if (text.isNotBlank()) out += TranscriptionSegment(text, part.first().startMs, maxOf(part.last().endMs, part.first().startMs))
        val rest = cur.subList(upTo, cur.size).toMutableList()
        cur = rest
    }

    for (w in words) {
        if (cur.isNotEmpty()) {
            val prev = cur.last()
            val gap = w.startMs - prev.endMs
            val spoken = prev.endMs - cur.first().startMs
            val crossesOverlap = leadingOverlapMs > 0 && prev.startMs < leadingOverlapMs && w.startMs >= leadingOverlapMs
            val split = SegmentRules.endsSentence(prev.text) ||
                crossesOverlap ||
                gap >= SegmentRules.LONG_PAUSE_MS ||
                (gap >= SegmentRules.SHORT_PAUSE_MS && spoken >= SegmentRules.MIN_MS_BEFORE_SHORT_PAUSE_SPLIT)
            if (split) flush()
            else if (w.endMs - cur.first().startMs > SegmentRules.MAX_SEGMENT_MS) {
                // Too long: break after the last clause end in the second half, else right here.
                val half = cur.size / 2
                val clause = (cur.lastIndex downTo half).firstOrNull { SegmentRules.endsClause(cur[it].text) }
                flush(if (clause != null) clause + 1 else cur.size)
            }
        }
        cur.add(w)
    }
    flush()
    return out
}

private const val FRAME = 320                // 20 ms at 16 kHz
private const val VOICED_DB = -24.75         // relative to the loud-speech level (95th percentile)
private const val FILL_GAP_FRAMES = 7        // close pauses up to ~140 ms
private const val MIN_SPAN_FRAMES = 3        // drop blips shorter than ~60 ms

/**
 * Finds where speech is in 16 kHz mono audio by frame energy relative to the loud parts. Falls back to the
 * whole window when nothing stands out.
 */
fun speechSpans(samples: FloatArray): List<SpeechSpan> {
    val n = samples.size / FRAME
    val whole = listOf(SpeechSpan(0, samples.size * 1000L / 16000))
    if (n == 0) return whole
    val rms = DoubleArray(n) { f ->
        var sum = 0.0
        for (i in f * FRAME until (f + 1) * FRAME) { val v = samples[i].toDouble() * 32768.0; sum += v * v }
        sqrt(sum / FRAME) + 1e-9
    }
    val ref = rms.sortedArray()[((n - 1) * 0.95).toInt()]
    val voiced = BooleanArray(n) { 20 * log10(rms[it] / ref + 1e-9) > VOICED_DB }
    var i = 0
    while (i < n) {
        if (!voiced[i]) {
            var j = i
            while (j < n && !voiced[j]) j++
            if (i > 0 && j < n && j - i <= FILL_GAP_FRAMES) for (k in i until j) voiced[k] = true
            i = j
        } else i++
    }
    val spans = mutableListOf<SpeechSpan>()
    i = 0
    while (i < n) {
        if (voiced[i]) {
            var j = i
            while (j < n && voiced[j]) j++
            if (j - i >= MIN_SPAN_FRAMES) spans += SpeechSpan(i * 20L, j * 20L)
            i = j
        } else i++
    }
    return spans.ifEmpty { whole }
}

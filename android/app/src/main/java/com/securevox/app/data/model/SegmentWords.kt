package com.securevox.app.data.model

import com.securevox.app.whisper.joinWords

/**
 * The timed words of a segment, or an empty list when it has none to trust. Words are only
 * returned when they rebuild the segment's text (ignoring whitespace), so a segment whose text was
 * edited or cleaned after transcription (e.g. a stripped [BLANK_AUDIO] marker) falls back to
 * segment-level behaviour instead of showing words that disagree with the text.
 */
fun TranscriptSegment.timedWords(): List<WordTiming> {
    if (words.isEmpty()) return emptyList()
    val decoded = WordCodec.decode(words)
    if (decoded.isEmpty()) return emptyList()
    return if (sameIgnoringSpace(joinWords(decoded.map { it.text }), text)) decoded else emptyList()
}

private fun sameIgnoringSpace(a: String, b: String): Boolean {
    var i = 0
    var j = 0
    while (true) {
        while (i < a.length && a[i].isWhitespace()) i++
        while (j < b.length && b[j].isWhitespace()) j++
        if (i >= a.length || j >= b.length) return i >= a.length && j >= b.length
        if (a[i] != b[j]) return false
        i++
        j++
    }
}

/**
 * Words laid out as one string so a tap on a character maps back to a word. [starts] holds the
 * character offset where each word begins in [text].
 */
class WordLayout(val words: List<WordTiming>) {
    val starts = IntArray(words.size)
    val text: String

    init {
        val sb = StringBuilder()
        words.forEachIndexed { i, w ->
            if (i > 0 && com.securevox.app.whisper.needsSpace(words[i - 1].text, w.text)) sb.append(' ')
            starts[i] = sb.length
            sb.append(w.text)
        }
        text = sb.toString()
    }

    /** End offset (exclusive) of word [i] in [text]. */
    fun endOf(i: Int): Int = starts[i] + words[i].text.length

    /** Index of the word at character [offset]; a tap in the gap before a word picks the word before it. */
    fun wordAt(offset: Int): Int {
        if (words.isEmpty()) return -1
        var lo = 0
        var hi = words.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/**
 * The word being spoken at [positionMs]: the last word that has started, so a pause keeps the
 * previous word lit instead of flickering off. -1 before the first word or outside the segment.
 */
fun activeWordIndex(words: List<WordTiming>, positionMs: Long, segStartMs: Long, segEndMs: Long): Int {
    if (words.isEmpty() || positionMs < segStartMs || positionMs >= segEndMs) return -1
    var lo = 0
    var hi = words.size - 1
    if (positionMs < words[0].startMs) return -1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (words[mid].startMs <= positionMs) lo = mid else hi = mid - 1
    }
    return lo
}

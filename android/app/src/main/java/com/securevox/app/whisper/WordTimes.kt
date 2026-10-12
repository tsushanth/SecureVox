package com.securevox.app.whisper

import com.securevox.app.data.model.WordTiming

/** A raw model token with whisper.cpp's token-level times (ms, -1 when unknown). */
data class TokenTime(val text: String, val startMs: Long, val endMs: Long)

/** Scripts written without spaces between words: Han, Hiragana, Katakana and their punctuation. */
fun isNoSpaceScript(c: Char): Boolean =
    c in '぀'..'ヿ' || c in '㐀'..'䶿' || c in '一'..'鿿' ||
        c in '　'..'〿' || c in '＀'..'￯' || c in 'ㇰ'..'ㇿ'

private fun isPunctuation(c: Char): Boolean = !c.isLetterOrDigit() && !c.isWhitespace()

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

/**
 * Groups model tokens into words. A token that starts with a space starts a word (so does the
 * first token), and so does every Chinese or Japanese character, because those scripts have no
 * spaces and each character should be tappable. Punctuation stays with the word before it.
 * A word starts where its first token starts and ends where its last token ends.
 */
fun groupTokensIntoWords(tokens: List<TokenTime>): List<WordTiming> {
    val words = ArrayList<WordTiming>()
    val text = StringBuilder()
    var start = -1L
    var end = -1L
    fun flush() {
        val w = text.toString().trim()
        if (w.isNotEmpty()) words.add(WordTiming(w, start, end))
        text.setLength(0)
    }
    for (token in tokens) {
        val core = token.text.trimStart()
        if (core.isEmpty()) { flush(); continue }
        val c = core.first()
        val startsNew = token.text.first().isWhitespace() || text.isEmpty() ||
            (isNoSpaceScript(c) && !isPunctuation(c)) ||
            (isNoSpaceScript(text.last()) && !isPunctuation(c))
        if (startsNew) {
            flush()
            start = token.startMs
        }
        text.append(core)
        end = token.endMs
    }
    flush()
    return words
}

/**
 * Makes raw word times safe to use: every word lies inside [segStart, segEnd], starts never go
 * backwards, ends never pass the next start, and unknown (negative) times are filled from the
 * neighbours. Zero-length words are allowed; the UI treats a word as active from its start until
 * the next word starts.
 */
fun repairWordTimes(words: List<WordTiming>, segStart: Long, segEnd: Long): List<WordTiming> {
    if (words.isEmpty()) return words
    val hi = maxOf(segEnd, segStart)
    val starts = LongArray(words.size)
    var prevStart = segStart
    var prevEnd = segStart
    for (i in words.indices) {
        var s = words[i].startMs
        if (s < 0) s = prevEnd
        s = s.coerceIn(segStart, hi)
        if (s < prevStart) s = prevStart
        starts[i] = s
        prevStart = s
        prevEnd = if (words[i].endMs >= 0) maxOf(words[i].endMs, s) else s
    }
    return words.mapIndexed { i, w ->
        val nextStart = if (i + 1 < words.size) starts[i + 1] else hi
        var e = if (w.endMs < 0) starts[i] else w.endMs
        e = minOf(maxOf(e, starts[i]), nextStart, hi)
        w.copy(startMs = starts[i], endMs = maxOf(e, starts[i]))
    }
}

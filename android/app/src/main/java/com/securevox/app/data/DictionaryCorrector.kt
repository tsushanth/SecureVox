package com.securevox.app.data

import com.securevox.app.data.model.WordTiming
import com.securevox.app.whisper.isNoSpaceScript
import com.securevox.app.whisper.joinWords

/**
 * Pure logic behind the Custom Dictionary (no Android classes, so it is unit tested on the JVM).
 *
 * What the dictionary does, and no more:
 *  1. [promptFor] turns the terms into a short hint that is given to the speech model as its initial
 *     prompt, which nudges it towards those spellings. This is a bias, not a guarantee.
 *  2. [DictionaryCorrector] then runs a deterministic pass over the finished text: wherever the text holds
 *     a term, ignoring capitalization and ignoring a single space or hyphen between letters ("open ai",
 *     "Open-AI" -> "OpenAI"), at a word boundary, it is rewritten exactly as the user typed it.
 *     It does NOT guess phonetic misspellings: "Sue Shant" never becomes "Sushanth".
 *
 * Nothing here throws on odd input; on any unexpected failure the original text is returned unchanged.
 */
object DictionaryLimits {
    const val MAX_TERMS = 500
    const val MAX_TERM_CODE_POINTS = 64
    /** Terms shorter than this are kept (and used as hints) but never auto-corrected: too many false hits. */
    const val MIN_CORRECTION_CODE_POINTS = 2
    /** The speech model reads about 224 prompt tokens; stay well below it, non-Latin text uses more tokens per character. */
    const val MAX_PROMPT_CODE_POINTS = 160
}

/** Cleans one user-entered term. Returns null when nothing usable is left or it is longer than the limit. */
fun sanitizeDictionaryTerm(raw: String?): String? {
    if (raw == null) return null
    val sb = StringBuilder(raw.length)
    var pendingSpace = false
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        when {
            // Controls and line/paragraph separators split or break a term; any whitespace becomes one space.
            Character.isWhitespace(cp) || Character.isSpaceChar(cp) || Character.isISOControl(cp) ||
                Character.getType(cp) == Character.LINE_SEPARATOR.toInt() ||
                Character.getType(cp) == Character.PARAGRAPH_SEPARATOR.toInt() -> pendingSpace = sb.isNotEmpty()
            // Unpaired surrogates, noncharacters and the replacement character carry no text.
            cp == 0xFFFD || cp == 0xFFFE || cp == 0xFFFF || Character.getType(cp) == Character.SURROGATE.toInt() ||
                Character.getType(cp) == Character.UNASSIGNED.toInt() -> Unit
            else -> {
                if (pendingSpace) { sb.append(' '); pendingSpace = false }
                sb.appendCodePoint(cp)
            }
        }
    }
    val out = sb.toString()
    if (out.isEmpty()) return null
    if (out.codePointCount(0, out.length) > DictionaryLimits.MAX_TERM_CODE_POINTS) return null
    return out
}

/** Sanitizes, drops duplicates that differ only in capitalization (first in sorted order wins) and caps the list. */
fun normalizeDictionaryTerms(raw: Collection<String?>?): List<String> {
    if (raw == null) return emptyList()
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (term in raw.mapNotNull { sanitizeDictionaryTerm(it) }.sorted()) {
        if (seen.add(foldKey(term))) out.add(term)
        if (out.size >= DictionaryLimits.MAX_TERMS) break
    }
    return out
}

/** Hint for the speech model: the terms, comma separated, within the prompt budget. Empty when there are none. */
fun promptFor(terms: Collection<String?>?): String {
    val clean = normalizeDictionaryTerms(terms)
    if (clean.isEmpty()) return ""
    val sb = StringBuilder()
    var count = 0
    for (term in clean) {
        val n = term.codePointCount(0, term.length)
        val add = n + if (sb.isEmpty()) 0 else 2
        if (count + add > DictionaryLimits.MAX_PROMPT_CODE_POINTS) continue
        if (sb.isNotEmpty()) sb.append(", ")
        sb.append(term)
        count += add
    }
    return sb.toString()
}

/** Key under which two terms count as the same word (capitalization is ignored). */
fun dictionaryTermKey(term: String): String = foldKey(term)

private fun foldKey(s: String): String {
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        sb.appendCodePoint(foldCp(cp))
    }
    return sb.toString()
}

private fun foldCp(cp: Int): Int = Character.toLowerCase(Character.toUpperCase(cp))

private fun isSeparator(cp: Int) = cp == ' '.code || cp == '-'.code || cp == 0x2010 || cp == 0x2011

private fun isMark(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> false
}

private fun isWordChar(cp: Int) = Character.isLetterOrDigit(cp) || isMark(cp)

private fun isSpacelessScript(cp: Int): Boolean {
    if (cp <= 0xFFFF && isNoSpaceScript(cp.toChar())) return true
    return when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.THAI, Character.UnicodeScript.LAO, Character.UnicodeScript.KHMER,
        Character.UnicodeScript.MYANMAR -> true
        else -> false
    }
}

/** True when a term may start or end between these two code points (a word edge). */
private fun isBoundary(prev: Int, next: Int): Boolean {
    if (!isWordChar(prev) || !isWordChar(next)) return true
    if (isMark(prev) || isMark(next)) return false
    if (isSpacelessScript(prev) || isSpacelessScript(next)) return true
    return Character.UnicodeScript.of(prev) != Character.UnicodeScript.of(next)
}

/** A found term inside a text: [start, end) in UTF-16 units. */
class DictionaryMatch(val start: Int, val end: Int, val term: String)

class DictionaryCorrector(terms: Collection<String?>?) {

    private class Entry(val term: String, val key: IntArray)

    private val byFirst: Map<Int, List<Entry>>
    private val termCount: Int
    /** The cleaned, deduplicated terms this corrector uses. */
    val terms: List<String> = normalizeDictionaryTerms(terms)

    init {
        val map = HashMap<Int, MutableList<Entry>>()
        var n = 0
        for (t in this.terms) {
            val key = keyOf(t)
            if (key.size < DictionaryLimits.MIN_CORRECTION_CODE_POINTS) continue
            map.getOrPut(key[0]) { ArrayList() }.add(Entry(t, key))
            n++
        }
        // Longest key first so "Jean-Luc Picard" wins over "Jean" at the same position.
        for (list in map.values) list.sortByDescending { it.key.size }
        byFirst = map
        termCount = n
    }

    val isEmpty: Boolean get() = termCount == 0

    private fun keyOf(term: String): IntArray {
        val out = ArrayList<Int>(term.length)
        var i = 0
        while (i < term.length) {
            val cp = term.codePointAt(i)
            i += Character.charCount(cp)
            if (!isSeparator(cp)) out.add(foldCp(cp))
        }
        return out.toIntArray()
    }

    /** Where in [text] a term was found, left to right, longest first, never overlapping. */
    fun findMatches(text: String): List<DictionaryMatch> {
        if (isEmpty || text.isEmpty()) return emptyList()
        return try {
            find(text)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun find(text: String): List<DictionaryMatch> {
        val matches = ArrayList<DictionaryMatch>()
        var i = 0
        var prevCp = -1
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val len = Character.charCount(cp)
            val candidates = byFirst[foldCp(cp)]
            var best: DictionaryMatch? = null
            if (candidates != null && (prevCp == -1 || isBoundary(prevCp, cp))) {
                for (entry in candidates) {
                    val end = matchAt(text, i, entry.key)
                    if (end < 0) continue
                    if (best == null || end - i > best.end - best.start) best = DictionaryMatch(i, end, entry.term)
                }
            }
            if (best != null) {
                matches.add(best)
                prevCp = text.codePointBefore(best.end)
                i = best.end
            } else {
                prevCp = cp
                i += len
            }
        }
        return matches
    }

    /** End index when the key matches at [start], tolerating one space/hyphen between characters; else -1. */
    private fun matchAt(text: String, start: Int, key: IntArray): Int {
        var j = start
        var lastCp = -1
        for (k in key.indices) {
            if (j >= text.length) return -1
            var cp = text.codePointAt(j)
            if (k > 0 && isSeparator(cp)) {
                j += Character.charCount(cp)
                if (j >= text.length) return -1
                cp = text.codePointAt(j)
            }
            if (foldCp(cp) != key[k]) return -1
            j += Character.charCount(cp)
            lastCp = cp
        }
        if (j < text.length && lastCp != -1 && !isBoundary(lastCp, text.codePointAt(j))) return -1
        return j
    }

    /** [text] with every dictionary term rewritten as the user typed it. Never throws; unchanged when nothing matches. */
    fun correct(text: String): String {
        if (isEmpty || text.isEmpty()) return text
        return try {
            val matches = find(text)
            if (matches.isEmpty()) return text
            val sb = StringBuilder(text.length + 8)
            var pos = 0
            for (m in matches) {
                sb.append(text, pos, m.start).append(m.term)
                pos = m.end
            }
            sb.append(text, pos, text.length)
            sb.toString()
        } catch (e: Exception) {
            text
        }
    }

    /**
     * Corrects a segment's text and its timed words together. Timestamps are never touched: a term that spans
     * several words becomes one word that runs from the first word's start to the last word's end. When the
     * words cannot be kept consistent with the corrected text they are dropped (the same "no word timing"
     * state older recordings are in), so the transcript itself is always corrected.
     */
    fun correctSegment(text: String, words: List<WordTiming>): Pair<String, List<WordTiming>> {
        if (isEmpty) return text to words
        return try {
            val newText = correct(text)
            if (words.isEmpty()) return newText to words
            val newWords = correctWords(words)
            if (newWords != null && stripped(joinWords(newWords.map { it.text })) == stripped(newText)) newText to newWords
            else newText to emptyList()
        } catch (e: Exception) {
            text to words
        }
    }

    private fun stripped(s: String) = s.filterNot { it.isWhitespace() }

    private fun correctWords(words: List<WordTiming>): List<WordTiming>? {
        val joined = StringBuilder()
        val starts = IntArray(words.size)
        for (i in words.indices) {
            if (i > 0 && com.securevox.app.whisper.needsSpace(words[i - 1].text, words[i].text)) joined.append(' ')
            starts[i] = joined.length
            joined.append(words[i].text)
        }
        val text = joined.toString()
        val matches = find(text)
        if (matches.isEmpty()) return words
        val out = ArrayList<WordTiming>(words.size)
        var wi = 0
        var mi = 0
        while (wi < words.size) {
            val ws = starts[wi]
            val we = ws + words[wi].text.length
            // First match that touches this word (matches are ordered and do not overlap).
            while (mi < matches.size && matches[mi].end <= ws) mi++
            val m = matches.getOrNull(mi)
            if (m == null || m.start >= we) {
                out.add(words[wi]); wi++; continue
            }
            // The match covers words wi..last; keep text before/after the match inside those words.
            var last = wi
            while (last + 1 < words.size && starts[last + 1] < m.end) last++
            val prefix = if (m.start > ws) text.substring(ws, m.start) else ""
            val lastEnd = starts[last] + words[last].text.length
            val suffix = if (m.end < lastEnd) text.substring(m.end, lastEnd) else ""
            out.add(WordTiming(prefix + m.term + suffix, words[wi].startMs, words[last].endMs))
            wi = last + 1
            mi++
        }
        return out
    }

    companion object {
        val EMPTY = DictionaryCorrector(emptyList())
    }
}

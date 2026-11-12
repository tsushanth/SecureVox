package com.securevox.app.data


/**
 * Search over recordings: a recording matches when the query is in its title or anywhere in its
 * transcript, ignoring case. Titles are automatic timestamps until renamed, so the transcript is
 * what people actually remember.
 */
object RecordingSearch {

    /** Titles are single-line and kept short enough for a list row and an app bar. */
    const val MAX_TITLE_LENGTH = 100

    fun normalizeQuery(query: String): String = query.trim()

    fun titleMatches(title: String, query: String): Boolean {
        val q = normalizeQuery(query)
        return q.isEmpty() || title.contains(q, ignoreCase = true)
    }

    fun textMatches(text: String, query: String): Boolean {
        val q = normalizeQuery(query)
        return q.isNotEmpty() && text.contains(q, ignoreCase = true)
    }

    /**
     * SQLite's LIKE ignores case for ASCII only, so it is exact for an all-ASCII query (the common case
     * and the cheap one: it runs inside the database). Anything else (Cyrillic, accented capitals, ...)
     * is matched with [textMatches] over the segment text instead.
     */
    fun canUseSqlLike(query: String): Boolean = normalizeQuery(query).all { it.code < 128 }

    /** Escapes LIKE wildcards so "50%" or "a_b" is searched literally (pair with ESCAPE '\'). */
    fun likePattern(query: String): String {
        val escaped = normalizeQuery(query)
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    /** Ids whose segment text matches [query]; used for the non-ASCII path. */
    fun matchingIds(segments: List<SegmentText>, query: String): Set<String> {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return emptySet()
        val hits = HashSet<String>()
        for (s in segments) {
            if (s.recordingId !in hits && s.text.contains(q, ignoreCase = true)) hits.add(s.recordingId)
        }
        return hits
    }

    /** A new title from user input: single line, trimmed, capped; null when nothing usable was typed. */
    fun normalizeTitle(input: String): String? {
        val oneLine = input.replace(Regex("\\s+"), " ").trim()
        if (oneLine.isEmpty()) return null
        return if (oneLine.length <= MAX_TITLE_LENGTH) oneLine else oneLine.take(MAX_TITLE_LENGTH).trimEnd()
    }
}

/** One transcript segment's text, without the word timings, for scanning. */
data class SegmentText(val recordingId: String, val text: String)

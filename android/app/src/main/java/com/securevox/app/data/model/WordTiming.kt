package com.securevox.app.data.model

import com.securevox.app.util.MiniJson

/**
 * One spoken word with its approximate start and end, in milliseconds from the start of the
 * recording. Whisper token timings are heuristic (typically within a few hundred ms), so treat
 * them as good enough to follow along and tap to seek, not as exact alignment.
 */
data class WordTiming(val text: String, val startMs: Long, val endMs: Long) {
    fun shifted(deltaMs: Long) = copy(startMs = startMs + deltaMs, endMs = endMs + deltaMs)
}

/**
 * Compact storage format for a segment's words (the `words` column): a JSON array of
 * [text, startMs, endMs] triples, e.g. `[["Hello,",120,480],["world",500,900]]`.
 * An empty string means "no word timings" (every recording made before word timing existed).
 */
object WordCodec {

    fun encode(words: List<WordTiming>): String {
        if (words.isEmpty()) return ""
        val sb = StringBuilder(words.size * 24)
        sb.append('[')
        words.forEachIndexed { i, w ->
            if (i > 0) sb.append(',')
            sb.append('[').append(MiniJson.quote(w.text)).append(',')
                .append(w.startMs).append(',').append(w.endMs).append(']')
        }
        sb.append(']')
        return sb.toString()
    }

    /** Never throws: anything unreadable decodes to an empty list (segment-level behaviour). */
    fun decode(encoded: String): List<WordTiming> {
        if (encoded.isEmpty()) return emptyList()
        val list = MiniJson.parse(encoded) as? List<*> ?: return emptyList()
        val out = ArrayList<WordTiming>(list.size)
        for (item in list) {
            val triple = item as? List<*> ?: return emptyList()
            if (triple.size != 3) return emptyList()
            val text = triple[0] as? String ?: return emptyList()
            val start = (triple[1] as? Double)?.toLong() ?: return emptyList()
            val end = (triple[2] as? Double)?.toLong() ?: return emptyList()
            out.add(WordTiming(text, start, end))
        }
        return out
    }
}

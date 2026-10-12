package com.securevox.app.util

/**
 * A tiny JSON reader for the two payloads this app produces itself (whisper output and the stored
 * word list). It exists so the parsing runs in plain JVM unit tests (org.json is only a stub there)
 * and so a stray character in transcript text cannot break a regex.
 *
 * Returns List<Any?>, Map<String, Any?>, String, Double, Boolean or null; and null for anything that
 * does not parse (never throws).
 */
object MiniJson {

    fun parse(text: String): Any? = try {
        val p = Parser(text)
        p.skipWs()
        val value = p.value()
        p.skipWs()
        if (p.atEnd()) value else null
    } catch (e: Exception) {
        null
    }

    /** Escapes [s] for use between double quotes in JSON. */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            skipWs()
            return when (val c = s[i]) {
                '[' -> array()
                '{' -> obj()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("unexpected '$c' at $i")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) error("bad literal at $i")
            i += word.length
            return v
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun string(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        '/' -> sb.append('/')
                        '\\' -> sb.append('\\')
                        '"' -> sb.append('"')
                        'u' -> {
                            sb.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> error("bad escape \\$e")
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun array(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (s[i] == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> error("expected , or ] at ${i - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s[i] == '}') { i++; return out }
            while (true) {
                skipWs()
                if (s[i] != '"') error("expected key at $i")
                val key = string()
                skipWs()
                if (s[i++] != ':') error("expected : at ${i - 1}")
                out[key] = value()
                skipWs()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> error("expected , or } at ${i - 1}")
                }
            }
        }
    }
}

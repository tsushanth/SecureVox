package com.securevox.app.whisper

/**
 * Whisper writes non-speech as bracketed markers such as [BLANK_AUDIO], [MUSIC] or (music).
 * They are not words, so they stay out of transcripts, exports and search.
 */
object NonSpeech {
    private const val CUES =
        "blank_audio|blank audio|inaudible|unintelligible|silence|music|applause|laughter|noise|crosstalk|no audio|sound"
    private val upperMarker = Regex("""\[[A-Z_ ]+]""")
    private val bracketCue = Regex("""\[(?:$CUES)[^\]]*]""", RegexOption.IGNORE_CASE)
    private val parenMarker = Regex("""\((?:$CUES)[^)]*\)""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("""\s+""")

    fun strip(text: String): String =
        text.replace(upperMarker, " ")
            .replace(bracketCue, " ")
            .replace(parenMarker, " ")
            .replace(spaces, " ")
            .trim()
}

package com.securevox.app.engines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TokenSegmenterTest {

    private fun w(text: String, start: Long, end: Long = start + 300) = TimedWord(text, start, end)

    // Real NeMo FastConformer output for a German clip (tokens and start times in seconds), sherpa-onnx 1.13.8.
    private val nemoTokens = arrayOf(
        " W", "en", "n", " man", " G", "l", "ü", "ck", " hat", ",", " kann", " eine", " ein", "ze", "l", "n", "e",
        " P", "h", "an", "t", "a", "s", "ie"
    )
    private val nemoTimes = floatArrayOf(
        0.0f, 0.16f, 0.24f, 0.4f, 0.56f, 0.64f, 0.72f, 0.8f, 0.96f, 1.2f, 1.36f, 1.52f, 1.76f, 1.92f, 2.0f, 2.08f, 2.24f,
        2.32f, 2.4f, 2.48f, 2.56f, 2.72f, 2.8f, 2.96f
    )

    @Test fun `subword tokens group into words`() {
        val groups = groupTokens(nemoTokens)
        assertEquals(
            listOf("Wenn", "man", "Glück", "hat,", "kann", "eine", "einzelne", "Phantasie"),
            groups.map { it.text }
        )
        assertEquals(0, groups.first().firstToken)
        assertEquals(2, groups.first().lastToken)
    }

    @Test fun `words get start times and end at the next start`() {
        val words = tokensToTimedWords(nemoTokens, nemoTimes)
        assertEquals(0L, words[0].startMs)
        assertEquals(400L, words[0].endMs) // "Wenn" runs until "man" starts
        assertEquals(2320L, words.last().startMs) // "Phantasie" starts at its first token
        assertEquals(2960L + SegmentRules.WORD_TAIL_MS, words.last().endMs) // and ends a tail after its last token
    }

    @Test fun `a long gap does not stretch a word past its tail`() {
        val words = tokensToTimedWords(arrayOf(" hello", " world"), floatArrayOf(0f, 3f))
        assertEquals(SegmentRules.WORD_TAIL_MS, words[0].endMs)
    }

    @Test fun `mismatched timestamps give no words`() {
        assertTrue(tokensToTimedWords(arrayOf(" a", " b"), floatArrayOf(0f)).isEmpty())
    }

    @Test fun `special tokens are dropped`() {
        val words = groupTokens(arrayOf("<|zh|>", "今", "天", "<|woitn|>")).map { it.text }
        assertEquals(listOf("今", "天"), words)
    }

    @Test fun `chinese characters are separate words and join without spaces`() {
        val groups = groupTokens(arrayOf("今", "天", "好", "。"))
        assertEquals(listOf("今", "天", "好。"), groups.map { it.text })
        assertEquals("今天好。", joinWords(groups.map { it.text }))
    }

    @Test fun `latin words are joined with spaces`() {
        assertEquals("a b c", joinWords(listOf("a", "b", "c")))
    }

    @Test fun `first token stamped at zero is clamped to first speech`() {
        val words = listOf(w("Wenn", 0, 400), w("man", 400, 700), w("sagt", 2500, 2800))
        val clamped = clampStartsToSpeech(words, listOf(SpeechSpan(1800, 4000)))
        assertEquals(1800L, clamped[0].startMs)
        assertTrue(clamped[0].endMs >= clamped[0].startMs)
        assertEquals(1800L, clamped[1].startMs) // nothing may start before speech does
        assertEquals(2500L, clamped[2].startMs) // later words keep their times
    }

    @Test fun `words after speech start are untouched`() {
        val words = listOf(w("late", 2500, 2800))
        assertEquals(words, clampStartsToSpeech(words, listOf(SpeechSpan(1800, 4000))))
    }

    @Test fun `clamp is a no-op without speech spans`() {
        val words = listOf(w("a", 0))
        assertEquals(words, clampStartsToSpeech(words, emptyList()))
    }

    @Test fun `speechSpans finds a tone between silences`() {
        val sr = 16000
        val audio = FloatArray(sr * 3) { i ->
            if (i in sr until 2 * sr) (0.5 * sin(2 * PI * 220 * i / sr)).toFloat() else 0f
        }
        val spans = speechSpans(audio)
        assertEquals(1, spans.size)
        assertTrue(spans[0].startMs in 900..1100)
        assertTrue(spans[0].endMs in 1900..2100)
    }

    @Test fun `splits at sentence ending punctuation`() {
        val words = listOf(w("Hello", 0), w("there.", 400), w("How", 800), w("are", 1200), w("you?", 1600))
        val segs = segmentWords(words)
        assertEquals(listOf("Hello there.", "How are you?"), segs.map { it.text })
        assertEquals(0L, segs[0].startTimeMs)
        assertEquals(700L, segs[0].endTimeMs)
        assertEquals(800L, segs[1].startTimeMs)
    }

    @Test fun `abbreviations and initials do not end a sentence`() {
        val words = listOf(w("Dr.", 0), w("Smith", 300), w("met", 600), w("J.", 900), w("Doe.", 1200))
        assertEquals(listOf("Dr. Smith met J. Doe."), segmentWords(words).map { it.text })
    }

    @Test fun `splits at a long pause even without punctuation`() {
        val words = listOf(w("one", 0), w("two", 400), w("three", 2500), w("four", 2900))
        assertEquals(listOf("one two", "three four"), segmentWords(words).map { it.text })
    }

    @Test fun `short pause splits only after enough speech`() {
        // 0.6 s gap after 1 s of speech: keep together
        val early = listOf(w("a", 0), w("b", 700, 1000), w("c", 1600))
        assertEquals(1, segmentWords(early).size)
        // same gap after 4 s of speech: split
        val late = (0 until 10).map { w("w$it", it * 400L, it * 400L + 400) } + w("after", 4000 + 600L)
        val segs = segmentWords(late)
        assertEquals(2, segs.size)
        assertEquals("after", segs[1].text)
    }

    @Test fun `chinese without punctuation splits on pauses and joins without spaces`() {
        // Shape of real SenseVoice output: one char every ~0.2 s, ~0.5 s gaps between clauses.
        val chars = "今天天气很好我们一起去公园散步吧然后"
        val times = listOf(120, 240, 540, 780, 1020, 1260, 1800, 1980, 2160, 2340, 2580, 2880, 3060, 3360, 3540, 3780, 4200, 4380)
        val words = chars.indices.map { w(chars[it].toString(), times[it].toLong(), times[it] + 200L) }
        val segs = segmentWords(words)
        assertEquals(2, segs.size)
        assertEquals("今天天气很好我们一起去公园散步吧", segs[0].text)
        assertEquals("然后", segs[1].text)
    }

    @Test fun `chinese sentence end marks split`() {
        val words = listOf(w("好。", 0), w("然", 300), w("后", 500))
        assertEquals(listOf("好。", "然后"), segmentWords(words).map { it.text })
    }

    @Test fun `segments are capped at 15 seconds`() {
        val words = (0 until 100).map { w("w$it", it * 400L, it * 400L + 400) } // 40 s, no punctuation or pauses
        val segs = segmentWords(words)
        assertTrue(segs.size >= 3)
        for (s in segs) assertTrue("segment ${s.startTimeMs}-${s.endTimeMs}", s.endTimeMs - s.startTimeMs <= SegmentRules.MAX_SEGMENT_MS)
        assertEquals(100, segs.sumOf { it.text.split(" ").size })
    }

    @Test fun `long segments prefer to break after a comma`() {
        val words = (0 until 60).map { i -> w(if (i == 30) "w$i," else "w$i", i * 400L, i * 400L + 400) }
        val segs = segmentWords(words)
        assertTrue(segs[0].text.endsWith("w30,"))
    }

    @Test fun `no segment straddles the overlap with the previous chunk`() {
        val words = (0 until 10).map { w("w$it", it * 300L, it * 300L + 300) } // continuous, crosses 1000 ms
        val segs = segmentWords(words, leadingOverlapMs = 1000)
        // the worker keeps segments starting at or after the overlap: everything from the first word >= 1000 survives
        val kept = segs.filter { it.startTimeMs >= 1000 }
        assertEquals("w4 w5 w6 w7 w8 w9", kept.joinToString(" ") { it.text })
        // and nothing that starts before it reaches into the kept region
        for (s in segs.filter { it.startTimeMs < 1000 }) assertTrue(s.text, s.text.split(" ").last().removePrefix("w").toInt() <= 3)
    }

    @Test fun `empty input gives no segments`() {
        assertTrue(segmentWords(emptyList()).isEmpty())
    }

    @Test fun `end to end German clip from real tokens`() {
        val words = clampStartsToSpeech(tokensToTimedWords(nemoTokens, nemoTimes), listOf(SpeechSpan(300, 3500)))
        val segs = segmentWords(words)
        assertEquals(1, segs.size)
        assertEquals("Wenn man Glück hat, kann eine einzelne Phantasie", segs[0].text)
        assertEquals(300L, segs[0].startTimeMs)
    }
}

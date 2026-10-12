package com.securevox.app.whisper

import com.securevox.app.data.model.WordCodec
import com.securevox.app.data.model.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordTimesTest {

    private fun tok(t: String, s: Long, e: Long) = TokenTime(t, s, e)

    @Test
    fun groupsTokensIntoWordsAndKeepsPunctuationWithTheWord() {
        val words = groupTokensIntoWords(
            listOf(
                tok(" And", 320, 400), tok(" so", 400, 520), tok(" Amer", 690, 900),
                tok("icans", 900, 1100), tok(",", 1100, 1200), tok(" ask", 1300, 1500)
            )
        )
        assertEquals(listOf("And", "so", "Americans,", "ask"), words.map { it.text })
        assertEquals(690L, words[2].startMs)
        assertEquals(1200L, words[2].endMs)
    }

    @Test
    fun splitsChineseAndJapaneseIntoOneWordPerCharacter() {
        val words = groupTokensIntoWords(listOf(tok("你", 0, 100), tok("好", 100, 200), tok("。", 200, 250)))
        assertEquals(listOf("你", "好。"), words.map { it.text })
        assertEquals("你好。", joinWords(words.map { it.text }))
    }

    @Test
    fun repairClampsOrdersAndFillsUnknownTimes() {
        val raw = listOf(
            WordTiming("a", -1, -1),       // unknown
            WordTiming("b", 900, 5_000),   // ends past the segment
            WordTiming("c", 500, 600),     // starts before the previous word
            WordTiming("d", 1_200, 1_100)  // ends before it starts
        )
        val fixed = repairWordTimes(raw, 1_000, 2_000)
        // Starts never go backwards and stay inside the segment.
        assertEquals(listOf(1_000L, 1_000L, 1_000L, 1_200L), fixed.map { it.startMs })
        // Ends never pass the next start or the segment end, and never precede the start.
        assertTrue(fixed.zipWithNext().all { (x, y) -> x.endMs <= y.startMs })
        assertTrue(fixed.all { it.endMs >= it.startMs && it.endMs <= 2_000 })
    }

    @Test
    fun parsesWhisperJsonWithTokensAndBuildsWords() {
        val json = """[{"text":" Hello, \"world\".","start":1000.000000,"end":3000.000000,
            "tokens":[[" Hello",1000,1500],[",",1500,1600],[" \"world",1700,2400],["\".",2400,2900]]}]"""
        val seg = parseWhisperSegments(json).single()
        assertEquals("Hello, \"world\".", seg.text)
        assertEquals(1000L, seg.startTimeMs)
        assertEquals(listOf("Hello,", "\"world\"."), seg.words.map { it.text })
        assertEquals(1700L, seg.words[1].startMs)
    }

    @Test
    fun oldStyleJsonWithoutTokensStillParses() {
        val seg = parseWhisperSegments("""[{"text":" Hi there","start":0.000000,"end":1500.000000}]""").single()
        assertEquals("Hi there", seg.text)
        assertEquals(1500L, seg.endTimeMs)
        assertTrue(seg.words.isEmpty())
    }

    @Test
    fun dropsWordsThatDoNotSpellTheSegment() {
        val json = """[{"text":" Hello world","start":0,"end":1000,"tokens":[[" Hello",0,400],[" there",400,900]]}]"""
        assertTrue(parseWhisperSegments(json).single().words.isEmpty())
    }

    @Test
    fun blankAudioMarkersAreDroppedFromWordsAndSegments() {
        val json = """[{"text":" [BLANK_AUDIO]","start":0,"end":1000,"tokens":[[" [",0,100],["BLANK",100,200],["_AUDIO",200,300],["]",300,400]]},
            {"text":" Yes [music] ok","start":1000,"end":2000,"tokens":[[" Yes",1000,1300],[" [",1300,1400],["music",1400,1500],["]",1500,1600],[" ok",1600,1900]]}]"""
        val segs = parseWhisperSegments(json)
        assertEquals(1, segs.size)
        assertEquals("Yes ok", segs[0].text)
        assertEquals(listOf("Yes", "ok"), segs[0].words.map { it.text })
    }

    @Test
    fun controlCharactersAndUnicodeEscapesSurvive() {
        val seg = parseWhisperSegments("""[{"text":"café\tx","start":0,"end":10}]""").single()
        assertEquals("café x", seg.text) // NonSpeech.strip collapses the tab to a space
    }

    @Test
    fun wordCodecRoundTripsAwkwardText() {
        val words = listOf(
            WordTiming("He said \"hi\",", 0, 400),
            WordTiming("back\\slash", 400, 800),
            WordTiming("é你\n", 800, 1200)
        )
        assertEquals(words, WordCodec.decode(WordCodec.encode(words)))
    }

    @Test
    fun wordCodecTreatsEmptyAndGarbageAsNoWords() {
        assertEquals("", WordCodec.encode(emptyList()))
        assertTrue(WordCodec.decode("").isEmpty())
        assertTrue(WordCodec.decode("not json").isEmpty())
        assertTrue(WordCodec.decode("[[\"a\",1]]").isEmpty())
        assertTrue(WordCodec.decode("[[\"a\",1,2],[\"b\"").isEmpty())
        assertTrue(WordCodec.decode("{\"a\":1}").isEmpty())
    }

    @Test
    fun wordCodecFormatIsStable() {
        // This exact shape is what is stored in users' databases; changing it needs a migration.
        assertEquals("[[\"Hi,\",10,20],[\"you\",30,40]]",
            WordCodec.encode(listOf(WordTiming("Hi,", 10, 20), WordTiming("you", 30, 40))))
    }
}

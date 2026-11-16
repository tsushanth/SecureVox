package com.securevox.app.data

import com.securevox.app.data.model.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryCorrectorTest {

    private fun fix(text: String, vararg terms: String) = DictionaryCorrector(terms.toList()).correct(text)

    // ---- Latin ------------------------------------------------------------------------------

    @Test
    fun latinTermsAreRewrittenAsTyped() {
        assertEquals(
            "i talked to John Smith about OpenAI",
            fix("i talked to john smith about openai", "John Smith", "OpenAI")
        )
    }

    @Test
    fun aSpaceOrHyphenBetweenLettersIsTolerated() {
        assertEquals("OpenAI and OpenAI and OpenAI", fix("open ai and Open-AI and OPENAI", "OpenAI"))
        assertEquals("Jean-Luc said hi", fix("jean luc said hi", "Jean-Luc"))
    }

    @Test
    fun onlyWholeWordsMatch() {
        assertEquals("Annabel met Ann.", fix("Annabel met ann.", "Ann"))
        assertEquals("the cat sat", fix("the cat sat", "at"))
        assertEquals("Anna's book", fix("anna's book", "Anna"))
    }

    @Test
    fun digitsAndMixedScriptEdgesCountAsBoundaries() {
        assertEquals("iOS17 is out", fix("ios17 is out", "iOS"))
    }

    @Test
    fun turkishDottedCapitalIMatches() {
        assertEquals("İstanbul'a gittim", fix("istanbul'a gittim", "İstanbul"))
    }

    @Test
    fun oneCharacterTermsAreNeverAutoCorrected() {
        assertEquals("a b", fix("a b", "A"))
    }

    // ---- Other scripts ----------------------------------------------------------------------

    @Test
    fun cyrillic() {
        assertEquals("я видел Анна и Анна.", fix("я видел анна и АННА.", "Анна"))
        assertEquals("видел Анне", fix("видел Анне", "Анна")) // an inflected form is a different word
    }

    @Test
    fun greekFinalSigmaAndCase() {
        assertEquals("ο Νίκος είπε", fix("ο νίκοσ είπε", "Νίκος"))
        assertEquals("η Σοφία.", fix("η ΣΟΦΊΑ.", "Σοφία"))
    }

    @Test
    fun arabic() {
        assertEquals("قال أحمد.", fix("قال أحمد.", "أحمد"))
        assertEquals("قال محمد أحمد", fix("قال محمد أحمد", "أحمد", "محمد"))
        // a different word that only starts with the term does not match
        assertEquals("قال أحمدي", fix("قال أحمدي", "أحمد"))
    }

    @Test
    fun cjkWithoutSpaces() {
        assertEquals("我昨天见到了田中先生", fix("我昨天见到了田中先生", "田中"))
        assertEquals("我买了iPhone手机", fix("我买了iphone手机", "iPhone"))
        assertEquals("さくらさんと話した", fix("さくらさんと話した", "さくら"))
        assertEquals("今日は鈴木さんに会った", fix("今日は鈴木さんに会った", "鈴木"))
    }

    @Test
    fun thaiWithoutSpaces() {
        assertEquals("ฉันไปกับสมชายเมื่อวาน", fix("ฉันไปกับสมชายเมื่อวาน", "สมชาย"))
    }

    // ---- Overlap, ties, order --------------------------------------------------------------

    @Test
    fun theLongestOverlappingTermWins() {
        val c = DictionaryCorrector(listOf("New York", "New York City"))
        assertEquals("I love New York City and New York", c.correct("I love new york city and new york"))
    }

    @Test
    fun matchesNeverOverlap() {
        val c = DictionaryCorrector(listOf("AB CD", "CD EF"))
        assertEquals("AB CD ef", c.correct("ab cd ef"))
        val m = c.findMatches("ab cd ef")
        for (i in 1 until m.size) assertTrue(m[i].start >= m[i - 1].end)
    }

    @Test
    fun correctionIsIdempotent() {
        val c = DictionaryCorrector(listOf("OpenAI", "John Smith", "C++", "iPhone"))
        val once = c.correct("john smith uses c++ and open ai on an iphone")
        assertEquals(once, c.correct(once))
        assertEquals("John Smith uses C++ and OpenAI on an iPhone", once)
    }

    // ---- Hostile and empty input -----------------------------------------------------------

    @Test
    fun emptyAndWhitespaceInputIsHarmless() {
        val c = DictionaryCorrector(listOf("", "   ", "\n\t", "OpenAI"))
        assertEquals("", c.correct(""))
        assertEquals("   ", c.correct("   "))
        assertEquals(listOf("OpenAI"), c.terms)
        assertTrue(DictionaryCorrector(emptyList()).isEmpty)
        assertTrue(DictionaryCorrector(null).isEmpty)
        assertTrue(DictionaryCorrector(listOf(null, "", " ")).isEmpty)
        assertEquals("unchanged text", DictionaryCorrector.EMPTY.correct("unchanged text"))
    }

    @Test
    fun regexSpecialCharactersAreLiteral() {
        val terms = arrayOf("C++", "a.b", "(x)", "[abc]", "\\d+", "$1", "^start$", ".*", "a|b")
        val text = "i use c++ and A.B and (X) and [ABC] and \\D+ and $1 and ^START$ and .* and A|B but not aXb"
        assertEquals(
            "i use C++ and a.b and (x) and [abc] and \\d+ and $1 and ^start$ and .* and a|b but not aXb",
            fix(text, *terms)
        )
    }

    @Test
    fun badTextNeverThrows() {
        val c = DictionaryCorrector(listOf("OpenAI", "田中"))
        val texts = listOf("\uD800 open ai", "open ai \uDC00", "\u0000\u0001open ai", "😀 open ai 😀", "opeń ai", "a".repeat(50_000))
        for (t in texts) {
            c.correct(t)
            c.correctSegment(t, listOf(WordTiming("open", 0, 10), WordTiming("ai", 10, 20)))
            c.findMatches(t)
        }
        assertEquals("OpenAI", c.correct("openai"))
        // a combining mark right after the term makes it a different word
        assertEquals("openaí", c.correct("openaí"))
    }

    @Test
    fun overlongAndControlCharacterTermsAreCleaned() {
        assertNull(sanitizeDictionaryTerm(null))
        assertNull(sanitizeDictionaryTerm(""))
        assertNull(sanitizeDictionaryTerm(" \t\n "))
        assertNull(sanitizeDictionaryTerm("x".repeat(DictionaryLimits.MAX_TERM_CODE_POINTS + 1)))
        assertEquals("x".repeat(DictionaryLimits.MAX_TERM_CODE_POINTS), sanitizeDictionaryTerm("x".repeat(DictionaryLimits.MAX_TERM_CODE_POINTS)))
        assertEquals("John Smith", sanitizeDictionaryTerm("  John \t\n  Smith\u0000 "))
        assertEquals("ab", sanitizeDictionaryTerm("a\u0007b"))
        assertEquals("😀😀", sanitizeDictionaryTerm("😀😀"))
        assertEquals("a", sanitizeDictionaryTerm("a\uD800"))
        // ZWNJ is part of Persian words and must survive
        assertEquals("می‌خواهم", sanitizeDictionaryTerm("می‌خواهم"))
    }

    @Test
    fun termsAreDedupedIgnoringCaseAndCapped() {
        assertEquals(listOf("iOS"), normalizeDictionaryTerms(listOf("ios", "iOS", " IOS ")))
        val many = (1..700).map { "term$it" }
        assertEquals(DictionaryLimits.MAX_TERMS, normalizeDictionaryTerms(many).size)
        assertTrue(normalizeDictionaryTerms(null).isEmpty())
    }

    @Test
    fun promptStaysWithinItsBudget() {
        assertEquals("", promptFor(emptyList()))
        assertEquals("Anna, OpenAI", promptFor(listOf("OpenAI", "Anna")))
        val long = (1..200).map { "Name$it" }
        val p = promptFor(long)
        assertTrue(p.codePointCount(0, p.length) <= DictionaryLimits.MAX_PROMPT_CODE_POINTS)
        assertTrue(p.isNotEmpty())
    }

    // ---- Segments, words and timestamps -----------------------------------------------------

    @Test
    fun aTermSpanningWordsBecomesOneWordWithTheSameTimespan() {
        val c = DictionaryCorrector(listOf("John Smith"))
        val words = listOf(WordTiming("john", 100, 400), WordTiming("smith,", 450, 900), WordTiming("said", 950, 1200), WordTiming("hi", 1250, 1400))
        val (text, fixed) = c.correctSegment("john smith, said hi", words)
        assertEquals("John Smith, said hi", text)
        assertEquals(
            listOf(WordTiming("John Smith,", 100, 900), WordTiming("said", 950, 1200), WordTiming("hi", 1250, 1400)),
            fixed
        )
    }

    @Test
    fun aSingleWordKeepsItsTimesAndPunctuation() {
        val c = DictionaryCorrector(listOf("OpenAI"))
        val words = listOf(WordTiming("(openai)", 0, 500), WordTiming("works", 500, 900))
        val (text, fixed) = c.correctSegment("(openai) works", words)
        assertEquals("(OpenAI) works", text)
        assertEquals(listOf(WordTiming("(OpenAI)", 0, 500), WordTiming("works", 500, 900)), fixed)
    }

    @Test
    fun cjkWordsKeepConsistentTimes() {
        val c = DictionaryCorrector(listOf("田中"))
        val words = listOf(WordTiming("田", 0, 200), WordTiming("中", 200, 400), WordTiming("さん", 400, 800))
        val (text, fixed) = c.correctSegment("田中さん", words)
        assertEquals("田中さん", text)
        assertEquals(listOf(WordTiming("田中", 0, 400), WordTiming("さん", 400, 800)), fixed)
    }

    @Test
    fun wordTimesAreDroppedWhenTheyNoLongerSpellTheText() {
        val c = DictionaryCorrector(listOf("John Smith"))
        val (text, fixed) = c.correctSegment("john smith", listOf(WordTiming("unrelated", 0, 100)))
        assertEquals("John Smith", text)
        assertTrue(fixed.isEmpty())
    }

    @Test
    fun segmentsWithoutMatchesAreReturnedUntouched() {
        val c = DictionaryCorrector(listOf("OpenAI"))
        val words = listOf(WordTiming("hello", 0, 10))
        val (text, fixed) = c.correctSegment("hello", words)
        assertEquals("hello", text)
        assertEquals(words, fixed)
        assertFalse(c.isEmpty)
    }

    @Test
    fun timestampsAreNeverChanged() {
        val c = DictionaryCorrector(listOf("John Smith", "OpenAI"))
        val words = listOf(WordTiming("john", 10, 20), WordTiming("smith", 20, 40), WordTiming("open", 40, 60), WordTiming("ai", 60, 90))
        val (_, fixed) = c.correctSegment("john smith open ai", words)
        assertEquals(10L, fixed.first().startMs)
        assertEquals(90L, fixed.last().endMs)
        for (i in 1 until fixed.size) assertTrue(fixed[i].startMs >= fixed[i - 1].endMs)
    }
}

package com.securevox.app.whisper

import java.text.Normalizer

/**
 * Hinglish output: writes the Devanagari the Hindi model produces in Roman letters, the way people type Hindi in
 * chat ("kya haal hai", "main theek hoon"), in lower case as chat does. Each word is transliterated on its own, so
 * a segment's text and its timed words stay in step. Pure rules plus a small list of very common words, no model and no
 * network, so it costs nothing and nothing leaves the phone.
 *
 * It is a spelling convention, not a recogniser: it cannot restore English words that the model wrote in
 * Devanagari to their English spelling ("फोन" becomes "phon"), and a word can have several accepted Roman spellings.
 * Latin text, digits and punctuation pass through unchanged.
 */
object RomanHindi {

    fun containsDevanagari(text: String): Boolean = text.any { isDevanagariLetter(it) || it in DIGITS || it == '।' || it == '॥' }

    fun fromDevanagari(text: String): String {
        if (!containsDevanagari(text)) return text
        val out = StringBuilder(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                isDevanagariLetter(c) -> {
                    var j = i
                    while (j < text.length && isDevanagariLetter(text[j])) j++
                    out.append(word(text.substring(i, j)))
                    i = j
                }
                c in DIGITS -> { out.append(('0' + (c - '०'))); i++ }
                c == '।' || c == '॥' -> { out.append('.'); i++ }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    // ---- character classes -------------------------------------------------------------------------------------

    private val DIGITS = '०'..'९'

    /** Letters, vowel signs, virama, nukta, nasal signs of the Devanagari block; not digits, danda or the abbreviation sign. */
    private fun isDevanagariLetter(c: Char) = c in 'ऀ'..'ॿ' && c !in DIGITS && c != '।' && c != '॥' && c != '॰'

    private const val VIRAMA = '्'
    private const val NUKTA = '़'

    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'ळ' to "l", 'ऩ' to "n", 'ऱ' to "r"
    )

    /** Consonant plus nukta (the dot below), as in ज़ and फ़. */
    private val NUKTA_FORMS = mapOf(
        'क' to "q", 'ख' to "kh", 'ग' to "g", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f", 'य' to "y"
    )

    private val INDEPENDENT_VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e", 'ऎ' to "e", 'ऒ' to "o"
    )

    private val MATRAS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e", 'ॆ' to "e", 'ॊ' to "o"
    )

    private val LABIALS = setOf('प', 'फ', 'ब', 'भ', 'म')

    // ---- very common words: the spellings chat uses, which the rules below would not guess --------------------

    private val COMMON: Map<String, String> = listOf(
        "है" to "hai", "हैं" to "hain", "हूँ" to "hoon", "हो" to "ho", "होता" to "hota", "होती" to "hoti", "होते" to "hote",
        "था" to "tha", "थी" to "thi", "थे" to "the", "में" to "mein", "मैं" to "main", "मैंने" to "maine",
        "यह" to "yeh", "ये" to "ye", "वह" to "woh", "वो" to "woh", "वे" to "ve",
        "नहीं" to "nahi", "ठीक" to "theek", "अच्छा" to "accha", "अच्छी" to "acchi", "अच्छे" to "acche",
        "क्या" to "kya", "क्यों" to "kyon", "कि" to "ki", "कुछ" to "kuch", "और" to "aur", "हम" to "hum", "तुम" to "tum",
        "आप" to "aap", "मुझे" to "mujhe", "तुझे" to "tujhe", "हमें" to "hamein", "उन्होंने" to "unhone", "उसने" to "usne",
        "एक" to "ek", "दो" to "do", "तीन" to "teen", "चार" to "chaar", "पांच" to "paanch", "पाँच" to "paanch",
        "यहाँ" to "yahan", "वहाँ" to "wahan", "कहाँ" to "kahan", "हाँ" to "haan", "जी" to "ji",
        "बहुत" to "bahut", "सब" to "sab", "भी" to "bhi", "तो" to "to", "जो" to "jo", "से" to "se", "को" to "ko",
        "का" to "ka", "की" to "ki", "के" to "ke", "पर" to "par", "लिए" to "liye", "साथ" to "saath",
        "आज" to "aaj", "कल" to "kal", "अब" to "ab", "फिर" to "phir", "लेकिन" to "lekin", "अगर" to "agar",
        "कैसे" to "kaise", "कब" to "kab", "कौन" to "kaun", "क्योंकि" to "kyunki", "इसलिए" to "isliye"
    ).associate { (k, v) -> Normalizer.normalize(k, Normalizer.Form.NFD) to v }

    // ---- one Devanagari word -------------------------------------------------------------------------------------

    private enum class Kind { CONSONANT, VOWEL, NASAL }

    private class Unit(val kind: Kind, var roman: String) {
        /** Consonant that still carries the inherent "a" (no vowel sign, no virama). */
        var inherent = false
        /** The vowel written after the consonant (or the whole text of an independent vowel). */
        var vowel = ""
        var deleted = false
        /** True for a consonant that is joined to the next one (virama). */
        var virama = false
        fun hasVowel() = when (kind) {
            Kind.VOWEL -> true
            Kind.NASAL -> false
            Kind.CONSONANT -> vowel.isNotEmpty() || (inherent && !deleted)
        }
    }

    private fun word(raw: String): String {
        val w = Normalizer.normalize(raw, Normalizer.Form.NFD)
        COMMON[w]?.let { return it }

        val units = ArrayList<Unit>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            val cons = CONSONANTS[c]
            when {
                cons != null -> {
                    var roman = cons
                    i++
                    if (i < w.length && w[i] == NUKTA) { roman = NUKTA_FORMS[c] ?: roman; i++ }
                    val u = Unit(Kind.CONSONANT, roman)
                    // ज्ञ is said "gya", not "jny".
                    val prev = units.lastOrNull()
                    if (c == 'ञ' && prev != null && prev.kind == Kind.CONSONANT && prev.virama && prev.roman == "j") {
                        prev.roman = "g"; u.roman = "y"
                    }
                    if (i < w.length && w[i] in MATRAS) { u.vowel = MATRAS.getValue(w[i]); i++ }
                    else if (i < w.length && w[i] == VIRAMA) { u.virama = true; i++ }
                    else u.inherent = true
                    units.add(u)
                }
                c in INDEPENDENT_VOWELS -> { units.add(Unit(Kind.VOWEL, "").also { it.vowel = INDEPENDENT_VOWELS.getValue(c) }); i++ }
                c in MATRAS -> { units.add(Unit(Kind.VOWEL, "").also { it.vowel = MATRAS.getValue(c) }); i++ }
                c == 'ं' || c == 'ँ' -> {
                    // Anusvara reads as m before p b bh m, otherwise n; chandrabindu is always n.
                    var next: Char? = null
                    for (k in i + 1 until w.length) { if (w[k] != NUKTA) { next = w[k]; break } }
                    val m = c == 'ं' && next != null && next in LABIALS
                    units.add(Unit(Kind.NASAL, if (m) "m" else "n")); i++
                }
                c == 'ः' -> { units.add(Unit(Kind.NASAL, "h")); i++ }
                c == 'ॐ' -> { units.add(Unit(Kind.VOWEL, "").also { it.vowel = "om" }); i++ }
                else -> i++ // virama or nukta out of place, avagraha, accents: nothing to say
            }
        }
        dropSchwas(units)

        val sb = StringBuilder()
        for ((idx, u) in units.withIndex()) {
            val last = idx == units.lastIndex
            when (u.kind) {
                Kind.NASAL -> sb.append(u.roman)
                Kind.VOWEL -> sb.append(u.vowel)
                Kind.CONSONANT -> {
                    sb.append(u.roman)
                    when {
                        u.vowel.isNotEmpty() -> sb.append(finalForm(u.vowel, last))
                        u.inherent && !u.deleted -> sb.append('a')
                    }
                }
            }
        }
        // च् followed by छ is the doubled "cch" in accha, not "chchh".
        return sb.toString().replace("chchh", "cch")
    }

    /** A word-final long a is written "a" ("pata", "kya") and a final long i "i" ("hindi"); inside a word they stay long. */
    private fun finalForm(vowel: String, last: Boolean): String = when {
        !last -> vowel
        vowel == "aa" -> "a"
        vowel == "ee" -> "i"
        else -> vowel
    }

    /**
     * The inherent "a" of a consonant is dropped where speech drops it: at the end of a word, and between two
     * syllables that both have a vowel (kamal, kamra, samajh, mehnat). Kept after the first syllable's nasal
     * (zindagi) and before a conjunct (namaste). Worked from the right so each decision sees its right neighbour.
     */
    private fun dropSchwas(units: List<Unit>) {
        for (i in units.indices.reversed()) {
            val u = units[i]
            if (u.kind != Kind.CONSONANT || !u.inherent) continue
            if (i == units.lastIndex) {
                if (units.subList(0, i).any { it.hasVowel() }) u.deleted = true
            } else if (i > 0) {
                val prev = units[i - 1]
                val next = units[i + 1]
                if (prev.kind != Kind.NASAL && prev.hasVowel() && next.kind != Kind.NASAL && next.hasVowel()) u.deleted = true
            }
        }
    }
}

/** What the decoder's text becomes for the language the user chose. */
object OutputScript {
    /** [languageCode] is the saved code. Hinglish writes Devanagari in Roman letters; everything else is as decoded. */
    fun apply(languageCode: String, text: String): String =
        if (WhisperLanguage.fromCode(languageCode).romanScript) RomanHindi.fromDevanagari(text) else text

    /** The segment as the user should see it; its timed words are rewritten with its text so tap-to-seek still lines up. */
    fun apply(languageCode: String, segment: TranscriptionSegment): TranscriptionSegment {
        if (!WhisperLanguage.fromCode(languageCode).romanScript) return segment
        return segment.copy(
            text = RomanHindi.fromDevanagari(segment.text),
            words = segment.words.map { it.copy(text = RomanHindi.fromDevanagari(it.text)) }
        )
    }
}

/**
 * Initial prompt for the decoder, by the language the user chose. A prompt written in the script you want steers
 * Whisper's Hindi, which otherwise drifts between scripts (measured on 25 FLEURS Hindi clips, tools/lang-bench):
 *  - Hinglish: a short Roman-letter Hindi sentence makes Tiny and Base write Roman Hindi directly, and Base then
 *    reads about as well as Small does without it (phonetic CER 21 vs 20, versus 40 without the prompt).
 *  - Hindi in Devanagari: a Devanagari primer fixes Base's script (23 of 25 clips, up from 1) but Small, the model
 *    Hindi needs, gains nothing (WER 58 against 54 without), so it stays off; see [HINDI_PRIMER_ENABLED].
 * The user's Custom Dictionary terms come last because the text nearest the audio weighs most.
 */
object LanguagePrompt {
    private const val HINGLISH_PRIMER = "Namaste, aap kaise hain? Main theek hoon. Yeh ek Hindi vaakya hai."
    private const val HINDI_PRIMER = "नमस्ते, आप कैसे हैं? मैं ठीक हूँ। यह एक हिंदी वाक्य है।"

    /** Off: no gain on Small (tools/lang-bench/results-hindi.md). */
    const val HINDI_PRIMER_ENABLED = false

    /** [languageCode] is the saved code ("hi", "hi-Latn", "en", ...). */
    fun build(languageCode: String, dictionaryPrompt: String): String {
        val primer = when (WhisperLanguage.fromCode(languageCode)) {
            WhisperLanguage.HINGLISH -> HINGLISH_PRIMER
            WhisperLanguage.HINDI -> if (HINDI_PRIMER_ENABLED) HINDI_PRIMER else ""
            else -> ""
        }
        return listOf(primer, dictionaryPrompt).filter { it.isNotBlank() }.joinToString(" ")
    }
}

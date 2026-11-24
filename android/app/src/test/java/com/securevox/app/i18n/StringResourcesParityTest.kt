package com.securevox.app.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every translated values-* folder has exactly the keys of values/ (strings and plurals), the same format
 * placeholders in the same set, the plural categories its language needs, and res/xml/locales_config.xml lists
 * exactly the shipped languages.
 */
class StringResourcesParityTest {
    private fun res(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun locales(): List<File> =
        res().listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!.sortedBy { it.name }

    private class Parsed(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun parse(file: File): Parsed {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
            val e = n as org.w3c.dom.Element
            val name = e.getAttribute("name")
            when (e.tagName) {
                "string" -> if (e.getAttribute("translatable") != "false") strings[name] = e.textContent
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[name] = (0 until items.length).associate {
                        val item = items.item(it) as org.w3c.dom.Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return Parsed(strings, plurals)
    }

    private fun placeholders(s: String): List<String> = Regex("""%\d\$[ds]|%%""").findAll(s).map { it.value }.sorted().toList()

    /** CLDR categories a language needs (at least these). */
    private fun required(folder: String): Set<String> = when (folder) {
        "values-ar" -> setOf("zero", "one", "two", "few", "many", "other")
        "values-ru", "values-pl", "values-cs", "values-uk" -> setOf("one", "few", "many", "other")
        "values-ro" -> setOf("one", "few", "other")
        "values-ja", "values-ko", "values-zh-rCN", "values-zh-rTW", "values-zh-rHK", "values-b+zh+Hant", "values-th", "values-vi", "values-in", "values-ms" -> setOf("other")
        else -> setOf("one", "other")
    }

    @Test
    fun everyLocaleHasTheSameKeysAndPlaceholdersAsEnglish() {
        val english = parse(File(res(), "values/strings.xml"))
        val locales = locales()
        assertTrue("expected the 26 translated locales, found ${locales.size}", locales.size >= 26)
        val folders = locales.map { it.name }
        for (f in listOf("values-cs", "values-el", "values-fi", "values-hu", "values-ro", "values-sv", "values-uk")) {
            assertTrue("$f is missing", f in folders)
        }
        for (dir in locales) {
            val t = parse(File(dir, "strings.xml"))
            assertEquals("${dir.name} string keys", english.strings.keys.sorted(), t.strings.keys.sorted())
            assertEquals("${dir.name} plural keys", english.plurals.keys.sorted(), t.plurals.keys.sorted())
            for ((key, value) in english.strings) {
                val translated = t.strings.getValue(key)
                assertTrue("${dir.name} $key is blank", translated.isNotBlank())
                assertEquals("${dir.name} $key placeholders", placeholders(value), placeholders(translated))
            }
            for ((key, forms) in t.plurals) {
                assertTrue("${dir.name} $key lacks plural categories ${required(dir.name) - forms.keys}", forms.keys.containsAll(required(dir.name)))
                forms.forEach { (q, text) -> assertTrue("${dir.name} $key/$q needs the count", text.contains("%1\$d")) }
            }
        }
    }

    @Test
    fun localesConfigListsExactlyTheShippedLanguages() {
        fun tag(folder: String): String = when (folder) {
            "values-in" -> "id"
            "values-pt-rBR" -> "pt-BR"
            "values-zh-rCN" -> "zh-CN"
            "values-zh-rTW" -> "zh-TW"
            "values-zh-rHK" -> "zh-HK"
            else -> folder.removePrefix("values-")
        }
        // values-b+zh+Hant is the script fallback for Traditional Chinese regions without their own folder (e.g. zh-MO);
        // it is not a separate picker entry, zh-TW and zh-HK cover it.
        val shipped = (locales().filter { it.name != "values-b+zh+Hant" }.map { tag(it.name) } + "en").sorted()
        val listed = Regex("""android:name="([^"]+)"""").findAll(File(res(), "xml/locales_config.xml").readText())
            .map { it.groupValues[1] }.sorted().toList()
        assertEquals(shipped, listed)
    }

    @Test
    fun noVendorOrModelNamesInAnyUserVisibleString() {
        val files = listOf(File(res(), "values/strings.xml")) + locales().map { File(it, "strings.xml") }
        val banned = Regex("Whisper|OpenAI|NeMo|SenseVoice", RegexOption.IGNORE_CASE)
        for (f in files) {
            parse(f).strings.forEach { (k, v) ->
                assertTrue("${f.parentFile.name} $k names a model or vendor", !banned.containsMatchIn(v))
            }
        }
    }
}

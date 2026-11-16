package com.securevox.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Service for managing custom dictionary words.
 * The user's names, technical terms and abbreviations. They are handed to the speech model as a hint when a
 * recording is transcribed, and DictionaryCorrector then rewrites each term in the text exactly as the user
 * typed it (capitalization, and a space or hyphen between letters). It does not fix phonetic misspellings.
 */
class CustomDictionaryService(private val context: Context) {

    companion object {
        private val Context.dictionaryDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "custom_dictionary"
        )
        private val KEY_WORDS = stringSetPreferencesKey("dictionary_words")

        @Volatile
        private var instance: CustomDictionaryService? = null

        fun getInstance(context: Context): CustomDictionaryService {
            return instance ?: synchronized(this) {
                instance ?: CustomDictionaryService(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val dataStore = context.dictionaryDataStore

    /**
     * Flow of all words in the dictionary, sorted alphabetically
     */
    val words: Flow<List<String>> = dataStore.data
        .map { prefs ->
            (prefs[KEY_WORDS] ?: emptySet()).sorted()
        }

    /**
     * Get all words synchronously (for non-UI operations)
     */
    suspend fun getWords(): List<String> {
        return words.first()
    }

    /**
     * Add a word to the dictionary. The word is cleaned first (see [sanitizeDictionaryTerm]).
     * @return true if word was added, false if it is unusable (empty, longer than
     * [DictionaryLimits.MAX_TERM_CODE_POINTS]), already there (capitalization ignored) or the dictionary is full
     */
    suspend fun addWord(word: String): Boolean = addWords(listOf(word)) > 0

    /**
     * Add multiple words to the dictionary. Unusable, duplicate and over-limit entries are skipped.
     * @return number of words actually added
     */
    suspend fun addWords(words: List<String>): Int {
        val cleaned = words.mapNotNull { sanitizeDictionaryTerm(it) }
        if (cleaned.isEmpty()) return 0

        var addedCount = 0
        dataStore.edit { prefs ->
            val currentWords = prefs[KEY_WORDS] ?: emptySet()
            val keys = HashSet<String>(currentWords.map { dictionaryTermKey(it) })
            val toAdd = LinkedHashSet<String>()
            for (w in cleaned) {
                if (currentWords.size + toAdd.size >= DictionaryLimits.MAX_TERMS) break
                if (keys.add(dictionaryTermKey(w))) toAdd.add(w)
            }
            addedCount = toAdd.size
            if (toAdd.isNotEmpty()) {
                prefs[KEY_WORDS] = currentWords + toAdd
            }
        }
        return addedCount
    }

    /**
     * Remove a word from the dictionary
     */
    suspend fun removeWord(word: String) {
        dataStore.edit { prefs ->
            val currentWords = prefs[KEY_WORDS] ?: emptySet()
            prefs[KEY_WORDS] = currentWords - word
        }
    }

    /**
     * Remove all words from the dictionary
     */
    suspend fun clearAll() {
        dataStore.edit { prefs ->
            prefs[KEY_WORDS] = emptySet()
        }
    }

    /**
     * Check if a word exists in the dictionary
     */
    suspend fun containsWord(word: String): Boolean {
        val currentWords = dataStore.data.first()[KEY_WORDS] ?: emptySet()
        val key = sanitizeDictionaryTerm(word)?.let { dictionaryTermKey(it) } ?: return false
        return currentWords.any { dictionaryTermKey(it) == key }
    }

    /**
     * Export dictionary as a newline-separated string
     */
    suspend fun exportToString(): String {
        return getWords().joinToString("\n")
    }

    /**
     * Import words from a newline-separated string
     * @return number of words imported
     */
    suspend fun importFromString(text: String): Int {
        val words = text.split("\n", "\r\n", "\r")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return addWords(words)
    }

    /**
     * Get word count
     */
    suspend fun getWordCount(): Int {
        val currentWords = dataStore.data.first()[KEY_WORDS] ?: emptySet()
        return currentWords.size
    }
}

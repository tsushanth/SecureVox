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
 * Words in the custom dictionary can be used to improve transcription accuracy
 * for names, technical terms, or other words that may be commonly misrecognized.
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
     * Add a word to the dictionary
     * @return true if word was added, false if it already exists
     */
    suspend fun addWord(word: String): Boolean {
        val trimmedWord = word.trim()
        if (trimmedWord.isBlank()) return false

        var added = false
        dataStore.edit { prefs ->
            val currentWords = prefs[KEY_WORDS] ?: emptySet()
            if (!currentWords.contains(trimmedWord)) {
                prefs[KEY_WORDS] = currentWords + trimmedWord
                added = true
            }
        }
        return added
    }

    /**
     * Add multiple words to the dictionary
     * @return number of words actually added
     */
    suspend fun addWords(words: List<String>): Int {
        val trimmedWords = words.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (trimmedWords.isEmpty()) return 0

        var addedCount = 0
        dataStore.edit { prefs ->
            val currentWords = prefs[KEY_WORDS] ?: emptySet()
            val newWords = trimmedWords - currentWords
            addedCount = newWords.size
            if (newWords.isNotEmpty()) {
                prefs[KEY_WORDS] = currentWords + newWords
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
        return currentWords.contains(word.trim())
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

package com.securevox.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey

/**
 * Settings that earlier versions showed but never acted on. Their switches are gone; this drops the values
 * they left in the settings store so nothing stale lingers. Nothing reads these keys, so removing them
 * changes no behaviour.
 */
object LegacySettings {
    /** Recycle Bin retention (days) and Auto-Delete Audio, as stored by versions up to 1.5.3. */
    val DEAD_KEYS: List<Preferences.Key<*>> = listOf(
        intPreferencesKey("recycle_bin_retention_days"),
        booleanPreferencesKey("auto_delete_audio"),
    )

    /** Removes the dead keys from [prefs]; returns true if any were present. */
    fun purge(prefs: MutablePreferences): Boolean {
        var removed = false
        for (key in DEAD_KEYS) {
            if (key in prefs) {
                prefs.remove(key)
                removed = true
            }
        }
        return removed
    }

    /** Safe to run on every launch: DataStore only rewrites its file when something changed. */
    suspend fun purge(dataStore: DataStore<Preferences>) {
        dataStore.edit { purge(it) }
    }
}

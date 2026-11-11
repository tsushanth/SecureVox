package com.securevox.app.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySettingsTest {

    private val retention = intPreferencesKey("recycle_bin_retention_days")
    private val autoDelete = booleanPreferencesKey("auto_delete_audio")
    private val theme = stringPreferencesKey("theme_mode")
    private val model = stringPreferencesKey("selected_model")

    @Test
    fun removesBothDeadKeysAndReportsIt() {
        val prefs = mutablePreferencesOf(retention to 7, autoDelete to true)
        assertTrue(LegacySettings.purge(prefs))
        assertFalse(retention in prefs)
        assertFalse(autoDelete in prefs)
    }

    @Test
    fun keepsEveryOtherSetting() {
        val prefs = mutablePreferencesOf(retention to 30, autoDelete to false, theme to "DARK", model to "ggml-base.bin")
        LegacySettings.purge(prefs)
        assertEquals("DARK", prefs[theme])
        assertEquals("ggml-base.bin", prefs[model])
    }

    @Test
    fun isANoOpWhenNothingIsStored() {
        val prefs = mutablePreferencesOf(theme to "LIGHT")
        assertFalse(LegacySettings.purge(prefs))
        assertEquals("LIGHT", prefs[theme])
    }

    @Test
    fun runningTwiceIsHarmless() {
        val prefs = mutablePreferencesOf(autoDelete to true, theme to "SYSTEM")
        assertTrue(LegacySettings.purge(prefs))
        assertFalse(LegacySettings.purge(prefs))
        assertEquals("SYSTEM", prefs[theme])
    }
}

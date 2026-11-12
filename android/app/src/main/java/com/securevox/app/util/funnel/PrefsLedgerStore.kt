package com.securevox.app.util.funnel

import android.content.Context

/**
 * The ledger's home: a private SharedPreferences file. It is excluded from Android's cloud backup and device
 * transfer (res/xml/backup_rules.xml, data_extraction_rules.xml) so "this stays on your phone" is literally true.
 */
class PrefsLedgerStore(context: Context) : LedgerStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun readAll(): Map<String, String> =
        prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun write(updates: Map<String, String>) {
        prefs.edit().apply { updates.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        /** Keep in step with the backup exclusion rules (without the .xml suffix there). */
        const val FILE = "funnel_local"
    }
}

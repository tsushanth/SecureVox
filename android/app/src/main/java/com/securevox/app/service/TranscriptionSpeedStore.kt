package com.securevox.app.service

import android.content.Context

/** Remembers, per model, how fast this phone transcribed last time (see [TranscriptionEta]). */
object TranscriptionSpeedStore {
    private const val PREFS = "securevox_prefs"

    private fun key(modelFileName: String) = "transcription_speed_$modelFileName"

    fun get(context: Context, modelFileName: String?): Float? {
        modelFileName ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getFloat(key(modelFileName), 0f).takeIf { it > 0f }
    }

    fun put(context: Context, modelFileName: String, speed: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(key(modelFileName), speed).apply()
    }
}

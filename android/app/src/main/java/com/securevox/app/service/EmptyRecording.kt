package com.securevox.app.service

import java.io.File

/**
 * What to do with a recording that stopped with no audio in it (a 44-byte, header-only WAV).
 *
 * Such a file is never worth keeping or transcribing, so it is deleted. Whether to tell the developer depends
 * on how long the user thought they were recording: an instant Start then Stop is just a tap, but a recording
 * that ran for a while and captured nothing means the microphone really delivered no audio.
 */
object EmptyRecording {
    const val HEADER_BYTES = 44L

    /** A recording shorter than this that captured nothing is user behaviour, not a fault. */
    const val REPORT_AFTER_MS = 1_500L

    fun isEmpty(file: File): Boolean = file.length() <= HEADER_BYTES

    /**
     * @param wallClockMs how long the user recorded, by the clock (pauses excluded)
     * @param report called once when this is a real microphone problem
     * @return the path to keep, or null when there is nothing to keep (no file, or an empty one that was deleted)
     */
    fun settle(file: File?, wallClockMs: Long, report: () -> Unit): String? {
        if (file == null) return null
        if (!isEmpty(file)) return file.absolutePath
        file.delete()
        if (wallClockMs > REPORT_AFTER_MS) report()
        return null
    }
}

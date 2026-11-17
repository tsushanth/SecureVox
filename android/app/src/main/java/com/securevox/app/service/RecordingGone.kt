package com.securevox.app.service

/**
 * Tells "the recording was deleted while it was being transcribed" apart from a real transcription failure.
 *
 * Transcript segments carry a foreign key to their recording. If the user deletes a recording while its
 * worker is still running (the native speech pass cannot be interrupted mid-chunk), the next segment insert
 * fails with SQLITE_CONSTRAINT_FOREIGNKEY (code 787). Nothing is lost and nothing needs fixing in that case, so
 * it must not be logged or reported as a failure. Pure JVM so it can be unit tested.
 */
object RecordingGone {

    /** True when [t], or anything in its cause chain, is a SQLite foreign-key constraint violation. */
    fun isForeignKeyViolation(t: Throwable?): Boolean {
        var cur = t
        var depth = 0
        while (cur != null && depth < 8) {
            val m = cur.message.orEmpty()
            if (m.contains("FOREIGN KEY constraint failed", ignoreCase = true) ||
                m.contains("SQLITE_CONSTRAINT_FOREIGNKEY") ||
                m.contains("code 787")
            ) return true
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * The run should end quietly: the write failed on the foreign key AND the recording row is really gone.
     * A foreign-key failure with the row still present is a genuine bug and stays reported.
     */
    fun deletedMidRun(t: Throwable?, recordingStillExists: Boolean): Boolean =
        isForeignKeyViolation(t) && !recordingStillExists
}

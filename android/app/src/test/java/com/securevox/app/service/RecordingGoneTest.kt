package com.securevox.app.service

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Reproduces the field failure "transcription.run: SQLiteConstraintException: FOREIGN KEY constraint failed
 * (code 787)" with real SQLite, using the same two tables and foreign key Room generates for Recording and
 * TranscriptSegment, and checks that a recording deleted mid-run is told apart from a genuine failure.
 */
class RecordingGoneTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON") // Room turns this on for every connection
            it.execute("CREATE TABLE recordings (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL)")
            it.execute(
                "CREATE TABLE transcript_segments (id TEXT NOT NULL PRIMARY KEY, recordingId TEXT NOT NULL, " +
                    "text TEXT NOT NULL, segmentIndex INTEGER NOT NULL, " +
                    "FOREIGN KEY(recordingId) REFERENCES recordings(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
        }
    }

    @After
    fun close() = db.close()

    private fun insertRecording(id: String) = db.prepareStatement("INSERT INTO recordings VALUES (?, 'r')").use {
        it.setString(1, id); it.executeUpdate()
    }

    private fun insertSegment(id: String, recordingId: String) =
        db.prepareStatement("INSERT INTO transcript_segments VALUES (?, ?, 'hello', 0)").use {
            it.setString(1, id); it.setString(2, recordingId); it.executeUpdate()
        }

    private fun deleteRecording(id: String) = db.prepareStatement("DELETE FROM recordings WHERE id = ?").use {
        it.setString(1, id); it.executeUpdate()
    }

    private fun segmentInsertFailure(recordingId: String): SQLException {
        try {
            insertSegment("s1", recordingId)
        } catch (e: SQLException) {
            return e
        }
        fail("expected the segment insert to violate the foreign key")
        throw IllegalStateException()
    }

    @Test
    fun segmentInsertAfterTheRecordingWasDeletedFailsOnTheForeignKey() {
        insertRecording("rec")
        insertSegment("s0", "rec") // chunk 0 of a normal run saves fine
        deleteRecording("rec") // the user deletes it while the next chunk is still being transcribed

        val e = segmentInsertFailure("rec")
        assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("FOREIGN KEY"))
        assertTrue("this is the error the field reported", RecordingGone.isForeignKeyViolation(e))
    }

    @Test
    fun deletedMidRunIsQuietOnlyWhenTheRowIsReallyGone() {
        val e = segmentInsertFailure("never-existed")
        assertTrue(RecordingGone.deletedMidRun(e, recordingStillExists = false))
        // Row still there: a foreign-key failure would be a genuine bug, so it must stay reported.
        assertFalse(RecordingGone.deletedMidRun(e, recordingStillExists = true))
    }

    @Test
    fun otherFailuresAreNeverTreatedAsDeletion() {
        assertFalse(RecordingGone.isForeignKeyViolation(null))
        assertFalse(RecordingGone.isForeignKeyViolation(OutOfMemoryError("heap")))
        assertFalse(RecordingGone.isForeignKeyViolation(IllegalStateException("model not loaded")))
        assertFalse(RecordingGone.deletedMidRun(IllegalStateException("model not loaded"), recordingStillExists = false))
    }

    @Test
    fun findsTheViolationInsideACauseChain() {
        val inner = segmentInsertFailure("gone")
        assertTrue(RecordingGone.isForeignKeyViolation(RuntimeException("wrapped", RuntimeException("again", inner))))
    }

    @Test
    fun androidsWordingIsRecognisedToo() {
        // What android.database.sqlite.SQLiteConstraintException says, per the field report.
        val android = RuntimeException("FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)")
        assertTrue(RecordingGone.isForeignKeyViolation(android))
    }
}

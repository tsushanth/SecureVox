package com.securevox.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptSegment

@Database(
    entities = [Recording::class, TranscriptSegment::class],
    version = 4,
    exportSchema = false
)
abstract class SecureVoxDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao

    companion object {
        private const val DATABASE_NAME = "securevox.db"

        /** Adds the per-segment speaker label. Keeps every existing recording and transcript. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcript_segments ADD COLUMN speaker INTEGER NOT NULL DEFAULT -1")
            }
        }

        /** SQL of [MIGRATION_3_4], exposed so a unit test can pin it. */
        const val ADD_WORDS_COLUMN_SQL =
            "ALTER TABLE transcript_segments ADD COLUMN words TEXT NOT NULL DEFAULT ''"

        /**
         * Adds the per-segment word timings. Keeps every existing recording and transcript; old
         * rows get '' (no words) and keep working at segment level.
         * Must stay registered in [buildDatabase]: without it fallbackToDestructiveMigration
         * would wipe every user's recordings on update.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(ADD_WORDS_COLUMN_SQL)
            }
        }

        @Volatile
        private var INSTANCE: SecureVoxDatabase? = null

        fun getInstance(context: Context): SecureVoxDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): SecureVoxDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                SecureVoxDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

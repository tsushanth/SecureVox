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
    version = 3,
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
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

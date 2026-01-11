package com.securevox.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.securevox.app.data.model.Recording
import com.securevox.app.data.model.TranscriptSegment

@Database(
    entities = [Recording::class, TranscriptSegment::class],
    version = 1,
    exportSchema = true
)
abstract class SecureVoxDatabase : RoomDatabase() {

    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao

    companion object {
        private const val DATABASE_NAME = "securevox.db"

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
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

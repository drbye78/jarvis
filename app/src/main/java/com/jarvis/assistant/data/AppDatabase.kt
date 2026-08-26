package com.jarvis.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 2: the v1 `alarms` table is replaced by the unified
 * `scheduled_alerts` store (PLAN.md §3.3). No backward compatibility is kept:
 * `fallbackToDestructiveMigration` drops ALL tables on any version jump —
 * including chat history — which is intentional and accepted (v1 → v2 wipes
 * old alarm rows and messages).
 *
 * `alarmDao()` keeps its historical name (returning the new [AlertDao])
 * because FunctionRouter — owned by another lane — constructs the scheduler
 * through it.
 */
@Database(
    entities = [MessageEntity::class, ScheduledAlertEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    /** Unified alert store accessor; name kept for cross-lane call-site parity. */
    abstract fun alarmDao(): AlertDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jarvis.db",
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

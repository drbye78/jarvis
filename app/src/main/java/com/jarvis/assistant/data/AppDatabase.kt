package com.jarvis.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 3: explicit migration support replaces [fallbackToDestructiveMigration]
 * so version bumps no longer silently wipe all tables.
 *
 * - v1→v2: DESTRUCTIVE by explicit decision (audit #21): the v1 `alarms`
 *   table was replaced by the unified `scheduled_alerts` schema and the v1
 *   schema was never exported, so no faithful migration can be written.
 *   `fallbackToDestructiveMigrationFrom(1)` gives v1 installs a clean,
 *   non-crashing upgrade (history + alarms are lost — accepted: no
 *   backward compatibility is kept for pre-release schemas) instead of the
 *   previous behavior, an IllegalStateException process crash on first
 *   launch after update.
 * - v2→v3: no-op — schema is identical; the migration exists solely to
 *   prevent destructive fallback on future version bumps.
 *
 * `alarmDao()` keeps its historical name (returning the new [AlertDao])
 * because FunctionRouter — owned by another lane — constructs the scheduler
 * through it.
 */
@Database(
    entities = [MessageEntity::class, ScheduledAlertEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    /** Unified alert store accessor; name kept for cross-lane call-site parity. */
    abstract fun alarmDao(): AlertDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * Migration from schema v2 → v3.
         * No-op: the table definitions are identical; this migration exists so that
         * Room does not fall back to destructive migration on a version bump.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes — data is preserved as-is.
            }
        }

        private val ALL_MIGRATIONS = arrayOf(MIGRATION_2_3)

        /** Pre-release schema with no exportable history: wipe, don't crash (audit #21). */
        private val DESTRUCTIVE_FROM_VERSIONS = intArrayOf(1)

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "jarvis.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .fallbackToDestructiveMigrationFrom(*DESTRUCTIVE_FROM_VERSIONS)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

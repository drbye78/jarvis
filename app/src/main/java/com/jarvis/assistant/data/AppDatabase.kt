package com.jarvis.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.UserFactEntity

/**
 * Version 4 (COGNITIVE_PLAN Phase 1): adds the cognitive memory tables.
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
 * - v3→v4 (COGNITIVE_PLAN 1.1): creates `user_facts` (+ `fact_fts` external
 *   content index with its sync triggers), `extraction_queue` and
 *   `memory_meta`. All NEW tables — no existing table is touched, so the
 *   conversation history and alarms survive intact. The FTS trigger
 *   statements are copied verbatim from the Room-generated
 *   `AppDatabase_Impl.createAllTables` (Room only validates tables on
 *   open — the sync triggers MUST be created by the migration too, or the
 *   index silently desyncs on migrated installs).
 * - DOWNGRADE: pre-release schema policy — an APK rollback (sideload, QA
 *   build) previously hit Room's IllegalStateException("Can't downgrade…")
 *   on first DB open; it now wipes destructively like the v1 stance instead
 *   of crashing.
 *
 * `alarmDao()` keeps its historical name (returning the new [AlertDao])
 * because FunctionRouter — owned by another lane — constructs the scheduler
 * through it.
 */
@Database(
    entities = [
        MessageEntity::class,
        ScheduledAlertEntity::class,
        UserFactEntity::class,
        com.jarvis.assistant.cognitive.data.FactFtsEntity::class,
        ExtractionQueueEntity::class,
        MemoryMetaEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    /** Unified alert store accessor; name kept for cross-lane call-site parity. */
    abstract fun alarmDao(): AlertDao

    /** COGNITIVE_PLAN 1.1: memory core accessors. */
    abstract fun userFactDao(): com.jarvis.assistant.cognitive.data.UserFactDao

    abstract fun extractionQueueDao(): com.jarvis.assistant.cognitive.data.ExtractionQueueDao

    abstract fun memoryMetaDao(): com.jarvis.assistant.cognitive.data.MemoryMetaDao

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

        /**
         * COGNITIVE_PLAN 1.1: migration from schema v3 → v4 (memory core).
         * Creates the four cognitive tables; the `fact_fts` virtual table is
         * created together with the four Room sync triggers (exact generated
         * statements — see the class KDoc). Existing tables are untouched.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_facts` (" +
                        "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`factId` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`subject` TEXT NOT NULL, " +
                        "`predicate` TEXT NOT NULL, " +
                        "`value` TEXT NOT NULL, " +
                        "`valueNormalized` TEXT NOT NULL, " +
                        "`searchText` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, " +
                        "`origin` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`supersedesId` TEXT, " +
                        "`contested` INTEGER NOT NULL, " +
                        "`sensitive` INTEGER NOT NULL, " +
                        "`sourceMessageId` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`lastConfirmedAt` INTEGER NOT NULL, " +
                        "`lastRecalledAt` INTEGER, " +
                        "`recallCount` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_facts_factId` " +
                        "ON `user_facts` (`factId`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_facts_status` " +
                        "ON `user_facts` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_facts_category` " +
                        "ON `user_facts` (`category`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_facts_updatedAt` " +
                        "ON `user_facts` (`updatedAt`)",
                )
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS `fact_fts` USING FTS4(" +
                        "`searchText` TEXT NOT NULL, content=`user_facts`)",
                )
                // Room's external-content FTS4 sync triggers — copied verbatim
                // from the generated AppDatabase_Impl.createAllTables.
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_fact_fts_BEFORE_UPDATE " +
                        "BEFORE UPDATE ON `user_facts` BEGIN DELETE FROM `fact_fts` " +
                        "WHERE `docid`=OLD.`rowid`; END",
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_fact_fts_AFTER_UPDATE " +
                        "AFTER UPDATE ON `user_facts` BEGIN INSERT INTO `fact_fts`(`docid`, " +
                        "`searchText`) VALUES (NEW.`rowid`, NEW.`searchText`); END",
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_fact_fts_BEFORE_DELETE " +
                        "BEFORE DELETE ON `user_facts` BEGIN DELETE FROM `fact_fts` " +
                        "WHERE `docid`=OLD.`rowid`; END",
                )
                db.execSQL(
                    "CREATE TRIGGER IF NOT EXISTS room_fts_content_sync_fact_fts_AFTER_INSERT " +
                        "AFTER INSERT ON `user_facts` BEGIN INSERT INTO `fact_fts`(`docid`, " +
                        "`searchText`) VALUES (NEW.`rowid`, NEW.`searchText`); END",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `extraction_queue` (" +
                        "`messageId` INTEGER PRIMARY KEY NOT NULL, " +
                        "`attempt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`batchId` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `memory_meta` (" +
                        "`key` TEXT PRIMARY KEY NOT NULL, " +
                        "`value` TEXT NOT NULL)",
                )
            }
        }

        private val ALL_MIGRATIONS = arrayOf(MIGRATION_2_3, MIGRATION_3_4)

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
                    // B5: rollback safety — see the class KDoc.
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

package com.jarvis.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jarvis.assistant.cognitive.data.BehaviorLogEntity
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.EntityRefEntity
import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import com.jarvis.assistant.cognitive.data.FactEntityLinkEntity
import com.jarvis.assistant.cognitive.data.FactVectorEntity
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.SessionSummaryEntity
import com.jarvis.assistant.cognitive.data.UserFactEntity

/**
 * Version 2 — the single coordinated schema bump (REMEDIATION_PLAN Phase 2).
 *
 * The pre-release v1→v7 chain was previously collapsed into version 1 with the
 * full current schema and every `Migration` constant gone. Version 2 is the
 * first *deliberate* post-collapse bump and it lands the data, cognitive and
 * alarms lane schema changes TOGETHER (indices/PKs/FKs, fact decay anchors,
 * alert clock domains + `ring_sessions`) — see REMEDIATION_PLAN §2. It is
 * still destructive: pre-1.0 has no backward compatibility, so installed
 * databases from any older version are wiped on first open (accepted).
 *
 * Schema policy going forward (AGENTS.md cognitive conventions still apply):
 *  - pre-release bumps keep the destructive fallback declared below;
 *  - the first REAL data-preserving migration (the schema-freeze promise)
 *    moves from **2→3** onward: it adds an `AutoMigration`/`Migration` here,
 *    exports `app/schemas/com.jarvis.assistant.data.AppDatabase/3.json`, and
 *    gets a migration test in `androidTest/.../data/MigrationTest.kt` (a
 *    scaffold + template for exactly that already exists there);
 *  - DOWNGRADE (APK rollback / sideload / QA build): wipes destructively
 *    instead of crashing with Room's "Can't downgrade…" IllegalStateException.
 *
 * `alarmDao()` keeps its historical name (returning the unified [AlertDao])
 * because FunctionRouter — owned by another lane — constructs the scheduler
 * through it.
 */
@Database(
    entities = [
        MessageEntity::class,
        ScheduledAlertEntity::class,
        RingSessionEntity::class,
        UserFactEntity::class,
        com.jarvis.assistant.cognitive.data.FactFtsEntity::class,
        ExtractionQueueEntity::class,
        MemoryMetaEntity::class,
        CommandEventEntity::class,
        HabitRuleEntity::class,
        BehaviorLogEntity::class,
        SessionSummaryEntity::class,
        FactVectorEntity::class,
        EntityRefEntity::class,
        FactEntityLinkEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao

    /** Unified alert store accessor; name kept for cross-lane call-site parity. */
    abstract fun alarmDao(): AlertDao

    /** Durable, process-independent ring state (REMEDIATION_PLAN Phase 2). */
    abstract fun ringSessionDao(): RingSessionDao

    /** COGNITIVE_PLAN 1.1: memory core accessors. */
    abstract fun userFactDao(): com.jarvis.assistant.cognitive.data.UserFactDao

    abstract fun extractionQueueDao(): com.jarvis.assistant.cognitive.data.ExtractionQueueDao

    abstract fun memoryMetaDao(): com.jarvis.assistant.cognitive.data.MemoryMetaDao

    /** COGNITIVE_PLAN 2.1–2.5: behaviour-layer accessors. */
    abstract fun commandEventDao(): com.jarvis.assistant.cognitive.data.CommandEventDao

    abstract fun habitRuleDao(): com.jarvis.assistant.cognitive.data.HabitRuleDao

    abstract fun behaviorLogDao(): com.jarvis.assistant.cognitive.data.BehaviorLogDao

    abstract fun sessionSummaryDao(): com.jarvis.assistant.cognitive.data.SessionSummaryDao

    /** COGNITIVE_PLAN Phase 3: semantic-recall accessors. */
    abstract fun factVectorDao(): com.jarvis.assistant.cognitive.data.FactVectorDao

    abstract fun entityDao(): com.jarvis.assistant.cognitive.data.EntityDao

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
                    // Pre-release schema: any version mismatch wipes and
                    // recreates from the current entities instead of crashing.
                    // The boolean form drops EVERY table found via
                    // `sqlite_master` (not only the declared entities) and,
                    // unlike the deprecated no-arg overload, also covers
                    // downgrade — so the separate
                    // `fallbackToDestructiveMigrationOnDowngrade(true)` call
                    // is gone (REMEDIATION_PLAN Phase 2).
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

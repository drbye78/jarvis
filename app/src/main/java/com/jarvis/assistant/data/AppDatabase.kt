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
 * Version 1 — the collapsed pre-release chain (audit remediation decision #2).
 *
 * The product is pre-1.0 and no backward compatibility is kept, so the old
 * v1→v7 chain (one destructive v1→v2 step, a no-op v2→v3, the cognitive
 * table-creating v3→v4/v4→v5/v5→v6, and the v6→v7 `anchorTimeMillis` fix)
 * has been COLLAPSED: this annotation now declares the FULL current schema at
 * version 1, and every `Migration` constant is gone. Installed databases from
 * old versioned builds are wiped on first open — accepted by the owner.
 *
 * Schema policy going forward (AGENTS.md cognitive conventions still apply):
 *  - pre-release bumps may keep `fallbackToDestructiveMigration()`;
 *  - the first REAL data-preserving migration (the planned 1→2 cognitive
 *    bump path) adds an `AutoMigration`/`Migration` here, exports
 *    `app/schemas/com.jarvis.assistant.data.AppDatabase/2.json`, and gets a
 *    migration test in `androidTest/.../data/MigrationTest.kt` (a scaffold +
 *    template for exactly that already exists there);
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
    version = 1,
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
                    // Pre-release schema (decision #2): any version mismatch
                    // wipes and recreates from the current entities instead
                    // of crashing — on upgrade AND on downgrade (B5 rollback
                    // safety kept from the v7 chain).
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

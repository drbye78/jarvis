package com.jarvis.assistant.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migration harness for the COLLAPSED pre-release schema (audit
 * remediation decision #2), now at version 2 (REMEDIATION_PLAN Phase 2).
 *
 * The old suite — v2→v3 no-op preservation, v3→v4 cognitive tables + FTS
 * trigger sync, and the rest of the v1→v7 chain — was deleted together with
 * the migrations themselves: there is nothing to migrate to or from (the v2
 * bump is destructive, `fallbackToDestructiveMigration(dropAllTables = true)`).
 * What remains (this file) is:
 *
 *  1. [currentSchema_v2_createsAndRoundTrips] — asserts the exported 2.json
 *     actually creates a usable database: message + alert rows insert and
 *     read back, and the new v2 tables/columns exist. This validates the
 *     schema export the build generates at
 *     `app/schemas/com.jarvis.assistant.data.AppDatabase/2.json`.
 *  2. A marked-up TEMPLATE (commented, at the bottom) for the FIRST real
 *     data-preserving migration 2→3, ready to uncomment + adapt when the
 *     schema-freeze promise is exercised — it reuses the same
 *     [MigrationTestHelper] rule and insert helpers, so the harness is
 *     genuinely reusable rather than re-derived then.
 *
 * A fresh-install DB from Room's own builder is additionally smoke-tested in
 * [DatabaseSmokeTest]; this file is about the EXPORTED schema JSON.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB_NAME = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),                 // no migrations exist post-collapse
    )

    // ----- helpers (reused by the future 1→2 template) ----------------------

    private fun SupportSQLiteDatabase.insertMessage(
        role: String = "user",
        content: String = "Привет, Джарвис",
        createdAt: Long = 1_700_000_000_000L,
        name: String? = null,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
    ): Long {
        val sql = buildString {
            append("INSERT INTO messages (role, content, createdAt")
            val values = mutableListOf("'$role'", "'$content'", createdAt.toString())
            if (name != null) { append(", name"); values.add("'$name'") }
            if (toolCallsJson != null) { append(", toolCallsJson"); values.add("'$toolCallsJson'") }
            if (toolCallId != null) { append(", toolCallId"); values.add("'$toolCallId'") }
            append(") VALUES (")
            append(values.joinToString(", "))
            append(")")
        }
        execSQL(sql)
        // Return the auto-generated id.
        val cursor = query("SELECT last_insert_rowid()", emptyArray<Any?>())
        cursor.moveToFirst()
        val id = cursor.getLong(0)
        cursor.close()
        return id
    }

    /**
     * `anchorTimeMillis` is a BASELINE column of the v1 schema (the old
     * v6→v7 ALTER that added it to migrated installs is gone with the
     * collapsed chain), so every raw insert must name it.
     */
    private fun SupportSQLiteDatabase.insertAlert(
        kind: String = ScheduledAlertEntity.KIND_ALARM,
        label: String = "подъём",
        triggerAtMillis: Long = 1_700_000_000_000L,
        anchorTimeMillis: Long = triggerAtMillis,
        repeatDaily: Boolean = false,
        enabled: Boolean = true,
        clockDomain: String = ClockDomain.RTC,
        anchorElapsedMillis: Long = 0L,
        armedElapsedMillis: Long = 0L,
    ): Int {
        execSQL(
            "INSERT INTO scheduled_alerts (kind, label, triggerAtMillis, anchorTimeMillis, repeatDaily, enabled, " +
                "clockDomain, anchorElapsedMillis, armedElapsedMillis) " +
                "VALUES ('$kind', '$label', $triggerAtMillis, $anchorTimeMillis, ${if (repeatDaily) 1 else 0}, " +
                "${if (enabled) 1 else 0}, '$clockDomain', $anchorElapsedMillis, $armedElapsedMillis)",
        )
        val cursor = query("SELECT last_insert_rowid()", emptyArray<Any?>())
        cursor.moveToFirst()
        val id = cursor.getInt(0)
        cursor.close()
        return id
    }

    // ----- tests ------------------------------------------------------------

    /**
     * Create/open round-trip scaffold: the exported 2.json creates every
     * table and the core alert/message rows read back intact, including the
     * v2 clock-domain columns and the new `ring_sessions` table.
     */
    @Test
    fun currentSchema_v2_createsAndRoundTrips() {
        val db = helper.createDatabase(TEST_DB_NAME, version = 2)
        db.insertMessage(role = "user", content = "Какая погода?")
        db.insertMessage(role = "assistant", content = "Сегодня +15 °C", name = "get_weather")
        db.insertAlert(kind = ScheduledAlertEntity.KIND_ALARM, label = "подъём", triggerAtMillis = 1_700_003_600_000L, repeatDaily = true)
        db.insertAlert(
            kind = ScheduledAlertEntity.KIND_TIMER,
            label = "чай",
            triggerAtMillis = 1_700_000_300_000L,
            clockDomain = ClockDomain.ELAPSED,
            anchorElapsedMillis = 12_345L,
            armedElapsedMillis = 12_345L,
        )

        val msgCursor = db.query("SELECT role, content FROM messages ORDER BY id ASC", emptyArray<Any?>())
        assertEquals(2, countRows(db, "messages"))
        msgCursor.moveToFirst()
        assertEquals("user", msgCursor.getString(0))
        assertEquals("Какая погода?", msgCursor.getString(1))
        msgCursor.moveToNext()
        assertEquals("assistant", msgCursor.getString(0))
        msgCursor.close()

        assertEquals(2, countRows(db, "scheduled_alerts"))
        val alertCursor = db.query(
            "SELECT kind, label, anchorTimeMillis, clockDomain, anchorElapsedMillis, armedElapsedMillis " +
                "FROM scheduled_alerts ORDER BY id ASC",
            emptyArray<Any?>(),
        )
        alertCursor.moveToFirst()
        assertEquals(ScheduledAlertEntity.KIND_ALARM, alertCursor.getString(0))
        assertEquals("подъём", alertCursor.getString(1))
        assertEquals(1_700_003_600_000L, alertCursor.getLong(2))
        assertEquals(ClockDomain.RTC, alertCursor.getString(3))
        alertCursor.moveToNext()
        assertEquals(ClockDomain.ELAPSED, alertCursor.getString(3))
        assertEquals(12_345L, alertCursor.getLong(4))
        assertEquals(12_345L, alertCursor.getLong(5))
        alertCursor.close()

        // The collapse must be complete: the cognitive + v2 tables are part
        // of the schema.
        assertEquals(0, countRows(db, "user_facts"))
        assertEquals(0, countRows(db, "command_events"))
        assertEquals(0, countRows(db, "fact_vectors"))
        assertEquals(0, countRows(db, "ring_sessions"))

        db.close()
    }

    private fun countRows(db: SupportSQLiteDatabase, table: String): Int {
        val cursor = db.query("SELECT COUNT(*) FROM $table", emptyArray<Any?>())
        cursor.moveToFirst()
        val count = cursor.getInt(0)
        cursor.close()
        return count
    }
}

/*
 * =====================================================================================
 * TODO(next-schema-bump) — TEMPLATE for the first REAL migration (decision #2: the
 * schema-freeze promise now starts at 2→3; earlier bumps were destructive). To use:
 *
 *   1. Bump @Database(version = 3), add the migration
 *        val MIGRATION_2_3 = object : Migration(2, 3) {
 *            override fun migrate(db: SupportSQLiteDatabase) { /* DDL here */ }
 *        }
 *      to AppDatabase and register it via addMigrations(MIGRATION_2_3).
 *   2. Re-export schemas (the build regenerates app/schemas/…/3.json).
 *   3. Uncomment + adapt this test — the MigrationTestHelper rule and the
 *      insertMessage/insertAlert helpers above are already reusable:
 *
 * @Test
 * fun migrate2To3_preservesData_andValidatesAgainstExportedSchema() {
 *     var db = helper.createDatabase(TEST_DB_NAME, version = 2).apply {
 *         insertMessage(content = "важно")
 *         insertAlert(label = "подъём", repeatDaily = true)
 *     }
 *     db.close()
 *
 *     db = helper.runMigrationsAndValidate(
 *         TEST_DB_NAME,
 *         version = 3,
 *         validateDroppedTables = true,   // throws if the migration silently drops a table
 *         AppDatabase.MIGRATION_2_3,
 *     )
 *
 *     assertEquals(1, countRows(db, "messages"))
 *     assertEquals(1, countRows(db, "scheduled_alerts"))
 *     // …assert the NEW columns/tables the migration introduced…
 *     db.close()
 * }
 * =====================================================================================
 */

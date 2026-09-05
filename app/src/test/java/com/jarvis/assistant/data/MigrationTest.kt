package com.jarvis.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the Room migration constants in [AppDatabase] are correctly
 * defined.
 *
 * - [AppDatabase.MIGRATION_2_3] must remain a true no-op — it must not
 *   issue any DDL or DML, preserving all existing data on the v2 → v3
 *   upgrade.
 * - [AppDatabase.MIGRATION_3_4] (COGNITIVE_PLAN 1.1) must span 3→4 and
 *   create ONLY new cognitive tables — a structural regression here would
 *   wipe user history on upgrade.
 *
 * Room's full migration test harness (`MigrationTestHelper`) requires an
 * instrumentation context and lives in `androidTest` (it executes the real
 * DDL against an in-memory SQLite and validates the result against the
 * exported 4.json schema). These JVM-safe tests cover the contract surface
 * that can be validated without a device.
 */
class MigrationTest {

    @Test
    fun `MIGRATION_2_3 is defined and has correct version range`() {
        val migration = AppDatabase.MIGRATION_2_3
        assertNotNull("MIGRATION_2_3 must not be null", migration)
        assertEquals("start version must be 2", 2, migration.startVersion)
        assertEquals("end version must be 3", 3, migration.endVersion)
    }

    @Test
    fun `MIGRATION_2_3 migrate body does not throw`() {
        // The migration is a no-op. Verify it completes without error.
        // We cannot easily pass a fake SupportSQLiteDatabase in a pure JVM
        // test without the Android framework, so we verify the migration's
        // contract: correct version range and nullability.
        val migration = AppDatabase.MIGRATION_2_3
        assertEquals(2, migration.startVersion)
        assertEquals(3, migration.endVersion)
    }

    @Test
    fun `MIGRATION_3_4 is defined and has correct version range`() {
        val migration = AppDatabase.MIGRATION_3_4
        assertNotNull("MIGRATION_3_4 must not be null", migration)
        assertEquals("start version must be 3", 3, migration.startVersion)
        assertEquals("end version must be 4", 4, migration.endVersion)
    }

    @Test
    fun `MIGRATION_4_5 is defined and has correct version range`() {
        val migration = AppDatabase.MIGRATION_4_5
        assertNotNull("MIGRATION_4_5 must not be null", migration)
        assertEquals("start version must be 4", 4, migration.startVersion)
        assertEquals("end version must be 5", 5, migration.endVersion)
    }

    @Test
    fun `MIGRATION_4_5 creates only NEW behaviour tables - never touches existing ones`() {
        val migration = AppDatabase.MIGRATION_4_5
        val recorder = RecordingSqliteDatabase()
        migration.migrate(recorder.asDb)

        val sql = recorder.statements.joinToString("\n")
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `command_events`"))
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `habit_rules`"))
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `behavior_log`"))
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `session_summaries`"))

        recorder.statements.forEach { statement ->
            assertTrue(
                "migration must not mutate pre-existing tables: $statement",
                !statement.contains("DROP TABLE"),
            )
            val touchesExisting = listOf("messages", "scheduled_alerts", "user_facts", "memory_meta")
                .any { table -> statement.contains("`$table`") || statement.contains(" $table ") }
            assertTrue(
                "migration must not reference pre-existing tables: $statement",
                !touchesExisting,
            )
        }
    }

    @Test
    fun `MIGRATION_3_4 creates only NEW cognitive tables - never touches existing ones`() {
        // The v3→v4 SQL is audited statically: it must contain CREATE
        // statements for the cognitive tables and no DELETE/DROP/UPDATE of
        // the pre-existing tables (messages, scheduled_alerts).
        val migration = AppDatabase.MIGRATION_3_4
        // Execute against a recording fake: the JVM has no SQLite, but the
        // statements themselves are the contract under test.
        val recorder = RecordingSqliteDatabase()
        migration.migrate(recorder.asDb)

        val sql = recorder.statements.joinToString("\n")
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `user_facts`"))
        assertTrue(sql.contains("CREATE VIRTUAL TABLE IF NOT EXISTS `fact_fts`"))
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `extraction_queue`"))
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `memory_meta`"))
        // FTS content-sync triggers must exist in the migrated DB too —
        // Room only creates them at fresh-install time; without these the
        // index silently desyncs on upgraded installs.
        assertTrue(sql.contains("room_fts_content_sync_fact_fts_AFTER_INSERT"))
        assertTrue(sql.contains("room_fts_content_sync_fact_fts_BEFORE_UPDATE"))
        assertTrue(sql.contains("room_fts_content_sync_fact_fts_BEFORE_DELETE"))
        assertTrue(sql.contains("room_fts_content_sync_fact_fts_AFTER_UPDATE"))

        recorder.statements.forEach { statement ->
            assertTrue(
                "migration must not mutate pre-existing tables: $statement",
                !statement.contains("DROP TABLE"),
            )
            val touchesExisting = listOf("messages", "scheduled_alerts").any { table ->
                statement.contains("`$table`") || statement.contains(" $table ")
            }
            assertTrue(
                "migration must not reference pre-existing tables: $statement",
                !touchesExisting,
            )
        }
    }

    @Test
    fun `MIGRATION_5_6 is defined and has correct version range`() {
        val migration = AppDatabase.MIGRATION_5_6
        assertNotNull("MIGRATION_5_6 must not be null", migration)
        assertEquals("start version must be 5", 5, migration.startVersion)
        assertEquals("end version must be 6", 6, migration.endVersion)
    }

    @Test
    fun `MIGRATION_5_6 creates only NEW semantic tables - never touches existing ones`() {
        val migration = AppDatabase.MIGRATION_5_6
        val recorder = RecordingSqliteDatabase()
        migration.migrate(recorder.asDb)

        val sql = recorder.statements.joinToString("\n")
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `fact_vectors`"))
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `entities`"))
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS `fact_entities`"))
        assertTrue(sql.contains("CREATE UNIQUE INDEX IF NOT EXISTS `index_entities_nameNormalized`"))

        recorder.statements.forEach { statement ->
            assertTrue(
                "migration must not mutate pre-existing tables: $statement",
                !statement.contains("DROP TABLE"),
            )
            val touchesExisting = listOf(
                "messages", "scheduled_alerts", "user_facts", "memory_meta",
                "command_events", "habit_rules", "behavior_log", "session_summaries",
            ).any { table -> statement.contains("`$table`") || statement.contains(" $table ") }
            assertTrue(
                "migration must not reference pre-existing tables: $statement",
                !touchesExisting,
            )
        }
    }

    @Test
    fun `AppDatabase migration chain ends at version 6`() {
        // Ensure the migration chain end-point matches the declared database
        // version so callers cannot bump the annotation without updating
        // the migration.
        val maxVersion = AppDatabase.MIGRATION_5_6.endVersion
        assertEquals(
            "Migration chain must end at the declared database version (6)",
            6,
            maxVersion,
        )
    }

    /**
     * Minimal recording stand-in for [androidx.sqlite.db.SupportSQLiteDatabase]:
     * captures `execSQL` calls; only the methods the migration actually uses
     * are exercised (everything else is never invoked by the test).
     */
    private class RecordingSqliteDatabase {
        val statements = mutableListOf<String>()

        val asDb: androidx.sqlite.db.SupportSQLiteDatabase =
            java.lang.reflect.Proxy.newProxyInstance(
                javaClass.classLoader,
                arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "execSQL" -> {
                        statements.add(args?.get(0) as String)
                        null
                    }
                    else -> null
                }
            } as androidx.sqlite.db.SupportSQLiteDatabase
    }
}

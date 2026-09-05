package com.jarvis.assistant.data

import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the v2 → v3 migration preserves all user data.
 *
 * The v2 schema has `messages` and `scheduled_alerts` tables. v3 is schema-
 * identical; the migration is a no-op whose sole purpose is to prevent
 * `fallbackToDestructiveMigration()` from silently wiping the database.
 *
 * This test:
 *  1. Creates a v2 database with sample rows in both tables.
 *  2. Runs migration to v3 via [AppDatabase.MIGRATION_2_3].
 *  3. Validates that every inserted row survived intact.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val TEST_DB_NAME = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),                 // no auto-migrations
    )

    // ----- helpers ----------------------------------------------------------

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

    private fun SupportSQLiteDatabase.insertAlert(
        kind: String = ScheduledAlertEntity.KIND_ALARM,
        label: String = "подъём",
        triggerAtMillis: Long = 1_700_000_000_000L,
        repeatDaily: Boolean = false,
        enabled: Boolean = true,
    ): Int {
        execSQL(
            "INSERT INTO scheduled_alerts (kind, label, triggerAtMillis, repeatDaily, enabled) " +
                "VALUES ('$kind', '$label', $triggerAtMillis, ${if (repeatDaily) 1 else 0}, ${if (enabled) 1 else 0})",
        )
        val cursor = query("SELECT last_insert_rowid()", emptyArray<Any?>())
        cursor.moveToFirst()
        val id = cursor.getInt(0)
        cursor.close()
        return id
    }

    // ----- tests ------------------------------------------------------------

    @Test
    fun migrate2To3_preservesMessagesAndAlerts() {
        // 1. Create a v2 database with data.
        var db = helper.createDatabase(TEST_DB_NAME, version = 2).apply {
            insertMessage(role = "user", content = "Какая погода?")
            insertMessage(role = "assistant", content = "Сегодня +15 °C")
            insertAlert(kind = ScheduledAlertEntity.KIND_ALARM, label = "подъём", triggerAtMillis = 1_700_003_600_000L, repeatDaily = true)
            insertAlert(kind = ScheduledAlertEntity.KIND_TIMER, label = "чай", triggerAtMillis = 1_700_000_300_000L)
        }
        db.close()

        // 2. Run the migration to v3.
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            version = 3,
            validateDroppedTables = true,
            AppDatabase.MIGRATION_2_3,
        )

        // 3. Verify messages survived.
        val msgCursor = db.query("SELECT COUNT(*) FROM messages", emptyArray<Any?>())
        msgCursor.moveToFirst()
        assertEquals("messages row count", 2, msgCursor.getInt(0))
        msgCursor.close()

        // Verify message content round-trips.
        val msgContent = db.query("SELECT role, content FROM messages ORDER BY id ASC", emptyArray<Any?>())
        msgContent.moveToFirst()
        assertEquals("user", msgContent.getString(0))
        assertEquals("Какая погода?", msgContent.getString(1))
        msgContent.moveToNext()
        assertEquals("assistant", msgContent.getString(0))
        assertEquals("Сегодня +15 °C", msgContent.getString(1))
        msgContent.close()

        // 4. Verify alerts survived.
        val alertCursor = db.query("SELECT COUNT(*) FROM scheduled_alerts", emptyArray<Any?>())
        alertCursor.moveToFirst()
        assertEquals("scheduled_alerts row count", 2, alertCursor.getInt(0))
        alertCursor.close()

        val alertDetail = db.query("SELECT kind, label, repeatDaily FROM scheduled_alerts ORDER BY id ASC", emptyArray<Any?>())
        alertDetail.moveToFirst()
        assertEquals(ScheduledAlertEntity.KIND_ALARM, alertDetail.getString(0))
        assertEquals("подъём", alertDetail.getString(1))
        assertEquals(1, alertDetail.getInt(2))  // repeatDaily
        alertDetail.moveToNext()
        assertEquals(ScheduledAlertEntity.KIND_TIMER, alertDetail.getString(0))
        assertEquals("чай", alertDetail.getString(1))
        assertEquals(0, alertDetail.getInt(2))  // repeatDaily
        alertDetail.close()

        db.close()
    }

    @Test
    fun migration2To3_noOp_doesNotDropTables() {
        // Even with no rows, the migration must not destroy the schema.
        var db = helper.createDatabase(TEST_DB_NAME, version = 2)
        db.close()

        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            version = 3,
            validateDroppedTables = true,   // would throw if any table was dropped
            AppDatabase.MIGRATION_2_3,
        )

        // Both tables should still exist and be queryable.
        val msgCursor = db.query("SELECT COUNT(*) FROM messages", emptyArray<Any?>())
        msgCursor.moveToFirst()
        assertEquals(0, msgCursor.getInt(0))
        msgCursor.close()

        val alertCursor = db.query("SELECT COUNT(*) FROM scheduled_alerts", emptyArray<Any?>())
        alertCursor.moveToFirst()
        assertEquals(0, alertCursor.getInt(0))
        alertCursor.close()

        db.close()
    }

    @Test
    fun migration2To3_preservesFullMessageColumns() {
        // Verify that nullable columns (name, toolCallsJson, toolCallId) survive.
        var db = helper.createDatabase(TEST_DB_NAME, version = 2).apply {
            insertMessage(
                role = "assistant",
                content = "Вызов инструмента",
                name = "get_weather",
                toolCallsJson = """[{"id":"call_1","function":{"name":"get_weather"}}]""",
                toolCallId = "call_1",
            )
        }
        db.close()

        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            version = 3,
            validateDroppedTables = true,
            AppDatabase.MIGRATION_2_3,
        )

        val cursor = db.query("SELECT name, toolCallsJson, toolCallId FROM messages WHERE id = 1", emptyArray<Any?>())
        cursor.moveToFirst()
        assertEquals("get_weather", cursor.getString(0))
        assertTrue(cursor.getString(1).contains("get_weather"))
        assertEquals("call_1", cursor.getString(2))
        cursor.close()

        db.close()
    }

    // ------------------------------------------------------------------
    // COGNITIVE_PLAN 1.1: v3 → v4 (memory core). The migration must create
    // the four cognitive tables WITHOUT touching messages/scheduled_alerts,
    // and the FTS index must be queryable + trigger-synced on migrated
    // installs (Room only creates the sync triggers at fresh-install time).
    // ------------------------------------------------------------------

    @Test
    fun migrate3To4_createsCognitiveTables_andPreservesExistingData() {
        // 1. Create a v3 database with data in both pre-existing tables.
        var db = helper.createDatabase(TEST_DB_NAME, version = 3).apply {
            insertMessage(role = "user", content = "меня зовут Алексей")
            insertAlert(kind = ScheduledAlertEntity.KIND_ALARM, label = "подъём", triggerAtMillis = 1_700_003_600_000L)
        }
        db.close()

        // 2. Migrate to v4 with full schema validation against 4.json.
        db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            version = 4,
            validateDroppedTables = true,
            AppDatabase.MIGRATION_3_4,
        )

        // 3. Pre-existing rows survive untouched.
        val msgCursor = db.query("SELECT content FROM messages", emptyArray<Any?>())
        msgCursor.moveToFirst()
        assertEquals("меня зовут Алексей", msgCursor.getString(0))
        msgCursor.close()

        val alertCursor = db.query("SELECT COUNT(*) FROM scheduled_alerts", emptyArray<Any?>())
        alertCursor.moveToFirst()
        assertEquals(1, alertCursor.getInt(0))
        alertCursor.close()

        // 4. The FTS index works on the migrated DB and is kept in sync by
        // the triggers (insert → search → update → stale value gone).
        db.execSQL(
            "INSERT INTO user_facts (factId, category, subject, predicate, value, valueNormalized, " +
                "searchText, confidence, origin, status, supersedesId, contested, sensitive, " +
                "sourceMessageId, createdAt, updatedAt, lastConfirmedAt, lastRecalledAt, recallCount) " +
                "VALUES ('f-1', 'PREFERENCE', 'user', 'likes', 'фильмы Тарковского', 'фильмы тарковского', " +
                "'user фильм тарковск preference', 0.9, 'EXPLICIT', 'ACTIVE', NULL, 0, 0, NULL, 1, 1, 1, NULL, 0)",
        )
        val ftsCursor = db.query(
            "SELECT value FROM user_facts JOIN fact_fts ON user_facts.rowId = fact_fts.rowid " +
                "WHERE fact_fts MATCH 'тарковск*'",
            emptyArray<Any?>(),
        )
        assertTrue("FTS match must hit on a migrated install", ftsCursor.moveToFirst())
        assertEquals("фильмы Тарковского", ftsCursor.getString(0))
        ftsCursor.close()

        // Trigger sync check: delete the fact → the FTS row must disappear too.
        db.execSQL("DELETE FROM user_facts WHERE factId = 'f-1'")
        val ftsAfter = db.query("SELECT COUNT(*) FROM fact_fts WHERE fact_fts MATCH 'тарковск*'", emptyArray<Any?>())
        ftsAfter.moveToFirst()
        assertEquals("FTS sync trigger must keep the index consistent", 0, ftsAfter.getInt(0))
        ftsAfter.close()

        // Queue + meta tables are usable.
        db.execSQL("INSERT INTO extraction_queue (messageId, attempt, state, batchId, createdAt, updatedAt) VALUES (1, 0, 'PENDING', NULL, 1, 1)")
        val qCursor = db.query("SELECT state FROM extraction_queue WHERE messageId = 1", emptyArray<Any?>())
        qCursor.moveToFirst()
        assertEquals("PENDING", qCursor.getString(0))
        qCursor.close()

        db.execSQL("INSERT INTO memory_meta (`key`, value) VALUES ('schemaRev', '4')")
        val metaCursor = db.query("SELECT value FROM memory_meta WHERE `key` = 'schemaRev'", emptyArray<Any?>())
        metaCursor.moveToFirst()
        assertEquals("4", metaCursor.getString(0))
        metaCursor.close()

        db.close()
    }

    @Test
    fun migration3To4_noData_stillValidates() {
        helper.createDatabase(TEST_DB_NAME, version = 3).close()
        val db = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            version = 4,
            validateDroppedTables = true,
            AppDatabase.MIGRATION_3_4,
        )
        db.close()
    }
}

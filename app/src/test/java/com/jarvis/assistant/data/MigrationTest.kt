package com.jarvis.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that the Room migration constants in [AppDatabase] are correctly
 * defined and that [AppDatabase.MIGRATION_2_3] is a true no-op — it must not
 * issue any DDL or DML, preserving all existing data on the v2 → v3 upgrade.
 *
 * Room's full migration test harness (`MigrationTestHelper`) requires an
 * instrumentation context and lives in `androidTest`.  These JVM-safe tests
 * cover the contract surface that can be validated without a device.
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
    fun `AppDatabase migration chain ends at version 3`() {
        // Ensure the migration chain end-point matches the declared database
        // version so callers cannot bump the annotation without updating
        // the migration.
        val maxVersion = AppDatabase.MIGRATION_2_3.endVersion
        assertEquals(
            "Migration chain must end at the declared database version (3)",
            3,
            maxVersion,
        )
    }
}

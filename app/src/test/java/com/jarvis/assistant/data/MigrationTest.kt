package com.jarvis.assistant.data

import androidx.room.migration.Migration
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit remediation decision #2 collapsed the pre-release Room chain:
 * `AppDatabase` was version 1 with the full schema, and after the Phase 2
 * coordinated bump it is version 2; every `Migration` object (2→3 … 6→7) is
 * deleted — old installs wipe destructively
 * (`fallbackToDestructiveMigration(dropAllTables = true)`), which makes the
 * old SQL-string assertions (and the vacuous "MIGRATION_2_3 migrate body does
 * not throw" test — it asserted the version range it had just read back) moot.
 * The recording [androidx.sqlite.db.SupportSQLiteDatabase] fake went with
 * them; real migration tests belong to
 * `androidTest/.../data/MigrationTest.kt` once the real data-preserving
 * migration exists (a runnable template lives there).
 *
 * What stays pinned here is the *collapse itself*: re-introducing any
 * Migration constant on AppDatabase/its companion is an explicit act that
 * must pass this gate on purpose (and ship the matching androidTest + exported
 * schema), not an accident of copy-paste.
 */
class MigrationTest {

    @Test
    fun `collapsed chain - AppDatabase exposes no Room Migration members`() {
        // Scan AppDatabase and its nested classes (the old companion lived
        // there) — not Room framework superclasses, whose internals are none
        // of this guard's business.
        val targets = generateSequence<Class<*>>(AppDatabase::class.java) { it.superclass }
            .take(1)
            .flatMap { db -> (sequenceOf(db) + db.declaredClasses.asSequence()) }
        val stray = targets
            .flatMap { clazz ->
                clazz.declaredFields.asSequence().map { f -> "field ${clazz.simpleName}.${f.name}" to f.type } +
                    clazz.declaredMethods.asSequence().map { m -> "method ${clazz.simpleName}.${m.name}" to m.returnType } +
                    clazz.declaredConstructors.asSequence()
                        .flatMap { c -> c.parameterTypes.asSequence().map { p -> "ctor param ${clazz.simpleName}.${p.simpleName}" to p } }
            }
            .filter { (_, type) -> Migration::class.java.isAssignableFrom(type) }
            .map { it.first }
            .toList()
        assertTrue(
            "The pre-release chain was collapsed (decision #2): AppDatabase must not carry any " +
                "androidx.room.migration.Migration member. If a real 1→N migration is being added, " +
                "update this guard deliberately and add the androidTest MigrationTest case + exported schema.\nFound: $stray",
            stray.isEmpty(),
        )
    }
}

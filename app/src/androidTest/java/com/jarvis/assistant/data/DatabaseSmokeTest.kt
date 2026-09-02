package com.jarvis.assistant.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-tests that the Room database can be created from the current schema
 * and that a basic MessageEntity insert + query round-trip succeeds.
 *
 * Uses an in-memory database so no persistent state is left on the device.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MessageDao

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.messageDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndQuery_roundTrip() = runBlocking {
        val entity = MessageEntity(
            role = "user",
            content = "Привет, Джарвис",
            createdAt = 1_700_000_000_000L,
        )

        val id = dao.insert(entity)
        assertTrue("inserted id must be > 0", id > 0)

        val all = dao.all()
        assertEquals("should have exactly 1 message", 1, all.size)

        val retrieved = all.first()
        assertEquals(id, retrieved.id)
        assertEquals("user", retrieved.role)
        assertEquals("Привет, Джарвис", retrieved.content)
        assertEquals(1_700_000_000_000L, retrieved.createdAt)
    }

    @Test
    fun nullableColumns_defaultToNull() = runBlocking {
        val entity = MessageEntity(
            role = "assistant",
            content = "Ответ",
            // name, toolCallsJson, toolCallId are all null by default
        )

        val id = dao.insert(entity)
        val all = dao.all()
        assertEquals(1, all.size)

        val retrieved = all.first()
        assertEquals(id, retrieved.id)
        assertEquals("assistant", retrieved.role)
        assertEquals("Ответ", retrieved.content)
        assertEquals(null, retrieved.name)
        assertEquals(null, retrieved.toolCallsJson)
        assertEquals(null, retrieved.toolCallId)
    }

    @Test
    fun clear_removesAllRows() = runBlocking {
        dao.insert(MessageEntity(role = "user", content = "one"))
        dao.insert(MessageEntity(role = "assistant", content = "two"))
        assertEquals(2, dao.all().size)

        dao.clear()
        assertEquals(0, dao.all().size)
    }
}

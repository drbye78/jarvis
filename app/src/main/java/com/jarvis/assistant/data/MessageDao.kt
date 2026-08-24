package com.jarvis.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(e: MessageEntity)

    @Query("SELECT * FROM messages ORDER BY createdAt ASC")
    suspend fun all(): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAt DESC LIMIT :n")
    suspend fun recentDesc(n: Int): List<MessageEntity>

    @Query(
        "DELETE FROM messages WHERE id NOT IN (" +
            "SELECT id FROM messages ORDER BY createdAt DESC LIMIT :n" +
        ")"
    )
    suspend fun trimTo(n: Int)

    @Query("DELETE FROM messages")
    suspend fun clear()
}

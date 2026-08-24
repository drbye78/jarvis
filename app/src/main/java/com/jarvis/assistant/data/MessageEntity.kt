package com.jarvis.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)

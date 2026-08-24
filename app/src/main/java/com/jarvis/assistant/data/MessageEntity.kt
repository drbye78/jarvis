package com.jarvis.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(name = "name")
    val name: String? = null,
    @androidx.room.ColumnInfo(name = "tool_calls_json")
    val toolCallsJson: String? = null,
    @androidx.room.ColumnInfo(name = "tool_call_id")
    val toolCallId: String? = null
)
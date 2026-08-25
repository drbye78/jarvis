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
    val name: String? = null,
    val toolCallsJson: String? = null,
    val toolCallId: String? = null,
)

/**
 * A scheduled alarm. `id` doubles as the AlarmManager request code so cancel
 * is always exact (the original hashed label+time, which collided).
 */
@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean = true,
    val repeatDaily: Boolean = true,
    val triggerMillis: Long,
)

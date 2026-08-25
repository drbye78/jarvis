package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.util.JsonOut
import kotlinx.serialization.json.jsonObject

/**
 * LLM tools for alarms and timers. All four are real implementations backed
 * by [AndroidAlarmScheduler]; the original `setAlarm` was a facade over a
 * receiver that only logged.
 */
class SetAlarmTool(private val scheduler: AndroidAlarmScheduler) : ToolContract {
    override val name = "setAlarm"
    override val description =
        "Set an alarm for a specific time of day (HH:mm, 24-hour). Use for wake-up alarms and reminders tied to a clock time."
    override val parametersJson = schema(
        mapOf(
            "time" to """{"type":"string","description":"Time in HH:mm format, e.g. 07:30"}""",
            "label" to """{"type":"string","description":"Optional alarm label, e.g. 'подъём'"}""",
            "repeat_daily" to """{"type":"boolean","description":"Repeat every day (default true)"}""",
        ),
        required = listOf("time"),
    )

    override suspend fun execute(arguments: String): String {
        val obj = ToolArgs.parse(arguments)
            ?: return JsonOut.error("Invalid JSON arguments")
        val timeStr = obj.string("time")
            ?: return JsonOut.error("Missing required parameter: time")
        val (hour, minute) = AlarmTimes.parseTime(timeStr)
            ?: return JsonOut.error("Invalid time format. Use HH:mm (00:00–23:59)")
        val label = obj.string("label")?.takeIf { it.isNotBlank() } ?: "Будильник"
        val repeatDaily = obj.bool("repeat_daily") ?: true
        val entity = scheduler.schedule(label, hour, minute, repeatDaily)
        return JsonOut.obj(
            "status" to "scheduled",
            "time" to timeStr,
            "label" to label,
            "repeat_daily" to repeatDaily,
            "alarm_id" to entity.id,
        )
    }
}

class CancelAlarmTool(private val context: Context, private val scheduler: AndroidAlarmScheduler) : ToolContract {
    override val name = "cancelAlarm"
    override val description =
        "Cancel an alarm by its label, or cancel all alarms. Use after listing alarms if the user asks to remove one."
    override val parametersJson = schema(
        mapOf(
            "label" to """{"type":"string","description":"Label of the alarm to cancel"}""",
            "all" to """{"type":"boolean","description":"Cancel every alarm (default false)"}""",
        ),
    )

    override suspend fun execute(arguments: String): String {
        val obj = ToolArgs.parse(arguments) ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val dao = com.jarvis.assistant.data.AppDatabase.getInstance(context).alarmDao()
        if (obj.bool("all") == true) {
            val alarms = dao.enabled()
            alarms.forEach { scheduler.cancel(it.id) }
            return JsonOut.obj("status" to "cancelled", "count" to alarms.size)
        }
        val label = obj.string("label")?.trim()?.lowercase()
            ?: return JsonOut.error("Provide a label or all=true")
        val alarms = dao.enabled()
        val match = alarms.filter { it.label.lowercase().contains(label) }
        if (match.isEmpty()) return JsonOut.error("No alarm matching '$label'")
        match.forEach { scheduler.cancel(it.id) }
        return JsonOut.obj("status" to "cancelled", "count" to match.size)
    }
}

class ListAlarmsTool(private val context: Context) : ToolContract {
    override val name = "listAlarms"
    override val description = "List all alarms currently set, with times and labels."
    override val parametersJson = schema(emptyMap())

    override suspend fun execute(arguments: String): String {
        val dao = com.jarvis.assistant.data.AppDatabase.getInstance(context).alarmDao()
        val alarms = dao.enabled()
        if (alarms.isEmpty()) return JsonOut.obj("alarms" to "[]")
        val items = alarms.map { a ->
            kotlinx.serialization.json.buildJsonObject {
                put("label", kotlinx.serialization.json.JsonPrimitive(a.label))
                put("time", kotlinx.serialization.json.JsonPrimitive("%02d:%02d".format(a.hour, a.minute)))
                put("repeat_daily", kotlinx.serialization.json.JsonPrimitive(a.repeatDaily))
            }
        }
        return JsonOut.list(items)
    }
}

class SetTimerTool(private val scheduler: AndroidAlarmScheduler) : ToolContract {
    override val name = "setTimer"
    override val description =
        "Start a countdown timer. Use for 'напомни через 10 минут', 'таймер на 5 минут' and similar. NOT for clock-time alarms."
    override val parametersJson = schema(
        mapOf(
            "minutes" to """{"type":"integer","description":"Duration in minutes"}""",
            "seconds" to """{"type":"integer","description":"Optional additional seconds"}""",
            "label" to """{"type":"string","description":"What the timer is for"}""",
        ),
        required = listOf("minutes"),
    )

    override suspend fun execute(arguments: String): String {
        val obj = ToolArgs.parse(arguments)
            ?: return JsonOut.error("Invalid JSON arguments")
        val minutes = obj.int("minutes")
            ?: return JsonOut.error("Missing required parameter: minutes")
        if (minutes !in 1..24 * 60) return JsonOut.error("minutes must be between 1 and 1440")
        val seconds = obj.int("seconds")?.coerceIn(0, 59) ?: 0
        val label = obj.string("label")?.takeIf { it.isNotBlank() } ?: "Таймер"
        val delayMs = (minutes * 60L + seconds) * 1000L
        scheduler.scheduleTimer(label, delayMs)
        return JsonOut.obj(
            "status" to "started",
            "minutes" to minutes,
            "seconds" to seconds,
            "label" to label,
        )
    }
}

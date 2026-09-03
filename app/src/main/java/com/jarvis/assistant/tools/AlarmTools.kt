package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.util.JsonOut
import java.util.Calendar
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Pure renderer for the listAlarms tool output — JVM-testable (m16: the voice
 * listing must show ALL alerts, including disabled ones, with a status field).
 */
object AlertListRenderer {

    fun render(alerts: List<ScheduledAlertEntity>): String {
        val items = alerts.map { alert ->
            val cal = Calendar.getInstance().apply { timeInMillis = alert.triggerAtMillis }
            buildJsonObject {
                put("id", JsonPrimitive(alert.id))
                put("kind", JsonPrimitive(alert.kind))
                put("label", JsonPrimitive(alert.label))
                put("time", JsonPrimitive("%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))))
                put("repeat_daily", JsonPrimitive(alert.repeatDaily))
                put("enabled", JsonPrimitive(alert.enabled))
                put("status", JsonPrimitive(if (alert.enabled) "enabled" else "disabled"))
            }
        }
        return JsonOut.list(items)
    }
}

/**
 * LLM tools for alarms and timers, all backed by the unified scheduled-alert
 * store through [AndroidAlarmScheduler] (PLAN.md §3.3).
 */
class SetAlarmTool(
    private val scheduler: AndroidAlarmScheduler,
    /** F6: locale-aware default label; FunctionRouter passes the
     *  `default_alarm_label` string resource (the values-en translation
     *  existed but was never referenced). Default keeps the RU literal for
     *  JVM tests. */
    private val defaultLabel: () -> String = { "Будильник" },
) : ToolContract {
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
        val label = obj.string("label")?.takeIf { it.isNotBlank() } ?: defaultLabel()
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

/**
 * Cancels ALARM-kind rows by label (or all). Matches enabled AND disabled
 * rows so leftovers from fired one-shots can be cleaned up by voice.
 * Timers are handled by [CancelTimerTool].
 */
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
        val alarms = alerts(context, ScheduledAlertEntity.KIND_ALARM)
        if (obj.bool("all") == true) {
            alarms.forEach { scheduler.cancel(it.id) }
            return JsonOut.obj("status" to "cancelled", "count" to alarms.size)
        }
        val label = obj.string("label")?.trim()?.lowercase()
            ?: return JsonOut.error("Provide a label or all=true")
        val match = alarms.filter { it.label.lowercase().contains(label) }
        if (match.isEmpty()) return JsonOut.error("No alarm matching '$label'")
        match.forEach { scheduler.cancel(it.id) }
        return JsonOut.obj("status" to "cancelled", "count" to match.size)
    }
}

/**
 * Cancels TIMER-kind rows by label (or all), symmetric to [CancelAlarmTool].
 */
class CancelTimerTool(private val context: Context, private val scheduler: AndroidAlarmScheduler) : ToolContract {
    override val name = "cancelTimer"
    override val description =
        "Cancel a countdown timer by its label, or cancel all timers."
    override val parametersJson = schema(
        mapOf(
            "label" to """{"type":"string","description":"Label of the timer to cancel"}""",
            "all" to """{"type":"boolean","description":"Cancel every timer (default false)"}""",
        ),
    )

    override suspend fun execute(arguments: String): String {
        val obj = ToolArgs.parse(arguments) ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val timers = alerts(context, ScheduledAlertEntity.KIND_TIMER)
        if (obj.bool("all") == true) {
            timers.forEach { scheduler.cancel(it.id) }
            return JsonOut.obj("status" to "cancelled", "count" to timers.size)
        }
        val label = obj.string("label")?.trim()?.lowercase()
            ?: return JsonOut.error("Provide a label or all=true")
        val match = timers.filter { it.label.lowercase().contains(label) }
        if (match.isEmpty()) return JsonOut.error("No timer matching '$label'")
        match.forEach { scheduler.cancel(it.id) }
        return JsonOut.obj("status" to "cancelled", "count" to match.size)
    }
}

/** m16: lists ALL alerts (alarms + timers, enabled AND disabled) with status. */
class ListAlarmsTool(private val context: Context) : ToolContract {
    override val name = "listAlarms"
    override val description = "List all alarms and timers currently set, with times, labels and enabled/disabled status."
    override val parametersJson = schema(emptyMap())

    override suspend fun execute(arguments: String): String =
        AlertListRenderer.render(allAlerts(context))
}

private suspend fun allAlerts(context: Context): List<ScheduledAlertEntity> =
    AppDatabase.getInstance(context).alarmDao().all()

private suspend fun alerts(context: Context, kind: String): List<ScheduledAlertEntity> =
    allAlerts(context).filter { it.kind == kind }

class SetTimerTool(
    private val scheduler: AndroidAlarmScheduler,
    /** F6: locale-aware default label (see SetAlarmTool). */
    private val defaultLabel: () -> String = { "Таймер" },
) : ToolContract {
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
        val label = obj.string("label")?.takeIf { it.isNotBlank() } ?: defaultLabel()
        val delayMs = (minutes * 60L + seconds) * 1000L
        // Persisted as a scheduled_alerts row + armed via the scheduler, so it
        // survives reboot and has a collision-free request code (M9/S3).
        val entity = scheduler.scheduleTimer(label, delayMs)
        return JsonOut.obj(
            "status" to "started",
            "minutes" to minutes,
            "seconds" to seconds,
            "label" to label,
            "timer_id" to entity.id,
        )
    }
}

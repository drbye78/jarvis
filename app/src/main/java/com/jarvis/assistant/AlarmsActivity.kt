package com.jarvis.assistant

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.data.AppDatabase
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Alarm management UI: live card list with enable switches and delete, an
 * empty state, and a time-picker dialog for adding alarms. Replaces the
 * v2.x situation where alarms could only be created by voice and were
 * invisible.
 */
class AlarmsActivity : AppCompatActivity() {

    private lateinit var adapter: AlarmListAdapter
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alarms)

        adapter = AlarmListAdapter(
            onToggle = { alarm, enabled -> toggle(alarm, enabled) },
            onDelete = { alarm -> delete(alarm) },
        )
        findViewById<RecyclerView>(R.id.alarmList).apply {
            layoutManager = LinearLayoutManager(this@AlarmsActivity)
            adapter = this@AlarmsActivity.adapter
        }

        emptyView = findViewById(R.id.emptyAlarms)
        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<Button>(R.id.addAlarmButton).setOnClickListener { showAddDialog() }

        lifecycleScope.launch {
            AppDatabase.getInstance(this@AlarmsActivity).alarmDao().alarmsLive()
                .collectLatest {
                    adapter.submit(it)
                    emptyView.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                }
        }
    }

    private fun toggle(alarm: ScheduledAlertEntity, enabled: Boolean) {
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@AlarmsActivity).alarmDao()
            AndroidAlarmScheduler(this@AlarmsActivity, dao).setEnabled(alarm.id, enabled)
        }
    }

    private fun delete(alarm: ScheduledAlertEntity) {
        lifecycleScope.launch {
            val dao = AppDatabase.getInstance(this@AlarmsActivity).alarmDao()
            AndroidAlarmScheduler(this@AlarmsActivity, dao).cancel(alarm.id)
        }
    }

    private fun showAddDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.alarm_label_hint)
            setPadding(48, 32, 48, 32)
        }
        TimePickerDialog(this, { _, hour, minute ->
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.alarm_label_title, hour, minute))
                .setView(input)
                .setPositiveButton(R.string.ok) { _, _ ->
                    val label = input.text.toString().ifBlank { getString(R.string.default_alarm_label) }
                    lifecycleScope.launch {
                        val dao = AppDatabase.getInstance(this@AlarmsActivity).alarmDao()
                        AndroidAlarmScheduler(this@AlarmsActivity, dao)
                            .schedule(label, hour, minute, repeatDaily = true)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }, 8, 0, true).show()
    }
}

class AlarmListAdapter(
    private val onToggle: (ScheduledAlertEntity, Boolean) -> Unit,
    private val onDelete: (ScheduledAlertEntity) -> Unit,
) : RecyclerView.Adapter<AlarmListAdapter.VH>() {

    private val items = mutableListOf<ScheduledAlertEntity>()

    fun submit(list: List<ScheduledAlertEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_alarm, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val alarm = items[position]
        holder.label.text = alarm.label
        // Unified store has no hour/minute columns; render from the trigger.
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = alarm.triggerAtMillis }
        holder.time.text =
            "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
        holder.repeat.text = holder.repeat.context.getString(
            if (alarm.repeatDaily) R.string.alarm_repeat_daily else R.string.alarm_repeat_once
        )
        holder.enabled.isChecked = alarm.enabled
        holder.enabled.setOnCheckedChangeListener { _, checked ->
            if (checked != alarm.enabled) onToggle(alarm, checked)
        }
        holder.delete.setOnClickListener { onDelete(alarm) }
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.alarmLabel)
        val time: TextView = view.findViewById(R.id.alarmTime)
        val repeat: TextView = view.findViewById(R.id.alarmRepeat)
        val enabled: com.google.android.material.materialswitch.MaterialSwitch =
            view.findViewById(R.id.alarmEnabled)
        val delete: ImageButton = view.findViewById(R.id.alarmDelete)
    }
}

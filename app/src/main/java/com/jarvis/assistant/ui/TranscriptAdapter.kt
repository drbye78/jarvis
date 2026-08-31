package com.jarvis.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.model.Message

/**
 * Chat-style transcript adapter v2.
 *
 * Changes vs v1: DiffUtil via [ListAdapter] instead of notifyDataSetChanged
 * (Room emits a whole new list per change — now only the changed rows
 * rebind), the system prompt is filtered out of the submitted list instead
 * of rendering as an empty row, and tool traffic renders as a compact
 * centered pill. Bubble styling lives in the item layouts / drawables; no
 * hardcoded hex here.
 *
 * [submit] keeps the old name (MainActivity call site) and feeds the diff
 * pipeline.
 */
class TranscriptAdapter : ListAdapter<Message, TranscriptAdapter.VH>(DIFF) {

    /** Row count the RecyclerView shows (system prompt filtered in [submit]). */
    override fun getItemCount(): Int = super.getItemCount()

    override fun getItemViewType(position: Int): Int = when (getItem(position).role) {
        ROLE_USER -> TYPE_USER
        ROLE_ASSISTANT -> TYPE_ASSISTANT
        else -> TYPE_SYSTEM // tool traffic
    }

    fun submit(messages: List<Message>) {
        submitList(messages.filter { it.role != ROLE_SYSTEM_PROMPT })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layout = when (viewType) {
            TYPE_USER -> R.layout.item_message_user
            TYPE_ASSISTANT -> R.layout.item_message_assistant
            else -> R.layout.item_message_system
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = getItem(position)
        holder.text.text = when (msg.role) {
            // Tool line: name + truncated payload — compact, non-interactive.
            ROLE_TOOL -> {
                val name = msg.name ?: "tool"
                "${name}: ${shorten(msg.content)}"
            }
            else -> msg.content
        }
    }

    private fun shorten(s: String, max: Int = 140): String =
        if (s.length <= max) s else s.take(max) + "…"

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
    }

    private companion object {
        const val TYPE_USER = 0
        const val TYPE_ASSISTANT = 1
        const val TYPE_SYSTEM = 2
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"
        const val ROLE_SYSTEM_PROMPT = "system"

        val DIFF = object : DiffUtil.ItemCallback<Message>() {
            override fun areItemsTheSame(oldItem: Message, newItem: Message): Boolean =
                oldItem === newItem || oldItem == newItem

            override fun areContentsTheSame(oldItem: Message, newItem: Message): Boolean =
                oldItem == newItem
        }
    }
}

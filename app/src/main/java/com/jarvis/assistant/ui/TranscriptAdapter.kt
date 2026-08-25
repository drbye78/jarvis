package com.jarvis.assistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jarvis.assistant.R
import com.jarvis.assistant.model.Message

/**
 * Simple transcript adapter: user messages right-aligned dark bubbles,
 * assistant replies left-aligned light bubbles, tool activity as compact
 * system lines.
 */
class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.VH>() {

    private val items = mutableListOf<Message>()

    fun submit(messages: List<Message>) {
        items.clear()
        items.addAll(messages)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position].role) {
        "user" -> TYPE_USER
        "assistant" -> TYPE_ASSISTANT
        else -> TYPE_SYSTEM // system prompt / tool traffic
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
        val msg = items[position]
        val text = when (msg.role) {
            "tool" -> "🔧 ${msg.name ?: "tool"}: ${shorten(msg.content)}"
            "system" -> "" // hide the system prompt
            else -> msg.content
        }
        holder.text.text = text
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
    }
}

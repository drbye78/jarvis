package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.data.MemoryMetaDao
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.SessionSummaryDao
import com.jarvis.assistant.cognitive.data.SessionSummaryEntity
import com.jarvis.assistant.data.MessageDao
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.llm.LlmHttpException
import com.jarvis.assistant.llm.withLlmRetry
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * COGNITIVE_PLAN 2.5: summarize-before-prune.
 *
 * The conversation table keeps only [com.jarvis.assistant.data.ConversationManager]
 * retention rows; everything older is deleted on every insert. Before that
 * delete lands, the manager's prune hook hands this class the deletion
 * cutoff; the summarizer reads the doomed rows (a fast LOCAL query), then —
 * fire-and-forget on the cognitive scope — compresses them through one
 * `chatOnce` call into a SESSION summary and advances the cursor
 * (`memory_meta.lastSummarizedMessageId`) only after a successful commit.
 *
 * Honesty notes:
 * - Summarization input is the ONE new egress class (plan §9.2): it is
 *   gated behind `memory.cloudEnabled`, and the whole feature behind
 *   `memory.enabled`.
 * - If the cloud call fails AFTER the rows were pruned, that batch's raw
 *   text is gone (the cursor does NOT advance, the summary simply never
 *   materializes for those rows) — a gap, never a fabricated summary. The
 *   normal cadence (nightly maintenance) summarizes long before the 200-row
 *   retention horizon, so the gap is a rare tail, and it is documented in
 *   the CHANGELOG.
 * - The DAILY digest compresses the day's SESSION rows (already-local text,
 *   no message re-read) — one extra cloud call per day at most.
 */
class Summarizer(
    private val summaryDao: SessionSummaryDao,
    private val messageDao: MessageDao,
    private val metaDao: MemoryMetaDao,
    private val llm: LlmClient,
    private val memoryEnabled: StateFlow<Boolean>,
    private val cloudEnabled: StateFlow<Boolean>,
    private val modelId: () -> String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val mutex = Mutex()

    // ----------------------------------------------------------------------
    // Summarize-before-prune (called from the ConversationManager hook)
    // ----------------------------------------------------------------------

    /**
     * Reads the rows in `(cursor, cutoff]` that are about to be pruned and
     * hands them to the background summarizer. The LOCAL read happens
     * synchronously inside the prune path (fast, indexed) so the rows can
     * never vanish mid-handoff; only the cloud call is deferred.
     */
    suspend fun captureDoomed(cutoffMessageId: Long) {
        if (!memoryEnabled.value) return
        val cursor = cursor()
        if (cutoffMessageId <= cursor) return
        val rows = try {
            messageDao.inRange(cursor, cutoffMessageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Summarizer: doomed-row read failed (prune continues)")
            return
        }
        val speakable = rows.filter { it.role != "tool" }
        if (speakable.isEmpty()) {
            // Nothing worth summarizing — still advance so the range is not re-scanned.
            advanceCursor(cutoffMessageId)
            return
        }
        // Fire-and-forget: the caller (addMessage on the turn path) must not
        // wait for a cloud call. The captured texts are self-contained here.
        background?.launch { summarizeBatch(speakable, cutoffMessageId) }
    }

    /** Set by the coordinator (its cognitive scope); null in pure tests. */
    internal var background: kotlinx.coroutines.CoroutineScope? = null

    /** Synchronous variant for tests and the maintenance backlog. */
    suspend fun summarizeBatch(
        rows: List<com.jarvis.assistant.data.MessageEntity>,
        upToMessageId: Long,
    ): SessionSummaryEntity? = mutex.withLock {
        if (!memoryEnabled.value || !cloudEnabled.value) return null
        val text = try {
            requestSummary(rows)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Summarizer: cloud call failed — cursor stays at %d", cursor())
            return null
        }
        if (text.isBlank()) return null
        val now = nowMs()
        val entity = SessionSummaryEntity(
            kind = SessionSummaryEntity.KIND_SESSION,
            fromMessageId = rows.first().id,
            toMessageId = rows.last().id,
            fromAt = rows.first().createdAt,
            toAt = rows.last().createdAt,
            text = text.trim(),
            modelId = modelId(),
            tokensIn = estimateTokens(rows),
            tokensOut = text.length / 4,
            createdAt = now,
        )
        return try {
            val id = summaryDao.insert(entity)
            advanceCursor(maxOf(upToMessageId, rows.last().id))
            entity.copy(id = id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Summarizer: summary insert failed — cursor stays")
            null
        }
    }

    // ----------------------------------------------------------------------
    // Maintenance: backlog + nightly DAILY digest
    // ----------------------------------------------------------------------

    /**
     * Maintenance entry: summarize any cursor→latest span in
     * [MAX_BACKLOG_BATCHES] chunks (bounded — the rest waits for the next
     * night) and build the DAILY digest.
     */
    suspend fun runBacklogAndDigest(): Int {
        var made = 0
        repeat(MAX_BACKLOG_BATCHES) {
            val cursor = cursor()
            val newest = messageDao.recentDesc(1).firstOrNull()?.id ?: return@repeat
            if (newest <= cursor + MIN_BATCH_SPAN) return@repeat
            val rows = messageDao.inRange(cursor, newest)
                .filter { it.role != "tool" }
                .take(BATCH_MESSAGES)
            if (rows.size < MIN_BATCH_SPAN) return@repeat
            if (summarizeBatch(rows, rows.last().id) != null) made++ else return@repeat
        }
        if (dailyDigest() != null) made++
        return made
    }

    /**
     * §2.5: the nightly DAILY digest over the day's SESSION rows. Keyed by
     * epoch-day so repeated maintenance runs on the same day are no-ops.
     */
    suspend fun dailyDigest(): SessionSummaryEntity? = mutex.withLock {
        if (!memoryEnabled.value || !cloudEnabled.value) return null
        val now = nowMs()
        val today = epochDay(now)
        if (metaDao.get(KEY_LAST_DAILY_DIGEST_DAY)?.toLongOrNull() == today) return null

        val dayStart = now - (now % DAY_MS)
        val sessions = summaryDao.sessionsSince(dayStart)
        if (sessions.size < MIN_SESSIONS_FOR_DAILY) return null

        val input = sessions.joinToString("\n") { "— ${it.text}" }
        val text = try {
            withLlmRetry(attempts = 2) { _ ->
                llm.chatOnce(
                    ChatRequest(
                        messages = listOf(
                            Message.system(DAILY_SYSTEM_PROMPT),
                            Message.user(input),
                        ),
                        tools = emptyList(),
                        temperature = TEMPERATURE,
                        maxTokens = MAX_TOKENS,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmHttpException) {
            Timber.w(e, "Summarizer: daily digest HTTP failure — retried tomorrow")
            return null
        } catch (e: Exception) {
            Timber.w(e, "Summarizer: daily digest failed — retried tomorrow")
            return null
        }
        if (text.isBlank()) return null

        val entity = SessionSummaryEntity(
            kind = SessionSummaryEntity.KIND_DAILY,
            fromMessageId = sessions.first().fromMessageId,
            toMessageId = sessions.last().toMessageId,
            fromAt = sessions.first().fromAt,
            toAt = sessions.last().toAt,
            text = text.trim(),
            modelId = modelId(),
            tokensIn = estimateTokensDay(sessions),
            tokensOut = text.length / 4,
            createdAt = now,
        )
        return try {
            val id = summaryDao.insert(entity)
            metaDao.putValue(KEY_LAST_DAILY_DIGEST_DAY, today.toString())
            entity.copy(id = id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Summarizer: daily digest insert failed")
            null
        }
    }

    // ----------------------------------------------------------------------
    // Read path (§7.1 SummarySection) — the coordinator's gatherSummary body
    // ----------------------------------------------------------------------

    /**
     * Renders the summary payload for the composer: the latest DAILY digest
     * plus the SESSION rows that follow it, already joined. Empty string =
     * "nothing to say" (the section is skipped). Bounded by
     * [PROMPT_CHAR_BUDGET] with a stable truncation (drop whole lines).
     */
    suspend fun renderForPrompt(): String {
        if (!memoryEnabled.value) return ""
        val daily = summaryDao.latestDaily() ?: return ""
        val sessions = summaryDao.sessionsAfter(daily.toAt)
        val parts = buildList {
            add(daily.text)
            sessions.forEach { add(it.text) }
        }
        val joined = parts.joinToString("\n") { "— $it" }
        if (joined.isBlank()) return ""
        return truncateByLines(joined, PROMPT_CHAR_BUDGET)
    }

    // ------------------------------------------------------------------

    private suspend fun cursor(): Long =
        metaDao.get(MemoryMetaEntity.KEY_LAST_SUMMARIZED_MESSAGE_ID)?.toLongOrNull() ?: 0L

    private suspend fun advanceCursor(to: Long) {
        metaDao.putValue(MemoryMetaEntity.KEY_LAST_SUMMARIZED_MESSAGE_ID, to.toString())
    }

    private suspend fun requestSummary(rows: List<com.jarvis.assistant.data.MessageEntity>): String {
        val transcript = rows.joinToString("\n") { row ->
            val who = when (row.role) {
                "user" -> "Пользователь"
                "assistant" -> "Ассистент"
                else -> row.role
            }
            "$who: ${row.content.take(MAX_LINE_CHARS)}"
        }
        return withLlmRetry(attempts = 2) { _ ->
            llm.chatOnce(
                ChatRequest(
                    messages = listOf(
                        Message.system(SESSION_SYSTEM_PROMPT),
                        Message.user(transcript),
                    ),
                    tools = emptyList(),
                    temperature = TEMPERATURE,
                    maxTokens = MAX_TOKENS,
                ),
            )
        }
    }

    private fun truncateByLines(text: String, budget: Int): String {
        if (text.length <= budget) return text
        val kept = StringBuilder()
        for (line in text.lineSequence()) {
            if (kept.length + line.length + 1 > budget) break
            if (kept.isNotEmpty()) kept.append('\n')
            kept.append(line)
        }
        return kept.toString()
    }

    private fun estimateTokens(rows: List<com.jarvis.assistant.data.MessageEntity>): Int =
        rows.sumOf { it.content.length } / 4

    private fun estimateTokensDay(rows: List<SessionSummaryEntity>): Int =
        rows.sumOf { it.text.length } / 4

    private fun epochDay(at: Long): Long = at / DAY_MS

    companion object {
        /** Rows per SESSION summary (≈10 dialogue exchanges). */
        const val BATCH_MESSAGES = 20

        /** A batch smaller than this is left for the next night. */
        const val MIN_BATCH_SPAN = 4

        /** Bounded backlog per maintenance run (plan §9.1 honest degradation). */
        const val MAX_BACKLOG_BATCHES = 6

        /** §7.1: hard SummarySection budget. */
        const val PROMPT_CHAR_BUDGET = 600

        const val TEMPERATURE = 0.2
        const val MAX_TOKENS = 350
        private const val MAX_LINE_CHARS = 300
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val MIN_SESSIONS_FOR_DAILY = 2

        const val KEY_LAST_DAILY_DIGEST_DAY = "lastDailyDigestDay"

        const val SESSION_SYSTEM_PROMPT =
            "Ты — модуль суммаризации диалогов голосового ассистента. " +
                "Сожми диалог в 1–3 короткие фразы на русском языке: только факты " +
                "(о чём говорили, какие команды выполнялись, какие договорённости). " +
                "Без приветствий и пояснений. Если диалог не содержит ничего содержательного — верни пустую строку."

        const val DAILY_SYSTEM_PROMPT =
            "Ты — модуль ежедневных итогов голосового ассистента. " +
                "Объедини краткие итоги дня в 2–4 строки на русском языке: темы, " +
                "команды, договорённости. Без приветствий и пояснений."
    }
}

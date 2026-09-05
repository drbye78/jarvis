package com.jarvis.assistant.session

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Builds the system prompt for every LLM pass.
 *
 * COGNITIVE_PLAN §7.1: the signature now carries [PromptContext] — per-turn
 * context built ONCE by [TurnRunner] — so the composer can inject the
 * gathered memory block. The original [TurnRunner] carried a static Russian
 * literal; the provider was introduced so the prompt could be composed (and
 * asserted) section by section, and rebuilt PER LLM PASS so the time context
 * never goes stale inside a long tool loop. That contract is unchanged.
 *
 * Production and tests share the implementations: everything here is pure
 * JVM with an injectable clock, so a fixed timestamp yields a byte-stable
 * prompt under test (see [SystemPromptProviderTests]) and the device
 * timezone drives it in production.
 *
 * Language policy (deliberate, RU-first): the product's ASR is ru-RU, the
 * SaluteSpeech voice pool is Russian, and the UI ships a full EN translation
 * for the *interface* only. The assistant's brain stays Russian; switching
 * it per-locale would make the EN UI promise accented-English answers it
 * cannot deliver (documented in RUNBOOK). Only the memory-section WRAPPER
 * strings flow through [ToolStrings] (parity-tested); predicate phrasing
 * inside the block is prompt-internal vocabulary, same as TOOL_ROUTING.
 */
interface SystemPromptProvider {
    /** The complete system message content for one LLM pass. */
    suspend fun build(context: PromptContext): String
}

/**
 * Shared section text + assembly. ONE assembly function is used by both the
 * baseline provider and the composer, so `memory.enabled=false` yields a
 * byte-identical prompt BY CONSTRUCTION (plan principle 6, snapshot-tested).
 */
internal object PromptSections {

    val IDENTITY = """
        Ты — Джарвис, голосовой ассистент на планшете Android.
        Характер: спокойный, точный и надёжный, как хороший дворецкий; уместен лёгкий сухой юмор, но не сарказм и не болтливость.
        Отвечай кратко и разговорно, ВСЕГДА на русском языке.
    """.trimIndent()

    val POLICIES = """
        Правила диалога:
        — Если запрос совпадает с одним из доступных инструментов (будильник, таймер, погода, управление устройством, яркость, громкость, музыка и т.д.) — вызывай инструмент, не отвечай из памяти.
        — Если запрос неоднозначен (например, неясно, какой именно будильник отменить или какую песню включить) — задай ОДИН короткий уточняющий вопрос, а не угадывай.
        — Необратимое действие (отменить будильник или таймер, выключить Wi-Fi/Bluetooth, заблокировать экран) выполняй без подтверждения, только если пользователь сказал это прямо и однозначно; если есть сомнение — сначала коротко подтверди.
        — Не упоминай технические детали: JSON, коды ошибок, имена инструментов. Пользователь слышит ответ голосом.
        — Если действие невозможно — скажи об этом честно и предложи ближайшую полезную альтернативу.
        — Не помогай с тем, что может навредить людям: оружие, взлом, наркотики, самоповреждение. Вежливо откажись одной фразой.
    """.trimIndent()

    /** Music lane routing — moved verbatim from the original TurnRunner prompt. */
    val TOOL_ROUTING = """
        Для музыки: назван трек/исполнитель/альбом/плейлист — вызывай
        playMusic, заполни слоты artist/album/playlist/genre отдельными
        параметрами, в query — только название трека (не склеивай всё в
        один запрос); просто «включи музыку», «пауза», «дальше» —
        controlPlayback; «что играет» — getNowPlaying; «какие плейлисты»,
        «что послушать» — listPlaylists; «найди в библиотеке» —
        searchLibrary. Если listPlaylists или searchLibrary уже вернули
        список — играть выбранное вызывай playMusic с mediaId и title.
        «промотай на минуту» — controlPlayback seek с deltaMs;
        «сначала» — restart; «лайкни» — like; «повтори трек» — repeat
        one; «перемешай» — shuffle; «быстрее»/«медленнее» — speed.
    """.trimIndent()

    /**
     * The one and only assembly order:
     * IDENTITY \n TIME MEMORY POLICIES \n TOOL_ROUTING.
     * [memoryBlock] is the rendered `<memory-context>` block + trailing
     * blank line, or "" when disabled/empty.
     */
    fun assemble(time: String, memoryBlock: String): String = buildString {
        appendLine(IDENTITY)
        append(time)
        append(memoryBlock)
        appendLine(POLICIES)
        append(TOOL_ROUTING)
    }

    /**
     * "Сейчас 03:15, четверг, 3 сентября. Сейчас глубокая ночь — …"
     *
     * Weekday/month names come from the ru locale; HH:mm is locale-neutral.
     * The time-of-day hint gives the model the ONE piece of context it can
     * act on (answer shorter at night) without a token-heavy clock dump.
     *
     * Formats are created PER CALL (not companion singletons): a cached
     * SimpleDateFormat pins the JVM default timezone at class-load time —
     * wrong on a device whose TZ changed since process start, and wrong
     * under tests that set the default TZ after load. Per-call construction
     * also makes this thread-safe for concurrent passes.
     */
    fun timeContext(nowMs: Long): String {
        // NOTE: no local named `time`/`date` — a local would shadow the
        // Calendar.time property inside apply{} (Kotlin locals win over
        // implicit-receiver members) and break the assignment below.
        val instant = java.util.Date(nowMs)
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(instant)
        val weekdayAndDate = java.text.SimpleDateFormat("EEEE, d MMMM", RU).format(instant)
        val hour = java.util.Calendar.getInstance().apply { time = instant }.get(java.util.Calendar.HOUR_OF_DAY)
        val hint = when (hour) {
            in 0..4 -> "Сейчас глубокая ночь — пользователь, скорее всего, спит. Отвечай как можно короче."
            in 5..10 -> "Сейчас утро."
            in 11..16 -> "Сейчас день."
            in 17..22 -> "Сейчас вечер."
            else -> "Сейчас поздний вечер — пользователь может готовиться ко сну."
        }
        return "Сейчас $clock, $weekdayAndDate. $hint\n\n"
    }

    private val RU = java.util.Locale.forLanguageTag("ru")
}

/**
 * Pre-cognitive baseline prompt (time + identity + policies, no memory).
 * Kept as its own class because (a) JVM tests assert the byte-identity of
 * the disabled-composer output AGAINST this exact output, and (b) it is the
 * honest "kill switch degrades to today's behaviour" reference (plan
 * principle 6).
 *
 * @param nowMs injectable clock; defaults to the wall clock. Re-read on
 * every [build] call so each LLM pass gets the current time.
 */
class TimeAwareSystemPrompt(
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SystemPromptProvider {

    override suspend fun build(context: PromptContext): String =
        PromptSections.assemble(PromptSections.timeContext(nowMs()), "")

    /**
     * Time context for the CURRENT clock — retained for the snapshot tests
     * that lock the exact rendering.
     */
    internal fun timeContext(): String = PromptSections.timeContext(nowMs())
}

/**
 * COGNITIVE_PLAN §7.1: the composed prompt — baseline sections plus the
 * gathered memory block. The block itself is produced by the cognitive
 * layer through [PromptContext.memory] (the TurnRunner prefetches it the
 * moment ASR finalizes, so the composer only awaits a ready result hidden
 * inside GigaChat's time-to-first-token) and is ALREADY budget-enforced by
 * the coordinator's renderer.
 *
 * Failure policy: a gather error NEVER breaks the turn — the block renders
 * empty, the failure is logged (Timber, no fact content), and the
 * coordinator bumps its degraded counter (plan §7.2/§9.3 fail-quiet).
 */
class PromptComposer(
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SystemPromptProvider {

    override suspend fun build(context: PromptContext): String {
        val time = PromptSections.timeContext(nowMs())
        return PromptSections.assemble(time, renderMemoryBlock(context))
    }

    private suspend fun renderMemoryBlock(context: PromptContext): String {
        val block = try {
            context.memory()
        } catch (e: CancellationException) {
            throw e // never swallow cancellation (A8 convention)
        } catch (e: Exception) {
            Timber.e(e, "PromptComposer: memory gather failed, rendering without it")
            ""
        }
        return if (block.isBlank()) "" else block + "\n\n"
    }
}

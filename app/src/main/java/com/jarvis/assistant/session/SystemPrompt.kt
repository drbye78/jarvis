package com.jarvis.assistant.session

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Builds the system prompt for every LLM pass.
 *
 * The original [TurnRunner] carried a static Russian literal: no time awareness
 * ("it's 3am, the user is probably asleep"), no personality beyond "short and
 * conversational", no safety rules, no clarification/confirmation policies —
 * the dialogue-system audit called all of these out. The prompt now flows
 * through this provider so it can be composed (and asserted) section by
 * section, and rebuilt PER LLM PASS so the time context never goes stale
 * inside a long tool loop.
 *
 * Production and tests share [TimeAwareSystemPrompt]: it is pure JVM with an
 * injectable clock, so a fixed timestamp yields a byte-stable prompt under
 * test (see [SystemPromptProviderTests]) and the device timezone drives it in
 * production.
 *
 * Language policy (deliberate, RU-first): the product's ASR is ru-RU, the
 * SaluteSpeech voice pool is Russian, and the UI ships a full EN translation
 * for the *interface* only. The assistant's brain stays Russian; switching it
 * per-locale would make the EN UI promise accented-English answers it cannot
 * deliver (documented in RUNBOOK).
 */
interface SystemPromptProvider {
    /** The complete system message content for one LLM pass. */
    fun build(): String
}

/**
 * Time-aware, policy-complete system prompt. All literals are Russian by
 * design (see [SystemPromptProvider] KDoc).
 *
 * @param nowMs injectable clock; defaults to the wall clock. Re-read on every
 * [build] call so each LLM pass gets the current time.
 */
class TimeAwareSystemPrompt(
    private val nowMs: () -> Long = System::currentTimeMillis,
) : SystemPromptProvider {

    override fun build(): String = buildString {
        appendLine(IDENTITY)
        append(timeContext())
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
    internal fun timeContext(): String {
        // NOTE: no local named `time`/`date` — a local would shadow the
        // Calendar.time property inside apply{} (Kotlin locals win over
        // implicit-receiver members) and break the assignment below.
        val instant = Date(nowMs())
        val clock = SimpleDateFormat("HH:mm", Locale.US).format(instant)
        val weekdayAndDate = SimpleDateFormat("EEEE, d MMMM", RU).format(instant)
        val hour = Calendar.getInstance().apply { time = instant }.get(Calendar.HOUR_OF_DAY)
        val hint = when (hour) {
            in 0..4 -> "Сейчас глубокая ночь — пользователь, скорее всего, спит. Отвечай как можно короче."
            in 5..10 -> "Сейчас утро."
            in 11..16 -> "Сейчас день."
            in 17..22 -> "Сейчас вечер."
            else -> "Сейчас поздний вечер — пользователь может готовиться ко сну."
        }
        return "Сейчас $clock, $weekdayAndDate. $hint\n\n"
    }

    private companion object {
        private val RU = Locale.forLanguageTag("ru")

        private val IDENTITY = """
            Ты — Джарвис, голосовой ассистент на планшете Android.
            Характер: спокойный, точный и надёжный, как хороший дворецкий; уместен лёгкий сухой юмор, но не сарказм и не болтливость.
            Отвечай кратко и разговорно, ВСЕГДА на русском языке.
        """.trimIndent()

        private val POLICIES = """
            Правила диалога:
            — Если запрос совпадает с одним из доступных инструментов (будильник, таймер, погода, управление устройством, яркость, громкость, музыка и т.д.) — вызывай инструмент, не отвечай из памяти.
            — Если запрос неоднозначен (например, неясно, какой именно будильник отменить или какую песню включить) — задай ОДИН короткий уточняющий вопрос, а не угадывай.
            — Необратимое действие (отменить будильник или таймер, выключить Wi-Fi/Bluetooth, заблокировать экран) выполняй без подтверждения, только если пользователь сказал это прямо и однозначно; если есть сомнение — сначала коротко подтверди.
            — Не упоминай технические детали: JSON, коды ошибок, имена инструментов. Пользователь слышит ответ голосом.
            — Если действие невозможно — скажи об этом честно и предложи ближайшую полезную альтернативу.
            — Не помогай с тем, что может навредить людям: оружие, взлом, наркотики, самоповреждение. Вежливо откажись одной фразой.
        """.trimIndent()

        /** Music lane routing — moved verbatim from the original TurnRunner prompt. */
        private val TOOL_ROUTING = """
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
    }
}

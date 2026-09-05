package com.jarvis.assistant.cognitive.extract

/**
 * COGNITIVE_PLAN §6.1: the offline heuristic that decides whether an
 * utterance is worth a cloud extraction call. Cheap string ops only — it
 * runs on the ingest path for every voice turn, on-device, no network.
 *
 * Signals (ordered, per plan §6.1): explicit memory verbs, first-person
 * self-statements and possessives, likes/dislikes, life-fact patterns.
 * Pure tool traffic («включи джаз», «погода», «таймер на 10 минут») is
 * skipped — the expected 50–70 % skip rate is what keeps cost and noise
 * down. Pure Kotlin, fixture-tested.
 */
object ExtractionGate {

    /** Explicit memory verbs — always extract. */
    private val MEMORY_VERBS = listOf(
        "запомни", "запомнить", "помни, что", "напомни, что я",
        "не забудь", "на будущее",
    )

    /** First-person self-statements / possessives (word-start anchored). */
    private val FIRST_PERSON = listOf(
        "я ", "я,", "я-", "мне ", "меня ", "мой ", "моя ", "моё ", "мое ",
        "мои ", "у меня",
    )

    /** Likes / dislikes patterns. */
    private val LIKES = listOf(
        "люблю", "любим", "нравится", "ненавижу", "не люблю", "обожаю",
        "не выношу", "увлекаюсь",
    )

    /** Life facts: names, dates, workplaces. */
    private val LIFE_FACTS = listOf(
        "зовут", "родился", "родилась", "день рождения", "живу", "работаю",
        "работаю в", "учусь", "женат", "замужем", "жена", "муж", "дочь",
        "сын", "начальник", "коллег",
    )

    /** Date/year shapes: 12.04, 12/04, 1979г, «в 1990 году». */
    private val DATE_REGEX = Regex(
        """(\b\d{1,2}[./]\d{1,2}\b)|""" +
            """(\b(19|20)\d{2}\b)|""" +
            """(\b\d{1,2}\s+(январ|феврал|март|апрел|ма[йя]|июн|июл|август|сентябр|ноябр|декабр))""",
    )

    /**
     * True when the utterance should be queued for cloud extraction.
     * Conservative by design: a false positive costs one batched call;
     * a false negative loses a fact forever.
     */
    fun shouldExtract(utterance: String): Boolean {
        val text = utterance.trim()
        if (text.length < MIN_LENGTH) return false

        val lowered = text.lowercase(java.util.Locale.ROOT)
        val hasSignal = (MEMORY_VERBS + LIKES + LIFE_FACTS + FIRST_PERSON)
            .any { lowered.contains(it) }
        return hasSignal || DATE_REGEX.containsMatchIn(text)
    }

    /** Below this the utterance cannot carry a stable fact («да», «стоп»). */
    private const val MIN_LENGTH = 8
}

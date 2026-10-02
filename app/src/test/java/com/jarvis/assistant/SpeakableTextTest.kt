package com.jarvis.assistant

import com.jarvis.assistant.speech.tts.SpeakableText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [SpeakableText] — the pure TTS-boundary normalizer.
 *
 * These pin the contract that matters: markup/symbols are cleaned for
 * SPEECH only, while ordinary prose (and, critically, ordinary words that
 * merely contain a unit letter) is returned byte-identical.
 */
class SpeakableTextTest {

    @Test
    fun `bullet list is joined into one spoken run`() {
        assertEquals("яблоки, бананы, груши.", SpeakableText.prepare("- яблоки\n- бананы\n- груши"))
    }

    @Test
    fun `ordered list is joined into one spoken run`() {
        assertEquals("раз, два.", SpeakableText.prepare("1. раз\n2. два"))
    }

    @Test
    fun `heading marker is removed`() {
        assertEquals("Заголовок", SpeakableText.prepare("## Заголовок"))
    }

    @Test
    fun `blockquote and checkbox markers are removed`() {
        assertEquals("важное дело", SpeakableText.prepare("> [x] важное дело"))
    }

    @Test
    fun `strong emphasis is unwrapped`() {
        assertEquals("Это важно сегодня.", SpeakableText.prepare("Это **важно** сегодня."))
    }

    @Test
    fun `underscore emphasis is unwrapped`() {
        assertEquals("Это важно сегодня.", SpeakableText.prepare("Это _важно_ сегодня."))
    }

    @Test
    fun `snake_case identifier is not mangled`() {
        assertEquals("имя snake_case_value тут", SpeakableText.prepare("имя snake_case_value тут"))
    }

    @Test
    fun `fenced code keeps inner text and drops the fence`() {
        assertEquals("val x = 1", SpeakableText.prepare("```kotlin\nval x = 1\n```"))
    }

    @Test
    fun `inline code backticks are dropped`() {
        assertEquals("Запусти ./gradlew сейчас.", SpeakableText.prepare("Запусти `./gradlew` сейчас."))
    }

    @Test
    fun `markdown link becomes its text`() {
        assertEquals("Яндекс — поиск", SpeakableText.prepare("[Яндекс](http://ya.ru) — поиск"))
    }

    @Test
    fun `bare url is removed`() {
        assertEquals("Смотри тут", SpeakableText.prepare("Смотри http://example.com тут"))
    }

    @Test
    fun `emoji is removed`() {
        assertEquals("Привет мир", SpeakableText.prepare("Привет \uD83D\uDE00 мир"))
    }

    @Test
    fun `zero width characters are removed`() {
        assertEquals("тест", SpeakableText.prepare("те\u200Bст"))
    }

    @Test
    fun `nineteen degrees uses the many form`() {
        assertEquals("19 градусов Цельсия", SpeakableText.prepare("19 °C"))
    }

    @Test
    fun `one degree uses the singular form`() {
        assertEquals("1 градус Цельсия", SpeakableText.prepare("1 °C"))
    }

    @Test
    fun `two degrees uses the few form`() {
        assertEquals("2 градуса Цельсия", SpeakableText.prepare("2 °C"))
    }

    @Test
    fun `negative temperature speaks the minus`() {
        assertEquals("минус 5 градусов Цельсия", SpeakableText.prepare("-5 °C"))
    }

    @Test
    fun `fahrenheit expands too`() {
        assertEquals("10 градусов Фаренгейта", SpeakableText.prepare("10 °F"))
    }

    @Test
    fun `percent expands with the correct plural`() {
        assertEquals("50 процентов", SpeakableText.prepare("50 %"))
    }

    @Test
    fun `kilometres per hour expands`() {
        assertEquals("120 километров в час", SpeakableText.prepare("120 км/ч"))
    }

    @Test
    fun `metres per second expands`() {
        assertEquals("5 метров в секунду", SpeakableText.prepare("5 м/с"))
    }

    @Test
    fun `kilometre unit expands with plural`() {
        assertEquals("10 километров", SpeakableText.prepare("10 км"))
    }

    @Test
    fun `year abbreviation is not converted to grams`() {
        assertEquals("2024 г.", SpeakableText.prepare("2024 г."))
    }

    @Test
    fun `multiple blank lines are collapsed`() {
        assertEquals("Первая\n\nВторая", SpeakableText.prepare("Первая\n\n\n\nВторая"))
    }

    @Test
    fun `paired quotes wrapping a short line are removed`() {
        assertEquals("Привет", SpeakableText.prepare("«Привет»"))
    }

    @Test
    fun `ellipsis collapses to a period`() {
        assertEquals("Думаю.", SpeakableText.prepare("Думаю…"))
    }

    @Test
    fun `clean sentence is returned unchanged`() {
        val clean = "Привет, как дела?"
        assertEquals(clean, SpeakableText.prepare(clean))
    }

    @Test
    fun `falsification - markup-free prose is byte identical`() {
        val clean = "Сегодня хорошая погода и я рад."
        assertEquals(clean, SpeakableText.prepare(clean))
    }

    @Test
    fun `ordinary words containing unit letters are not mangled`() {
        val words = listOf("смысл", "гора", "мера", "сантиметр", "грамм", "сок", "молоко", "газ")
        for (word in words) {
            assertEquals(word, SpeakableText.prepare(word))
        }
    }

    @Test
    fun `transform is idempotent`() {
        val messy = "## Итог\n\n- **19 °C** и [ссылка](http://x)\n- 50 % \uD83D\uDE00"
        val once = SpeakableText.prepare(messy)
        assertEquals(once, SpeakableText.prepare(once))
    }
}

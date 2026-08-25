package com.jarvis.assistant

import com.jarvis.assistant.util.SentenceBuffer
import com.jarvis.assistant.util.splitSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun `splits on russian boundaries`() {
        val parts = "Привет! Как дела? Хорошо.".splitSentences()
        assertEquals(listOf("Привет!", " Как дела?", " Хорошо."), parts)
    }

    @Test
    fun `abbreviation dots are not boundaries`() {
        val parts = "г. Москва, ул. Ленина, стр. 1. Далее текст.".splitSentences()
        // Only the final '.' of "1." and the sentence end split.
        assertEquals(2, parts.size)
    }

    @Test
    fun `тд and similar are not boundaries`() {
        val parts = "Он купил чай, кофе и т.д. и ушёл.".splitSentences()
        assertEquals(1, parts.size)
    }

    @Test
    fun `ellipsis and newline split`() {
        val parts = "Стоп…\nНовая строка".splitSentences()
        assertEquals(2, parts.size)
    }
}

class SentenceBufferTest {

    @Test
    fun `accumulates until sentence completes`() {
        val buf = SentenceBuffer()
        assertEquals(emptyList<String>(), buf.append("Привет,"))
        assertEquals(emptyList<String>(), buf.append(" как"))
        assertEquals(listOf("Привет, как дела?"), buf.append(" дела?"))
    }

    @Test
    fun `flush remainder at end of stream`() {
        val buf = SentenceBuffer()
        buf.append("Первое. Второе")
        assertNull(null) // sanity
        val rest = buf.flushRemaining()
        assertEquals("Второе", rest)
    }

    @Test
    fun `force flushes oversized remainder`() {
        val buf = SentenceBuffer(maxChars = 20)
        val long = "а".repeat(50)
        val out = buf.append(long)
        // Remainder exceeded maxChars -> force-flushed
        assertEquals(1, out.size)
        assertEquals(50, out[0].length)
        assertNull(buf.flushRemaining())
    }

    @Test
    fun `multiple sentences from one delta`() {
        val buf = SentenceBuffer()
        val out = buf.append("Раз. Два. Три.")
        assertEquals(listOf("Раз.", " Два.", " Три."), out)
    }
}

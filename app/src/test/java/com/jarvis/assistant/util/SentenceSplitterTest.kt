package com.jarvis.assistant.util

import com.jarvis.assistant.util.SentenceSplitter.endsWithSentence
import com.jarvis.assistant.util.SentenceSplitter.splitSentences
import org.junit.Assert.*
import org.junit.Test

class SentenceSplitterTest {

    @Test fun `simple sentence ending with dot`() {
        assertTrue("Hello.".endsWithSentence())
    }

    @Test fun `question mark`() {
        assertTrue("Как дела?".endsWithSentence())
    }

    @Test fun `exclamation`() {
        assertTrue("Отлично!".endsWithSentence())
    }

    @Test fun `ellipsis`() {
        assertTrue("Подожди…".endsWithSentence())
    }

    @Test fun `newline`() {
        assertTrue("Да\n".endsWithSentence())
    }

    @Test fun `empty string is not a sentence`() {
        assertFalse("".endsWithSentence())
    }

    @Test fun `mid sentence no boundary`() {
        assertFalse("Hello world".endsWithSentence())
    }

    @Test fun `abbreviation with single letter`() {
        // endsWithSentence is a simple char check — '.' IS a sentence boundary by that heuristic.
        // The abbreviation guard is in splitSentences(), not endsWithSentence().
        assertTrue("ул.".endsWithSentence())
    }

    @Test fun `split simple sentence`() {
        val result = "Hello. World.".splitSentences()
        assertEquals(2, result.size)
        assertEquals("Hello.", result[0])
        assertEquals(" World.", result[1])
    }

    @Test fun `split with question and exclamation`() {
        val result = "Hi! How are you? Good.".splitSentences()
        assertEquals(3, result.size)
    }

    @Test fun `no sentence boundaries returns single fragment`() {
        val result = "Just some words".splitSentences()
        assertEquals(listOf("Just some words"), result)
    }

    @Test fun `empty string split`() {
        val result = "".splitSentences()
        assertTrue(result.isEmpty())
    }
}

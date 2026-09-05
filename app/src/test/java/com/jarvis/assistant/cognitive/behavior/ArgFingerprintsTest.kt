package com.jarvis.assistant.cognitive.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §8.1: the fingerprint normalizers — the ONLY thing
 * telemetry stores about a command. Deterministic, content-bounded, and
 * never a raw utterance.
 */
class ArgFingerprintsTest {

    @Test
    fun `music queries normalize across phrasings`() {
        val a = ArgFingerprints.of("playMusic", """{"query":"Джаз 70-х!"}""")
        val b = ArgFingerprints.of("playMusic", """{"query":"джаз 70-х"}""")
        assertEquals(a, b)
        assertEquals("q:джаз 70 х", a)
    }

    @Test
    fun `weather fingerprints by city`() {
        assertEquals("city:москва", ArgFingerprints.of("getWeather", """{"city":"Москва"}"""))
    }

    @Test
    fun `volume buckets to 25-wide steps`() {
        assertEquals("level:25", ArgFingerprints.of("setVolume", """{"level":"30"}"""))
        assertEquals("level:25", ArgFingerprints.of("setVolume", """{"level":"25.0"}"""))
        assertEquals("level:100", ArgFingerprints.of("setVolume", """{"level":100}"""))
    }

    @Test
    fun `hour buckets are 2-hour wide`() {
        assertEquals(0, ArgFingerprints.hourBucket(0))
        assertEquals(0, ArgFingerprints.hourBucket(1))
        assertEquals(11, ArgFingerprints.hourBucket(23))
        assertEquals(5, ArgFingerprints.hourBucket(11))
    }

    @Test
    fun `argument-free tools collapse to all`() {
        assertEquals("all", ArgFingerprints.of("getNowPlaying", "{}"))
        assertEquals("all", ArgFingerprints.of("listPlaylists", null))
    }

    @Test
    fun `unknown tools get a stable sorted projection`() {
        val a = ArgFingerprints.of("someTool", """{"b":"2","a":"1"}""")
        val b = ArgFingerprints.of("someTool", """{"a":"1","b":"2"}""")
        assertEquals(a, b)
        assertTrue(a.contains("a=1"))
    }

    @Test
    fun `fingerprints are bounded`() {
        val long = "x".repeat(1000)
        val fp = ArgFingerprints.of("playMusic", """{"query":"$long"}""")
        assertFalse(fp.length > 120)
    }

    @Test
    fun `normalize strips punctuation and collapses space`() {
        assertEquals("тарковский films", ArgFingerprints.normalize("Тарковский, films!"))
    }
}

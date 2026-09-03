package com.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the RU/EN resource parity that previous patches established by hand:
 * every key in values/strings.xml must exist in values-en/strings.xml and
 * vice versa. A new key added to only one locale fails HERE, at the source,
 * instead of shipping a UI that lies about its language (the original
 * dead-EN-resources defect F6).
 */
class ResourceParityTest {

    private fun stringsFile(localeDir: String): File {
        // Unit tests run with the module dir as the working directory; walk
        // up defensively anyway so this also works from the repo root.
        val candidates = listOf(
            File("src/main/res/$localeDir/strings.xml"),
            File("app/src/main/res/$localeDir/strings.xml"),
        )
        candidates.firstOrNull { it.isFile }?.let { return it }
        var walk: File? = File(".").absoluteFile
        repeat(4) {
            if (walk != null) {
                val candidate = File(walk, "app/src/main/res/$localeDir/strings.xml")
                if (candidate.isFile) return candidate
                walk = walk!!.parentFile
            }
        }
        return candidates[0] // for the existence assertion in keys()
    }

    private fun keys(localeDir: String): Set<String> {
        val file = stringsFile(localeDir)
        assertTrue("strings.xml not found for $localeDir (wd=${File(".").absoluteFile})", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            out += nodes.item(i).attributes.getNamedItem("name").nodeValue
        }
        return out
    }

    @Test
    fun `RU and EN string keys are in parity`() {
        val ru = keys("values")
        val en = keys("values-en")
        val missingInEn = ru - en
        val missingInRu = en - ru
        assertEquals("keys missing in values-en: $missingInEn", 0, missingInEn.size)
        assertEquals("keys missing in values: $missingInRu", 0, missingInRu.size)
    }

    @Test
    fun `runtime spoken phrase keys exist in both locales`() {
        val ru = keys("values")
        val en = keys("values-en")
        listOf(
            "phrase_asr_open_failed", "phrase_asr_failed", "phrase_turn_timeout",
            "phrase_network_error", "phrase_generic_error", "phrase_too_many_tool_steps",
            "phrase_llm_timeout", "phrase_llm_failed", "phrase_offline",
            "phrase_wake_engine_error", "phrase_voice_sample",
        ).forEach { key ->
            assertTrue("missing RU key $key", key in ru)
            assertTrue("missing EN key $key", key in en)
        }
    }

    @Test
    fun `every tool activity label exists in both locales`() {
        val ru = keys("values")
        val en = keys("values-en")
        val expected = setOf(
            "activity_tool_unknown",
            "activity_tool_set_alarm", "activity_tool_cancel_alarm", "activity_tool_list_alarms",
            "activity_tool_set_timer", "activity_tool_cancel_timer", "activity_tool_get_weather",
            "activity_tool_get_device_info", "activity_tool_set_brightness", "activity_tool_set_volume",
            "activity_tool_set_wifi", "activity_tool_set_bluetooth", "activity_tool_set_dnd",
            "activity_tool_lock_screen", "activity_tool_open_app", "activity_tool_play_music",
            "activity_tool_control_playback", "activity_tool_get_now_playing",
            "activity_tool_list_playlists", "activity_tool_search_library",
        )
        assertEquals(expected, expected.intersect(ru))
        assertEquals(expected, expected.intersect(en))
    }
}

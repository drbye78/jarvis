package com.jarvis.assistant

import com.jarvis.assistant.tools.int
import com.jarvis.assistant.util.JsonOut
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the shared tool-argument helpers (F9) and the safe
 * JSON builder (D1).
 */
class ToolContractTest {

    @Test
    fun `int accepts float-form integer numbers`() {
        // F9: LLMs occasionally emit integer fields as "50.0"; the old
        // toIntOrNull() rejected them and the tool answered "Missing
        // required parameter" for a value the model DID supply.
        val obj = Json.parseToJsonElement("""{"level":50.0,"other":7,"bad":"x"}""").jsonObject
        assertEquals(50, obj.int("level"))
        assertEquals(7, obj.int("other"))
        assertNull(obj.int("missing"))
        assertNull(obj.int("bad"))
    }

    @Test
    fun `JsonOut renders null as JSON null`() {
        // D1: a null value used to serialize as the STRING "null"
        // ({"x":"null"}), mis-typing the field for the LLM.
        assertEquals("""{"x":null,"y":"v"}""", JsonOut.obj("x" to null, "y" to "v"))
    }
}

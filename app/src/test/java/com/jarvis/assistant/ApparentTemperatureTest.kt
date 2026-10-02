package com.jarvis.assistant

import com.jarvis.assistant.weather.apparentTemperatureC
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DERIVED apparent-temperature estimate used where the upstream has no
 * "feels like" reading (Project EOL). It is an estimate, so these tests pin the
 * regime boundaries and the rounding, not a server value.
 */
class ApparentTemperatureTest {

    @Test
    fun `null temperature yields null`() {
        assertNull(apparentTemperatureC(null, 20.0, 50.0))
    }

    @Test
    fun `cold and windy applies wind chill colder than the air`() {
        // 0 °C + 20 km/h → Environment-Canada wind chill.
        val feels = apparentTemperatureC(0.0, 20.0, 50.0)!!
        assertTrue("wind chill must be colder than air, got $feels", feels < 0.0)
        assertEquals(-5.2, feels, 0.1)
    }

    @Test
    fun `hot and humid applies the heat index hotter than the air`() {
        // 32 °C + 70 % → Rothfusz heat index.
        val feels = apparentTemperatureC(32.0, 5.0, 70.0)!!
        assertTrue("heat index must be hotter than air, got $feels", feels > 32.0)
        assertEquals(40.4, feels, 0.5)
    }

    @Test
    fun `a neutral temperature is returned unchanged`() {
        assertEquals(15.0, apparentTemperatureC(15.0, 30.0, 90.0)!!, 0.001)
    }

    @Test
    fun `wind chill needs wind above the threshold`() {
        // 4.0 km/h is below 4.8 → no correction even below 10 °C.
        assertEquals(5.0, apparentTemperatureC(5.0, 4.0, 90.0)!!, 0.001)
    }

    @Test
    fun `heat index needs humidity present`() {
        // 32 °C but no humidity → falls back to the air temperature.
        assertEquals(32.0, apparentTemperatureC(32.0, 5.0, null)!!, 0.001)
    }

    @Test
    fun `rounds to one decimal`() {
        assertEquals(15.0, apparentTemperatureC(15.04, null, null)!!, 0.001)
        assertEquals(15.1, apparentTemperatureC(15.06, null, null)!!, 0.001)
    }
}

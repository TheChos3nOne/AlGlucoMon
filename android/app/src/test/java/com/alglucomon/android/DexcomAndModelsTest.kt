package com.alglucomon.android

import com.alglucomon.android.data.AppSettings
import com.alglucomon.android.data.DexcomClient
import com.alglucomon.android.data.GlucoseStatus
import com.alglucomon.android.data.GlucoseUnit
import com.alglucomon.android.data.TrendDirection
import com.alglucomon.android.data.formatDelta
import com.alglucomon.android.data.formatGlucose
import com.alglucomon.android.data.getGlucoseStatus
import com.alglucomon.android.data.luxToBrightness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DexcomAndModelsTest {

    @Test
    fun parsesDexcomTimestampsAcrossFormats() {
        assertEquals(1_700_000_000_000L, DexcomClient.parseDexcomTimestamp("Date(1700000000000)"))
        assertEquals(1_700_000_000_000L, DexcomClient.parseDexcomTimestamp("/Date(1700000000000)/"))
        assertEquals(1_700_000_000_000L, DexcomClient.parseDexcomTimestamp("Date(1700000000000+0000)"))
        assertEquals(1_700_000_000_000L, DexcomClient.parseDexcomTimestamp("Date(1700000000000-0500)"))
    }

    @Test
    fun parsesStringAndNumericTrends() {
        assertEquals(TrendDirection.FORTY_FIVE_UP, DexcomClient.parseTrendValue("FortyFiveUp"))
        assertEquals(TrendDirection.FLAT, DexcomClient.parseTrendValue(4))
        assertEquals(TrendDirection.DOUBLE_DOWN, DexcomClient.parseTrendValue(7))
        assertEquals(TrendDirection.SINGLE_UP, DexcomClient.parseTrendValue("2"))
    }

    @Test
    fun formatsUnitsAndThresholds() {
        assertEquals("117", formatGlucose(117, GlucoseUnit.MG_DL))
        assertEquals("6.5", formatGlucose(117, GlucoseUnit.MMOL_L))
        assertEquals("+4", formatDelta(4, GlucoseUnit.MG_DL))
        assertEquals("-0.5", formatDelta(-9, GlucoseUnit.MMOL_L))

        val s = AppSettings(urgentLowThreshold = 55, lowThreshold = 70, highThreshold = 180)
        assertEquals(GlucoseStatus.URGENT_LOW, getGlucoseStatus(54, s))
        assertEquals(GlucoseStatus.LOW, getGlucoseStatus(65, s))
        assertEquals(GlucoseStatus.IN_RANGE, getGlucoseStatus(115, s))
        assertEquals(GlucoseStatus.HIGH, getGlucoseStatus(210, s))
    }

    @Test
    fun mapsAmbientLuxToBoundedMonotonicBrightness() {
        val dark = luxToBrightness(0f)
        val dimRoom = luxToBrightness(5f)
        val office = luxToBrightness(300f)
        val daylight = luxToBrightness(2000f)

        assertEquals(0.03f, dark, 0.001f)
        assertTrue(dimRoom > dark)
        assertTrue(office > dimRoom)
        assertTrue(daylight >= office)
        assertEquals(1.0f, daylight, 0.01f)
    }
}

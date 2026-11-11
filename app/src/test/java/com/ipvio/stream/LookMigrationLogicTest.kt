package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir: colour defaults – red on navy, upstream's green migrations must not win. */
class LookMigrationLogicTest {
    private fun step(accent: Int, custom: Int) =
        LookMigrationLogic.toOnAir(accent, custom, Look.CUSTOM, Look.BRAND_GREEN, Look.DEFAULT_CUSTOM)

    @Test fun defaultIsOnAirRedOnNavy() {
        val preset = Look.ACCENTS[Look.DEFAULT_ACCENT]
        assertEquals("OnAir", preset.name)
        assertEquals(Look.BRAND_RED, preset.fill)
        assertEquals(Look.BRAND_NAVY, Look.BGS[Look.DEFAULT_BG].bg)
        assertEquals(Look.BRAND_RED, Look.DEFAULT_CUSTOM)
    }

    @Test fun greenIsStillOfferedButNotDefault() {
        val i = Look.ACCENTS.indexOfFirst { it.fill == Look.BRAND_GREEN }
        assertTrue(i > 0)
        assertEquals(Look.ACCENTS.size, Look.CUSTOM)
    }

    @Test fun whiteTextIsReadableOnTheRedFill() {
        // relative luminance of #D32F2F ≈ 0.16 → contrast with white ≈ 5:1 (≥ 4.5 for normal text)
        fun lin(c: Int): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        val r = Look.BRAND_RED
        val l = 0.2126 * lin(r shr 16 and 255) + 0.7152 * lin(r shr 8 and 255) + 0.0722 * lin(r and 255)
        assertTrue(1.05 / (l + 0.05) >= 4.5)
    }

    /** The English UI shows "Green" for upstream's preset (source strings are German, translated when shown). */
    @Test fun presetNamesHaveEnglishNames() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI)
        assertEquals("Green", I18nCore.translate("Grün", 1))
        assertEquals("OnAir", I18nCore.translate("OnAir", 1))
        assertEquals("OnAir Navy", I18nCore.translate("OnAir Navy", 1))
        assertEquals("Grün", Look.ACCENTS.last().name)
    }

    @Test fun freshInstallSkipsLegacyMigrations() {
        assertTrue(LookMigrationLogic.isFresh(hasVersion = false, hasAccent = false, hasCustom = false))
        assertFalse(LookMigrationLogic.isFresh(hasVersion = true, hasAccent = false, hasCustom = false))
        assertFalse(LookMigrationLogic.isFresh(hasVersion = false, hasAccent = true, hasCustom = false))
        assertFalse(LookMigrationLogic.isFresh(hasVersion = false, hasAccent = false, hasCustom = true))
    }

    @Test fun ipvioGreenUserGetsOnAirDefault() {
        assertEquals(Look.DEFAULT_ACCENT to Look.DEFAULT_CUSTOM, step(0, Look.BRAND_GREEN))
    }

    @Test fun upstreamCustomIndexMovesToNewCustomIndex() {
        val (a, c) = step(LookMigrationLogic.OLD_CUSTOM, 0x123456)
        assertEquals(Look.CUSTOM, a)
        assertEquals(0x123456, c)   // an own colour is kept
    }

    @Test fun otherPresetsAreUntouched() {
        for (i in 1 until LookMigrationLogic.OLD_CUSTOM) assertEquals(i, step(i, 0x00FF00).first)
    }

    @Test fun alphaBitsAreDropped() {
        assertEquals(Look.DEFAULT_CUSTOM, step(0, Look.BRAND_GREEN or 0xFF000000.toInt()).second)
    }
}

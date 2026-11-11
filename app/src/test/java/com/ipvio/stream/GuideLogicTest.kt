package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir Phase 3: guide database rules (window, episode numbers, categories, when to re-import). */
class GuideLogicTest {
    private val now = 1_800_000_000L
    private val h = 3600L

    @Test fun windowKeepsMinus3hToPlus36h() {
        assertTrue(GuideLogic.keep(now - 4 * h, now - 3 * h + 1, now))      // ends just inside the past edge
        assertFalse(GuideLogic.keep(now - 4 * h, now - 3 * h, now))         // ends exactly at now − 3 h → gone
        assertTrue(GuideLogic.keep(now + 36 * h - 1, now + 37 * h, now))     // starts just before now + 36 h
        assertFalse(GuideLogic.keep(now + 36 * h, now + 37 * h, now))       // starts at now + 36 h → not kept
        assertTrue(GuideLogic.keep(now - h, now + h, now))
        assertFalse(GuideLogic.keep(now + h, now + h, now))                  // zero length
    }

    @Test fun catchUpChannelsKeepArchiveDaysUpTo7() {
        assertTrue(GuideLogic.keep(now - 2 * 86_400L, now - 2 * 86_400L + h, now, pastDays = 3))
        assertFalse(GuideLogic.keep(now - 2 * 86_400L, now - 2 * 86_400L + h, now, pastDays = 0))
        assertFalse(GuideLogic.keep(now - 9 * 86_400L, now - 9 * 86_400L + h, now, pastDays = 30))   // capped at 7 days
        assertTrue(GuideLogic.keep(now - 6 * 86_400L, now - 6 * 86_400L + h, now, pastDays = 30))
        assertEquals(now - 7 * 86_400L, GuideLogic.pruneBefore(now))
    }

    @Test fun xmltvNsIsZeroBased() {
        assertEquals("S01E05", GuideLogic.fromXmltvNs("0.4."))
        assertEquals("S03E12", GuideLogic.fromXmltvNs("2 . 11 . 0/1"))
        assertEquals("S02E01", GuideLogic.fromXmltvNs("1/4.0/10."))
        assertEquals("E08", GuideLogic.fromXmltvNs(".7."))
        assertEquals("S06", GuideLogic.fromXmltvNs("5.."))
        assertNull(GuideLogic.fromXmltvNs(".."))
        assertNull(GuideLogic.fromXmltvNs("garbage"))
    }

    @Test fun onscreenForms() {
        assertEquals("S01E05", GuideLogic.fromOnscreen("S01E05"))
        assertEquals("S01E05", GuideLogic.fromOnscreen("S1 E5"))
        assertEquals("S02E10", GuideLogic.fromOnscreen("2x10"))
        assertEquals("E12", GuideLogic.fromOnscreen("Episode 12"))
        assertEquals("Part 2", GuideLogic.fromOnscreen("Part 2"))
        assertNull(GuideLogic.fromOnscreen("   "))
    }

    @Test fun episodeChoosesBestSystem() {
        assertEquals("S01E05", GuideLogic.episode(listOf("onscreen" to "S9E9", "xmltv_ns" to "0.4.")))
        assertEquals("S04E02", GuideLogic.episode(listOf("onscreen" to "S4E2")))
        assertEquals("EP01234567.0012", GuideLogic.episode(listOf("dd_progid" to "EP01234567.0012")))
        assertNull(GuideLogic.episode(listOf("dd_progid" to "MV00012345.0000")))   // a film
        assertNull(GuideLogic.episode(listOf("dd_progid" to "SH00012345.0000")))   // a show without episode
        assertNull(GuideLogic.episode(emptyList()))
        assertNull(GuideLogic.episode(listOf("xmltv_ns" to "  ")))
        assertEquals("1234", GuideLogic.episode(listOf("common" to "1234")))
    }

    @Test fun categoriesRoundTrip() {
        val s = GuideLogic.joinCategories(listOf(" Sports ", "Football", "Sports", "", "News|Talk"))
        assertEquals("Sports|Football|News/Talk", s)
        assertEquals(listOf("Sports", "Football", "News/Talk"), GuideLogic.splitCategories(s))
        assertNull(GuideLogic.joinCategories(listOf(" ", "")))
        assertEquals(emptyList<String>(), GuideLogic.splitCategories(null))
    }

    @Test fun descriptionsAreCutAtAWord() {
        assertNull(GuideLogic.shortDesc("   "))
        assertEquals("short", GuideLogic.shortDesc(" short "))
        val long = "word ".repeat(200)
        val d = GuideLogic.shortDesc(long)!!
        assertTrue(d.length <= GuideLogic.MAX_DESC + 2)
        assertTrue(d.endsWith(" …"))
    }

    @Test fun signatureChangesOnlyWhenInputsChange() {
        val files = listOf(Triple("epg_1.xml.gz", 1000L, 5000L))
        val a = GuideLogic.signature(files, setOf("b.us", "a.us"), mapOf("a.us" to 2))
        assertEquals(a, GuideLogic.signature(files, listOf("a.us", "b.us"), mapOf("a.us" to 2)))   // order doesn't matter
        assertNotEquals(a, GuideLogic.signature(listOf(Triple("epg_1.xml.gz", 2000L, 5000L)), setOf("a.us", "b.us"), mapOf("a.us" to 2)))
        assertNotEquals(a, GuideLogic.signature(files, setOf("a.us"), mapOf("a.us" to 2)))
        assertNotEquals(a, GuideLogic.signature(files, setOf("a.us", "b.us"), mapOf("a.us" to 3)))
    }
}

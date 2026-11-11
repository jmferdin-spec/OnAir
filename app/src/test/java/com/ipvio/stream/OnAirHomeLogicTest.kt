package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir Phase 2/4: five-tile home, standalone guide, "now" screens and their tile counts. */
class OnAirHomeLogicTest {

    @Test fun fiveTilesInSpecOrderLiveFirst() {
        assertEquals(listOf(OnAirTile.LIVE, OnAirTile.MOVIES, OnAirTile.SHOWS, OnAirTile.SPORTS, OnAirTile.EVENTS), OnAirHomeLogic.TILES)
        assertEquals(OnAirTile.LIVE, OnAirHomeLogic.INITIAL)
    }

    @Test fun englishLabels() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI)
        assertEquals(listOf("Live TV", "Movies", "Shows", "Sports", "Events"), OnAirHomeLogic.TILES.map { I18nCore.translate(it.label, 1) })
        assertEquals("1234 channels", I18nCore.translate(OnAirHomeLogic.subtitle(OnAirTile.LIVE, 1234)!!, 1))
        assertEquals("12 on now", I18nCore.translate(OnAirHomeLogic.subtitle(OnAirTile.MOVIES, 1234, 12)!!, 1))
        assertEquals("Nothing on now", I18nCore.translate(OnAirHomeLogic.subtitle(OnAirTile.SHOWS, 1234, 0)!!, 1))
        assertEquals("3 live", I18nCore.translate(OnAirHomeLogic.subtitle(OnAirTile.EVENTS, 1234, 3)!!, 1))
        assertEquals("Nothing live now", I18nCore.translate(OnAirHomeLogic.subtitle(OnAirTile.EVENTS, 1234, 0)!!, 1))
    }

    @Test fun liveTvOpensTheGuideTheOthersTheirNowScreen() {
        assertTrue(OnAirHomeLogic.opensGuide(OnAirTile.LIVE))
        for (t in OnAirTile.entries - OnAirTile.LIVE) assertFalse(t.name, OnAirHomeLogic.opensGuide(t))
    }

    @Test fun countsOnlyWhenKnown() {
        assertNull(OnAirHomeLogic.subtitle(OnAirTile.LIVE, 0))
        assertEquals("7 Sender", OnAirHomeLogic.subtitle(OnAirTile.LIVE, 7))
        assertEquals("7 Sender", OnAirHomeLogic.subtitle(OnAirTile.LIVE, 7, 3))          // Live TV keeps the channel count
        for (t in OnAirTile.entries - OnAirTile.LIVE) assertNull(t.name, OnAirHomeLogic.subtitle(t, 7, null))   // snapshot not there yet
        assertEquals("5 laufen gerade", OnAirHomeLogic.subtitle(OnAirTile.SPORTS, 7, 5))
    }

    @Test fun fiveInARowWhenTheyFitElseThreePlusTwo() {
        assertEquals(5, OnAirHomeLogic.columns(900f))              // 5 × 150 + 4 × 20 = 830
        assertEquals(5, OnAirHomeLogic.columns(830f))
        assertEquals(3, OnAirHomeLogic.columns(829f))
        assertEquals(listOf(5), OnAirHomeLogic.rows(5).map { it.size })
        assertEquals(listOf(3, 2), OnAirHomeLogic.rows(3).map { it.size })
        assertEquals(OnAirTile.LIVE, OnAirHomeLogic.rows(3)[0][0])
    }

    @Test fun guideOpensAtLastWatchedChannelOfTheList() {
        val ids = listOf("c1", "c2", "c3")
        assertEquals("c3", OnAirHomeLogic.guideStart(listOf("movie9", "c3", "c1"), ids))
        assertEquals("c1", OnAirHomeLogic.guideStart(listOf("movie9", "other"), ids))   // nothing watched in this list → first
        assertEquals("c1", OnAirHomeLogic.guideStart(emptyList(), ids))
        assertEquals("", OnAirHomeLogic.guideStart(listOf("c3"), emptyList()))
    }

    @Test fun backReturnsWhereItCameFrom() {
        assertEquals(OnAirScreen.HOME, OnAirHomeLogic.backFrom(OnAirScreen.GUIDE, OnAirScreen.HOME))
        assertEquals(OnAirScreen.VOD, OnAirHomeLogic.backFrom(OnAirScreen.GUIDE, OnAirScreen.VOD))   // opened from IPVIO's Live TV
        assertEquals(OnAirScreen.HOME, OnAirHomeLogic.backFrom(OnAirScreen.NOW, OnAirScreen.HOME))
        assertEquals(OnAirScreen.HOME, OnAirHomeLogic.backFrom(OnAirScreen.VOD, OnAirScreen.HOME))
        assertEquals(OnAirScreen.HOME, OnAirHomeLogic.backFrom(OnAirScreen.HOME, OnAirScreen.HOME))
        assertEquals(OnAirScreen.VOD, OnAirHomeLogic.backFrom(OnAirScreen.DEBUG, OnAirScreen.VOD))   // debug screen → Settings
    }
}

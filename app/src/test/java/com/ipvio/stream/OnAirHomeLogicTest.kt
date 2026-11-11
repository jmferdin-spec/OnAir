package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir Phase 2: five-tile home, standalone guide, placeholders. */
class OnAirHomeLogicTest {

    @Test fun fiveTilesInSpecOrderLiveFirst() {
        assertEquals(listOf(OnAirTile.LIVE, OnAirTile.MOVIES, OnAirTile.SHOWS, OnAirTile.SPORTS, OnAirTile.EVENTS), OnAirHomeLogic.TILES)
        assertEquals(OnAirTile.LIVE, OnAirHomeLogic.INITIAL)
    }

    @Test fun englishLabels() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI)
        assertEquals(listOf("Live TV", "Movies", "Shows", "Sports", "Events"), OnAirHomeLogic.TILES.map { I18nCore.translate(it.label, 1) })
        assertEquals("1234 channels", I18nCore.translate(OnAirHomeLogic.subtitle(OnAirTile.LIVE, 1234)!!, 1))
        assertEquals("Coming in Phase 4", I18nCore.translate(OnAirHomeLogic.subtitle(OnAirTile.SPORTS, 1234)!!, 1))
    }

    @Test fun onlyLiveTvIsReadyInPhase2() {
        assertTrue(OnAirHomeLogic.isReady(OnAirTile.LIVE))
        for (t in OnAirTile.entries - OnAirTile.LIVE) assertFalse(t.name, OnAirHomeLogic.isReady(t))
    }

    @Test fun liveCountOnlyWhenKnown() {
        assertNull(OnAirHomeLogic.subtitle(OnAirTile.LIVE, 0))
        assertEquals("7 Sender", OnAirHomeLogic.subtitle(OnAirTile.LIVE, 7))
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
        assertEquals(OnAirScreen.HOME, OnAirHomeLogic.backFrom(OnAirScreen.SOON, OnAirScreen.HOME))
        assertEquals(OnAirScreen.HOME, OnAirHomeLogic.backFrom(OnAirScreen.VOD, OnAirScreen.HOME))
        assertEquals(OnAirScreen.HOME, OnAirHomeLogic.backFrom(OnAirScreen.HOME, OnAirScreen.HOME))
    }
}

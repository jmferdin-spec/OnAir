package com.ipvio.stream

import com.ipvio.stream.OnNowLogic.Chan
import com.ipvio.stream.OnNowLogic.Prog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir Phase 4: rules of the Movies, Shows, Sports and Events screens. Invented data only. */
class NowScreenLogicTest {
    private val now = 1_800_000_000L
    private val m = 60L

    private fun item(cls: OnNowClass, title: String, startMin: Long, lenMin: Long = 60, sport: Sport? = null, chan: String = "Channel $title") =
        OnNowItem(cls, sport, Prog("x.us", title, now + startMin * m, now + (startMin + lenMin) * m), listOf(Chan(chan, chan, "GROUP")))

    @Test fun moviesMostRecentlyStartedFirstOrByChannel() {
        val items = listOf(
            item(OnNowClass.MOVIE, "Old Film", -100, 120, chan = "Cinema 2"),
            item(OnNowClass.MOVIE, "New Film", -5, 110, chan = "Cinema 10"),
            item(OnNowClass.MOVIE, "Mid Film", -40, 100, chan = "Cinema 1"),
            item(OnNowClass.SHOW, "A Show", -1),
            item(OnNowClass.MOVIE, "Finished", -130, 120)                  // ended 10 min ago: drops off
        )
        assertEquals(listOf("New Film", "Mid Film", "Old Film"), NowScreenLogic.cards(items, OnNowClass.MOVIE, now).map { it.title })
        assertEquals(listOf("Mid Film", "Old Film", "New Film"),
            NowScreenLogic.cards(items, OnNowClass.MOVIE, now, NowScreenLogic.Sort.CHANNEL).map { it.title })   // Cinema 1, 2, 10
        assertEquals(listOf("A Show"), NowScreenLogic.cards(items, OnNowClass.SHOW, now).map { it.title })
    }

    @Test fun durations() {
        assertEquals("0 min", NowScreenLogic.dur(-3))
        assertEquals("45 min", NowScreenLogic.dur(45))
        assertEquals("1h", NowScreenLogic.dur(60))
        assertEquals("1h 10m", NowScreenLogic.dur(70))
        assertEquals("2h 5m", NowScreenLogic.dur(125))
    }

    @Test fun timeLineInEnglish() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI)
        val i = item(OnNowClass.MOVIE, "Quiet Harbor", -45, 115)
        assertEquals("45 min gelaufen · noch 1h 10m", NowScreenLogic.timeLine(i, now))
        assertEquals("45 min in · 1h 10m left", I18nCore.translate(NowScreenLogic.timeLine(i, now), 1))
        assertEquals("starts in 25 min", I18nCore.translate(NowScreenLogic.startsIn(now + 25 * m - 30, now), 1))
        assertEquals("Soccer 7 · 2 soon", I18nCore.translate(NowScreenLogic.groupLabel(NowScreenLogic.SportGroup(Sport.SOCCER, 7, 2)), 1))
        assertEquals("NFL · 2 soon", I18nCore.translate(NowScreenLogic.groupLabel(NowScreenLogic.SportGroup(Sport.NFL, 0, 2)), 1))
        assertEquals("NBA 3", NowScreenLogic.groupLabel(NowScreenLogic.SportGroup(Sport.NBA, 3, 0)))
        assertEquals("Live now", I18nCore.translate("Läuft gerade", 1))
        assertEquals("Nothing classified as a movie is airing right now", I18nCore.translate("Gerade läuft nichts, das als Film erkannt wird", 1))
        assertEquals("4 empty PPV slots hidden", I18nCore.translate("4 leere PPV-Plätze ausgeblendet", 1))
    }

    @Test fun sportGroupsWithLiveAndSoonCounts() {
        val live = listOf(
            item(OnNowClass.SPORTS, "Ridgebacks vs Stormers", -30, sport = Sport.NHL),
            item(OnNowClass.SPORTS, "Harbor FC vs Lions", -10, sport = Sport.SOCCER),
            item(OnNowClass.SPORTS, "City vs United", -50, sport = Sport.SOCCER),
            item(OnNowClass.SPORTS, "Over Already", -120, 60, sport = Sport.GOLF),     // finished
            item(OnNowClass.MOVIE, "A Film", -20)
        )
        val soon = listOf(
            item(OnNowClass.SPORTS, "Kickoff Show", 20, sport = Sport.NFL),
            item(OnNowClass.SPORTS, "Later Game", 90, sport = Sport.NBA),             // beyond 60 min
            item(OnNowClass.SPORTS, "Derby", 45, sport = Sport.SOCCER),
            item(OnNowClass.SHOW, "Not Sport", 10)
        )
        val g = NowScreenLogic.sportGroups(live, soon, now)
        assertEquals(listOf(
            NowScreenLogic.SportGroup(Sport.NFL, 0, 1),
            NowScreenLogic.SportGroup(Sport.NHL, 1, 0),
            NowScreenLogic.SportGroup(Sport.SOCCER, 2, 1)
        ), g)                                                                         // in Sport order
        assertEquals(listOf("Kickoff Show", "Derby"), NowScreenLogic.startingSoon(soon, now).map { it.title })
        assertEquals(Sport.SOCCER, NowScreenLogic.pickSport(g, Sport.SOCCER))
        assertEquals(Sport.NFL, NowScreenLogic.pickSport(g, Sport.GOLF))              // remembered group has no games now
        assertEquals(Sport.NFL, NowScreenLogic.pickSport(g, null))
        assertNull(NowScreenLogic.pickSport(emptyList(), Sport.NFL))
    }

    @Test fun tileCounts() {
        val items = listOf(
            item(OnNowClass.MOVIE, "F1", -10), item(OnNowClass.MOVIE, "F2", -20), item(OnNowClass.SHOW, "S1", -5),
            item(OnNowClass.EVENT, "E1", -15), item(OnNowClass.MOVIE, "Gone", -200, 60)
        )
        assertEquals(2, NowScreenLogic.tileCount(OnAirTile.MOVIES, items, now))
        assertEquals(1, NowScreenLogic.tileCount(OnAirTile.SHOWS, items, now))
        assertEquals(0, NowScreenLogic.tileCount(OnAirTile.SPORTS, items, now))
        assertEquals(1, NowScreenLogic.tileCount(OnAirTile.EVENTS, items, now))
        assertNull(NowScreenLogic.tileCount(OnAirTile.LIVE, items, now))
    }

    @Test fun cardKeysAreStableAndDistinct() {
        val a = item(OnNowClass.MOVIE, "Quiet Harbor", -45)
        val b = item(OnNowClass.MOVIE, "Quiet Harbor", -45, chan = "Other Cinema")   // same programme, other channel
        val c = item(OnNowClass.MOVIE, "Quiet Harbor", -10)
        assertEquals(NowScreenLogic.key(a), NowScreenLogic.key(b))
        assertFalse(NowScreenLogic.key(a) == NowScreenLogic.key(c))
        assertTrue(NowScreenLogic.stillOn(a, now))
        assertFalse(NowScreenLogic.stillOn(a, a.prog.stop))
    }

    @Test fun classOfTiles() {
        assertNull(NowScreenLogic.classOf(OnAirTile.LIVE))
        assertEquals(OnNowClass.SPORTS, NowScreenLogic.classOf(OnAirTile.SPORTS))
        assertEquals(OnNowClass.EVENT, NowScreenLogic.classOf(OnAirTile.EVENTS))
    }
}

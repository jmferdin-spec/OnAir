package com.ipvio.stream

import com.ipvio.stream.MultiviewLogic.Action
import com.ipvio.stream.MultiviewLogic.Dir
import com.ipvio.stream.MultiviewLogic.Layout
import com.ipvio.stream.MultiviewLogic.Result
import com.ipvio.stream.MultiviewLogic.Screen
import com.ipvio.stream.MultiviewLogic.Source
import com.ipvio.stream.MultiviewLogic.State
import com.ipvio.stream.MultiviewLogic.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir v2 (Phase 7): Multiview rules – layouts, selection, menu, remove / full screen / exit, decoding budget, sources. */
class MultiviewLogicTest {
    private val two = MultiviewLogic.start("espn", "fox")

    @Test fun startsSideBySideWithTheAddedScreenSelected() {
        assertEquals(listOf("espn", "fox"), two.screens.map { it.channelId })
        assertEquals(Layout.SPLIT, two.layout)
        assertEquals(1, two.selected)
        assertNull(MultiviewLogic.add(two, "cnn"))                                 // v2: two screens at most
        assertEquals(3, MultiviewLogic.add(two, "cnn", max = 4)!!.count)          // designed for more later
    }

    @Test fun leftRightMoveTheSelectionSideBySide() {
        assertEquals(0, MultiviewLogic.move(two, Dir.LEFT).selected)
        assertEquals(1, MultiviewLogic.move(two, Dir.RIGHT).selected)              // already right: stays
        assertEquals(1, MultiviewLogic.move(two, Dir.UP).selected)                 // no row above
        assertEquals(1, MultiviewLogic.move(two, Dir.DOWN).selected)
        // 2×2 later: Down/Up between rows
        val four = State(listOf(Screen("a"), Screen("b"), Screen("c"), Screen("d")), selected = 1)
        assertEquals(3, MultiviewLogic.move(four, Dir.DOWN).selected)
        assertEquals(0, MultiviewLogic.move(four, Dir.LEFT).selected)
    }

    @Test fun enlargedUpDownBetweenLargeAndSmall() {
        val big = (MultiviewLogic.act(two, 0, Action.ENLARGE) as Result.Stay).state
        assertEquals(Layout.ENLARGED, big.layout); assertEquals(0, big.large); assertEquals(0, big.selected)
        assertEquals(1, MultiviewLogic.move(big, Dir.DOWN).selected)              // to the small screen (audio follows)
        assertEquals(0, MultiviewLogic.move(MultiviewLogic.move(big, Dir.DOWN), Dir.UP).selected)
        assertEquals(0, MultiviewLogic.move(big, Dir.LEFT).selected)              // large screen: Left/Right do nothing
    }

    @Test fun menuReadsReduceOnTheEnlargedScreenAndResumeWhenPaused() {
        assertEquals(listOf(Action.CHANGE_CHANNEL, Action.PAUSE, Action.ENLARGE, Action.FULL_SCREEN, Action.REMOVE), MultiviewLogic.menu(two, 0))
        val big = (MultiviewLogic.act(two, 1, Action.ENLARGE) as Result.Stay).state
        assertTrue(Action.REDUCE in MultiviewLogic.menu(big, 1))
        assertTrue(Action.ENLARGE in MultiviewLogic.menu(big, 0))
        val back = (MultiviewLogic.act(big, 1, Action.REDUCE) as Result.Stay).state
        assertEquals(Layout.SPLIT, back.layout)
        val paused = (MultiviewLogic.act(two, 0, Action.PAUSE) as Result.Stay).state
        assertTrue(paused.screens[0].paused)
        assertTrue(Action.RESUME in MultiviewLogic.menu(paused, 0))
        assertFalse((MultiviewLogic.act(paused, 0, Action.RESUME) as Result.Stay).state.screens[0].paused)
        assertTrue(MultiviewLogic.resumeAtLive)
    }

    @Test fun fullScreenRemoveChangeAndExit() {
        assertEquals(Result.FullScreen("fox"), MultiviewLogic.act(two, 1, Action.FULL_SCREEN))        // the other stream stops
        assertEquals(Result.FullScreen("espn"), MultiviewLogic.act(two, 1, Action.REMOVE))            // the remaining one goes full-screen
        assertEquals(Result.Pick(0), MultiviewLogic.act(two, 0, Action.CHANGE_CHANNEL))
        val changed = MultiviewLogic.change(two, 0, "cnn")
        assertEquals(listOf("cnn", "fox"), changed.screens.map { it.channelId }); assertEquals(1, changed.selected)
        assertEquals("fox", MultiviewLogic.exitTo(two))                                                // Back → Yes: selected stays
        assertEquals("espn", MultiviewLogic.exitTo(MultiviewLogic.move(two, Dir.LEFT)))
        // with more screens later, removing one keeps multiview going
        val three = State(listOf(Screen("a"), Screen("b"), Screen("c")), selected = 2)
        val r = MultiviewLogic.remove(three, 0) as Result.Stay
        assertEquals(listOf("b", "c"), r.state.screens.map { it.channelId }); assertEquals(1, r.state.selected)
    }

    @Test fun boxesSideBySideAndEnlarged() {
        val w = 1920f; val h = 1080f
        val split = MultiviewLogic.boxes(two, w, h)
        assertEquals(2, split.size)
        assertEquals(split[0].w, split[1].w, 0.01f)                                // 50/50
        assertEquals(16f / 9f, split[0].w / split[0].h, 0.01f)
        assertTrue(split[0].x + split[0].w <= split[1].x)                           // no overlap
        val big = (MultiviewLogic.act(two, 0, Action.ENLARGE) as Result.Stay).state
        val e = MultiviewLogic.boxes(big, w, h)
        assertTrue(e[0].w >= w * 0.6f && e[0].w <= w * 0.75f + 0.5f)                // large ≈ 75 % width
        assertTrue(e[1].y >= e[0].y + e[0].h)                                       // small one along the bottom
        assertTrue(e[1].w < e[0].w / 2)
        val four = State(listOf(Screen("a"), Screen("b"), Screen("c"), Screen("d")))
        assertEquals(4, MultiviewLogic.boxes(four, w, h).size)                      // 2×2 already defined
    }

    @Test fun decodingBudget() {
        // onn 4K Plus: AVC measured 134 fps at 1080p, HEVC 247 fps
        val budget = mapOf("video/avc" to MultiviewLogic.budgetFrom1080p(134.0), "video/hevc" to MultiviewLogic.budgetFrom1080p(247.0))
        val avc1080p50 = Stream("video/avc", 1920, 1080, 50f)
        val avc1080p60 = Stream("video/avc", 1920, 1080, 60f)
        val avc4k30 = Stream("video/avc", 3840, 2160, 30f)
        val hevc4k50 = Stream("video/hevc", 3840, 2160, 50f)
        assertTrue(MultiviewLogic.fits(budget, listOf(avc1080p50, avc1080p50)))
        assertTrue(MultiviewLogic.fits(budget, listOf(avc1080p60, avc1080p60)))     // 120 of 134: tight but in
        assertFalse(MultiviewLogic.fits(budget, listOf(avc4k30, avc1080p50)))       // AVC 4K + anything: over
        assertTrue(MultiviewLogic.fits(budget, listOf(hevc4k50, avc1080p50)))       // different decoders
        assertFalse(MultiviewLogic.fits(budget, listOf(hevc4k50, hevc4k50)))
        assertTrue(MultiviewLogic.fits(budget, listOf(Stream("video/mpeg2", 1920, 1080, 50f))))   // unknown codec: no guess
        assertEquals(8160L * 50, MultiviewLogic.blocksPerSecond(avc1080p50))
    }

    @Test fun secondScreenUsesALowerSourceNextTo4k() {
        val wanted = Source("espn-4k", "4K")
        val variants = listOf(wanted, Source("espn-hd", "HD"), Source("espn-fhd", "FHD"))
        assertEquals(MultiviewLogic.Choice(Source("espn-fhd", "FHD"), false), MultiviewLogic.chooseSource(wanted, variants, listOf("4K")))
        // nothing else in 4K: keep what was picked
        assertEquals(MultiviewLogic.Choice(wanted, false), MultiviewLogic.chooseSource(wanted, variants, listOf("FHD")))
        // only in 4K: try anyway, with the "may stutter" note
        assertEquals(MultiviewLogic.Choice(wanted, true), MultiviewLogic.chooseSource(wanted, listOf(wanted), listOf("UHD")))
        // a 1080p pick next to 4K is fine as it is
        val fhd = Source("fox-fhd", "FHD")
        assertEquals(MultiviewLogic.Choice(fhd, false), MultiviewLogic.chooseSource(fhd, listOf(fhd), listOf("4K")))
    }

    @Test fun streamLimitMessageOnlyWhenAnotherScreenPlays() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI)
        val m = MultiviewLogic.startFailure(httpCode = 403, connectionRefused = false, othersPlaying = 1, maxConnections = 2)!!
        assertEquals("Your account allows 2 streams; one may be in use on another device", I18nCore.translate(m, 1))
        assertEquals("Your account allows 2 streams; one may be in use on another device",
            I18nCore.translate(MultiviewLogic.startFailure(null, connectionRefused = true, othersPlaying = 1, maxConnections = null)!!, 1))
        assertNull(MultiviewLogic.startFailure(403, false, othersPlaying = 0, maxConnections = 2))     // alone: the normal error
        assertNull(MultiviewLogic.startFailure(404, false, othersPlaying = 1, maxConnections = 2))     // channel gone: not a limit
        assertTrue(MultiviewLogic.available(2)); assertTrue(MultiviewLogic.available(null)); assertFalse(MultiviewLogic.available(1))
    }

    // ------------------------------------------------------------------ Phase 8: picker search

    private val chans = listOf(
        MultiviewLogic.SearchChan("c1", "ESPN HD", "US| SPORTS"),
        MultiviewLogic.SearchChan("c2", "TUDN Fútbol", "LATINO| DEPORTES"),
        MultiviewLogic.SearchChan("c3", "Cinema One", "US| MOVIES"),
        MultiviewLogic.SearchChan("c4", "ESPN 2", "US| SPORTS")
    )
    private fun now(title: String, chan: String) = OnNowItem(
        OnNowClass.SPORTS, Sport.NFL, OnNowLogic.Prog("x.us", title, 0, 3600), listOf(OnNowLogic.Chan(chan, "Chan $chan", "US| SPORTS"))
    )

    @Test fun searchFindsProgrammesFirstThenChannels() {
        val onNow = listOf(now("Eagles vs Cowboys", "c4"), now("Evening News", "c9"))
        val hits = MultiviewLogic.search("espn", chans, onNow)
        assertEquals(listOf("c1", "c4"), hits.map { it.channelId })
        val game = MultiviewLogic.search("eagles", chans, onNow)
        assertEquals(listOf(MultiviewLogic.Hit("c4", "Eagles vs Cowboys", "Chan c4", true)), game)
        // a channel found by its programme is not listed again by name
        // "es": the programme "Eagles …" on c4 first, then channel ESPN HD (c1); ESPN 2 is c4 – already listed, not again
        assertEquals(listOf("c4", "c1"), MultiviewLogic.search("es", chans, onNow).map { it.channelId })
    }

    @Test fun searchIsAccentAndCaseInsensitiveAndNeedsTwoLetters() {
        assertEquals(listOf("c2"), MultiviewLogic.search("FUTBOL", chans, emptyList()).map { it.channelId })
        assertEquals(listOf("c2"), MultiviewLogic.search("tudn fút", chans, emptyList()).map { it.channelId })
        assertTrue(MultiviewLogic.search("e", chans, emptyList()).isEmpty())          // one letter: nothing yet
        assertTrue(MultiviewLogic.search("  ", chans, emptyList()).isEmpty())
        assertEquals(1, MultiviewLogic.search("es", chans, emptyList(), max = 1).size)
    }

    @Test fun phase8TextsInEnglish() {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI)
        assertEquals("Exit multiview?", I18nCore.translate("Multiview beenden?", 1))
        assertEquals("Enlarge screen", I18nCore.translate("Bild vergrößern", 1))
        assertEquals("Reduce screen", I18nCore.translate("Bild verkleinern", 1))
        assertEquals("Playback failed (HTTP 502)", I18nCore.translate("Wiedergabe fehlgeschlagen (HTTP 502)", 1))
        assertEquals("Add channel", I18nCore.translate("Sender für Multiview hinzufügen", 1))
    }
}

package com.ipvio.stream

import com.ipvio.stream.GuideUpdateLogic.Blocked
import com.ipvio.stream.GuideUpdateLogic.Phase
import com.ipvio.stream.RefreshLogic.Wait
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** OnAir v2 (Phase 6): the "Update guide" line on the home screen. */
class GuideUpdateLogicTest {
    private val t0 = 1_800_000_000_000L
    private val min = 60_000L
    /** Stand-in for the screen's time format: minutes after t0. */
    private val fmt: (Long) -> String = { "T+" + (it - t0) / min }

    private fun en(s: String): String {
        I18nCore.load(DictA, DictB, DictC, DictD, DictE, DictF, DictG, DictH, DictI)
        return I18nCore.translate(s, 1)
    }

    @Test fun runningStepWinsEverything() {
        val b = Blocked(Wait.INTERVAL, t0 + 15 * min, t0)
        assertEquals("Downloading guide …", en(GuideUpdateLogic.line(Phase.DOWNLOADING, b, "HTTP 500", t0, t0, fmt)))
        assertEquals("Importing guide …", en(GuideUpdateLogic.line(Phase.IMPORTING, null, null, 0, t0, fmt)))
    }

    @Test fun tooSoonSaysWhyAndFromWhen() {
        assertEquals("Guide was updated at T+0 – next update possible from T+15",
            en(GuideUpdateLogic.line(null, Blocked(Wait.INTERVAL, t0 + 15 * min, t0), null, 0, t0, fmt)))
        assertEquals("The last attempt failed – next try from T+30",
            en(GuideUpdateLogic.line(null, Blocked(Wait.BACKOFF, t0 + 30 * min, t0), null, 0, t0, fmt)))
        assertEquals("The provider asked us to wait – next try from T+40",
            en(GuideUpdateLogic.line(null, Blocked(Wait.PROVIDER, t0 + 40 * min, t0), null, 0, t0, fmt)))
        assertEquals("Next update possible from T+15",
            en(GuideUpdateLogic.blockedText(Blocked(Wait.INTERVAL, t0 + 15 * min, 0), fmt)))
    }

    @Test fun errorOnlyWhileNewerThanTheStoredGuide() {
        assertEquals("Update failed: HTTP 503", en(GuideUpdateLogic.line(null, null, "HTTP 503", t0 + 5 * min, t0, fmt)))
        // the guide was downloaded after that error (e.g. by the schedule): show the update time again
        assertEquals("Guide updated T+10", en(GuideUpdateLogic.line(null, null, "HTTP 503", t0 + 5 * min, t0 + 10 * min, fmt)))
    }

    @Test fun updatedTimeOrNothingYet() {
        assertEquals("Guide updated T+0", en(GuideUpdateLogic.line(null, null, null, 0, t0, fmt)))
        assertEquals("No guide loaded yet", en(GuideUpdateLogic.line(null, null, null, 0, 0, fmt)))
        assertEquals("Update guide", en("Programm aktualisieren"))
    }

    @Test fun severalGuideAddressesRunIfAnyIsFree() {
        val now = t0
        // provider guide blocked 10 more minutes, custom guide free → may run
        assertNull(GuideUpdateLogic.combine(now, listOf((now + 10 * min) to Wait.INTERVAL, now to Wait.NONE)))
        // both blocked → the one free soonest
        assertEquals(Wait.BACKOFF to now + 5 * min,
            GuideUpdateLogic.combine(now, listOf((now + 10 * min) to Wait.INTERVAL, (now + 5 * min) to Wait.BACKOFF)))
        assertNull(GuideUpdateLogic.combine(now, emptyList()))
    }
}

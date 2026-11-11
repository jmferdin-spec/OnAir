package com.ipvio.stream

import com.ipvio.stream.RefreshLogic.HOUR
import com.ipvio.stream.RefreshLogic.MIN
import com.ipvio.stream.RefreshLogic.State
import com.ipvio.stream.RefreshLogic.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir: provider rate-limit protection – when the catalog / guide may be fetched again. */
class RefreshLogicTest {
    private val now = 1_800_000_000_000L
    private val fresh = State(lastSuccess = now - 2 * HOUR, lastAttempt = now - 2 * HOUR)

    // ------------------------------------------------------------------ app start / login with something stored

    @Test fun app_start_with_cache_never_fetches() {
        assertFalse(RefreshLogic.allowed(fresh, now, Trigger.AUTO, hasCache = true))
        // not even when the stored copy is old – that's the background job's job
        val old = State(lastSuccess = now - 30 * 24 * HOUR, lastAttempt = now - 30 * 24 * HOUR)
        assertFalse(RefreshLogic.allowed(old, now, Trigger.AUTO, hasCache = true))
        assertNull(RefreshLogic.nextAllowedAt(old, now, Trigger.AUTO, hasCache = true))
    }

    @Test fun app_start_without_cache_fetches_unless_backing_off() {
        assertTrue(RefreshLogic.allowed(State(), now, Trigger.AUTO, hasCache = false))
        // cache was deleted although the last success was recent: still allowed (otherwise the app would stay empty)
        assertTrue(RefreshLogic.allowed(fresh, now, Trigger.AUTO, hasCache = false))
        val failedJustNow = RefreshLogic.failed(State(), now - MIN)
        assertFalse(RefreshLogic.allowed(failedJustNow, now, Trigger.AUTO, hasCache = false))
        assertTrue(RefreshLogic.allowed(failedJustNow, now + 15 * MIN, Trigger.AUTO, hasCache = false))
    }

    // ------------------------------------------------------------------ background schedule

    @Test fun scheduled_waits_12_hours_after_success() {
        val s = State(lastSuccess = now, lastAttempt = now)
        assertFalse(RefreshLogic.allowed(s, now + 11 * HOUR + 59 * MIN, Trigger.SCHEDULED, true))
        assertTrue(RefreshLogic.allowed(s, now + 12 * HOUR, Trigger.SCHEDULED, true))
        assertEquals(now + 12 * HOUR, RefreshLogic.nextAllowedAt(s, now + HOUR, Trigger.SCHEDULED, true))
    }

    @Test fun scheduled_never_run_or_no_cache_is_allowed() {
        assertTrue(RefreshLogic.allowed(State(), now, Trigger.SCHEDULED, true))
        assertTrue(RefreshLogic.allowed(fresh, now, Trigger.SCHEDULED, hasCache = false))
    }

    // ------------------------------------------------------------------ back-off

    @Test fun backoff_grows_and_caps_at_12_hours() {
        assertEquals(0L, RefreshLogic.backoffMs(0))
        assertEquals(15 * MIN, RefreshLogic.backoffMs(1))
        assertEquals(30 * MIN, RefreshLogic.backoffMs(2))
        assertEquals(HOUR, RefreshLogic.backoffMs(3))
        assertEquals(12 * HOUR, RefreshLogic.backoffMs(7))
        assertEquals(12 * HOUR, RefreshLogic.backoffMs(50))
        for (f in 1..10) assertTrue(RefreshLogic.backoffMs(f + 1) >= RefreshLogic.backoffMs(f))
    }

    @Test fun scheduled_after_failures_waits_backoff_not_an_hour_tick() {
        val start = State(lastSuccess = now - 13 * HOUR, lastAttempt = now - 13 * HOUR)
        var s = RefreshLogic.failed(start, now)
        assertEquals(1, s.failures)
        assertFalse(RefreshLogic.allowed(s, now + 14 * MIN, Trigger.SCHEDULED, true))
        assertTrue(RefreshLogic.allowed(s, now + 15 * MIN, Trigger.SCHEDULED, true))
        s = RefreshLogic.failed(s, now + 15 * MIN)
        assertFalse(RefreshLogic.allowed(s, now + 40 * MIN, Trigger.SCHEDULED, true))
        assertTrue(RefreshLogic.allowed(s, now + 45 * MIN, Trigger.SCHEDULED, true))
        // success clears everything and the 12-hour rule applies again
        val ok = RefreshLogic.succeeded(s, now + HOUR)
        assertEquals(0, ok.failures)
        assertFalse(RefreshLogic.allowed(ok, now + 2 * HOUR, Trigger.SCHEDULED, true))
    }

    @Test fun failure_keeps_last_success() {
        val s = RefreshLogic.failed(fresh, now)
        assertEquals(fresh.lastSuccess, s.lastSuccess)
        assertEquals(now, s.lastAttempt)
    }

    @Test fun cancelled_attempt_is_not_a_failure() {
        val s = RefreshLogic.attempted(fresh, now)
        assertEquals(0, s.failures)
        assertEquals(now, s.lastAttempt)
    }

    // ------------------------------------------------------------------ manual refresh

    @Test fun manual_works_any_time_except_double_press() {
        assertTrue(RefreshLogic.allowed(fresh, now, Trigger.MANUAL, true))
        val pressed = RefreshLogic.succeeded(fresh, now)
        assertFalse(RefreshLogic.allowed(pressed, now + 5_000, Trigger.MANUAL, true))
        assertTrue(RefreshLogic.allowed(pressed, now + RefreshLogic.MANUAL_DEBOUNCE_MS, Trigger.MANUAL, true))
        // back-off does not block the owner (e.g. retyped password after a typo)
        val failed = RefreshLogic.failed(RefreshLogic.failed(State(), now - MIN), now - MIN)
        assertTrue(RefreshLogic.allowed(failed, now, Trigger.MANUAL, false))
    }

    // ------------------------------------------------------------------ provider cooldown (Retry-After)

    @Test fun provider_cooldown_blocks_every_trigger() {
        val s = RefreshLogic.failed(State(), now, cooldownMs = 2 * HOUR)
        for (t in Trigger.entries) {
            assertFalse(t.name, RefreshLogic.allowed(s, now + HOUR, t, hasCache = false))
        }
        assertTrue(RefreshLogic.allowed(s, now + 2 * HOUR, Trigger.MANUAL, false))
        assertEquals(now + 2 * HOUR, RefreshLogic.nextAllowedAt(s, now + HOUR, Trigger.MANUAL, false))
    }

    @Test fun provider_cooldown_is_capped() {
        val s = RefreshLogic.failed(State(), now, cooldownMs = 30 * 24 * HOUR)
        assertEquals(now + RefreshLogic.MAX_COOLDOWN_MS, s.cooldownUntil)
    }

    @Test fun success_clears_cooldown() {
        val s = RefreshLogic.succeeded(RefreshLogic.failed(State(), now, cooldownMs = HOUR), now + 2 * HOUR)
        assertEquals(0L, s.cooldownUntil)
    }

    // ------------------------------------------------------------------ clock going backwards

    @Test fun clock_set_back_does_not_lock_out() {
        val future = State(lastSuccess = now + 400 * 24 * HOUR, lastAttempt = now + 400 * 24 * HOUR, failures = 3,
            cooldownUntil = now + 400 * 24 * HOUR)
        assertTrue(RefreshLogic.allowed(future, now, Trigger.SCHEDULED, true))
        assertTrue(RefreshLogic.allowed(future, now, Trigger.MANUAL, true))
    }

    // ------------------------------------------------------------------ storage

    @Test fun encode_decode_round_trip() {
        val s = State(1L, 2L, 3, 4L)
        assertEquals(s, RefreshLogic.decode(RefreshLogic.encode(s)))
    }

    @Test fun decode_garbage_is_empty_state() {
        assertEquals(State(), RefreshLogic.decode(null))
        assertEquals(State(), RefreshLogic.decode(""))
        assertEquals(State(), RefreshLogic.decode("1;2;3"))
        assertEquals(State(0L, 2L, 0, 4L), RefreshLogic.decode("x;2;-5;4"))
    }

    // ------------------------------------------------------------------ HTTP

    @Test fun retry_after_seconds_and_date() {
        assertEquals(120_000L, RefreshLogic.retryAfterMs("120", now))
        assertEquals(0L, RefreshLogic.retryAfterMs("-3", now))
        assertEquals(RefreshLogic.MAX_COOLDOWN_MS, RefreshLogic.retryAfterMs("99999999", now))
        assertNull(RefreshLogic.retryAfterMs(null, now))
        assertNull(RefreshLogic.retryAfterMs("  ", now))
        assertNull(RefreshLogic.retryAfterMs("soon", now))
        // 1_800_000_000_000 = Fri, 15 Jan 2027 08:00:00 GMT
        assertEquals(10 * MIN, RefreshLogic.retryAfterMs("Fri, 15 Jan 2027 08:10:00 GMT", now))
        assertEquals(0L, RefreshLogic.retryAfterMs("Fri, 15 Jan 2027 07:00:00 GMT", now))
    }

    @Test fun throttle_codes() {
        assertTrue(RefreshLogic.isThrottle(429, false))
        assertTrue(RefreshLogic.isThrottle(458, false))
        assertTrue(RefreshLogic.isThrottle(509, false))
        assertTrue(RefreshLogic.isThrottle(503, true))
        assertFalse(RefreshLogic.isThrottle(503, false))
        assertFalse(RefreshLogic.isThrottle(200, true))
        assertFalse(RefreshLogic.isThrottle(401, false))
    }

    @Test fun strike_cooldown_escalates_to_30_minutes() {
        assertEquals(0L, RefreshLogic.strikeCooldownMs(0))
        assertEquals(30_000L, RefreshLogic.strikeCooldownMs(1))
        assertEquals(60_000L, RefreshLogic.strikeCooldownMs(2))
        assertEquals(120_000L, RefreshLogic.strikeCooldownMs(3))
        assertEquals(30 * MIN, RefreshLogic.strikeCooldownMs(7))
        assertEquals(30 * MIN, RefreshLogic.strikeCooldownMs(40))
    }

    @Test fun in_run_retries_are_spaced_out() {
        assertTrue(RefreshLogic.IN_RUN_RETRY_MS.all { it >= 5_000L })
        assertTrue(RefreshLogic.IN_RUN_RETRY_MS.zipWithNext().all { (a, b) -> b > a })
    }
}

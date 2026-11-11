package com.ipvio.stream

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * OnAir: rate-limit protection for the Xtream provider (pure logic, tested in RefreshLogicTest).
 *
 * The catalog (player_api.php lists) and the EPG (xmltv.php) are kept on the device and are only fetched again when
 * - the background schedule asks for it, and at least [MIN_INTERVAL_MS] have passed since the last success, or
 * - the owner presses a refresh button (debounced by [MANUAL_DEBOUNCE_MS]), or
 * - there is nothing cached at all (first login / wiped data).
 * After a failure the next attempt waits an increasing time ([BACKOFF_MS]); a provider `Retry-After` is honoured.
 */
object RefreshLogic {
    const val MIN = 60_000L
    const val HOUR = 60 * MIN

    /** Scheduled refreshes never run more often than this. */
    const val MIN_INTERVAL_MS = 12 * HOUR
    /** Two manual refreshes this close together: the second one is ignored (double press, impatient retry). */
    const val MANUAL_DEBOUNCE_MS = 15_000L
    /** A provider cooldown (Retry-After) is honoured up to this long; longer values are treated as this. */
    const val MAX_COOLDOWN_MS = 12 * HOUR

    /** Wait after the 1st, 2nd, 3rd … failure in a row; the last value repeats. */
    val BACKOFF_MS = listOf(15 * MIN, 30 * MIN, HOUR, 2 * HOUR, 4 * HOUR, 8 * HOUR, 12 * HOUR)

    /** Delays between attempts inside one download (connection dropped mid-transfer) – not immediate. */
    val IN_RUN_RETRY_MS = listOf(5_000L, 20_000L)

    /** HTTP status codes that mean "slow down". */
    val RATE_LIMIT_CODES = setOf(429, 458, 509)

    /**
     * Stored state of one gate (catalog or one EPG source). All times are wall-clock milliseconds, 0 = never.
     * [cooldownUntil] = the provider asked us to wait until then (Retry-After or rate-limit backoff).
     */
    data class State(
        val lastSuccess: Long = 0L,
        val lastAttempt: Long = 0L,
        val failures: Int = 0,
        val cooldownUntil: Long = 0L
    )

    enum class Trigger {
        /** App start, profile switch, return from a screen: use what is cached, fetch only if nothing is cached. */
        AUTO,
        /** Background schedule (WorkManager). */
        SCHEDULED,
        /** The owner pressed a button (login, reload). */
        MANUAL,
        /**
         * OnAir: lightweight Events refresh (get_live_streams for the PPV categories only) when the home or Events screen
         * opens: at most every [EVENTS_INTERVAL_MS] after the last attempt, with the normal backoff after failures.
         */
        EVENTS,
        /**
         * OnAir v2: the owner's "Update guide" button – downloads only the guide (xmltv.php): at most every
         * [GUIDE_MANUAL_INTERVAL_MS] after the last attempt, plus the normal backoff and provider cooldown.
         */
        GUIDE
    }

    /** The Events refresh runs at most this often. */
    const val EVENTS_INTERVAL_MS = 30 * MIN

    /** The manual guide update ([Trigger.GUIDE]) runs at most this often. */
    const val GUIDE_MANUAL_INTERVAL_MS = 15 * MIN

    /** Why a fetch has to wait (for the message shown to the owner). */
    enum class Wait { NONE, INTERVAL, BACKOFF, PROVIDER }

    /**
     * The reason [nextAllowedAt] lies in the future, the one that ends last: the provider's cooldown, the failure backoff,
     * or the trigger's own interval. [NONE] when allowed now (or never by itself).
     */
    fun waitReason(state: State, now: Long, trigger: Trigger, hasCache: Boolean): Wait {
        val s = sane(state, now)
        val next = nextAllowedAt(s, now, trigger, hasCache) ?: return Wait.NONE
        if (next <= now) return Wait.NONE
        return when (next) {
            s.cooldownUntil -> Wait.PROVIDER
            backoffEnd(s) -> Wait.BACKOFF
            else -> Wait.INTERVAL
        }
    }

    fun backoffMs(failures: Int): Long = if (failures <= 0) 0L else BACKOFF_MS[(failures - 1).coerceAtMost(BACKOFF_MS.lastIndex)]

    /** The wall clock went backwards (stored times lie in the future): don't stay locked out – forget the stored times. */
    private fun sane(s: State, now: Long): State {
        val ahead = 5 * MIN
        return State(
            lastSuccess = if (s.lastSuccess > now + ahead) 0L else s.lastSuccess,
            lastAttempt = if (s.lastAttempt > now + ahead) 0L else s.lastAttempt,
            failures = s.failures,
            cooldownUntil = if (s.cooldownUntil > now + MAX_COOLDOWN_MS + ahead) 0L else s.cooldownUntil
        )
    }

    private fun backoffEnd(s: State): Long = if (s.failures <= 0) 0L else s.lastAttempt + backoffMs(s.failures)

    /**
     * Earliest time a fetch may run (≥ [now]); null = never by itself ([Trigger.AUTO] with something cached).
     * [hasCache] = something usable is stored on the device.
     * - AUTO: only when nothing is cached, and not while backing off after a failure.
     * - SCHEDULED: 12 h after the last success, and not while backing off.
     * - MANUAL: at once, except right after another attempt.
     * Always: not within a provider cooldown.
     */
    fun nextAllowedAt(state: State, now: Long, trigger: Trigger, hasCache: Boolean): Long? {
        val s = sane(state, now)
        val t = when (trigger) {
            Trigger.MANUAL -> if (s.lastAttempt > 0L) s.lastAttempt + MANUAL_DEBOUNCE_MS else 0L
            Trigger.AUTO -> if (hasCache) return null else backoffEnd(s)
            Trigger.SCHEDULED -> maxOf(backoffEnd(s), if (hasCache && s.lastSuccess > 0L) s.lastSuccess + MIN_INTERVAL_MS else 0L)
            Trigger.EVENTS -> if (!hasCache) return null else maxOf(backoffEnd(s), if (s.lastAttempt > 0L) s.lastAttempt + EVENTS_INTERVAL_MS else 0L)
            Trigger.GUIDE -> maxOf(backoffEnd(s), if (s.lastAttempt > 0L) s.lastAttempt + GUIDE_MANUAL_INTERVAL_MS else 0L)
        }
        return maxOf(t, s.cooldownUntil, now)
    }

    /** May a fetch run now? See [nextAllowedAt]. */
    fun allowed(state: State, now: Long, trigger: Trigger, hasCache: Boolean): Boolean =
        nextAllowedAt(state, now, trigger, hasCache)?.let { it <= now } ?: false

    fun succeeded(s: State, now: Long): State = State(lastSuccess = now, lastAttempt = now, failures = 0, cooldownUntil = 0L)

    /** A fetch failed. [cooldownMs] = how long the provider asked us to wait (Retry-After), if it said so. */
    fun failed(s: State, now: Long, cooldownMs: Long? = null): State {
        val until = if (cooldownMs != null && cooldownMs > 0) now + cooldownMs.coerceAtMost(MAX_COOLDOWN_MS) else 0L
        return State(
            lastSuccess = s.lastSuccess, lastAttempt = now, failures = (s.failures + 1).coerceAtMost(1000),
            cooldownUntil = maxOf(s.cooldownUntil, until)
        )
    }

    /** The fetch was started but cancelled by the owner: counts as an attempt (debounce), not as a failure. */
    fun attempted(s: State, now: Long): State = s.copy(lastAttempt = now)

    // ------------------------------------------------------------------ storage (one line, no credentials)

    fun encode(s: State): String = "${s.lastSuccess};${s.lastAttempt};${s.failures};${s.cooldownUntil}"

    fun decode(v: String?): State {
        val p = v?.split(';') ?: return State()
        if (p.size != 4) return State()
        return State(
            p[0].toLongOrNull() ?: 0L, p[1].toLongOrNull() ?: 0L,
            (p[2].toIntOrNull() ?: 0).coerceAtLeast(0), p[3].toLongOrNull() ?: 0L
        )
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * Retry-After header in milliseconds (delta seconds or an HTTP date), null if missing or unreadable.
     * Never negative; capped at [MAX_COOLDOWN_MS].
     */
    fun retryAfterMs(header: String?, now: Long): Long? {
        val h = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        h.toLongOrNull()?.let { return (it * 1000).coerceIn(0L, MAX_COOLDOWN_MS) }
        return try {
            val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            f.timeZone = TimeZone.getTimeZone("GMT")
            val t = f.parse(h)?.time ?: return null
            (t - now).coerceIn(0L, MAX_COOLDOWN_MS)
        } catch (e: Exception) {
            null
        }
    }

    /** Does this answer mean the provider wants us to slow down? 503 only counts when it says how long to wait. */
    fun isThrottle(code: Int, hasRetryAfter: Boolean): Boolean = code in RATE_LIMIT_CODES || (code == 503 && hasRetryAfter)

    /** Escalating cooldown for repeated throttle/server errors across all provider calls: 30 s, 1 min, 2 min … max 30 min. */
    fun strikeCooldownMs(strikes: Int): Long =
        if (strikes <= 0) 0L else (30_000L shl (strikes - 1).coerceAtMost(6)).coerceAtMost(30 * MIN)

    // ------------------------------------------------------------------ on-demand EPG (get_short_epg / get_simple_data_table)

    /** How long a per-channel EPG answer is reused: data 15 min, "nothing found" 30 min (avoids asking again on every scroll). */
    const val CHANNEL_EPG_TTL_MS = 15 * MIN
    const val CHANNEL_EPG_EMPTY_TTL_MS = 30 * MIN
}

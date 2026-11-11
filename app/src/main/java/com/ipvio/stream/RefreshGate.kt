package com.ipvio.stream

import android.content.Context
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException

/**
 * OnAir: stored refresh state per source (catalog, each EPG address) – see [RefreshLogic] for the rules.
 * Keys are hashes of the address, never the address itself (it contains the password).
 */
object RefreshGate {
    private const val PREFS = "onair_refresh"

    fun catalogKey(url: String) = "pl_" + url.trim().hashCode()
    fun epgKey(url: String) = "epg_" + url.trim().hashCode()

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized fun state(c: Context, key: String): RefreshLogic.State = RefreshLogic.decode(prefs(c).getString(key, null))

    @Synchronized private fun put(c: Context, key: String, s: RefreshLogic.State) {
        prefs(c).edit().putString(key, RefreshLogic.encode(s)).commit()
    }

    fun allowed(c: Context, key: String, trigger: RefreshLogic.Trigger, hasCache: Boolean): Boolean {
        val now = System.currentTimeMillis()
        val ok = RefreshLogic.allowed(state(c, key), now, trigger, hasCache) && !ProviderGuard.coolingDown(now)
        if (!ok) LoginDebug.info("refresh gate: $key blocked (trigger=$trigger, cached=$hasCache)")
        return ok
    }

    @Synchronized fun succeeded(c: Context, key: String) {
        put(c, key, RefreshLogic.succeeded(state(c, key), System.currentTimeMillis()))
    }

    /** A fetch failed; a provider cooldown seen during it (429/503 Retry-After) is carried over. */
    @Synchronized fun failed(c: Context, key: String) {
        val now = System.currentTimeMillis()
        val s = RefreshLogic.failed(state(c, key), now, ProviderGuard.remainingMs(now).takeIf { it > 0 })
        put(c, key, s)
        LoginDebug.info("refresh gate: $key failed ${s.failures}x, next try in ${RefreshLogic.backoffMs(s.failures) / 60_000} min")
    }

    @Synchronized fun attempted(c: Context, key: String) {
        put(c, key, RefreshLogic.attempted(state(c, key), System.currentTimeMillis()))
    }

    /** Earliest time a fetch with [trigger] may run (null = not by itself), including a live provider cooldown. */
    fun nextAllowedAt(c: Context, key: String, trigger: RefreshLogic.Trigger, hasCache: Boolean): Long? {
        val now = System.currentTimeMillis()
        val t = RefreshLogic.nextAllowedAt(state(c, key), now, trigger, hasCache) ?: return null
        return maxOf(t, now + ProviderGuard.remainingMs(now))
    }

    fun forget(c: Context, key: String) { prefs(c).edit().remove(key).apply() }
}

/** Thrown instead of contacting the provider while it has asked us to wait. Never retried. */
class ProviderBusy(val waitMs: Long) :
    IOException("Der Anbieter begrenzt gerade die Verbindungen. Später erneut versuchen.")

/**
 * OnAir: guard for every request to the provider's API (not for playback streams).
 * - 429/458/509, or 503 with Retry-After: no further API calls until the cooldown is over (Retry-After, otherwise
 *   escalating 30 s → 30 min). Calls during the cooldown fail at once with [ProviderBusy] instead of hitting the server.
 * - Other 5xx answers also add a strike, so a struggling server is not hammered by per-channel EPG lookups.
 * - Any successful answer clears the strikes.
 */
object ProviderGuard : Interceptor {
    @Volatile private var until = 0L
    @Volatile private var strikes = 0

    fun coolingDown(now: Long = System.currentTimeMillis()) = now < until
    fun remainingMs(now: Long = System.currentTimeMillis()) = (until - now).coerceAtLeast(0L)

    /** [base] with this guard in front of it. */
    fun client(base: OkHttpClient): OkHttpClient = base.newBuilder().addInterceptor(this).build()

    override fun intercept(chain: Interceptor.Chain): Response {
        val now = System.currentTimeMillis()
        if (now < until) throw ProviderBusy(until - now)
        val r = chain.proceed(chain.request())
        val code = r.code
        val ra = RefreshLogic.retryAfterMs(r.header("Retry-After"), System.currentTimeMillis())
        when {
            code in 200..399 -> if (strikes != 0) strikes = 0
            RefreshLogic.isThrottle(code, ra != null) || code in 500..599 -> strike(ra, code)
        }
        return r
    }

    @Synchronized private fun strike(retryAfter: Long?, code: Int) {
        strikes = (strikes + 1).coerceAtMost(20)
        val wait = retryAfter ?: RefreshLogic.strikeCooldownMs(strikes)
        until = maxOf(until, System.currentTimeMillis() + wait)
        LoginDebug.info("provider guard: HTTP $code, pausing API calls for ${wait / 1000} s (strike $strikes)")
    }
}

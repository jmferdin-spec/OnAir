package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * OnAir Phase 3: Android glue for [OnNowLogic] – guide rows from [GuideDb] joined with the live channels.
 *
 * The result is computed off the main thread and kept for [TTL_MS] (60 s), so the "now" screens can ask as often as they
 * like (open, minute tick) without re-reading and re-classifying the guide. A new playlist or guide import invalidates it.
 * Reads only the local database – never contacts the provider.
 */
object OnNow {
    const val TTL_MS = 60_000L

    /** Everything the "now" screens need at one moment. [at] = Unix seconds the snapshot was computed for. */
    class Snapshot(val at: Long, val items: List<OnNowItem>, val events: OnNowLogic.Events, val ms: Long)

    private val lock = Mutex()
    @Volatile private var cached: Snapshot? = null
    @Volatile private var cachedKey: List<Any>? = null

    fun chans(live: List<Item>): List<OnNowLogic.Chan> =
        live.filter { it.kind == Kind.LIVE }.map { OnNowLogic.Chan(it.id, it.title, it.group, it.catId, it.quality, it.logo, it.tvgId) }

    /** Live channels by lower-case tvg-id (several channels often share one: HD / FHD / 4K, regions). */
    fun chansByTvg(chans: List<OnNowLogic.Chan>): Map<String, List<OnNowLogic.Chan>> {
        val out = HashMap<String, MutableList<OnNowLogic.Chan>>()
        for (c in chans) {
            val k = c.tvgId?.trim()?.lowercase()?.ifEmpty { null } ?: continue
            out.getOrPut(k) { ArrayList(2) }.add(c)
        }
        return out
    }

    /** Current snapshot: cached if younger than 60 s and nothing changed, else computed in the background. */
    suspend fun snapshot(c: Context, live: List<Item>, force: Boolean = false): Snapshot = lock.withLock {
        val key = listOf(System.identityHashCode(live), live.size, Xmltv.importedAt, Xmltv.rowCount)
        val nowMs = System.currentTimeMillis()
        val hit = cached
        if (!force && hit != null && cachedKey == key && nowMs - hit.at * 1000 in 0 until TTL_MS) return@withLock hit
        val s = withContext(Dispatchers.Default) { compute(c.applicationContext, live, nowMs) }
        cached = s; cachedKey = key
        s
    }

    /** Cards on now (cached, see [snapshot]). */
    suspend fun items(c: Context, live: List<Item>): List<OnNowItem> = snapshot(c, live).items

    fun invalidate() { cached = null; cachedKey = null }

    private fun compute(c: Context, live: List<Item>, nowMs: Long): Snapshot {
        val now = nowMs / 1000
        val chans = chans(live)
        val items = OnNowLogic.build(runCatching { GuideDb.onNow(c, now) }.getOrDefault(emptyList()), chansByTvg(chans), now)
        // "Coming up" is only needed for event channels: read the next 24 h and keep just those
        val eventTvgs = chans.filter { OnNowLogic.isEventChannel(it) }.mapNotNull { it.tvgId?.trim()?.lowercase()?.ifEmpty { null } }.toHashSet()
        val next = if (eventTvgs.isEmpty()) emptyList()
        else runCatching { GuideDb.startingBetween(c, now, now + 24 * 3600L) }.getOrDefault(emptyList()).filter { it.tvgId in eventTvgs }
        val events = OnNowLogic.events(items, next, chans, now)
        return Snapshot(now, items, events, System.currentTimeMillis() - nowMs)
    }
}

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
 *
 * Everything that depends only on the playlist (channels with their normalised names and PPV flags, the tvg-id map, the
 * sorted event channels) is built once per playlist load ([ChanIndex], [prepare]) and reused by every snapshot.
 */
object OnNow {
    const val TTL_MS = 60_000L

    /** Everything the "now" screens need at one moment. [at] = Unix seconds the snapshot was computed for. */
    class Snapshot(val at: Long, val items: List<OnNowItem>, val events: OnNowLogic.Events, val ms: Long, val stages: String = "")

    private val lock = Mutex()
    @Volatile private var cached: Snapshot? = null
    @Volatile private var cachedKey: List<Any>? = null

    /** The channel side of the "now" screens for one playlist: computed once per playlist load. */
    class ChanIndex(val source: List<Item>) {
        val chans: List<OnNowLogic.Chan> = chans(source)
        val byTvg: Map<String, List<OnNowLogic.Chan>> = chansByTvg(chans)
        val events = OnNowLogic.EventIndex(chans)
    }

    private val indexLock = Any()
    @Volatile private var index: ChanIndex? = null
    @Volatile var indexMs: Long = 0L
        private set

    /** The index for [live] (same list object = same playlist); built here if [prepare] has not done it yet. */
    fun indexFor(live: List<Item>): ChanIndex {
        index?.takeIf { it.source === live }?.let { return it }
        synchronized(indexLock) {
            index?.takeIf { it.source === live }?.let { return it }
            val t0 = System.nanoTime()
            val i = ChanIndex(live)
            indexMs = (System.nanoTime() - t0) / 1_000_000
            index = i
            return i
        }
    }

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

    /**
     * After every playlist load (MainActivity): build the per-playlist [ChanIndex] in the background. Debug builds also
     * compute one snapshot and log the stage timings (profiling on the device without opening a screen).
     */
    suspend fun prepare(c: Context, live: List<Item>) {
        if (live.isEmpty()) return
        val i = withContext(Dispatchers.Default) { indexFor(live) }
        if (!BuildConfig.DEBUG) return
        LoginDebug.info("on-now index: ${i.chans.size} channels, ${i.events.channels.size} PPV/event, ${indexMs} ms")
        val s = snapshot(c, live, force = true)
        LoginDebug.info("on-now: ${s.ms} ms total, ${s.items.size} cards – ${s.stages}")
    }

    private fun compute(c: Context, live: List<Item>, nowMs: Long): Snapshot {
        val now = nowMs / 1000
        val t = Stages()
        val idx = indexFor(live)
        t.lap("index")
        val progs = runCatching { GuideDb.onNow(c, now) }.getOrDefault(emptyList())
        t.lap("db now (${progs.size})")
        val items = OnNowLogic.build(progs, idx.byTvg, now)
        t.lap("build")
        // "Coming up" only for the event channels' tvg-ids – asked in SQL, not by reading all of the next 24 h
        val eventTvgs = idx.events.tvgIds
        val next = if (eventTvgs.isEmpty()) emptyList()
        else runCatching { GuideDb.startingBetween(c, now, now + 24 * 3600L, eventTvgs) }.getOrDefault(emptyList())
        t.lap("db next (${next.size})")
        val events = OnNowLogic.events(items, next, idx.events, now)
        t.lap("events")
        return Snapshot(now, items, events, System.currentTimeMillis() - nowMs, t.toString())
    }

    /** Wall-clock time per stage, for the debug screen and the log. */
    private class Stages {
        private var last = System.nanoTime()
        private val sb = StringBuilder()
        fun lap(name: String) {
            val n = System.nanoTime()
            if (sb.isNotEmpty()) sb.append(" · ")
            sb.append(name).append(' ').append((n - last) / 1_000_000).append(" ms")
            last = n
        }
        override fun toString() = sb.toString()
    }
}

package com.ipvio.stream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EpgFull {
    private val cache = LruCache<String, Pair<Long, List<EpgEntry>>>(300)
    fun trim() = cache.clear()

    /**
     * Programm eines Senders: ab 1 h zurück, bei Sendern mit Archiv (Catchup) so weit zurück, wie das Archiv reicht.
     * Zuerst Zwischenspeicher und geladenes XMLTV (ohne Netz), dann Xtream.
     *
     * OnAir Phase 3: the guide comes from the SQLite database (read off the main thread). The provider is asked
     * (get_simple_data_table) only when [network] is set – the one channel the owner has focused – and only if the database
     * has no rows at all for that channel. No bulk prefetch.
     */
    suspend fun get(item: Item, network: Boolean = false): List<EpgEntry> {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val days = Catchup.of(item)?.days ?: 0
        val from = if (days > 0) nowSec - days * 86400L else nowSec - 3600
        val local = withContext(Dispatchers.IO) { Xmltv.forChannel(item.tvgId) }.filter { it.end > from }
        if (local.isNotEmpty()) return local
        // answers fetched earlier for a focused channel: reused 15 min, "nothing / failed" 30 min
        cache[item.id]?.let { (at, l) ->
            val ttl = if (l.isEmpty()) RefreshLogic.CHANNEL_EPG_EMPTY_TTL_MS else RefreshLogic.CHANNEL_EPG_TTL_MS
            if (nowMs - at in 0 until ttl) return l.ifEmpty { Epg.now(item) }
        }
        val url = if (network && !Xmltv.has(item.tvgId)) Epg.xtreamApi(item, "get_simple_data_table") else null
        if (url != null && !ProviderGuard.coolingDown(nowMs)) {
            val body = EpgNet.fetch(url)
            val out = if (body != null) Epg.parseListings(body) { _, e -> e > from } else emptyList()
            cache[item.id] = nowMs to out
            if (out.isNotEmpty()) return out
        }
        return Epg.now(item)
    }

    /** Vergangene Sendungen im Archiv eines Senders, neueste zuerst. */
    suspend fun archive(item: Item): List<EpgEntry> {
        val info = Catchup.of(item) ?: return emptyList()
        return CatchupLogic.archived(info, get(item, network = true), System.currentTimeMillis() / 1000)
    }
}

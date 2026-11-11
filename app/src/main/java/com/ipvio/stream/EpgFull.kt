package com.ipvio.stream

object EpgFull {
    private val cache = LruCache<String, Pair<Long, List<EpgEntry>>>(300)
    fun trim() = cache.clear()

    /**
     * Programm eines Senders: ab 1 h zurück, bei Sendern mit Archiv (Catchup) so weit zurück, wie das Archiv reicht.
     * Zuerst Zwischenspeicher und geladenes XMLTV (ohne Netz), dann Xtream.
     */
    suspend fun get(item: Item): List<EpgEntry> {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val days = Catchup.of(item)?.days ?: 0
        val from = if (days > 0) nowSec - days * 86400L else nowSec - 3600
        val local = Xmltv.forChannel(item.tvgId).filter { it.end > from }
        // Mit Archiv reicht XMLTV nur, wenn es auch vergangene Sendungen enthält
        val localOk = local.size >= 3 && local.any { it.end > nowSec } && (days == 0 || local.first().start <= nowSec - 6 * 3600)
        if (localOk) return local
        // OnAir: answers reused 15 min, "nothing / failed" 30 min (no repeated requests for the same channel)
        cache[item.id]?.let { (at, l) ->
            val ttl = if (l.isEmpty()) RefreshLogic.CHANNEL_EPG_EMPTY_TTL_MS else RefreshLogic.CHANNEL_EPG_TTL_MS
            if (nowMs - at in 0 until ttl) return if (l.isNotEmpty()) l else if (local.isNotEmpty()) local else Epg.now(item)
        }
        val url = Epg.xtreamApi(item, "get_simple_data_table")
        if (url != null && !ProviderGuard.coolingDown(nowMs)) {
            val body = EpgNet.fetch(url)
            val out = if (body != null) Epg.parseListings(body) { _, e -> e > from } else emptyList()
            cache[item.id] = nowMs to out
            if (out.isNotEmpty()) return out
        }
        if (local.isNotEmpty()) return local
        return Epg.now(item)
    }

    /** Vergangene Sendungen im Archiv eines Senders, neueste zuerst. */
    suspend fun archive(item: Item): List<EpgEntry> {
        val info = Catchup.of(item) ?: return emptyList()
        return CatchupLogic.archived(info, get(item), System.currentTimeMillis() / 1000)
    }
}

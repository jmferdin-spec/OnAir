package com.ipvio.stream

/**
 * OnAir: the lightweight Events refresh (pure, tested in EventRefreshLogicTest).
 *
 * PPV providers rename their event slots near show time ("[PPV] EVENTS 01:" → "[PPV] EVENTS 01: Title Fight Night").
 * Instead of reloading the whole catalog, OnAir asks player_api `get_live_streams&category_id=…` for the PPV / event
 * categories only – at most every 30 minutes ([RefreshLogic.Trigger.EVENTS]) – and keeps the new names as a small
 * overlay that is applied to the playlist ([EventNames]). A full catalog reload brings the names itself and clears it.
 */
object EventRefreshLogic {
    /** At most this many categories per refresh (a provider with dozens of "event" groups must not cost dozens of calls). */
    const val MAX_CATEGORIES = 6

    private val LIVE_ID = Regex("""/live/[^/]+/[^/]+/(\d+)(?:\.\w+)?(?:\?.*)?$""")

    /** Xtream stream id of a live channel address (<server>/live/<user>/<pass>/<id>.<ext>), or null. */
    fun streamIdOf(url: String): String? = LIVE_ID.find(url)?.groupValues?.get(1)

    /**
     * Category ids to refresh: those of channels in a PPV / event *category* (not channels found only by a PPV name in a
     * normal category – that would mean reloading a whole sports category). Most channels first, at most [max].
     */
    fun categories(chans: List<OnNowLogic.Chan>, max: Int = MAX_CATEGORIES): List<String> =
        chans.filter { it.ppvCategory && !it.catId.isNullOrBlank() }
            .groupingBy { it.catId!!.trim() }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(max).map { it.key }

    /** One channel of the playlist as the refresh sees it. */
    class Known(val itemId: String, val url: String, val title: String)

    /**
     * New names: item id → name from the provider's answer ([fresh] = stream id → name), only where it differs from what
     * the app shows and is not empty. [clean] turns the provider's raw name into the shown title (the parser's rule).
     */
    fun renames(known: List<Known>, fresh: Map<String, String>, clean: (String) -> String = { it.trim() }): Map<String, String> {
        if (fresh.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        for (k in known) {
            val sid = streamIdOf(k.url) ?: continue
            val raw = fresh[sid] ?: continue
            val n = clean(raw).trim()
            if (n.isNotEmpty() && n != k.title) out[k.itemId] = n
        }
        return out
    }

    // ------------------------------------------------------------------ stored overlay ("id\tname" lines)

    fun encode(m: Map<String, String>): String =
        m.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}\t${it.value.replace('\t', ' ').replace('\n', ' ')}" }

    fun decode(s: String?): Map<String, String> =
        s.orEmpty().lineSequence().mapNotNull { l -> val i = l.indexOf('\t'); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1) }
            .filter { it.second.isNotBlank() }.toMap()

    /** Overlay after a refresh: old names kept, new ones on top; at most [max] entries (a few hundred PPV slots). */
    fun merge(old: Map<String, String>, new: Map<String, String>, max: Int = 2000): Map<String, String> {
        val m = LinkedHashMap(old); m.putAll(new)
        return if (m.size <= max) m else m.entries.toList().takeLast(max).associate { it.key to it.value }
    }

    /**
     * [items] with the overlay's names applied (only live channels; others untouched). Returns the same list object when
     * nothing changes, so callers can tell.
     */
    fun apply(items: List<Item>, names: Map<String, String>): List<Item> {
        if (names.isEmpty()) return items
        var out: ArrayList<Item>? = null
        for ((i, it) in items.withIndex()) {
            if (it.kind != Kind.LIVE) continue
            val n = names[it.id] ?: continue
            if (n == it.title) continue
            if (out == null) out = ArrayList(items)
            out[i] = it.copy(title = n)
        }
        return out ?: items
    }
}

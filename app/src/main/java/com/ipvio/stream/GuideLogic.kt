package com.ipvio.stream

/**
 * OnAir Phase 3: rules for the guide database ([GuideDb]) – pure, tested in GuideLogicTest.
 * Times are Unix seconds, like [EpgEntry].
 */
object GuideLogic {
    /** Bump when the stored columns or the import rules change: forces a re-import from the stored XMLTV files. */
    const val SCHEMA = 1

    /** Window kept for every channel: now − 3 h … now + 36 h. */
    const val PAST_S = 3 * 3600L
    const val FUTURE_S = 36 * 3600L
    /** Catch-up channels keep their archive days, at most this many. */
    const val MAX_PAST_DAYS = 7
    /** Descriptions are cut to this length (with "…"). */
    const val MAX_DESC = 400
    /** Longest programme that is still plausible (same rule as [epgSane]); bounds the "on now" query. */
    const val MAX_LEN_S = 12 * 3600L

    /** Keep a programme [start, stop) at import time? [pastDays] = catch-up days of the channel (0 = none). */
    fun keep(start: Long, stop: Long, now: Long, pastDays: Int = 0): Boolean {
        if (stop <= start) return false
        val past = if (pastDays > 0) pastDays.coerceAtMost(MAX_PAST_DAYS) * 86_400L else PAST_S
        return stop > now - past && start < now + FUTURE_S
    }

    /** Rows ending before this are deleted on every start (catch-up channels can keep up to [MAX_PAST_DAYS]). */
    fun pruneBefore(now: Long): Long = now - MAX_PAST_DAYS * 86_400L

    fun shortDesc(d: String?): String? {
        val t = d?.trim().orEmpty()
        if (t.isEmpty()) return null
        if (t.length <= MAX_DESC) return t
        val cut = t.lastIndexOf(' ', MAX_DESC).takeIf { it > MAX_DESC / 2 } ?: MAX_DESC
        return t.substring(0, cut) + " …"
    }

    // ------------------------------------------------------------------ categories

    /** XMLTV `<category>` values as one column: trimmed, de-duplicated, "|"-separated (a "|" inside becomes "/"). */
    fun joinCategories(values: List<String>): String? =
        values.map { it.trim().replace('|', '/') }.filter { it.isNotEmpty() }.distinct().joinToString("|").ifEmpty { null }

    fun splitCategories(s: String?): List<String> = if (s.isNullOrEmpty()) emptyList() else s.split('|').filter { it.isNotEmpty() }

    // ------------------------------------------------------------------ episode numbers

    private val ONSCREEN_SE = Regex("""(?i)\bS\s*(\d{1,3})\s*[ .:-]?\s*E\s*(\d{1,4})\b""")
    private val ONSCREEN_X = Regex("""\b(\d{1,3})\s*x\s*(\d{1,4})\b""")
    private val ONSCREEN_E = Regex("""(?i)^\s*(?:E|EP|Ep\.?|Episode)\s*(\d{1,4})\s*$""")

    private fun se(season: Int?, episode: Int?): String? = when {
        season != null && episode != null -> String.format(java.util.Locale.ROOT, "S%02dE%02d", season, episode)
        episode != null -> String.format(java.util.Locale.ROOT, "E%02d", episode)
        season != null -> String.format(java.util.Locale.ROOT, "S%02d", season)
        else -> null
    }

    /** xmltv_ns "season.episode.part", zero-based, each part may be "n/total" or empty: "0.4." → S01E05. */
    fun fromXmltvNs(v: String): String? {
        val parts = v.split('.')
        if (parts.size < 2) return null
        fun num(p: String): Int? = p.substringBefore('/').trim().toIntOrNull()?.takeIf { it >= 0 }
        return se(num(parts[0])?.plus(1), num(parts[1])?.plus(1))
    }

    /** "S01E05", "S1 E5", "1x05", "E12", "Episode 12" → normalised; other non-empty text is kept as it is. */
    fun fromOnscreen(v: String): String? {
        val t = v.trim()
        if (t.isEmpty()) return null
        ONSCREEN_SE.find(t)?.let { return se(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        ONSCREEN_X.find(t)?.let { return se(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        ONSCREEN_E.find(t)?.let { return se(null, it.groupValues[1].toInt()) }
        return t.take(32)
    }

    /**
     * Episode marker from all `<episode-num system="…">` values of a programme, or null if it has none.
     * Order: xmltv_ns, onscreen, dd_progid (only "EP…" ids are episodes; "MV" = film, "SH" = show without episode), others.
     */
    fun episode(nums: List<Pair<String?, String>>): String? {
        val clean = nums.map { (s, v) -> s?.trim()?.lowercase().orEmpty() to v.trim() }.filter { it.second.isNotEmpty() }
        clean.firstOrNull { it.first == "xmltv_ns" }?.let { fromXmltvNs(it.second)?.let { e -> return e } }
        clean.firstOrNull { it.first == "onscreen" }?.let { fromOnscreen(it.second)?.let { e -> return e } }
        clean.firstOrNull { it.first == "dd_progid" }?.let { if (it.second.uppercase().startsWith("EP")) return it.second.take(32) }
        return clean.firstOrNull { it.first != "xmltv_ns" && it.first != "onscreen" && it.first != "dd_progid" }?.second?.take(32)
    }

    // ------------------------------------------------------------------ when to import

    /**
     * Fingerprint of everything an import depends on: the stored files (name, time, size), the channels that are wanted
     * (visible tvg-ids), the catch-up days and [SCHEMA]. Unchanged fingerprint → no re-import, only pruning.
     */
    fun signature(files: List<Triple<String, Long, Long>>, wanted: Collection<String>, pastDays: Map<String, Int>): String {
        var h = 1125899906842597L
        fun mix(s: String) { for (ch in s) h = 31 * h + ch.code; h = 31 * h + 7 }
        mix("v$SCHEMA")
        for ((n, t, l) in files.sortedBy { it.first }) { mix(n); mix(t.toString()); mix(l.toString()) }
        for (w in wanted.sorted()) mix(w)
        for ((k, v) in pastDays.toSortedMap()) { mix(k); mix(v.toString()) }
        return java.lang.Long.toHexString(h)
    }
}

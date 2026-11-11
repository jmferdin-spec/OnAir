package com.ipvio.stream

/**
 * OnAir Phase 3: rules for the guide database ([GuideDb]) – pure, tested in GuideLogicTest.
 * Times are Unix seconds, like [EpgEntry].
 */
object GuideLogic {
    /** Bump when the stored columns or the import rules change: forces a re-import from the stored XMLTV files. */
    const val SCHEMA = 2   // 2: import also records the UTC offsets of the start times (debug screen)

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

    // ------------------------------------------------------------------ time offsets (debug screen)

    const val NO_OFFSET = "none"
    const val BAD_OFFSET = "invalid"

    /**
     * UTC offset of an XMLTV time ("20261111200000 -0500" → "-0500"), [NO_OFFSET] if it has none (read as UTC, see
     * [xmltvTime]) or [BAD_OFFSET] if the time itself can't be read. Times are stored as UTC seconds; the offset only shows
     * which zone the provider wrote them in.
     */
    fun offsetOf(raw: String?): String {
        val t = raw?.trim() ?: return BAD_OFFSET
        if (xmltvTime(t) == null) return BAD_OFFSET
        val rest = t.substring(14).trim()
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-') && rest.substring(1, 5).all { it in '0'..'9' }) return rest.substring(0, 5)
        return NO_OFFSET
    }

    /** Offset in seconds as "+HHMM" / "-HHMM" (e.g. the app's time zone right now). */
    fun formatOffset(sec: Int): String {
        val a = kotlin.math.abs(sec) / 60
        return String.format(java.util.Locale.ROOT, "%s%02d%02d", if (sec < 0) "-" else "+", a / 60, a % 60)
    }

    /** Counts per offset during an import; stored as "-0500:29131,none:3" (most frequent first). */
    class OffsetTally {
        private val m = HashMap<String, Int>()
        fun add(raw: String?) { val k = offsetOf(raw); m[k] = (m[k] ?: 0) + 1 }
        fun counts(): Map<String, Int> = m
        fun encode(): String = encodeOffsets(m)
    }

    fun encodeOffsets(m: Map<String, Int>): String =
        m.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).joinToString(",") { "${it.key}:${it.value}" }

    fun decodeOffsets(s: String?): List<Pair<String, Int>> =
        s.orEmpty().split(',').mapNotNull { p ->
            val i = p.lastIndexOf(':'); if (i <= 0) null else p.substring(i + 1).toIntOrNull()?.let { p.substring(0, i) to it }
        }

    /** "-0500 100.0 %" / "-0500 97.0 % · none 3.0 %"; "–" when nothing was recorded (guide imported before this existed). */
    fun describeOffsets(list: List<Pair<String, Int>>): String {
        val total = list.sumOf { it.second }
        if (total <= 0) return "–"
        return list.joinToString("   ·   ") { (k, n) -> String.format(java.util.Locale.ROOT, "%s %.1f %%", k, n * 100.0 / total) }
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

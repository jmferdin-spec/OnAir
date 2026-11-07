package com.ipvio.stream

/**
 * Pure logic for reading film information from the provider's own Xtream interface (player_api.php).
 * Used when no TMDB key is entered, or when a title is not found at TMDB.
 */
object ProviderLogic {
    class Acct(val host: String, val user: String, val pass: String)

    private val MOVIE = Regex("""^(https?://[^/]+)/movie/([^/]+)/([^/]+)/(\d+)(?:\.\w+)?(?:\?.*)?$""")
    private val SERIES = Regex("""^(https?://[^/]+)/series/([^/]+)/([^/]+)/(\d+)(?:\.\w+)?(?:\?.*)?$""")
    private val YT_ID = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val YT_URL = Regex("""(?:v=|youtu\.be/|embed/)([A-Za-z0-9_-]{11})""")
    private val YEAR = Regex("""(?:19|20)\d{2}""")

    /** Account and film id from a film address, or null if the address is not an Xtream film address. */
    fun movieRef(url: String): Pair<Acct, String>? {
        val m = MOVIE.find(url) ?: return null
        val g = m.groupValues
        return Acct(g[1], g[2], g[3]) to g[4]
    }

    /** Account of a series, taken from its first episode address (or the series address itself). */
    fun seriesAcct(i: Item): Acct? {
        val u = i.episodes.firstOrNull()?.url ?: i.url
        // OnAir: player_api login – episodes not loaded yet, the account comes from the get_series_info reference
        val g = SERIES.find(u)?.groupValues ?: return XtreamApiLogic.refsOf(i.tvgId).firstOrNull()?.acct
        return Acct(g[1], g[2], g[3])
    }

    fun supports(i: Item): Boolean = when (i.kind) {
        Kind.MOVIE -> movieRef(i.url) != null
        Kind.SERIES -> seriesAcct(i) != null
        else -> false
    }

    fun apiUrl(a: Acct, action: String, extra: String = ""): String =
        "${a.host}/player_api.php?username=${a.user}&password=${a.pass}&action=$action$extra"

    /** Empty, "null" and "N/A" texts count as missing. */
    fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("N/A", true) }

    /** Rating on a 0-10 scale from the provider's "rating" or, if that is empty or 0, "rating_5based". */
    fun ratingOf(rating: String?, rating5: String?): Double? {
        val r = clean(rating)?.replace(',', '.')?.toDoubleOrNull()
        if (r != null && r > 0) return r.coerceAtMost(10.0)
        val r5 = clean(rating5)?.replace(',', '.')?.toDoubleOrNull()
        return if (r5 != null && r5 > 0) (r5 * 2).coerceAtMost(10.0) else null
    }

    /** Running time in minutes from seconds, "HH:MM:SS" or minutes. */
    fun minutes(secs: String?, duration: String?, runMin: String?): Int? {
        clean(secs)?.toDoubleOrNull()?.let { if (it > 0) return (it / 60).toInt().coerceAtLeast(1) }
        clean(duration)?.let { d ->
            val p = d.split(':').mapNotNull { it.trim().toIntOrNull() }
            if (p.size == 3) { val m = p[0] * 60 + p[1]; if (m > 0) return m }
            if (p.size == 2) { val m = p[0] * 60 + p[1]; if (m > 0) return m }
        }
        clean(runMin)?.toIntOrNull()?.let { if (it > 0) return it }
        return null
    }

    fun genres(s: String?): List<String> =
        clean(s)?.split(',', '/', '|')?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()?.take(6) ?: emptyList()

    /** YouTube video id from an id or a YouTube address, else null. */
    fun youtubeId(s: String?): String? {
        val t = clean(s) ?: return null
        if (YT_ID.matches(t)) return t
        return YT_URL.find(t)?.groupValues?.get(1)
    }

    fun year(s: String?): String? = clean(s)?.let { YEAR.find(it)?.value }

    /** Same title (ignoring the provider's language prefix, accents and punctuation)? */
    fun sameName(title: String, name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val a = MatchLogic.norm(title)
        val b = MatchLogic.norm(name)
        if (a.isNotEmpty() && a == b) return true
        val c = MatchLogic.norm(MatchLogic.searchTitle(title))
        return c.isNotEmpty() && c == MatchLogic.norm(MatchLogic.searchTitle(name))
    }

    class Info(
        val plot: String?, val cover: String?, val backdrop: String?, val rating: Double?,
        val genres: List<String>, val year: String?, val runtime: Int?, val trailer: String?
    ) {
        fun isEmpty() = plot == null && cover == null && backdrop == null && rating == null && genres.isEmpty() && trailer == null
    }

    /** Builds [Info] from the provider's text fields (keys as delivered by Xtream). */
    fun infoOf(f: Map<String, String>, backdrop: String?, coverKeys: List<String>): Info {
        val cover = coverKeys.firstNotNullOfOrNull { clean(f[it]) }
        return Info(
            plot = clean(f["plot"]) ?: clean(f["description"]),
            cover = cover?.takeIf { it.startsWith("http") },
            backdrop = clean(backdrop)?.takeIf { it.startsWith("http") },
            rating = ratingOf(f["rating"], f["rating_5based"]),
            genres = genres(f["genre"]),
            year = year(f["releasedate"]) ?: year(f["releaseDate"]) ?: year(f["release_date"]) ?: year(f["year"]),
            runtime = minutes(f["duration_secs"], f["duration"], f["episode_run_time"]),
            trailer = youtubeId(f["youtube_trailer"])
        )
    }
}

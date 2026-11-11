package com.ipvio.stream

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * OnAir: loading an Xtream account through player_api.php instead of the get.php M3U (many providers switch get.php off).
 *
 * The API data is written out as ordinary M3U lines so the rest of the app (parser, language filter, cache, EPG,
 * catch-up) stays exactly as it is. Category names go into `group-title` unchanged – OnAir's Sports/Events detection
 * depends on them.
 *
 * Series: get_series only lists the shows, not their episodes. Each show becomes a placeholder line whose address ends in
 * [SERIES_EXT]; the parser keeps it as a reference ([Item.tvgId]) and the episodes are fetched with get_series_info when
 * the show is opened ([SeriesInfo]).
 */
object XtreamApiLogic {
    const val SERIES_EXT = "onairseries"
    private const val SEP = "\u0001"
    const val NO_GROUP = "Sonstige"
    /**
     * M3U attribute carrying a live channel's Xtream category_id next to its category name (group-title).
     * Sports/Events detection relies on both; the parser keeps it in [Item.catId].
     */
    const val CAT_ATTR = "xc-category-id"

    private val GET_PHP = Regex("""^(https?://[^/?#]+)/get\.php\?(.*)$""", RegexOption.IGNORE_CASE)
    private val REF = Regex("""^(https?://[^/]+)/series/([^/]+)/([^/]+)/([^/.#]+)\.$SERIES_EXT(?:#(.*))?$""")

    /** Account from a get.php address (user and password stay URL-encoded as in the address), or null. */
    fun acctOf(getUrl: String): ProviderLogic.Acct? {
        val m = GET_PHP.find(getUrl.trim()) ?: return null
        val q = m.groupValues[2].split('&').mapNotNull { p ->
            val i = p.indexOf('='); if (i <= 0) null else p.substring(0, i).lowercase() to p.substring(i + 1)
        }.toMap()
        val u = q["username"]?.takeIf { it.isNotEmpty() } ?: return null
        val pw = q["password"]?.takeIf { it.isNotEmpty() } ?: return null
        return ProviderLogic.Acct(m.groupValues[1], u, pw)
    }

    /** player_api.php address; [action] null = account check (user_info / server_info). */
    fun apiUrl(a: ProviderLogic.Acct, action: String? = null, extra: String = ""): String =
        "${a.host}/player_api.php?username=${a.user}&password=${a.pass}" + (if (action != null) "&action=$action" else "") + extra

    private fun dec(s: String) = try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }

    /** Query-encoded value → path segment (a space must be %20 in a path, not "+"). */
    fun pathSeg(queryEncoded: String): String = URLEncoder.encode(dec(queryEncoded), "UTF-8").replace("+", "%20")

    /** <server>/<type>/<user>/<pass>/<id>.<ext> */
    fun streamUrl(a: ProviderLogic.Acct, type: String, id: String, ext: String): String =
        "${a.host}/$type/${pathSeg(a.user)}/${pathSeg(a.pass)}/$id" + (if (ext.isNotEmpty()) ".$ext" else "")

    /** Live format: ts like the old get.php request, unless the account only allows something else. */
    fun liveExt(allowed: List<String>): String {
        val l = allowed.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return when {
            l.isEmpty() || "ts" in l -> "ts"
            "m3u8" in l -> "m3u8"
            else -> l.first()
        }
    }

    /** Category id → name from a get_*_categories answer. */
    fun categories(json: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()   // server order (used for the category sort order)
        val a = try { JSONArray(json.trim()) } catch (e: Exception) { return out }
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.opt("category_id")?.toString()?.trim().orEmpty()
            val n = o.optString("category_name").trim()
            if (id.isNotEmpty() && n.isNotEmpty()) out[id] = n
        }
        return out
    }

    /**
     * Category sort order (name → position in its list) from the category lists already fetched during the download,
     * so no extra requests are needed. Films first, then series, then live – the first occurrence of a name wins
     * (same rule as the old separate category requests).
     */
    fun categoryOrder(vod: Collection<String>, series: Collection<String>, live: Collection<String>): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (l in listOf(vod, series, live)) l.forEachIndexed { i, n -> if (n.isNotBlank() && !out.containsKey(n)) out[n] = i }
        return out
    }

    /** Account check: null = OK, else the HTTP-style code to report (401 = rejected). Also returns the allowed formats. */
    class Account(val ok: Boolean, val formats: List<String>, val status: String?)

    fun account(json: String): Account {
        val o = try { JSONObject(json.trim()) } catch (e: Exception) { return Account(false, emptyList(), null) }
        val ui = o.optJSONObject("user_info") ?: return Account(false, emptyList(), null)
        val auth = ui.opt("auth")?.toString()?.trim()
        val f = ui.optJSONArray("allowed_output_formats")
        val formats = if (f == null) emptyList() else (0 until f.length()).map { f.optString(it) }
        return Account(auth == "1" || auth == "true", formats, ui.optString("status").ifBlank { null })
    }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }

    /** Attribute value: no quotes or line breaks (they would break the M3U line). */
    fun attr(s: String): String = s.replace('"', '\'').replace('\r', ' ').replace('\n', ' ').trim()

    /** Display name after the last comma of #EXTINF: a comma inside the name would cut it, so it becomes "‚". */
    fun name(s: String): String = s.replace('\r', ' ').replace('\n', ' ').replace(',', '‚').trim()

    fun group(f: Map<String, String>, cats: Map<String, String>): String =
        clean(f["category_id"])?.let { cats[it] } ?: clean(f["category_name"]) ?: NO_GROUP

    private fun extinf(attrs: List<Pair<String, String?>>, title: String): String =
        "#EXTINF:-1" + attrs.filter { !it.second.isNullOrBlank() }.joinToString("") { " ${it.first}=\"${attr(it.second!!)}\"" } + "," + name(title)

    /** One live channel as M3U lines, or null if it has no id/name. */
    fun liveEntry(f: Map<String, String>, cats: Map<String, String>, a: ProviderLogic.Acct, ext: String): String? {
        val id = clean(f["stream_id"]) ?: return null
        val n = clean(f["name"]) ?: return null
        val archive = clean(f["tv_archive"]) == "1"
        val days = clean(f["tv_archive_duration"])?.toIntOrNull()?.takeIf { it > 0 }
        val attrs = mutableListOf<Pair<String, String?>>(
            "tvg-id" to clean(f["epg_channel_id"]), "tvg-name" to n, "tvg-logo" to clean(f["stream_icon"]),
            "group-title" to group(f, cats), CAT_ATTR to clean(f["category_id"])
        )
        if (archive && days != null) { attrs += "catchup" to "xc"; attrs += "catchup-days" to days.toString() }
        return extinf(attrs, n) + "\n" + streamUrl(a, "live", id, ext) + "\n"
    }

    private val HAS_YEAR = Regex("""[\(\[](?:19|20)\d{2}[\)\]]""")

    /** One film as M3U lines; the year is added as "(2021)" when the name has none (the parser reads it from there). */
    fun movieEntry(f: Map<String, String>, cats: Map<String, String>, a: ProviderLogic.Acct): String? {
        val id = clean(f["stream_id"]) ?: return null
        var n = clean(f["name"]) ?: return null
        val y = (clean(f["year"]) ?: clean(f["releasedate"]) ?: clean(f["release_date"]))?.let { Regex("""(?:19|20)\d{2}""").find(it)?.value }
        if (y != null && !HAS_YEAR.containsMatchIn(n)) n = "$n ($y)"
        val ext = clean(f["container_extension"]) ?: "mp4"
        val attrs = listOf("tvg-name" to n, "tvg-logo" to clean(f["stream_icon"]), "group-title" to group(f, cats))
        return extinf(attrs, n) + "\n" + streamUrl(a, "movie", id, ext) + "\n"
    }

    /** One show as a placeholder line (episodes come later via get_series_info). */
    fun seriesEntry(f: Map<String, String>, cats: Map<String, String>, a: ProviderLogic.Acct): String? {
        val id = clean(f["series_id"]) ?: return null
        val n = clean(f["name"]) ?: return null
        val attrs = listOf("tvg-name" to n, "tvg-logo" to clean(f["cover"]), "group-title" to group(f, cats))
        return extinf(attrs, n) + "\n" + streamUrl(a, "series", id, SERIES_EXT) + "\n"
    }

    // ------------------------------------------------------------------ Series references and episodes

    fun isSeriesRef(url: String): Boolean = REF.matches(url)

    /** Placeholder address plus how its episodes are labelled (source label, quality, language) for the variant picker. */
    fun ref(url: String, label: String, q: String?, lang: String?): String =
        url + "#" + URLEncoder.encode(label + SEP + (q ?: "") + SEP + (lang ?: ""), "UTF-8")

    class Ref(val acct: ProviderLogic.Acct, val seriesId: String, val label: String, val q: String?, val lang: String?)

    fun parseRef(s: String): Ref? {
        val m = REF.find(s) ?: return null
        val g = m.groupValues
        val p = dec(g[5]).split(SEP)
        return Ref(ProviderLogic.Acct(g[1], g[2], g[3]), g[4], p.getOrNull(0).orEmpty(), p.getOrNull(1)?.ifEmpty { null }, p.getOrNull(2)?.ifEmpty { null })
    }

    /** All references stored for a show (several when the same show comes from several categories/languages). */
    fun refsOf(tvgId: String?): List<Ref> = tvgId?.split(' ')?.mapNotNull { parseRef(it) } ?: emptyList()

    const val MAX_REFS = 8

    class Ep(val season: Int, val num: Int, val id: String, val ext: String)

    /** Episodes from a get_series_info answer ("episodes" as {"1":[…]} or as [[…]]). */
    fun episodesOf(json: String): List<Ep> {
        val root = try { JSONObject(json.trim()) } catch (e: Exception) { return emptyList() }
        val out = ArrayList<Ep>()
        fun add(seasonKey: Int?, a: JSONArray) {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val id = clean(o.opt("id")?.toString()) ?: continue
                val s = o.opt("season")?.toString()?.trim()?.toIntOrNull() ?: seasonKey ?: 1
                val n = o.opt("episode_num")?.toString()?.trim()?.toIntOrNull() ?: (i + 1)
                out.add(Ep(s, n, id, clean(o.optString("container_extension")) ?: "mp4"))
            }
        }
        when (val e = root.opt("episodes")) {
            is JSONObject -> for (k in e.keys()) e.optJSONArray(k)?.let { add(k.trim().toIntOrNull(), it) }
            is JSONArray -> for (i in 0 until e.length()) e.optJSONArray(i)?.let { add(null, it) }
        }
        return out
    }

    private fun rank(q: String?) = when (q) { "4K" -> 4; "FHD" -> 3; "HD" -> 2; else -> 1 }

    /** Episodes of all sources merged like the M3U parser does: same season+episode = one episode with several sources. */
    fun merge(parts: List<Pair<Ref, List<Ep>>>): List<Episode> {
        class R(val s: Int, val n: Int, val url: String, val label: String, val q: String?, val lang: String?)
        val raw = ArrayList<R>()
        for ((r, eps) in parts) for (e in eps) {
            if (e.season !in 0..0xFFFF || e.num !in 0..0xFFFF) continue
            raw.add(R(e.season, e.num, streamUrl(r.acct, "series", e.id, e.ext), r.label, r.q, r.lang))
        }
        raw.sortWith(compareBy({ it.s }, { it.n }))
        val out = ArrayList<Episode>()
        var i = 0
        while (i < raw.size) {
            var j = i + 1
            while (j < raw.size && raw[j].s == raw[i].s && raw[j].n == raw[i].n) j++
            if (j - i == 1) {
                val r = raw[i]
                out.add(Episode(r.s, r.n, EpTitles.of(r.n), r.url, emptyList(), r.q))
            } else {
                val vars = raw.subList(i, j).sortedByDescending { rank(it.q) }.map { Variant(it.url, it.label, it.q, it.lang) }
                out.add(Episode(raw[i].s, raw[i].n, EpTitles.of(raw[i].n), vars[0].url, vars, vars[0].quality))
            }
            i = j
        }
        return out
    }
}

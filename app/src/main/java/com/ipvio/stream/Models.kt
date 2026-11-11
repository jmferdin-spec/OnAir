package com.ipvio.stream

enum class Kind { LIVE, MOVIE, SERIES }

data class Variant(val url: String, val label: String, val quality: String?, val lang: String?)

data class Episode(
    val season: Int, val num: Int, val title: String, val url: String,
    val variants: List<Variant> = emptyList(), val quality: String? = null
)

/**
 * Wählt die Quelle einer Folge nach Sprache, Qualität und Quelle (Vorgabe des Nutzers).
 * Ohne feste Qualität entscheidet [cap] (siehe [QualityLogic]): die beste Quelle, die das Gerät gut schafft.
 */
fun pickVariant(ep: Episode, q: String?, lang: String?, label: String?, cap: Int = 4): Variant {
    var c = ep.variants.ifEmpty { listOf(Variant(ep.url, "", ep.quality, null)) }
    if (lang != null) c.filter { it.lang == lang }.takeIf { it.isNotEmpty() }?.let { c = it }
    if (q != null) c.filter { it.quality == q }.takeIf { it.isNotEmpty() }?.let { c = it }
    if (label != null) c.filter { it.label == label }.takeIf { it.isNotEmpty() }?.let { c = it }
    if (q == null && c.size > 1) return c[QualityLogic.order(c.map { it.quality }, cap).first()]
    return c.first()
}

/** „Episode 1“ … nur einmal im Speicher statt einmal je Folge. */
object EpTitles {
    private val CACHE = arrayOfNulls<String>(2000)
    fun of(n: Int): String {
        if (n < 0 || n >= CACHE.size) return "Episode $n"
        return CACHE[n] ?: "Episode $n".also { CACHE[n] = it }
    }
}

/**
 * Die Folgen einer Serie, platzsparend gespeichert (statt einer Datenbank – gemessen: gleicher Effekt, ohne Ladezeit):
 * Staffel/Folge als Zahl, die Adresse nur als Ende („12345.mkv“) – der gemeinsame Anfang („http://server/series/user/pass/“)
 * liegt einmal im Speicher. Eine Folge ([Episode]) wird erst beim Zugriff gebaut. Für den Rest der App ist es eine normale Liste.
 * Bei großen Anbietern sind ~70 % aller Einträge Folgen: 581.000 Folgen brauchen so ~12 MB statt ~88 MB.
 */
class EpisodeTable private constructor(
    /** (Staffel shl 16) or Folge */
    private val se: IntArray,
    private val pre: Array<String>,
    /** Anfang je Folge (Index in [pre]); null = alle [pre] 0 */
    private val preIdx: ByteArray?,
    /** Adress-Enden hintereinander, [sufEnd] = Ende je Folge */
    private val suf: String,
    private val sufEnd: IntArray,
    /** Qualität je Folge als Code (siehe [Q]) */
    private val q: ByteArray,
    /** Quellen je Folge, nur bei Folgen mit mehreren Quellen (sonst null) */
    private val vars: Array<List<Variant>?>?,
    /** Anzahl verschiedener Staffeln (für die Karten, ohne alle Folgen zu bauen) */
    val seasonCount: Int
) : AbstractList<Episode>(), RandomAccess {
    override val size: Int get() = se.size

    override fun get(index: Int): Episode {
        val k = se[index]
        val n = k and 0xFFFF
        val start = if (index == 0) 0 else sufEnd[index - 1]
        val p = pre[preIdx?.get(index)?.toInt() ?: 0]
        return Episode(k ushr 16, n, EpTitles.of(n), p + suf.substring(start, sufEnd[index]), vars?.get(index) ?: emptyList(), Q[q[index].toInt()])
    }

    companion object {
        private val Q = arrayOf(null, "HD", "FHD", "4K")
        private fun qCode(s: String?): Int = Q.indexOf(s)

        /** Anfang einer Adresse bis einschließlich des letzten „/“. */
        fun prefixOf(url: String): String = url.substring(0, url.lastIndexOf('/') + 1)

        /**
         * Kompakte Form einer Folgenliste. Passt etwas nicht ins Schema (ungewöhnlicher Titel, Qualität, Nummer,
         * über 120 verschiedene Adress-Anfänge), bleibt die Liste, wie sie ist – nichts geht verloren.
         */
        fun of(list: List<Episode>, pool: StringPool? = null): List<Episode> {
            if (list.isEmpty()) return emptyList()
            if (list is EpisodeTable) return list
            val n = list.size
            val se = IntArray(n)
            val q = ByteArray(n)
            val sufEnd = IntArray(n)
            val sb = StringBuilder(n * 12)
            val pres = ArrayList<String>(2)
            val preIdx = ByteArray(n)
            var vars: Array<List<Variant>?>? = null
            val seasons = HashSet<Int>()
            for (i in 0 until n) {
                val e = list[i]
                if (e.season !in 0..0xFFFF || e.num !in 0..0xFFFF || e.title != EpTitles.of(e.num)) return list
                val qc = qCode(e.quality)
                if (qc < 0) return list
                se[i] = (e.season shl 16) or e.num
                q[i] = qc.toByte()
                seasons.add(e.season)
                val p = prefixOf(e.url)
                var pi = pres.indexOf(p)
                if (pi < 0) {
                    if (pres.size >= 120) return list
                    pres.add(pool?.of(p) ?: p); pi = pres.lastIndex
                }
                preIdx[i] = pi.toByte()
                sb.append(e.url, p.length, e.url.length)
                sufEnd[i] = sb.length
                if (e.variants.isNotEmpty()) {
                    if (vars == null) vars = arrayOfNulls(n)
                    vars[i] = e.variants
                }
            }
            return EpisodeTable(se, pres.toTypedArray(), if (pres.size == 1) null else preIdx, sb.toString(), sufEnd, q, vars, seasons.size)
        }
    }
}

/** Anzahl der Staffeln einer Serie – schnell bei der kompakten Liste (Karten zeigen das bei jedem Zeichnen). */
fun seasonCount(item: Item): Int = (item.episodes as? EpisodeTable)?.seasonCount ?: item.episodes.map { it.season }.distinct().size

data class Item(
    val id: String,
    val kind: Kind,
    val title: String,
    val year: Int?,
    val url: String,
    val logo: String?,
    val group: String,
    val episodes: List<Episode> = emptyList(),
    val quality: String? = null,
    val tvgId: String? = null,
    /** Sprache laut Kennzeichnung des Anbieters (Kürzel aus [LangTagLogic], "?" = ohne Kennung). */
    val lang: String = LangTagLogic.UNKNOWN,
    /** OnAir: Xtream category_id of a live channel (player_api login), null otherwise. [group] holds the category name. */
    val catId: String? = null
) {
    // Schlüssel für den Metadaten-Cache: einmal pro Titel berechnet statt bei jedem Sortiervergleich neu.
    // Eigenes Feld statt "by lazy": spart bei 100.000+ Titeln zwei Hilfsobjekte je Titel.
    private var mkCache: String? = null
    val mk: String get() = mkCache ?: (kind.name + "|" + title.lowercase() + "|" + (year ?: 0)).also { mkCache = it }
}

/**
 * Ergebnis des Einlesens: Einträge (ohne ausgeblendete Sprachen) plus die im Kopf der Liste genannten EPG-Adressen.
 * [counts] = Titel je Sprache in der ganzen Liste, [bytes] = Datenmenge je Sprache (für die Speicher-Schätzung).
 */
class ParsedPlaylist(
    val items: List<Item>, val epgUrls: List<String>,
    val counts: Map<String, Int> = emptyMap(), val bytes: Map<String, Long> = emptyMap(),
    /** Sender mit Archiv (Catchup) laut Playlist: Sender-ID → Angaben. */
    val catchup: Map<String, CatchupInfo> = emptyMap()
)

/** Ergebnis des schnellen Prüf-Durchlaufs (ohne Titel anzulegen): Titel und Datenmenge je Sprache. */
class PlaylistScan(val counts: Map<String, Int>, val bytes: Map<String, Long>, val entries: Int)

/** Wird geworfen, wenn das Einlesen abgebrochen werden soll (Speicher knapp, Nutzer bricht ab). */
class ParseStop(msg: String) : RuntimeException(msg)

/**
 * Legt gleiche Texte nur einmal im Speicher ab (Kategorien, Beschriftungen, Sprachen).
 * Große Playlists wiederholen dieselbe Kategorie zehntausendfach – ohne Pool wäre jede Wiederholung ein eigener Text.
 */
class StringPool {
    private val map = HashMap<String, String>()
    fun of(s: String): String = map.getOrPut(s) { s }
    fun ofOrNull(s: String?): String? = if (s == null) null else of(s)
}

object M3uParser {
    /** Sprache eines Eintrags; die Kategorie wird pro Name nur einmal untersucht. */
    private class LangOf(private val pool: StringPool) {
        private val byGroup = HashMap<String, String>()
        fun of(group: String, name: String, live: Boolean): String {
            val g = byGroup.getOrPut(group) { LangTagLogic.detect(group) ?: "" }
            if (g.isNotEmpty()) return g
            return pool.of(LangTagLogic.of("", name, live))
        }
    }

    // Folge bis 4-stellig: Anime wie One Piece haben über 1.000 Folgen ("S01 E1050" wurde sonst zu Folge 105)
    private val SE = Regex("""^(.*?)[\s._-]*[Ss](\d{1,2})[\s._-]*[Ee](\d{1,4})""")
    private val YEAR = Regex("""[\(\[]((?:19|20)\d{2})[\)\]]""")
    private val PREFIX = Regex("""^\s*[A-Za-z]{2,3}\s*[|:]\s*""")
    private val LANG = Regex("""^\s*([A-Za-z]{2,3})\s*[|:]""")
    private val BRACKETS = Regex("""[\(\[][^\)\]]*[\)\]]""")
    private val TAGS = Regex("""\b(4K|UHD|FHD|HD|HEVC|H265|1080p|720p|MULTI|GERMAN|DUAL)\b""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s{2,}""")
    private val VOD_GROUP = Regex("""film|movie|vod|kino|cinema""", RegexOption.IGNORE_CASE)
    private val VIDEO_FILE = Regex("""\.(mp4|mkv|avi)(\?.*)?$""", RegexOption.IGNORE_CASE)
    private val Q4K = Regex("""\b(4K|UHD|2160p?)\b""", RegexOption.IGNORE_CASE)
    private val QFHD = Regex("""\b(FHD|1080[pi]?)\b""", RegexOption.IGNORE_CASE)
    private val QHD = Regex("""\b(HD|720p?)\b""", RegexOption.IGNORE_CASE)

    private fun quality(name: String, group: String): String? {
        val s = "$name $group"
        return when {
            Q4K.containsMatchIn(s) -> "4K"
            QFHD.containsMatchIn(s) -> "FHD"
            QHD.containsMatchIn(s) -> "HD"
            else -> null
        }
    }

    private fun rank(q: String?) = when (q) { "4K" -> 4; "FHD" -> 3; "HD" -> 2; else -> 1 }

    // Einmal gebaut statt für jede Zeile neu (große Playlists haben 100.000+ Zeilen)
    private val ATTRS = listOf("tvg-logo", "tvg-id", "group-title", "url-tvg", "x-tvg-url", XtreamApiLogic.CAT_ATTR)
        .associateWith { Regex(it + "=\"([^\"]*)\"") }

    private fun attr(line: String, key: String): String? =
        (ATTRS[key] ?: Regex(key + "=\"([^\"]*)\"")).find(line)?.groupValues?.get(1)

    private fun epgFromHeader(first: String?): List<String> {
        if (first == null) return emptyList()
        return listOf("url-tvg", "x-tvg-url").mapNotNull { attr(first, it) }
            .flatMap { it.split(',') }.map { it.trim() }.filter { it.startsWith("http") }
    }

    fun epgUrls(text: String): List<String> =
        epgFromHeader(text.lineSequence().firstOrNull { it.startsWith("#EXTM3U") })

    private fun clean(raw: String): String {
        var t = LangTagLogic.stripDashTag(raw.replace(PREFIX, ""))
        t = t.replace(BRACKETS, "")
        t = t.replace(TAGS, "")
        t = t.replace(SPACES, " ").trim().trim('-', '|', ':', '.', ' ')
        return if (t.isBlank()) raw.trim() else t
    }

    private fun isSeparator(n: String): Boolean {
        val t = n.trim()
        return t.isEmpty() || t.startsWith("***") || t.startsWith("---") || t.startsWith("===") ||
            t.startsWith("###") || t.startsWith("___")
    }

    fun parse(text: String): List<Item> = parseLines(text.lineSequence()).items

    /** Liest zeilenweise aus einem Reader. So muss die ganze Liste nie als ein einziger String im Speicher liegen. */
    fun parseStream(reader: java.io.Reader, hidden: Set<String> = emptySet(), check: (() -> Unit)? = null): ParsedPlaylist =
        java.io.BufferedReader(reader, 1 shl 16).useLines { parseLines(it, hidden, check) }

    /** Schneller Durchlauf ohne Titel anzulegen: wie viele Titel und wie viele Daten je Sprache (für die Sprachauswahl). */
    fun scanStream(reader: java.io.Reader, check: (() -> Unit)? = null): PlaylistScan =
        java.io.BufferedReader(reader, 1 shl 16).useLines { lines ->
            val st = Stats()
            val langOf = LangOf(StringPool())
            var n = 0
            entries(lines, check) { e -> n++; classify(e, st, langOf) }
            PlaylistScan(st.counts(), st.bytes, n)
        }

    /** Ein Eintrag der Liste: die #EXTINF-Zeile wird erst bei Bedarf weiter zerlegt (Logo, tvg-id). */
    private class Entry(val info: String, val name: String, val group: String, val url: String)

    /** Zählt Titel und Datenmenge je Sprache; Serien zählen je Sprache einmal (nicht jede Folge). */
    private class Stats {
        val titles = HashMap<String, Int>()
        val bytes = HashMap<String, Long>()
        val series = HashSet<String>()
        fun add(lang: String, size: Int, seriesKey: String?) {
            bytes[lang] = (bytes[lang] ?: 0L) + size
            if (seriesKey == null || series.add(lang + "\u0001" + seriesKey)) titles[lang] = (titles[lang] ?: 0) + 1
        }
        fun counts(): Map<String, Int> = titles
    }

    /** Was beim Einlesen eines Eintrags feststeht: Art, Sprache, Staffel/Folge. */
    private class Kinded(val kind: Kind, val lang: String, val se: MatchResult?)

    /** Geht die Zeilen durch und meldet jeden Eintrag einzeln – nichts wird gesammelt. */
    private inline fun entries(lines: Sequence<String>, noinline check: (() -> Unit)?, onHeader: (String) -> Unit = {}, onEntry: (Entry) -> Unit) {
        var info: String? = null
        var name: String? = null
        var group = "Sonstige"
        var header = false
        var k = 0
        for (line in lines) {
            val l = line.trim()
            if (!header && l.startsWith("#EXTM3U")) { header = true; onHeader(l) }
            if (l.startsWith("#EXTINF")) {
                info = l
                name = l.substringAfterLast(',').trim()
                group = attr(l, "group-title")?.ifBlank { null } ?: "Sonstige"
            } else if (l.isNotEmpty() && !l.startsWith("#")) {
                val n = name
                val inf = info
                if (n != null && inf != null) {
                    onEntry(Entry(inf, n, group, l))
                    if (++k % 2000 == 0) check?.invoke()
                }
                name = null; info = null
            }
        }
    }

    /** Art und Sprache eines Eintrags; zählt ihn in [st]. null = Trenner, wird übersprungen. */
    private fun classify(e: Entry, st: Stats, langOf: LangOf): Kinded? {
        if (isSeparator(e.name)) return null
        val m = SE.find(e.name)
        val isSeriesUrl = e.url.contains("/series/")
        val isMovie = e.url.contains("/movie/") || VIDEO_FILE.containsMatchIn(e.url) || VOD_GROUP.containsMatchIn(e.group)
        // OnAir: an Xtream /live/ address is always a channel (e.g. "HBO" in a "MOVIE CHANNELS" group is not a film)
        val kind = if (e.url.contains("/live/")) Kind.LIVE else if (m != null || isSeriesUrl) Kind.SERIES else if (isMovie) Kind.MOVIE else Kind.LIVE
        val lang = langOf.of(e.group, e.name, kind == Kind.LIVE)
        val countKey = if (kind == Kind.SERIES) (m?.groupValues?.get(1) ?: e.name).trim().lowercase() else null
        st.add(lang, e.info.length + e.url.length + 2, countKey)
        return Kinded(kind, lang, m)
    }

    /** Folge einer Serie beim Zusammenfassen am Ende (nur für eine Serie gleichzeitig im Speicher). */
    private class EpRaw(val season: Int, val num: Int, val url: String, val label: String, val q: String?, val lang: String?)

    /**
     * Folgen einer Serie während des Einlesens – ohne ein Objekt je Folge: Zahlen in Feldern, Adress-Enden als Bytes
     * hintereinander, Anfänge/Beschriftungen/Sprachen als Verweise auf gemeinsame Texte. Spart beim Einlesen großer Listen
     * (580.000 Folgen) gut 30 MB in der Spitze.
     */
    private class SeriesAcc(val title: String, val logo: String?, val group: String, val q: String?, val lang: String) {
        var n = 0
        var ss = IntArray(4)
        var nn = IntArray(4)
        var pre = arrayOfNulls<String>(4)
        var lab = arrayOfNulls<String>(4)
        var qq = arrayOfNulls<String>(4)
        var vl = arrayOfNulls<String>(4)
        var sufEnd = IntArray(4)
        var suf = ByteArray(32)
        var sufLen = 0
        var done = false
        /** OnAir: get_series_info references (player_api login), episodes are fetched when the show is opened. */
        var refs: ArrayList<String>? = null

        fun add(season: Int, num: Int, p: String, url: String, label: String, q: String?, lang: String?) {
            if (n == ss.size) {
                val c = n * 2
                ss = ss.copyOf(c); nn = nn.copyOf(c); pre = pre.copyOf(c); lab = lab.copyOf(c); qq = qq.copyOf(c); vl = vl.copyOf(c); sufEnd = sufEnd.copyOf(c)
            }
            val b = url.substring(p.length).toByteArray(Charsets.UTF_8)
            if (sufLen + b.size > suf.size) suf = suf.copyOf(maxOf(suf.size * 2, sufLen + b.size))
            System.arraycopy(b, 0, suf, sufLen, b.size)
            sufLen += b.size
            ss[n] = season; nn[n] = num
            pre[n] = p; lab[n] = label; qq[n] = q; vl[n] = lang
            sufEnd[n] = sufLen
            n++
        }

        /** Alle Folgen als einfache Liste (nur kurz, beim Zusammenfassen dieser einen Serie). */
        fun raw(): ArrayList<EpRaw> {
            val out = ArrayList<EpRaw>(n)
            for (i in 0 until n) {
                val start = if (i == 0) 0 else sufEnd[i - 1]
                val url = pre[i] + String(suf, start, sufEnd[i] - start, Charsets.UTF_8)
                out.add(EpRaw(ss[i], nn[i], url, lab[i]!!, qq[i], vl[i]))
            }
            return out
        }

        fun free() { done = true; n = 0; ss = IntArray(0); nn = IntArray(0); pre = arrayOfNulls(0); lab = arrayOfNulls(0); qq = arrayOfNulls(0); vl = arrayOfNulls(0); sufEnd = IntArray(0); suf = ByteArray(0) }
    }

    private fun epTitle(n: Int): String = EpTitles.of(n)

    /**
     * Liest die Liste in einem Durchgang. Ausgeblendete Sprachen ([hidden]) werden nur gezählt, aber nicht angelegt –
     * so braucht eine Liste mit 800.000 Einträgen nur so viel Speicher wie die gewählten Sprachen.
     * [check] wird alle 2.000 Einträge aufgerufen (Fortschritt, Abbruch, Speicher-Wache).
     */
    fun parseLines(lines: Sequence<String>, hidden: Set<String> = emptySet(), check: (() -> Unit)? = null): ParsedPlaylist {
        val pool = StringPool()
        val langOf = LangOf(pool)
        val st = Stats()
        val out = ArrayList<Item>()
        val series = LinkedHashMap<String, SeriesAcc>()
        var header: String? = null
        var headCu: CatchupInfo? = null
        val catchup = HashMap<String, CatchupInfo>()

        entries(lines, check, onHeader = { header = it; headCu = CatchupLogic.fromInfo(it, "") }) { e ->
            val kd = classify(e, st, langOf) ?: return@entries
            if (kd.lang in hidden) return@entries
            val group = pool.of(e.group)
            val q = quality(e.name, e.group)
            when (kd.kind) {
                Kind.SERIES -> {
                    val m = kd.se
                    // Schlüssel (= Serien-ID) wie bisher aus dem bereinigten Namen, damit Fortsetzen/Meine Liste erhalten bleiben
                    val cleaned = clean(m?.groupValues?.get(1) ?: e.name)
                    val key = cleaned.lowercase()
                    val title = TitleText.title(cleaned)
                    val acc = series.getOrPut(key) {
                        SeriesAcc(title, attr(e.info, "tvg-logo")?.ifBlank { null }, group, q, kd.lang)
                    }
                    if (acc.done) return@entries
                    if (XtreamApiLogic.isSeriesRef(e.url)) {
                        val rl = acc.refs ?: ArrayList<String>(1).also { acc.refs = it }
                        val vl0 = LANG.find(e.name)?.groupValues?.get(1)?.uppercase()
                        val lab0 = listOfNotNull(vl0, q, group.take(18)).joinToString(" · ")
                        if (rl.size < XtreamApiLogic.MAX_REFS) rl.add(XtreamApiLogic.ref(e.url, lab0, q, vl0))
                        return@entries
                    }
                    val season = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
                    val num = m?.groupValues?.get(3)?.toIntOrNull() ?: (acc.n + 1)
                    val vl = pool.ofOrNull(LANG.find(e.name)?.groupValues?.get(1)?.uppercase())
                    val label = pool.of(listOfNotNull(vl, q, group.take(18)).joinToString(" · "))
                    acc.add(season, num, pool.of(EpisodeTable.prefixOf(e.url)), e.url, label, q, vl)
                }
                Kind.MOVIE -> {
                    val year = YEAR.find(e.name)?.groupValues?.get(1)?.toIntOrNull()
                    out.add(Item(e.url.hashCode().toString(), Kind.MOVIE, TitleText.title(clean(e.name)), year, e.url,
                        attr(e.info, "tvg-logo")?.ifBlank { null }, group, quality = q, lang = kd.lang))
                }
                Kind.LIVE -> {
                    val id = e.url.hashCode().toString()
                    out.add(Item(id, Kind.LIVE, TitleText.decode(e.name), null, e.url,
                        attr(e.info, "tvg-logo")?.ifBlank { null }, group, quality = q,
                        tvgId = attr(e.info, "tvg-id")?.ifBlank { null }, lang = kd.lang,
                        catId = pool.ofOrNull(attr(e.info, XtreamApiLogic.CAT_ATTR)?.ifBlank { null })))
                    CatchupLogic.fromInfo(e.info, e.url, headCu)?.let { catchup[id] = it }
                }
            }
        }

        for ((key, acc) in series) {
            if (acc.done) continue
            val refs = acc.refs?.joinToString(" ")
            val raw = acc.raw()
            acc.free()   // sofort freigeben: Folgen werden Serie für Serie umgebaut
            raw.sortWith(compareBy({ it.season }, { it.num }))
            val eps = ArrayList<Episode>()
            var i = 0
            while (i < raw.size) {
                var j = i + 1
                while (j < raw.size && raw[j].season == raw[i].season && raw[j].num == raw[i].num) j++
                if (j - i == 1) {
                    val r = raw[i]
                    // Nur eine Quelle: keine eigene Liste (spart bei 100.000+ Folgen viel Speicher; pickVariant nimmt dann url/quality der Folge)
                    eps.add(Episode(r.season, r.num, epTitle(r.num), r.url, emptyList(), r.q))
                } else {
                    val vars = raw.subList(i, j).sortedByDescending { rank(it.q) }.map { Variant(it.url, it.label, it.q, it.lang) }
                    eps.add(Episode(raw[i].season, raw[i].num, epTitle(raw[i].num), vars[0].url, vars, vars[0].quality))
                }
                i = j
            }
            out.add(Item("s_$key", Kind.SERIES, acc.title, null, "", acc.logo, acc.group, EpisodeTable.of(eps, pool), acc.q, tvgId = refs, lang = acc.lang))
        }
        out.trimToSize()
        return ParsedPlaylist(out, epgFromHeader(header), st.counts(), st.bytes, catchup)
    }
}

package com.ipvio.stream

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Binärer Zwischenspeicher der Playlist (ohne Android, testbar).
 * Version 5: enthält nur die gezeigten Sprachen (große Anbieter: 800.000 Einträge → z. B. 120.000) und im Kopf
 * die Anzahl und Datenmenge je Sprache der ganzen Liste sowie die damals ausgeblendeten Sprachen.
 * Die ganze Liste liegt zusätzlich komprimiert auf dem Gerät (pl_*.m3u.gz), daraus wird bei einer anderen Sprachauswahl neu eingelesen.
 * OnAir version 6: like 5, plus the Xtream category_id of each entry ([Item.catId]) after the tvg-id.
 * Version 4 (ganze Liste, Sprache vorne in jedem Eintrag) und 3 (ohne Sprache) werden weiter gelesen.
 */
object PlaylistCacheIO {
    const val VERSION = 6
    private const val MAX_STR = 1 shl 20      // 1 MB pro Text: mehr kann nur eine beschädigte Datei sein
    private const val MAX_COUNT = 2_000_000

    /**
     * [hidden] = beim Schreiben ausgeblendete Sprachen (nur Version 5); null = der Speicher enthält die ganze Liste (Version 3/4).
     * [bytes] = Datenmenge je Sprache der ganzen Liste (für die Speicher-Schätzung), bei Version 3/4 leer.
     */
    class Cached(
        val items: List<Item>, val epg: List<String>, val order: Map<String, Int>, val counts: Map<String, Int>,
        val bytes: Map<String, Long> = emptyMap(), val hidden: Set<String>? = null
    )

    /**
     * Schreibt Version 5. [list] = die gezeigten Einträge; [counts]/[bytes] beschreiben die ganze Liste.
     * Ohne [counts] wird aus [list] gezählt (dann enthält [list] alles, [hidden] ist leer).
     */
    fun write(
        out: OutputStream, url: String, list: List<Item>, epg: List<String>, order: Map<String, Int>,
        counts: Map<String, Int>? = null, bytes: Map<String, Long> = emptyMap(), hidden: Set<String> = emptySet()
    ) {
        val o = DataOutputStream(out)
        o.writeInt(VERSION)
        o.str(url.trim())
        o.writeInt(epg.size); epg.forEach { o.str(it) }
        o.writeInt(order.size); order.forEach { (k, v) -> o.str(k); o.writeInt(v) }
        o.str(LangTagLogic.encode(hidden))
        val c = counts ?: HashMap<String, Int>().also { m -> for (i in list) m[i.lang] = (m[i.lang] ?: 0) + 1 }
        o.writeInt(c.size); c.forEach { (k, v) -> o.str(k); o.writeInt(v) }
        o.writeInt(bytes.size); bytes.forEach { (k, v) -> o.str(k); o.writeLong(v) }
        o.writeInt(list.size)
        for (i in list) o.item(i)
        o.flush()
    }

    /** Kopf des Zwischenspeichers (Adresse geprüft); die Einträge folgen danach im Datenstrom. */
    private class Head(
        val version: Int, val epg: List<String>, val order: Map<String, Int>,
        val hidden: Set<String>?, val counts: Map<String, Int>?, val bytes: Map<String, Long>
    )

    private fun DataInputStream.head(url: String): Head? {
        val version = readInt()
        if (version !in 3..6) return null
        if (str() != url.trim()) return null
        val ne = count()
        val epg = ArrayList<String>(ne)
        for (k in 0 until ne) epg.add(str() ?: "")
        val no = count()
        val order = LinkedHashMap<String, Int>()
        for (k in 0 until no) { val key = str() ?: ""; order[key] = readInt() }
        if (version < 5) return Head(version, epg, order, null, null, emptyMap())
        val stored = LangTagLogic.decode(str())
        val nc = count()
        val counts = HashMap<String, Int>()
        for (k in 0 until nc) { val key = str() ?: ""; counts[key] = readInt() }
        val nb = count()
        val bytes = HashMap<String, Long>()
        for (k in 0 until nb) { val key = str() ?: ""; bytes[key] = readLong() }
        return Head(version, epg, order, stored, counts, bytes)
    }

    /**
     * Nur der Kopf, ohne Einträge: EPG-Adressen, Kategorien-Reihenfolge, Zähler und die damals ausgeblendeten Sprachen.
     * [Cached.hidden] = null bedeutet: der Speicher enthält die ganze Liste (Version 3/4). Die Zähler sind dann leer.
     */
    fun readHeader(input: InputStream, url: String): Cached? {
        val h = DataInputStream(input).head(url) ?: return null
        return Cached(emptyList(), h.epg, h.order, h.counts ?: emptyMap(), h.bytes, h.hidden)
    }

    /**
     * Liest den Zwischenspeicher; null, wenn er zu einer anderen Adresse gehört oder eine unbekannte Version hat. Beschädigt → Ausnahme.
     * [check] wird alle 2.000 Einträge aufgerufen (Speicher-Wache, Abbruch).
     */
    fun read(input: InputStream, url: String, hidden: Set<String>, check: (() -> Unit)? = null): Cached? {
        val i = DataInputStream(input)
        val h = i.head(url) ?: return null
        val n = i.count()
        val pool = StringPool()
        val counts = HashMap<String, Int>()
        val items = ArrayList<Item>(if (hidden.isEmpty()) n else minOf(n, 4096))
        for (k in 0 until n) {
            i.readItem(h.version, pool, hidden, counts)?.let { items.add(it) }
            if (check != null && k % 2000 == 1999) check()
        }
        items.trimToSize()
        return Cached(items, h.epg, h.order, h.counts ?: counts, h.bytes, h.hidden)
    }

    private fun DataInputStream.count(): Int {
        val n = readInt()
        if (n < 0 || n > MAX_COUNT) throw IOException("Zwischenspeicher beschädigt")
        return n
    }

    private fun DataOutputStream.str(s: String?) {
        if (s == null) writeInt(-1) else { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    }

    private fun DataInputStream.str(): String? {
        val n = readInt()
        if (n < 0) return null
        if (n > MAX_STR) throw IOException("Zwischenspeicher beschädigt")
        val b = ByteArray(n)
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    /** Überspringt einen Text, ohne ihn anzulegen (für ausgeblendete Einträge). */
    private fun DataInputStream.skipStr() {
        val n = readInt()
        if (n < 0) return
        if (n > MAX_STR) throw IOException("Zwischenspeicher beschädigt")
        var left = n
        while (left > 0) {
            val k = skipBytes(left)
            if (k <= 0) { if (read() < 0) throw java.io.EOFException(); left-- } else left -= k
        }
    }

    private fun DataOutputStream.item(i: Item) {
        str(i.id); writeInt(i.kind.ordinal); str(i.lang); str(i.title); writeInt(i.year ?: -1); str(i.url); str(i.logo); str(i.group)
        str(i.quality); str(i.tvgId); str(i.catId)
        writeInt(i.episodes.size)
        for (e in i.episodes) {
            writeInt(e.season); writeInt(e.num); str(e.title); str(e.url); str(e.quality)
            writeInt(e.variants.size)
            for (v in e.variants) { str(v.url); str(v.label); str(v.quality); str(v.lang) }
        }
    }

    /** Liest einen Eintrag; ausgeblendete Sprachen liefern null (gezählt werden sie trotzdem). */
    private fun DataInputStream.readItem(version: Int, pool: StringPool, hidden: Set<String>, counts: HashMap<String, Int>): Item? {
        val id = str() ?: ""
        val kind = Kind.entries.getOrNull(readInt()) ?: throw IOException("Zwischenspeicher beschädigt")
        var lang: String? = if (version >= 4) pool.of(str() ?: LangTagLogic.UNKNOWN) else null
        if (lang != null) {
            counts[lang] = (counts[lang] ?: 0) + 1
            if (lang in hidden) { skipItemRest(version); return null }
        }
        // Ältere Zwischenspeicher: Titel noch mit „&amp;“ bzw. „|EN|“ – beim Lesen bereinigen (schnell, meist unverändert)
        val rawTitle = str() ?: ""
        val title = if (kind == Kind.LIVE) TitleText.decode(rawTitle) else TitleText.title(rawTitle)
        val y = readInt()
        val url = str() ?: ""
        val logo = str()
        val group = pool.of(str() ?: "")
        val quality = pool.ofOrNull(str())
        val tvg = str()
        val catId = if (version >= 6) pool.ofOrNull(str()) else null
        val n = count()
        val eps = ArrayList<Episode>(n)
        for (k in 0 until n) {
            val season = readInt()
            val num = readInt()
            val et = pool.of(str() ?: "")
            val eu = str() ?: ""
            val eq = pool.ofOrNull(str())
            val vc = count()
            // Ältere Zwischenspeicher enthalten auch Einzel-Quellen als Liste: dann wie beim Einlesen weglassen (spart Speicher)
            val vars = if (vc == 0) emptyList() else ArrayList<Variant>(vc).also { l ->
                for (j in 0 until vc) l.add(Variant(str() ?: "", pool.of(str() ?: ""), pool.ofOrNull(str()), pool.ofOrNull(str())))
            }
            eps.add(Episode(season, num, et, eu, if (vars.size > 1) vars else emptyList(), eq))
        }
        if (lang == null) {
            lang = pool.of(LangTagLogic.of(group, rawTitle, kind == Kind.LIVE))
            counts[lang] = (counts[lang] ?: 0) + 1
            if (lang in hidden) return null
        }
        return Item(
            id = id, kind = kind, title = title, year = if (y < 0) null else y, url = url, logo = logo,
            group = group, episodes = EpisodeTable.of(eps, pool), quality = quality, tvgId = tvg, lang = lang, catId = catId
        )
    }

    private fun DataInputStream.skipItemRest(version: Int) {
        skipStr(); readInt(); skipStr(); skipStr(); skipStr(); skipStr(); skipStr()   // Titel, Jahr, Adresse, Logo, Kategorie, Qualität, tvg-id
        if (version >= 6) skipStr()   // OnAir: category_id
        val n = count()
        for (k in 0 until n) {
            readInt(); readInt(); skipStr(); skipStr(); skipStr()
            val vc = count()
            for (j in 0 until vc) { skipStr(); skipStr(); skipStr(); skipStr() }
        }
    }
}

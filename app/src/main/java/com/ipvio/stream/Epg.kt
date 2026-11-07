package com.ipvio.stream

import android.content.Context
import android.util.Base64
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

private val FMT = object : ThreadLocal<HashMap<String, SimpleDateFormat>>() {
    override fun initialValue() = HashMap<String, SimpleDateFormat>()
}

private fun fmt(pattern: String): SimpleDateFormat {
    val loc = Lang.locale()
    val m = FMT.get()!!
    val f = m.getOrPut(pattern + "|" + loc.toLanguageTag()) { SimpleDateFormat(pattern, loc) }
    f.timeZone = epgZone()
    return f
}

/** Zeitzone für alle Uhrzeiten (Einstellung "Zeitzone" unter Sprache & Region; Standard: Systemzeit des Geräts). */
fun epgZone(): TimeZone = General.zone()

/** Uhrzeit im gewählten Format (24 h / 12 h) und in der gewählten Zeitzone. Die Formatter werden pro Thread wiederverwendet. */
fun hhmm(sec: Long): String = fmt(SettingsLogic.timePattern(General.timeFmt, General.sys24)).format(Date(sec * 1000))

/** Tag "Mo 19.10." in der gewählten Zeitzone und Sprache. */
fun dayLabel(sec: Long): String = fmt("EE dd.MM.").format(Date(sec * 1000))

/** Gemeinsamer Netzwerkzugang für Programminfos: ein Client (Verbindungen werden wiederverwendet) und höchstens 6 Abfragen gleichzeitig. */
object EpgNet {
    val client = Net.withTimeouts(8, 12)
    val gate = Semaphore(6)

    suspend fun fetch(url: String): String? = gate.withPermit {
        withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            } catch (e: Exception) {
                null
            }
        }
    }
}

/** Prüft, ob eine Sendung plausible Zeiten hat (Schutz gegen kaputte EPG-Daten). */
fun epgSane(start: Long, end: Long): Boolean {
    if (end <= start) return false
    if (end - start > 12 * 3600L) return false
    val now = System.currentTimeMillis() / 1000
    if (start > now + 7 * 86400L) return false
    if (end > now + 8 * 86400L) return false
    return true
}

object Xmltv {
    var status by mutableStateOf("Noch nicht geladen")
    var channelCount by mutableStateOf(0)
    private val progs = ConcurrentHashMap<String, List<EpgEntry>>()
    private val client = Net.withTimeouts(20, 180)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private fun prefs(c: Context) = c.getSharedPreferences("ipvio_epg", Context.MODE_PRIVATE)
    fun customUrl(c: Context): String = prefs(c).getString("custom", "") ?: ""
    fun setCustom(c: Context, u: String) { prefs(c).edit().putString("custom", u.trim()).apply() }

    fun has(id: String?): Boolean = id != null && progs.containsKey(id.lowercase())
    fun forChannel(id: String?): List<EpgEntry> = if (id == null) emptyList() else progs[id.lowercase()] ?: emptyList()

    /**
     * Lädt das XMLTV-Programm für die sichtbaren Sender (ausgeblendete Sprachen kommen gar nicht erst an).
     * Gespeichert wird nur das Nötige: Fenster −1 h bis +36 h, höchstens 80 Sendungen je Sender, Beschreibung gekürzt,
     * gleiche Titel nur einmal. Wird der Speicher knapp, hört das Einlesen auf und behält das bis dahin Gelesene.
     */
    fun start(c: Context, urls: List<String>, items: List<Item>) {
        val all = (urls + listOf(customUrl(c))).filter { it.startsWith("http") }.distinct()
        if (all.isEmpty()) { status = "Keine EPG-Adresse gefunden"; return }
        val wanted = items.filter { it.kind == Kind.LIVE }.mapNotNull { it.tvgId?.lowercase() }.toSet()
        if (wanted.isEmpty()) { status = "Playlist enthält keine Sender-IDs (tvg-id)"; return }
        job?.cancel()
        job = scope.launch {
            status = "Wird geladen …"
            val map = HashMap<String, MutableList<EpgEntry>>()
            var err: String? = null
            truncated = false
            // Sender mit Archiv (Catchup): auch vergangene Sendungen behalten (höchstens 7 Tage, Speicher)
            val past = HashMap<String, Int>()
            for (it in items) {
                val id = it.tvgId ?: continue
                val d = Catchup.of(it)?.days ?: continue
                val k = id.lowercase()
                past[k] = maxOf(past[k] ?: 0, d.coerceAtMost(7))
            }
            for (u in all) {
                try { parse(u, wanted, map, past) } catch (e: Exception) { LoginDebug.error(u, "xmltv", e); err =if (e is PlaylistError) e.message else e.javaClass.simpleName }
            }
            progs.clear()
            for ((k, v) in map) progs[k] = v.sortedBy { it.start }
            channelCount = progs.size
            LoginDebug.info("xmltv: ${progs.size} channels with guide data" + (if (err != null) " (error: $err)" else ""))
            status = if (progs.isEmpty()) "Keine Programmdaten erhalten (${err ?: "leer"})"
            else if (truncated) "Geladen: ${progs.size} Sender mit Programm (gekürzt, Speicher knapp)"
            else "Geladen: ${progs.size} Sender mit Programm"
        }
    }

    private fun parseTime(s: String?): Long? = xmltvTime(s)

    private const val MAX_PER_CHANNEL = 80
    private const val MAX_DESC = 400
    @Volatile private var truncated = false

    private fun shortDesc(d: String): String =
        if (d.length <= MAX_DESC) d else d.substring(0, d.lastIndexOf(' ', MAX_DESC).takeIf { it > MAX_DESC / 2 } ?: MAX_DESC) + " …"

    private fun parse(url: String, wanted: Set<String>, out: HashMap<String, MutableList<EpgEntry>>, past: Map<String, Int> = emptyMap()) {
        LoginDebug.request(url)
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (BuildConfig.DEBUG) LoginDebug.response(url, r.code, r.message, runCatching { r.peekBody(1024).string() }.getOrDefault("<unreadable>"))
            if (!r.isSuccessful) throw PlaylistError("HTTP ${r.code}")
            var ins: InputStream = BufferedInputStream((r.body ?: throw PlaylistError("leer")).byteStream())
            ins.mark(2)
            val b1 = ins.read()
            val b2 = ins.read()
            ins.reset()
            if (b1 == 0x1f && b2 == 0x8b) ins = GZIPInputStream(ins)
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            val now = System.currentTimeMillis() / 1000
            val pool = StringPool()
            var seen = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                    val ch = p.getAttributeValue(null, "channel")?.lowercase()
                    val s = parseTime(p.getAttributeValue(null, "start"))
                    val e = parseTime(p.getAttributeValue(null, "stop"))
                    var title = ""
                    var desc = ""
                    var inner = p.next()
                    while (!(inner == XmlPullParser.END_TAG && p.name == "programme") && inner != XmlPullParser.END_DOCUMENT) {
                        if (inner == XmlPullParser.START_TAG) {
                            if (p.name == "title" && title.isEmpty()) title = p.nextText()
                            else if (p.name == "desc" && desc.isEmpty()) desc = p.nextText()
                        }
                        inner = p.next()
                    }
                    val pd = if (ch != null) past[ch] ?: 0 else 0
                    if (ch != null && ch in wanted && s != null && e != null &&
                        e > now - (if (pd > 0) pd * 86400L else 3600L) && s < now + 129600 && epgSane(s, e)
                    ) {
                        val l = out.getOrPut(ch) { ArrayList() }
                        if (l.size < MAX_PER_CHANNEL + pd * 48) l.add(EpgEntry(pool.of(title), shortDesc(desc), s, e))
                    }
                    // Speicher wird knapp: aufhören und das bisher Gelesene behalten (lieber Lücken im Programm als ein Absturz)
                    if (++seen % 5000 == 0 && Mem.heapTight(0.70)) { truncated = true; return }
                }
                ev = p.next()
            }
        }
    }
}

/** Kleiner Zwischenspeicher mit Obergrenze: die zuletzt benutzten Einträge bleiben, die ältesten fallen heraus. */
class LruCache<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    @Synchronized operator fun get(k: K): V? = map[k]
    @Synchronized operator fun set(k: K, v: V) { map[k] = v }
    @Synchronized fun clear() = map.clear()
}

object Epg {
    private val cache = LruCache<String, Pair<Long, List<EpgEntry>>>(400)
    fun trim() = cache.clear()
    private val LIVE_RE = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?(?:\?.*)?$""")

    fun supported(item: Item): Boolean = LIVE_RE.matches(item.url) || Xmltv.has(item.tvgId)

    /** Adresse der Xtream-Programmabfrage [action] für einen Sender oder null, wenn der Sender keine Xtream-Adresse hat. */
    internal fun xtreamApi(item: Item, action: String): String? {
        val p = LIVE_RE.find(item.url)?.groupValues ?: return null
        return "${p[1]}/player_api.php?username=${p[2]}&password=${p[3]}&action=$action&stream_id=${p[4]}"
    }

    /** Xtream liefert Titel und Beschreibung Base64-kodiert, manche Anbieter aber im Klartext. */
    private fun decode(s: String): String {
        if (s.isBlank()) return ""
        return try {
            val d = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
            if (d.isBlank() || d.contains('\uFFFD')) s else d
        } catch (e: Exception) {
            s
        }
    }

    /** Liest "epg_listings" einer Xtream-Antwort; kaputte Einträge werden übersprungen. Ergebnis nach Startzeit sortiert. */
    internal fun parseListings(body: String, keep: (start: Long, end: Long) -> Boolean = { _, _ -> true }): List<EpgEntry> {
        val out = ArrayList<EpgEntry>()
        try {
            val a = JSONObject(body).optJSONArray("epg_listings")
            if (a != null) for (x in 0 until a.length()) {
                val o = a.getJSONObject(x)
                val s = o.optString("start_timestamp").toLongOrNull() ?: continue
                val e = o.optString("stop_timestamp").toLongOrNull() ?: continue
                if (!epgSane(s, e) || !keep(s, e)) continue
                out.add(EpgEntry(decode(o.optString("title")), decode(o.optString("description")), s, e))
            }
        } catch (e: Exception) {
        }
        out.sortBy { it.start }
        return out
    }

    /** Laufende und kommende Sendungen eines Senders. Reihenfolge: Zwischenspeicher, geladenes XMLTV (sofort), dann Xtream-Abfrage. */
    suspend fun now(item: Item): List<EpgEntry> {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        cache[item.id]?.let { if (nowMs - it.first < 120_000) return it.second }
        val local = Xmltv.forChannel(item.tvgId).filter { it.end > nowSec }
        if (local.isNotEmpty() && current(local) != null) return local.take(8)
        val url = xtreamApi(item, "get_short_epg")
        if (url != null) {
            LoginDebug.request("$url&limit=8")
            val body = EpgNet.fetch("$url&limit=8")
            if (BuildConfig.DEBUG) LoginDebug.info("get_short_epg fallback: " + (if (body == null) "failed" else "${body.length} chars"))
            if (body != null) {
                val out = parseListings(body)
                if (out.isNotEmpty()) { cache[item.id] = nowMs to out; return out }
            }
        }
        return local.take(8)
    }

    fun current(list: List<EpgEntry>): EpgEntry? {
        val n = System.currentTimeMillis() / 1000
        return list.firstOrNull { n >= it.start && n < it.end }
    }
}

data class Seg(val label: String, val start: Long, val end: Long)

object IntroDb {
    private val client = Net.base

    suspend fun get(tmdbId: Int, season: Int, episode: Int, durMs: Long): List<Seg> {
        val tv = if (season > 0 && episode > 0) "&season=$season&episode=$episode" else ""
        val url = "https://api.theintrodb.org/v3/media?tmdb_id=$tmdbId$tv&duration_ms=$durMs"
        val body = withContext(Dispatchers.IO) {
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                }
            } catch (e: Exception) {
                null
            }
        } ?: return emptyList()
        val out = ArrayList<Seg>()
        try {
            val o = JSONObject(body)
            // Nur plausible Abschnitte (siehe SegLogic): fehlende Endzeiten bei Intro/Rückblick oder ein „Intro“ mitten in der Folge
            // führten sonst zu Knöpfen, die nicht passen
            fun read(key: String, kind: Int, label: String) {
                val a = o.optJSONArray(key) ?: return
                for (x in 0 until a.length()) {
                    val s = a.optJSONObject(x) ?: continue
                    val st = if (!s.has("start_ms") || s.isNull("start_ms")) null else s.optLong("start_ms")
                    val en = if (!s.has("end_ms") || s.isNull("end_ms")) null else s.optLong("end_ms")
                    SegLogic.clean(kind, st, en, durMs)?.let { (a0, b0) -> out.add(Seg(label, a0, b0)) }
                }
            }
            read("intro", SegLogic.INTRO, "Intro überspringen")
            read("recap", SegLogic.RECAP, "Rückblick überspringen")
            read("credits", SegLogic.CREDITS, "Abspann überspringen")
        } catch (e: Exception) {
        }
        return out
    }
}
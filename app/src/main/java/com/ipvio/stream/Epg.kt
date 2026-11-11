package com.ipvio.stream

import android.content.Context
import android.util.Base64
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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
    // OnAir: per-channel guide lookups go through the provider rate-limit guard as well
    val client = ProviderGuard.client(Net.withTimeouts(8, 12))
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
    /** OnAir: when the newest stored guide was downloaded (0 = none), for the settings screen. */
    var storedAt by mutableLongStateOf(0L)
        private set
    private val progs = ConcurrentHashMap<String, List<EpgEntry>>()
    private val client = ProviderGuard.client(Net.withTimeouts(20, 180))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    // ------------------------------------------------------------------ OnAir: guide stored on the device
    // The XMLTV answer is kept gzip-compressed per address (epg_<hash>.xml.gz – the address itself contains the password,
    // so only its hash is used). App start reads it from there; only the refresh schedule or a button downloads it again.

    private const val PRUNE_MS = 3 * 86_400_000L
    private const val SPACE_RESERVE = 64L * 1024 * 1024

    fun file(c: Context, url: String) = File(c.filesDir, "epg_" + url.trim().hashCode() + ".xml.gz")
    private fun tmpOf(f: File) = File(f.path + ".tmp")

    fun deleteFile(c: Context, url: String) {
        val f = file(c, url)
        runCatching { f.delete(); tmpOf(f).delete() }
        RefreshGate.forget(c, RefreshGate.epgKey(url))
    }

    fun deleteAll(c: Context) {
        runCatching { c.filesDir.listFiles { f -> f.name.startsWith("epg_") }?.forEach { it.delete() } }
    }

    /** Stored guides of addresses no longer in use (other account, changed custom address) – deleted after 3 days. */
    private fun prune(c: Context, keep: Collection<String>) {
        runCatching {
            val names = keep.map { file(c, it).name }.toSet()
            val now = System.currentTimeMillis()
            c.filesDir.listFiles { f -> f.name.startsWith("epg_") }
                ?.filter { (it.name !in names && now - it.lastModified() > PRUNE_MS) || it.name.endsWith(".tmp") }
                ?.forEach { it.delete() }
        }
    }

    private fun allUrls(c: Context, urls: List<String>) = (urls + listOf(customUrl(c))).filter { it.startsWith("http") }.distinct()

    /**
     * Downloads [url] into its file when the refresh gate allows it ([trigger]); the old file stays until a complete,
     * valid answer has arrived. Returns an error text, or null (downloaded, or not due and the stored copy is used).
     */
    private fun fetchIfAllowed(c: Context, url: String, trigger: RefreshLogic.Trigger, active: () -> Boolean): String? {
        val f = file(c, url)
        val key = RefreshGate.epgKey(url)
        if (!RefreshGate.allowed(c, key, trigger, f.exists() && f.length() > 0)) return null
        val t = tmpOf(f)
        return try {
            download(c, url, t, active)
            if (!looksLikeXmltv(t)) throw PlaylistError("leer")
            f.delete()
            if (!t.renameTo(f)) throw java.io.IOException("rename")
            RefreshGate.succeeded(c, key)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            t.delete(); RefreshGate.attempted(c, key); throw e
        } catch (e: Exception) {
            t.delete()
            LoginDebug.error(url, "xmltv download", e)
            if (!active()) RefreshGate.attempted(c, key) else RefreshGate.failed(c, key)
            when (e) {
                is ProviderBusy -> "HTTP 429"
                is PlaylistError -> e.message
                else -> e.javaClass.simpleName
            }
        }
    }

    /** Streams the answer to [to], gzip-compressed (kept as it is if the server already sends gzip). */
    private fun download(c: Context, url: String, to: File, active: () -> Boolean) {
        LoginDebug.request(url)
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (BuildConfig.DEBUG) LoginDebug.response(url, r.code, r.message, runCatching { r.peekBody(1024).string() }.getOrDefault("<unreadable>"))
            if (!r.isSuccessful) throw PlaylistError("HTTP ${r.code}")
            val body = r.body ?: throw PlaylistError("leer")
            val len = body.contentLength()
            if (len > 0 && c.filesDir.usableSpace < len + SPACE_RESERVE) throw PlaylistError(PlaylistLogic.NO_SPACE)
            val ins = BufferedInputStream(body.byteStream(), 1 shl 16)
            ins.mark(2)
            val gz = ins.read() == 0x1f && ins.read() == 0x8b
            ins.reset()
            FileOutputStream(to).use { fo ->
                val out = if (gz) BufferedOutputStream(fo, 1 shl 16)
                else object : GZIPOutputStream(BufferedOutputStream(fo, 1 shl 16), 1 shl 16) { init { def.setLevel(Deflater.BEST_SPEED) } }
                val buf = ByteArray(1 shl 16)
                var n = 0L
                var checked = 0L
                while (true) {
                    val k = ins.read(buf)
                    if (k < 0) break
                    out.write(buf, 0, k)
                    n += k
                    if (n - checked >= 1L shl 20) {
                        checked = n
                        if (!active()) throw kotlinx.coroutines.CancellationException("abgebrochen")
                    }
                }
                (out as? GZIPOutputStream)?.finish()
                out.flush()
            }
        }
    }

    /** A stored answer that is not XMLTV (empty, HTML error page) must not replace a good one. */
    private fun looksLikeXmltv(f: File): Boolean = try {
        openStored(f).use { ins ->
            val head = ByteArray(2048)
            val n = ins.read(head)
            n > 0 && String(head, 0, n, Charsets.UTF_8).let { it.contains("<tv") || it.contains("<?xml") }
        }
    } catch (e: Exception) {
        false
    }

    private fun openStored(f: File): InputStream {
        val ins = BufferedInputStream(FileInputStream(f), 1 shl 16)
        ins.mark(2)
        val gz = ins.read() == 0x1f && ins.read() == 0x8b
        ins.reset()
        return if (gz) GZIPInputStream(ins, 1 shl 16) else ins
    }

    /** Background job with the app closed: only renew the stored guide files (no parsing, nothing kept in memory). */
    fun refreshFiles(c: Context, urls: List<String>, trigger: RefreshLogic.Trigger) {
        val all = allUrls(c, urls)
        for (u in all) fetchIfAllowed(c, u, trigger) { true }
        prune(c, all)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("ipvio_epg", Context.MODE_PRIVATE)
    fun customUrl(c: Context): String = prefs(c).getString("custom", "") ?: ""
    fun setCustom(c: Context, u: String) { prefs(c).edit().putString("custom", u.trim()).apply() }

    fun has(id: String?): Boolean = id != null && progs.containsKey(id.lowercase())
    fun forChannel(id: String?): List<EpgEntry> = if (id == null) emptyList() else progs[id.lowercase()] ?: emptyList()

    /**
     * Lädt das XMLTV-Programm für die sichtbaren Sender (ausgeblendete Sprachen kommen gar nicht erst an).
     * Gespeichert wird nur das Nötige: Fenster −1 h bis +36 h, höchstens 80 Sendungen je Sender, Beschreibung gekürzt,
     * gleiche Titel nur einmal. Wird der Speicher knapp, hört das Einlesen auf und behält das bis dahin Gelesene.
     *
     * OnAir: read from the guide stored on the device. It is downloaded only when [trigger] allows it (see [RefreshLogic]):
     * AUTO = only if nothing is stored yet, SCHEDULED = at most every 12 h, MANUAL = the owner pressed a button.
     */
    fun start(c: Context, urls: List<String>, items: List<Item>, trigger: RefreshLogic.Trigger = RefreshLogic.Trigger.AUTO) {
        val app = c.applicationContext
        val all = allUrls(app, urls)
        if (all.isEmpty()) { status = "Keine EPG-Adresse gefunden"; return }
        val wanted = items.filter { it.kind == Kind.LIVE }.mapNotNull { it.tvgId?.lowercase() }.toSet()
        if (wanted.isEmpty()) { status = "Playlist enthält keine Sender-IDs (tvg-id)"; return }
        job?.cancel()
        job = scope.launch {
            status = "Wird geladen …"
            val active = { isActive }
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
            var newest = 0L
            for (u in all) {
                fetchIfAllowed(app, u, trigger, active)?.let { err = it }
                val f = file(app, u)
                if (!f.exists()) continue
                newest = maxOf(newest, f.lastModified())
                try { parse(f, wanted, map, past) } catch (e: Exception) { LoginDebug.error(u, "xmltv", e); err = if (e is PlaylistError) e.message else e.javaClass.simpleName }
            }
            if (!isActive) return@launch
            prune(app, all)
            storedAt = newest
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

    /** OnAir: parses a stored guide file (see [file]). */
    private fun parse(f: File, wanted: Set<String>, out: HashMap<String, MutableList<EpgEntry>>, past: Map<String, Int> = emptyMap()) {
        openStored(f).use { ins ->
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
        val local = Xmltv.forChannel(item.tvgId).filter { it.end > nowSec }
        if (local.isNotEmpty() && current(local) != null) return local.take(8)
        // OnAir: per-channel answers are reused for 15 min (finished programmes dropped), "nothing" for 30 min –
        // scrolling the guide must not send one request per row again and again
        cache[item.id]?.let { (at, l) ->
            val ttl = if (l.isEmpty()) RefreshLogic.CHANNEL_EPG_EMPTY_TTL_MS else RefreshLogic.CHANNEL_EPG_TTL_MS
            if (nowMs - at in 0 until ttl) return l.filter { it.end > nowSec }.ifEmpty { local.take(8) }
        }
        val url = xtreamApi(item, "get_short_epg")
        if (url != null && !ProviderGuard.coolingDown(nowMs)) {
            LoginDebug.request("$url&limit=8")
            val body = EpgNet.fetch("$url&limit=8")
            if (BuildConfig.DEBUG) LoginDebug.info("get_short_epg fallback: " + (if (body == null) "failed" else "${body.length} chars"))
            val out = if (body != null) parseListings(body) else emptyList()
            cache[item.id] = nowMs to out
            if (out.isNotEmpty()) return out
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
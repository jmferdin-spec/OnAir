package com.ipvio.stream

import android.content.Context
import android.util.Base64
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    /** OnAir Phase 3: when the guide database was last filled (ms, 0 = never) and how many programmes it holds. */
    var importedAt by mutableLongStateOf(0L)
        private set
    var rowCount by mutableIntStateOf(0)
        private set
    /** OnAir v2: what a running update is doing right now (null = nothing), for the home screen's "Update guide" line. */
    var phase by mutableStateOf<GuideUpdateLogic.Phase?>(null)
        private set
    /** OnAir v2: error of the last update the owner started (button), and when; null after a successful one. */
    var lastError by mutableStateOf<String?>(null)
        private set
    var errorAt by mutableLongStateOf(0L)
        private set
    /** OnAir Phase 3: tvg-ids (lower case) that have programmes in [GuideDb] – small, kept in memory for [has]. */
    @Volatile private var ids: Set<String> = emptySet()
    /** Recently read channels (the guide scrolls back and forth); cleared whenever the database changes. */
    private val recent = LruCache<String, List<EpgEntry>>(300)
    @Volatile private var appCtx: Context? = null
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
        clearDb(c)   // OnAir Phase 3: the guide database too
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
        phase = GuideUpdateLogic.Phase.DOWNLOADING
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
            LoginDebug.response(url, r.code, r.message) { runCatching { r.peekBody(1024).string() }.getOrDefault("<unreadable>") }
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

    /** Does the guide have programmes for this tvg-id? (memory only, safe on the main thread) */
    fun has(id: String?): Boolean = id != null && id.trim().lowercase() in ids

    /**
     * Programmes of one channel, by start time, from [GuideDb]. Blocking database read – call from a background thread
     * (Epg.now / EpgFull.get do). Channels without guide data return at once without touching the database.
     */
    fun forChannel(id: String?): List<EpgEntry> {
        val k = id?.trim()?.lowercase() ?: return emptyList()
        if (k !in ids) return emptyList()
        recent[k]?.let { return it }
        val c = appCtx ?: return emptyList()
        return runCatching { GuideDb.forChannel(c, k) }.getOrElse { emptyList() }.also { recent[k] = it }
    }

    /** Re-reads the in-memory view of the database (channel set, counts) after an import or prune. */
    private fun refreshView(c: Context) {
        ids = runCatching { GuideDb.channelIds(c) }.getOrElse { emptySet() }
        recent.clear()
        val i = runCatching { GuideDb.info(c) }.getOrNull()
        importedAt = i?.importedAt ?: 0L
        rowCount = i?.rows ?: 0
        channelCount = ids.size
    }

    /** Everything removed (all playlists deleted). */
    fun clearDb(c: Context) {
        job?.cancel()
        GuideDb.clear(c)
        ids = emptySet(); recent.clear(); importedAt = 0L; rowCount = 0; channelCount = 0
    }

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
        appCtx = app
        val all = allUrls(app, urls)
        if (all.isEmpty()) { status = "Keine EPG-Adresse gefunden"; return }
        val wanted = items.filter { it.kind == Kind.LIVE }.mapNotNull { it.tvgId?.trim()?.lowercase()?.ifEmpty { null } }.toSet()
        if (wanted.isEmpty()) { status = "Playlist enthält keine Sender-IDs (tvg-id)"; return }
        // Sender mit Archiv (Catchup): auch vergangene Sendungen behalten (höchstens 7 Tage)
        val past = HashMap<String, Int>()
        for (it in items) {
            val id = it.tvgId ?: continue
            val d = Catchup.of(it)?.days ?: continue
            val k = id.trim().lowercase()
            past[k] = maxOf(past[k] ?: 0, d.coerceAtMost(GuideLogic.MAX_PAST_DAYS))
        }
        job?.cancel()
        job = scope.launch {
          val me = coroutineContext[Job]
          try {
            // What the database already has is usable at once (no waiting for the import)
            if (ids.isEmpty()) refreshView(app)
            status = "Wird geladen …"
            val active = { isActive }
            var err: String? = null
            for (u in all) fetchIfAllowed(app, u, trigger, active)?.let { err = it }
            if (!isActive) return@launch
            prune(app, all)
            val files = all.map { file(app, it) }.filter { it.exists() && it.length() > 0 }
            storedAt = files.maxOfOrNull { it.lastModified() } ?: 0L
            // OnAir Phase 3: import into SQLite only when the stored files, the wanted channels or the rules changed
            val now = System.currentTimeMillis() / 1000
            val sig = GuideLogic.signature(files.map { Triple(it.name, it.lastModified(), it.length()) }, wanted, past)
            val current = runCatching { GuideDb.meta(app, "sig") }.getOrNull()
            if (files.isNotEmpty() && sig != current) {
                status = "Programm wird eingelesen …"
                phase = GuideUpdateLogic.Phase.IMPORTING
                val t0 = System.currentTimeMillis()
                try {
                    val offsets = GuideLogic.OffsetTally()
                    val rows = GuideDb.replaceAll(app, sig, { mapOf("offsets" to offsets.encode()) }) { sink ->
                        for (f in files) parseInto(f, wanted, past, now, sink, active, offsets)
                    }
                    LoginDebug.info("guide import: $rows programmes in ${System.currentTimeMillis() - t0} ms, start-time offsets ${offsets.encode()}")
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LoginDebug.error("guide.db", "import", e)
                    err = if (e is PlaylistError) e.message else e.javaClass.simpleName
                }
            } else {
                runCatching { GuideDb.prune(app, GuideLogic.pruneBefore(now)) }
            }
            if (!isActive) return@launch
            refreshView(app)
            LoginDebug.info("guide: ${ids.size} channels, $rowCount programmes" + (if (err != null) " (error: $err)" else ""))
            status = if (ids.isEmpty()) "Keine Programmdaten erhalten (${err ?: "leer"})"
            else "Geladen: ${ids.size} Sender mit Programm"
            // OnAir v2: the result of an update the owner started is shown next to the button
            if (trigger == RefreshLogic.Trigger.GUIDE || trigger == RefreshLogic.Trigger.MANUAL) {
                lastError = err; errorAt = System.currentTimeMillis()
            }
          } finally {
            if (job === me) phase = null
          }
        }
    }

    /**
     * OnAir v2: may the owner's "Update guide" button run now? null = yes; else why not and from when (the guide address
     * that is free soonest). Includes the provider's cooldown. Reads only stored state – no request.
     */
    fun manualCheck(c: Context, urls: List<String>): GuideUpdateLogic.Blocked? {
        val app = c.applicationContext
        val all = allUrls(app, urls)
        if (all.isEmpty()) return null
        val now = System.currentTimeMillis()
        val per = all.map { u ->
            val key = RefreshGate.epgKey(u)
            val cached = file(app, u).let { it.exists() && it.length() > 0 }
            val next = RefreshGate.nextAllowedAt(app, key, RefreshLogic.Trigger.GUIDE, cached)
            val reason = if (ProviderGuard.coolingDown(now)) RefreshLogic.Wait.PROVIDER
            else RefreshLogic.waitReason(RefreshGate.state(app, key), now, RefreshLogic.Trigger.GUIDE, cached)
            next to reason
        }
        val (reason, until) = GuideUpdateLogic.combine(now, per) ?: return null
        return GuideUpdateLogic.Blocked(reason, until, storedAt)
    }

    /**
     * OnAir v2: the home screen's "Update guide" button – downloads only the guide (xmltv.php, every guide address of the
     * playlist that may be fetched now) and re-imports it. Never the catalog. Returns why it can't run, or null if started.
     */
    fun updateNow(c: Context, urls: List<String>, items: List<Item>): GuideUpdateLogic.Blocked? {
        if (phase != null) return null   // already running
        manualCheck(c, urls)?.let { return it }
        start(c, urls, items, RefreshLogic.Trigger.GUIDE)
        return null
    }

    private fun parseTime(s: String?): Long? = xmltvTime(s)

    /**
     * OnAir Phase 3: streams one stored guide file into the database import ([sink]). Keeps what [GuideLogic.keep] allows for
     * wanted channels, with sub-title, categories and episode number. Nothing is collected in memory.
     */
    private fun parseInto(
        f: File, wanted: Set<String>, past: Map<String, Int>, now: Long, sink: GuideDb.Sink, active: () -> Boolean,
        offsets: GuideLogic.OffsetTally? = null
    ) {
        openStored(f).use { ins ->
            val p = Xml.newPullParser()
            p.setInput(ins, null)
            val cats = ArrayList<String>(4)
            val eps = ArrayList<Pair<String?, String>>(2)
            var seen = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "programme") {
                    val ch = p.getAttributeValue(null, "channel")?.trim()?.lowercase()
                    val rawStart = p.getAttributeValue(null, "start")
                    val s = parseTime(rawStart)
                    val e = parseTime(p.getAttributeValue(null, "stop"))
                    // Channels nobody can see: skip the whole element without reading its texts
                    if (ch == null || ch !in wanted || s == null || e == null) {
                        skipElement(p)
                    } else {
                        var title = ""
                        var sub: String? = null
                        var desc: String? = null
                        cats.clear(); eps.clear()
                        var inner = p.next()
                        while (!(inner == XmlPullParser.END_TAG && p.name == "programme") && inner != XmlPullParser.END_DOCUMENT) {
                            if (inner == XmlPullParser.START_TAG) when (p.name) {
                                "title" -> if (title.isEmpty()) title = p.nextText().trim()
                                "sub-title" -> if (sub == null) sub = p.nextText().trim().ifEmpty { null }
                                "desc" -> if (desc == null) desc = p.nextText()
                                "category" -> p.nextText().let { if (cats.size < 8) cats.add(it) }
                                "episode-num" -> { val sys = p.getAttributeValue(null, "system"); eps.add(sys to p.nextText()) }
                            }
                            inner = p.next()
                        }
                        if (title.isNotEmpty() && epgSane(s, e) && GuideLogic.keep(s, e, now, past[ch] ?: 0)) {
                            sink.add(ch, s, e, title, sub, GuideLogic.shortDesc(desc), GuideLogic.joinCategories(cats), GuideLogic.episode(eps))
                            offsets?.add(rawStart)
                        }
                    }
                    if (++seen % 5000 == 0 && !active()) throw kotlinx.coroutines.CancellationException("abgebrochen")
                }
                ev = p.next()
            }
        }
    }

    /** Skips the current element (parser on its START_TAG) including everything inside. */
    private fun skipElement(p: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
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
    /**
     * OnAir Phase 3: [network] = this is the one channel the owner is looking at (focused panel, playing channel). Only then,
     * and only if the guide database has no rows at all for the channel, is the provider asked (get_short_epg). List rows
     * never ask.
     */
    suspend fun now(item: Item, network: Boolean = false): List<EpgEntry> {
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val local = withContext(Dispatchers.IO) { Xmltv.forChannel(item.tvgId) }.filter { it.end > nowSec }
        if (local.isNotEmpty() && current(local) != null) return local.take(8)
        // OnAir: per-channel answers are reused for 15 min (finished programmes dropped), "nothing" for 30 min –
        // scrolling the guide must not send one request per row again and again
        cache[item.id]?.let { (at, l) ->
            val ttl = if (l.isEmpty()) RefreshLogic.CHANNEL_EPG_EMPTY_TTL_MS else RefreshLogic.CHANNEL_EPG_TTL_MS
            if (nowMs - at in 0 until ttl) return l.filter { it.end > nowSec }.ifEmpty { local.take(8) }
        }
        val url = if (network && !Xmltv.has(item.tvgId)) xtreamApi(item, "get_short_epg") else null
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
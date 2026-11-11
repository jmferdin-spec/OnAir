package com.ipvio.stream

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.io.Reader
import java.io.Writer
import java.nio.charset.Charset
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class PL(val name: String, val url: String)

/**
 * Playlists: Liste der gespeicherten Zugänge, Laden, Zwischenspeicher.
 *
 * Ablauf beim Laden (ausgelegt auf Anbieter mit 800.000+ Einträgen und Geräte mit wenig Speicher):
 * 1. Der Zugang wird VOR dem Download gemerkt (Absturz, Stromausfall, App geschlossen → beim nächsten Start geht es weiter).
 * 2. Download in eine Datei (pl_*.part), nicht in den Speicher. Erlaubt der Server es, wird ein abgebrochener Download fortgesetzt.
 * 3. Einlesen Eintrag für Eintrag. Ausgeblendete Sprachen werden nur gezählt. Ist die Liste für das Gerät zu groß und noch keine
 *    Sprache gewählt, wird gefragt, welche Sprachen geladen werden sollen.
 * 4. Gespeichert werden die gezeigten Titel (pl_*.bin, schneller Start) und die ganze Liste komprimiert (pl_*.m3u.gz, ~10 %),
 *    damit eine andere Sprachauswahl ohne neuen Download geht.
 */
object PlaylistStore {
    /**
     * Die geladenen Einträge – schon ohne die im Sprachfilter ausgeblendeten Sprachen.
     * Ausgeblendete Sprachen werden gar nicht erst in den Speicher geholt (große Anbieter: 283.000 → z. B. 57.000 Titel).
     */
    private var rawChannels by mutableStateOf<List<Item>>(emptyList())
    /**
     * Neue Liste übernehmen. [shown] = schon nach Jugendschutz gefiltert (im Hintergrund berechnet), sonst wird es hier gemacht –
     * so muss das Filtern (Regex über alle Titel) nicht beim Zeichnen im Hauptthread passieren.
     */
    private fun setItems(l0: List<Item>, shown: List<Item>? = null) {
        LibMatch.reset()
        // OnAir: newer PPV / event channel names from the Events refresh, on top of the cached catalog
        val l = EventNames.apply(l0)
        filtered = (if (l === l0) shown else null) ?: shownOf(l); filteredFor = l; filteredVer = filterVer()
        rawChannels = l
    }

    /**
     * OnAir: the Events refresh found new channel names ([names] = item id → name): store them and show them at once.
     * If a playlist load is running it applies them itself when it finishes (setItems).
     */
    suspend fun applyEventNames(c: Context, names: Map<String, String>) {
        EventNames.put(c, names)
        if (!loadLock.tryLock()) return
        try {
            val cur = rawChannels
            if (cur.isNotEmpty()) withContext(Dispatchers.Default) { EventNames.apply(cur) }.let { if (it !== cur) setItems(it) }
        } finally {
            loadLock.unlock()
        }
    }

    /** Stand der Filter (Jugendschutz, „gestörte Titel ausblenden“): ändert er sich, wird die gezeigte Liste neu gebildet. */
    private fun filterVer(): Int = Parental.version * 10_000 + (if (DeadLinks.hide) DeadLinks.version else 0)

    private fun shownOf(l: List<Item>): List<Item> {
        var r = if (Parental.hideAdult) Parental.filter(l) else l
        if (DeadLinks.hide) r = DeadLinks.filter(r)
        return r
    }
    @Volatile private var filteredFor: List<Item>? = null
    @Volatile private var filteredVer = -1
    @Volatile private var filtered: List<Item> = emptyList()

    /** Anzahl Titel je Sprache in der ganzen Playlist (auch ausgeblendete) – für die Sprachauswahl in den Einstellungen. */
    var langCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** Datenmenge je Sprache der ganzen Playlist – für die Speicher-Schätzung ([fits]). */
    var langBytes by mutableStateOf<Map<String, Long>>(emptyMap())
        private set
    /** Mit welcher Sprachauswahl die angezeigten Titel eingelesen wurden. */
    @Volatile private var shownHidden: Set<String> = emptySet()

    /** Alle Einträge, wie sie die App zeigt: ohne ausgeblendete Sprachen und mit aktivem Jugendschutz ohne Erwachsenen-Inhalte. */
    val channels: List<Item>
        get() {
            val raw = rawChannels
            val ver = filterVer()
            if (!Parental.hideAdult && !DeadLinks.hide) return raw
            if (raw !== filteredFor || ver != filteredVer) {
                filtered = shownOf(raw); filteredFor = raw; filteredVer = ver
            }
            return filtered
        }

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** true, solange heruntergeladen oder eingelesen wird (nicht beim schnellen Lesen des Zwischenspeichers) – dann Ladebildschirm. */
    var heavy by mutableStateOf(false)
        private set
    /** Fortschritt für den Ladebildschirm: aktueller Schritt, verarbeitete Bytes und Gesamtgröße (-1 = unbekannt). */
    var phase by mutableStateOf("")
        private set
    var bytesRead by mutableLongStateOf(0L)
        private set
    var totalBytes by mutableLongStateOf(-1L)
        private set
    /** true, sobald Download und Einlesen fertig sind und nur noch gespeichert wird. */
    var downloadDone by mutableStateOf(false)
        private set
    /** Offene Rückfrage: welche Sprachen sollen geladen werden (sehr große Playlist)? Der Ladebildschirm zeigt dann die Auswahl. */
    var langAsk by mutableStateOf<LangAsk?>(null)
        private set
    /** Beginn des laufenden Downloads/Einlesens (für die Hinweise bei langer Ladezeit im Ladebildschirm), 0 = keiner. */
    var loadStarted by mutableLongStateOf(0L)
        private set
    /** Nutzer möchte während des Ladens doch Sprachen wählen: nach dem Download bzw. sofort beim Einlesen fragen. */
    var askRequested by mutableStateOf(false)
        private set
    var epgUrls: List<String> = emptyList()
    var catOrder by mutableStateOf<Map<String, Int>>(emptyMap())
    val lists = mutableStateListOf<PL>()
    var active by mutableIntStateOf(0)
    private var inited = false
    private val loadLock = Mutex()
    @Volatile private var job: Job? = null
    /** Letztes Einlesen war zu groß: beim nächsten Laden mit Oberfläche die Sprachauswahl zeigen. */
    @Volatile private var askNext = false
    @Volatile private var call: Call? = null

    // OnAir: every provider request goes through the rate-limit guard
    private val client = ProviderGuard.client(Net.withTimeouts(20, 120))
    /** OnAir: address of the playlist currently shown (to tell "already showing" from "switching account"). */
    @Volatile private var shownUrl: String? = null
    /** OnAir: category order taken from the category lists of the last player_api download (saves 3 requests). */
    @Volatile private var apiOrder: Pair<Int, Map<String, Int>>? = null

    private val XTREAM = Regex("""^(https?://[^/]+)/get\.php\?.*?username=([^&]+).*?password=([^&]+)""")
    /** User-Agent for provider requests (unchanged from IPVIO – it works with the owner's provider). */
    internal const val UA = "IPVIOStream/1.0"
    /** Eine fertig heruntergeladene, aber noch nicht eingelesene Liste wird so lange wiederverwendet. */
    private const val RAW_MAX_AGE = 12 * 3_600_000L
    /** Freier Platz, der nach dem Download mindestens bleiben soll. */
    private const val SPACE_RESERVE = 64L * 1024 * 1024

    const val MSG_CANCEL = "Laden abgebrochen. Deine Angaben bleiben gespeichert."
    const val MSG_TOO_BIG = "Die gewählten Inhalte sind zu groß für den Speicher dieses Geräts. Bitte weniger Sprachen wählen."

    /** Rückfrage bei sehr großen Playlists: welche Sprachen sollen geladen werden? */
    class LangAsk(val counts: Map<String, Int>, val bytes: Map<String, Long>, val preselect: Set<String>, val budget: Long) {
        internal val answer = CompletableDeferred<Set<String>?>()
        val titles: Int get() = counts.values.sum()
        fun titlesOf(shown: Set<String>): Int = counts.entries.sumOf { if (it.key in shown) it.value else 0 }
        fun estimate(shown: Set<String>): Long = LoadLogic.estimate(bytes, counts.keys - shown)
        fun fits(shown: Set<String>): Boolean = estimate(shown) <= budget
        /** Gewählte (gezeigte) Sprachen, null = abbrechen. */
        fun choose(shown: Set<String>?) { answer.complete(shown) }
        /** Überspringen: die ganze Playlist laden. */
        fun chooseAll() { answer.complete(counts.keys) }
    }

    /** Während des Ladens doch die Sprachauswahl zeigen (Hinweis nach 15 Minuten). */
    fun requestLanguageChoice() { askRequested = true }

    /** Wird beim Einlesen geworfen, wenn der Nutzer währenddessen die Sprachauswahl angefordert hat. */
    private class AskAgain : RuntimeException("Sprachauswahl")

    /** Sprache des Geräts (nicht der App), z. B. "de". */
    private fun systemLang(): String = try {
        android.content.res.Resources.getSystem().configuration.locales[0].language
    } catch (e: Exception) { java.util.Locale.getDefault().language }

    /** Wie viel Speicher die Playlist höchstens belegen soll. */
    fun budget(): Long = LoadLogic.budget(Runtime.getRuntime().maxMemory())

    /** Passt diese Sprachauswahl in den Speicher? (Schätzung aus dem letzten Einlesen; unbekannt = ja.) */
    fun fits(hidden: Set<String>): Boolean = LoadLogic.fits(langBytes, hidden, budget())

    /** Laufendes Laden abbrechen (zweimal Zurück im Ladebildschirm). Die Angaben bleiben gespeichert. */
    fun cancel() {
        error = MSG_CANCEL
        askRequested = false
        langAsk?.choose(null)
        runCatching { call?.cancel() }
        job?.cancel()
    }

    private fun cancelled() = job?.isActive == false

    private fun prefs(c: Context) = c.getSharedPreferences("ipvio", Context.MODE_PRIVATE)

    private fun ensure(c: Context) {
        if (inited) return
        inited = true
        EventNames.load(c)
        cleanupStale(c)
        val p = prefs(c)
        // Bevorzugt die verschlüsselte Fassung; die alte Klartext-Fassung wird beim ersten Start übernommen und ersetzt.
        val enc = p.getString("lists_enc", null)
        val json = (if (enc != null) Secrets.decrypt(enc) else null) ?: p.getString("lists", "[]")
        try {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                lists.add(PL(o.getString("name"), o.getString("url")))
            }
        } catch (e: Exception) {
        }
        val old = p.getString("url", null)
        if (lists.isEmpty() && old != null) lists.add(PL(hostOf(old), old))
        active = p.getInt("active", 0).coerceIn(0, maxOf(lists.size - 1, 0))
        if (enc == null && lists.isNotEmpty()) persist(c)   // Übernahme der alten Klartext-Liste (nach dem Setzen von "active")
    }

    /** Reste abgebrochener Downloads, die älter als zwei Tage sind, löschen (eine Rohdatei kann über 200 MB groß sein). */
    private fun cleanupStale(c: Context) {
        runCatching {
            val now = System.currentTimeMillis()
            c.filesDir.listFiles { f ->
                f.name.startsWith("pl_") && (f.name.endsWith(".m3u") || f.name.endsWith(".part") || f.name.endsWith(".info") || f.name.endsWith(".tmp"))
            }?.filter { now - it.lastModified() > 2 * 86_400_000L }?.forEach { it.delete() }
        }
    }

    private fun persist(c: Context) {
        val a = JSONArray()
        for (l in lists) a.put(JSONObject().put("name", l.name).put("url", l.url))
        val e = prefs(c).edit().putInt("active", active).remove("url")
        val token = Secrets.encrypt(a.toString())
        if (token != null) e.putString("lists_enc", token).remove("lists")
        else e.putString("lists", a.toString()).remove("lists_enc")
        e.apply()
    }

    // ------------------------------------------------------------------ gemerkter Ladeauftrag

    /** Die Playlist, die gerade hinzugefügt wird (oder beim letzten Mal nicht fertig wurde), sonst null. */
    fun pending(c: Context): LoadLogic.Pending? {
        val p = prefs(c)
        val enc = p.getString("pending_enc", null)
        return LoadLogic.Pending.decode((if (enc != null) Secrets.decrypt(enc) else null) ?: p.getString("pending", null))
    }

    private fun writePending(c: Context, v: LoadLogic.Pending?) {
        val e = prefs(c).edit()
        if (v == null) e.remove("pending_enc").remove("pending")
        else {
            val token = Secrets.encrypt(v.encode())
            if (token != null) e.putString("pending_enc", token).remove("pending") else e.putString("pending", v.encode()).remove("pending_enc")
        }
        e.commit()   // sofort auf die Platte: ein Absturz direkt danach darf nichts verlieren
    }

    fun clearPending(c: Context) = writePending(c, null)

    private fun failPending(c: Context, msg: String) {
        runCatching { pending(c)?.let { writePending(c, it.copy(state = LoadLogic.FAILED, msg = msg)) } }
    }

    fun hostOf(url: String): String = try { android.net.Uri.parse(url).host ?: "Playlist" } catch (e: Exception) { "Playlist" }

    fun savedUrl(c: Context): String? { ensure(c); return lists.getOrNull(active)?.url }

    /** Xtream-Adresse (Benutzer und Passwort URL-kodiert) oder null, wenn eine Angabe fehlt oder ungültig ist. */
    fun xtreamUrl(host: String, user: String, pass: String): String? = PlaylistLogic.xtreamUrl(host, user, pass)

    fun activate(c: Context, i: Int) { ensure(c); if (i in lists.indices) { active = i; persist(c) } }

    fun rename(c: Context, i: Int, name: String) {
        ensure(c)
        if (i in lists.indices && name.isNotBlank()) { lists[i] = PL(name.trim(), lists[i].url); persist(c) }
    }

    fun remove(c: Context, i: Int) {
        ensure(c)
        if (i !in lists.indices) return
        deleteFiles(c, lists[i].url)
        lists.removeAt(i)
        active = active.coerceIn(0, maxOf(lists.size - 1, 0))
        persist(c)
        if (lists.isEmpty()) setItems(emptyList())
    }

    /** OnAir: category order – from the player_api download just done if possible, otherwise asked separately. */
    private fun orderFor(url: String): Map<String, Int> {
        val o = apiOrder
        apiOrder = null
        if (o != null && o.first == key(url) && o.second.isNotEmpty()) return o.second
        return fetchOrder(url)
    }

    private fun fetchOrder(url: String): Map<String, Int> {
        val m = XTREAM.find(url) ?: return emptyMap()
        val base = "${m.groupValues[1]}/player_api.php?username=${m.groupValues[2]}&password=${m.groupValues[3]}&action="
        val out = LinkedHashMap<String, Int>()
        for (act in listOf("get_vod_categories", "get_series_categories", "get_live_categories")) {
            try {
                val body = client.newCall(Request.Builder().url(base + act).build()).execute().use { r ->
                    if (r.isSuccessful) r.body?.string() else null
                } ?: continue
                val a = JSONArray(body)
                for (i in 0 until a.length()) {
                    val n = a.getJSONObject(i).optString("category_name")
                    if (n.isNotBlank() && !out.containsKey(n)) out[n] = i
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    // ------------------------------------------------------------------ Dateien je Playlist

    private fun key(url: String) = url.trim().hashCode()
    private fun cacheFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".bin")
    private fun gzFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".m3u.gz")
    private fun rawFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".m3u")
    private fun partFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".part")
    private fun infoFile(c: Context, url: String) = File(c.filesDir, "pl_" + key(url) + ".info")
    private fun tmp(f: File) = File(f.path + ".tmp")

    private fun deleteFiles(c: Context, url: String) {
        val main = listOf(cacheFile(c, url), gzFile(c, url), rawFile(c, url), partFile(c, url), infoFile(c, url), Catchup.file(c, url))
        (main + main.map { tmp(it) }).forEach { runCatching { it.delete() } }
        // OnAir: the stored guide and refresh state of this account
        epgFor(url, emptyList()).forEach { Xmltv.deleteFile(c, it) }
        RefreshGate.forget(c, RefreshGate.catalogKey(url))
    }

    private fun writeCache(
        c: Context, url: String, list: List<Item>, epg: List<String>, order: Map<String, Int>,
        counts: Map<String, Int>, bytes: Map<String, Long>, hidden: Set<String>
    ) {
        try {
            val f = cacheFile(c, url)
            val t = tmp(f)
            BufferedOutputStream(FileOutputStream(t), 1 shl 16).use { PlaylistCacheIO.write(it, url, list, epg, order, counts, bytes, hidden) }
            f.delete()
            t.renameTo(f)
        } catch (e: Exception) {
        }
    }

    private fun readHeader(c: Context, url: String): PlaylistCacheIO.Cached? {
        val f = cacheFile(c, url)
        if (!f.exists()) return null
        return try {
            BufferedInputStream(FileInputStream(f), 1 shl 12).use { PlaylistCacheIO.readHeader(it, url) }
        } catch (e: Exception) {
            runCatching { f.delete() }
            null
        }
    }

    private fun readCache(c: Context, url: String, hidden: Set<String>): PlaylistCacheIO.Cached? {
        val f = cacheFile(c, url)
        if (!f.exists()) return null
        return try {
            BufferedInputStream(FileInputStream(f), 1 shl 16).use { PlaylistCacheIO.read(it, url, hidden, guard(askable = false)) }
        } catch (e: ParseStop) {
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { f.delete() }
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    private fun sig(l: List<Item>): Long {
        var s = l.size.toLong()
        for (i in l) s = s * 31 + i.id.hashCode()
        return s
    }

    /**
     * Wache für lange Lesevorgänge (alle 2.000 Einträge): Abbruch durch den Nutzer, und Stopp, bevor der Speicher ausgeht.
     * Ein kontrollierter Abbruch mit Hinweis ist besser als ein Absturz mitten im Einlesen.
     */
    private fun guard(askable: Boolean = true): () -> Unit {
        val j = job
        return {
            if (j != null && !j.isActive) throw CancellationException("abgebrochen")
            if (askable && askRequested) throw AskAgain()
            if (Mem.heapTight(0.85)) {
                System.gc()
                if (Mem.heapTight(0.85)) throw ParseStop("Speicher")
            }
        }
    }

    // ------------------------------------------------------------------ Download

    /**
     * Lädt die Liste in eine Datei, mit bis zu zwei neuen Versuchen bei Verbindungsabbruch (fortgesetzt, wenn der Server es erlaubt).
     * OnAir: the retries wait longer each time ([RefreshLogic.IN_RUN_RETRY_MS]); a throttling provider is never retried.
     */
    private suspend fun downloadWithRetry(c: Context, u: String): Pair<File, Charset> {
        val waits = RefreshLogic.IN_RUN_RETRY_MS
        var last: IOException? = null
        for (attempt in 0..waits.size) {
            try {
                return download(c, u)
            } catch (e: IOException) {
                LoginDebug.error(u, "download attempt ${attempt + 1}/${waits.size + 1}", e)
                if (cancelled()) throw CancellationException("abgebrochen")
                if (e is ProviderBusy || ProviderGuard.coolingDown() || !PlaylistLogic.retryable(e)) throw e
                last = e
                if (attempt < waits.size) { phase = "Verbindung unterbrochen – neuer Versuch …"; delay(waits[attempt]) }
            }
        }
        throw last ?: IOException("Download")
    }

    private fun charsetOf(name: String?): Charset = try { Charset.forName(name ?: "UTF-8") } catch (e: Exception) { Charsets.UTF_8 }

    /**
     * Lädt die Liste in eine Datei – nicht in den Arbeitsspeicher. Gibt die fertige Datei und ihren Zeichensatz zurück.
     * Liegt noch eine fertig geladene, aber nicht eingelesene Datei vor (Absturz/Stromausfall beim Einlesen), wird sie verwendet.
     */
    private fun download(c: Context, u: String): Pair<File, Charset> {
        val acct = XtreamApiLogic.acctOf(u) ?: return downloadM3u(c, u)
        // OnAir: Xtream login loads through player_api.php; get.php only if that fails
        try {
            return downloadApi(c, u, acct)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelled()) throw CancellationException("abgebrochen")
            LoginDebug.error(u, "player_api", e)
            // OnAir: the provider is throttling us – asking get.php right away would only make it worse
            if (e is ProviderBusy || ProviderGuard.coolingDown()) throw e
            LoginDebug.info("player_api failed, falling back to get.php")
            try {
                return downloadM3u(c, u)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                LoginDebug.error(u, "get.php fallback", e2)
                throw e   // the player_api reason is the more useful message (e.g. wrong password)
            }
        }
    }

    /** Small player_api answer as text (account check, categories); logged (masked). */
    private fun apiText(u: String): Pair<Int, String> {
        LoginDebug.request(u)
        val cl = client.newCall(Request.Builder().url(u).header("User-Agent", UA).build())
        call = cl
        try {
            cl.execute().use { r ->
                val body = r.body?.string().orEmpty()
                LoginDebug.response(u, r.code, r.message) { body }
                return r.code to body
            }
        } finally {
            call = null
        }
    }

    /**
     * OnAir: the whole account through player_api.php (live, films, series with their categories), written as M3U lines
     * into the same download file the get.php path uses – parser, cache, language filter and EPG stay unchanged.
     * The big stream lists are read piece by piece, never as one string.
     */
    private fun downloadApi(c: Context, u: String, a: ProviderLogic.Acct): Pair<File, Charset> {
        val raw = rawFile(c, u)
        val info = infoFile(c, u)
        val part = partFile(c, u)
        val pi = LoadLogic.PartInfo.decode(runCatching { info.readText() }.getOrNull())?.takeIf { it.urlHash == key(u) }
        if (pi != null && raw.exists() && raw.length() > 0 && System.currentTimeMillis() - raw.lastModified() < RAW_MAX_AGE) {
            return raw to charsetOf(pi.charset)
        }
        raw.delete(); part.delete()
        phase = "Verbindung zum Server wird aufgebaut …"
        bytesRead = 0L; totalBytes = -1L; downloadDone = false
        val (code, body) = apiText(XtreamApiLogic.apiUrl(a))
        if (code !in 200..299) throw PlaylistError(PlaylistLogic.httpError(code))
        val acc = XtreamApiLogic.account(body, System.currentTimeMillis())
        if (!acc.ok) throw PlaylistError(PlaylistLogic.httpError(401))
        acc.info?.let { AccountStore.put(c, u, it) }   // OnAir v2: max connections etc. for Multiview / debug screen
        val liveExt = XtreamApiLogic.liveExt(acc.formats)
        info.writeText(LoadLogic.PartInfo(key(u), null, null, "UTF-8").encode())
        phase = "Playlist wird heruntergeladen …"
        val counts = IntArray(3)
        val catNames = Array<Collection<String>>(3) { emptyList() }
        part.outputStream().bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("#EXTM3U\n")
            val sections = listOf(
                Triple("get_live_categories", "get_live_streams") { f: Map<String, String>, cats: Map<String, String> -> XtreamApiLogic.liveEntry(f, cats, a, liveExt) },
                Triple("get_vod_categories", "get_vod_streams") { f: Map<String, String>, cats: Map<String, String> -> XtreamApiLogic.movieEntry(f, cats, a) },
                Triple("get_series_categories", "get_series") { f: Map<String, String>, cats: Map<String, String> -> XtreamApiLogic.seriesEntry(f, cats, a) }
            )
            for ((idx, s) in sections.withIndex()) {
                val (catAction, listAction, entry) = s
                if (cancelled()) throw CancellationException("abgebrochen")
                val (cc, cb) = apiText(XtreamApiLogic.apiUrl(a, catAction))
                val cats = if (cc in 200..299) XtreamApiLogic.categories(cb) else emptyMap()
                catNames[idx] = cats.values
                val lu = XtreamApiLogic.apiUrl(a, listAction)
                LoginDebug.request(lu)
                val cl = client.newCall(Request.Builder().url(lu).header("User-Agent", UA).build())
                call = cl
                try {
                    cl.execute().use { r ->
                        LoginDebug.response(lu, r.code, r.message) { runCatching { r.peekBody(2048).string() }.getOrDefault("<unreadable>") }
                        if (!r.isSuccessful) throw PlaylistError(PlaylistLogic.httpError(r.code))
                        val rb = r.body ?: throw PlaylistError("Der Server hat keine Daten geliefert.")
                        val base = bytesRead
                        val ins = CountingStream(rb.byteStream()) { bytesRead = base + it }
                        counts[idx] = XtreamApi.forEachObject(ins.reader(Charsets.UTF_8), { if (cancelled()) throw CancellationException("abgebrochen") }) { f ->
                            entry(f, cats)?.let { w.write(it) }
                        }
                    }
                } finally {
                    call = null
                }
            }
        }
        LoginDebug.info("player_api: live=${counts[0]} movies=${counts[1]} series=${counts[2]}")
        if (counts.sum() == 0) throw PlaylistError("Keine Sender in der Playlist gefunden.")
        raw.delete()
        if (!part.renameTo(raw)) throw IOException("Datei konnte nicht umbenannt werden")
        // sections are live, films, series; the sort order lists films, series, live
        apiOrder = key(u) to XtreamApiLogic.categoryOrder(catNames[1], catNames[2], catNames[0])
        return raw to Charsets.UTF_8
    }

    private fun downloadM3u(c: Context, u: String): Pair<File, Charset> {
        val raw = rawFile(c, u)
        val info = infoFile(c, u)
        val part = partFile(c, u)
        val pi = LoadLogic.PartInfo.decode(runCatching { info.readText() }.getOrNull())?.takeIf { it.urlHash == key(u) }
        if (pi != null && raw.exists() && raw.length() > 0 && System.currentTimeMillis() - raw.lastModified() < RAW_MAX_AGE) {
            return raw to charsetOf(pi.charset)
        }
        raw.delete()
        var offset = if (pi != null && part.exists()) part.length() else 0L
        // Nach einem Stromausfall können die letzten geschriebenen Bytes fehlen oder unsauber sein: ein Stück zurückgehen
        if (offset > 0) { offset = maxOf(0L, offset - 65_536); RandomAccessFile(part, "rw").use { it.setLength(offset) } }
        val extra = if (pi != null) LoadLogic.resumeHeaders(offset, pi.etag, pi.lastModified) else emptyMap()
        if (extra.isEmpty()) offset = 0L
        val rb = Request.Builder().url(u).header("User-Agent", UA)
        extra.forEach { (k, v) -> rb.header(k, v) }
        phase = if (offset > 0) "Download wird fortgesetzt …" else "Verbindung zum Server wird aufgebaut …"
        bytesRead = offset; totalBytes = -1L; downloadDone = false
        val cl = client.newCall(rb.build())
        call = cl
        try {
            LoginDebug.request(u)
            cl.execute().use { r ->
                LoginDebug.response(u, r.code, r.message) { runCatching { r.peekBody(2048).string() }.getOrDefault("<unreadable>") }
                var append = false
                when (LoadLogic.resumeAction(r.code, r.header("Content-Range"), offset)) {
                    LoadLogic.APPEND -> append = true
                    LoadLogic.RESTART -> offset = 0L
                    else -> {
                        if (offset > 0) { part.delete(); info.delete() }   // Fortsetzen ging schief: beim nächsten Versuch von vorn
                        throw PlaylistError(if (r.code == 206 || r.code == 416) "Download konnte nicht fortgesetzt werden. Bitte erneut versuchen." else PlaylistLogic.httpError(r.code))
                    }
                }
                val body = r.body ?: throw PlaylistError("Der Server hat keine Daten geliefert.")
                val len = body.contentLength()
                val total = when {
                    append && len >= 0 -> offset + len
                    append -> LoadLogic.rangeTotal(r.header("Content-Range"))
                    else -> len
                }
                totalBytes = total
                if (len > 0 && c.filesDir.usableSpace < len + SPACE_RESERVE) throw PlaylistError(PlaylistLogic.NO_SPACE)
                val cs = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                info.writeText(LoadLogic.PartInfo(key(u), r.header("ETag"), r.header("Last-Modified"), cs.name()).encode())
                phase = if (append) "Download wird fortgesetzt …" else "Playlist wird heruntergeladen …"
                FileOutputStream(part, append).use { fo ->
                    val out = BufferedOutputStream(fo, 1 shl 16)
                    val buf = ByteArray(1 shl 16)
                    val ins = body.byteStream()
                    var n = offset
                    var last = 0L
                    while (true) {
                        val k = ins.read(buf)
                        if (k < 0) break
                        out.write(buf, 0, k)
                        n += k
                        if (n - last >= 262_144) {
                            last = n; bytesRead = n
                            if (cancelled()) throw CancellationException("abgebrochen")
                        }
                    }
                    out.flush()
                    bytesRead = n
                }
                if (total > 0 && part.length() < total) throw IOException("Download unvollständig")
            }
        } finally {
            call = null
        }
        if (part.length() == 0L) throw PlaylistError("Der Server hat keine Daten geliefert.")
        raw.delete()
        if (!part.renameTo(raw)) throw IOException("Datei konnte nicht umbenannt werden")
        return raw to charsetOf(LoadLogic.PartInfo.decode(runCatching { info.readText() }.getOrNull())?.charset)
    }

    // ------------------------------------------------------------------ Einlesen

    private class Built(val parsed: ParsedPlaylist, val hidden: Set<String>)

    /** Liest eine Datei (heruntergeladen oder komprimiert gespeichert) mit Fortschritt; [teeTo] = gleichzeitig komprimiert speichern. */
    private fun <T> readFile(src: File, gz: Boolean, cs: Charset, teeTo: File?, body: (Reader) -> T): T {
        totalBytes = src.length(); bytesRead = 0L; downloadDone = false
        val fin = CountingStream(FileInputStream(src)) { bytesRead = it }
        val ins: InputStream = if (gz) GZIPInputStream(BufferedInputStream(fin, 1 shl 16), 1 shl 16) else BufferedInputStream(fin, 1 shl 16)
        var reader: Reader = InputStreamReader(ins, if (gz) Charsets.UTF_8 else cs)
        var w: Writer? = null
        try {
            if (teeTo != null) {
                w = OutputStreamWriter(FastGzip(FileOutputStream(teeTo)), Charsets.UTF_8)
                reader = TeeReader(reader, w)
            }
            val r = body(reader)
            w?.close()
            w = null
            return r
        } catch (e: Throwable) {
            runCatching { w?.close() }
            teeTo?.delete()
            throw e
        } finally {
            runCatching { reader.close() }
        }
    }

    /**
     * Liest eine Liste ein. Ist noch keine Sprache gewählt (und nicht bewusst „alles“) und die Liste für dieses Gerät groß,
     * wird zuerst schnell gezählt und dann gefragt ([ask]) – ohne Rückfrage (Hintergrund) wird abgebrochen.
     * Fordert der Nutzer währenddessen die Sprachauswahl an, wird neu gefragt und neu eingelesen.
     */
    private suspend fun build(c: Context, src: File, gz: Boolean, cs: Charset, ask: Boolean, teeTo: File?, forceAsk: Boolean = false): Built {
        var force = forceAsk
        while (true) {
            try {
                return buildOnce(c, src, gz, cs, ask, teeTo, force)
            } catch (e: AskAgain) {
                if (!ask) throw PlaylistError(MSG_TOO_BIG)
                force = true
            }
        }
    }

    private suspend fun buildOnce(c: Context, src: File, gz: Boolean, cs: Charset, ask: Boolean, teeTo: File?, forceAsk: Boolean): Built {
        var hidden = LangFilter.hidden
        val budget = budget()
        val wantAsk = ask && (forceAsk || askRequested)
        askRequested = false
        val unchosen = hidden.isEmpty() && !LangFilter.allChosen
        if (wantAsk || unchosen) {
            val size = if (gz) src.length() * 12 else src.length()
            if (wantAsk || size > budget) {
                phase = "Playlist wird geprüft …"
                val scan = readFile(src, gz, cs, null) { r -> M3uParser.scanStream(r, guard()) }
                if (wantAsk || LoadLogic.estimate(scan.bytes, hidden) > budget) {
                    if (!ask) throw PlaylistError(MSG_TOO_BIG)
                    val now = scan.counts.keys - hidden
                    val pre = if (hidden.isNotEmpty() && now.isNotEmpty()) now else LoadLogic.systemPreselect(scan.counts, systemLang())
                    val shown = askLanguages(scan.counts, scan.bytes, budget, pre) ?: throw PlaylistError(MSG_CANCEL)
                    hidden = scan.counts.keys - shown
                    LangFilter.set(c, hidden)
                    loadStarted = System.currentTimeMillis()
                }
            }
        }
        phase = "Titel werden eingelesen …"
        val parsed = readFile(src, gz, cs, teeTo) { r -> M3uParser.parseStream(r, hidden, guard()) }
        return Built(parsed, hidden)
    }

    private suspend fun askLanguages(counts: Map<String, Int>, bytes: Map<String, Long>, budget: Long, preselect: Set<String>): Set<String>? {
        val a = LangAsk(counts, bytes, preselect, budget)
        langAsk = a
        phase = "Sprachen auswählen"
        try {
            return a.answer.await()
        } finally {
            langAsk = null
        }
    }

    private fun epgFor(u: String, fromList: List<String>): List<String> {
        val urls = ArrayList(fromList)
        XTREAM.find(u)?.let { urls.add("${it.groupValues[1]}/xmltv.php?username=${it.groupValues[2]}&password=${it.groupValues[3]}") }
        return urls
    }

    /** Die komprimierte Kopie ist fertig: übernehmen und die große Rohdatei löschen. */
    private fun commitGz(c: Context, u: String, gzTmp: File) {
        val gz = gzFile(c, u)
        if (gzTmp.exists() && gzTmp.length() > 0) { gz.delete(); gzTmp.renameTo(gz) }
        rawFile(c, u).delete(); infoFile(c, u).delete()
    }

    /**
     * OnAir: [trigger] = why the guide may be fetched (AUTO = from the device; downloaded only if nothing is stored),
     * [fromNetwork] = the playlist itself was just downloaded (then catch-up details may be asked for too).
     */
    private fun show(
        c: Context, u: String, items: List<Item>, epg: List<String>, order: Map<String, Int>,
        counts: Map<String, Int>, bytes: Map<String, Long>, hidden: Set<String>,
        trigger: RefreshLogic.Trigger = RefreshLogic.Trigger.AUTO, fromNetwork: Boolean = false
    ) {
        epgUrls = epg
        catOrder = order
        langCounts = counts
        langBytes = bytes
        shownHidden = hidden
        shownUrl = u
        setItems(items, shownOf(items))
        Catchup.start(c, u, items, allowNetwork = fromNetwork)
        Xmltv.start(c, epg, items, trigger)
    }

    /** OnAir: message when a refresh is held back (provider asked us to wait, or it was just asked). */
    private fun waitMsg(c: Context, key: String, trigger: RefreshLogic.Trigger, hasCache: Boolean): String {
        val at = RefreshGate.nextAllowedAt(c, key, trigger, hasCache) ?: System.currentTimeMillis()
        return "Der Anbieter wurde gerade erst abgefragt. Neuer Versuch ab ${hhmm(at / 1000)} möglich."   // translated when shown
    }

    /** OnAir: error text; a throttled provider gets its own message. */
    private fun errorText(e: Throwable): String = when (e) {
        is ProviderBusy -> PlaylistLogic.httpError(429)
        is ParseStop -> MSG_TOO_BIG
        else -> PlaylistLogic.friendlyError(e)
    }

    private enum class Local { OK, NONE, FAILED }

    /**
     * Ohne Netz laden: aus dem Zwischenspeicher, wenn er zur aktuellen Sprachauswahl passt, sonst aus der komprimiert
     * gespeicherten ganzen Liste neu einlesen ([allowGz]).
     */
    private suspend fun fromLocal(c: Context, u: String, allowGz: Boolean, ask: Boolean): Local {
        val hidden = LangFilter.hidden
        val head = readHeader(c, u)
        if (head != null) {
            // Alte Fassung (ganze Liste ohne Sprach-Zahlen) bei sehr großer Datei und ohne Sprachauswahl: nicht alles in den Speicher holen
            val legacyTooBig = head.hidden == null && hidden.isEmpty() && cacheFile(c, u).length() > budget()
            if (!legacyTooBig && (head.hidden == null || head.hidden == hidden)) {
                val cached = readCache(c, u, hidden)
                if (cached != null && cached.counts.isNotEmpty()) {
                    show(c, u, cached.items, cached.epg, cached.order, cached.counts, cached.bytes, hidden)
                    return Local.OK
                }
            }
        }
        val gz = gzFile(c, u)
        if (!allowGz || !gz.exists()) return Local.NONE
        heavy = true
        if (loadStarted == 0L) loadStarted = System.currentTimeMillis()
        return try {
            val b = build(c, gz, true, Charsets.UTF_8, ask, null)
            val p = b.parsed
            if (p.counts.isEmpty()) return Local.NONE
            val epg = head?.epg?.takeIf { it.isNotEmpty() } ?: epgFor(u, p.epgUrls)
            val order = head?.order ?: emptyMap()
            phase = "Playlist wird gespeichert …"; downloadDone = true
            writeCache(c, u, p.items, epg, order, p.counts, p.bytes, b.hidden)
            Catchup.saveParsed(c, u, p.catchup)
            show(c, u, p.items, epg, order, p.counts, p.bytes, b.hidden)
            Local.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (cancelled()) throw CancellationException("abgebrochen")
            error = errorText(e)
            Local.FAILED
        } catch (e: OutOfMemoryError) {
            error = MSG_TOO_BIG
            Local.FAILED
        }
    }

    /**
     * Lädt eine Playlist. [save] = neu hinzugefügt (wird erst bei Erfolg in die Liste übernommen, aber vorher gemerkt),
     * [ask] = bei sehr großen Listen nach den Sprachen fragen dürfen (nicht im Hintergrund), [resumed] = automatisch fortgesetzt.
     * OnAir: [trigger] decides whether the provider may be asked ([RefreshLogic]): MANUAL for buttons and login,
     * SCHEDULED for the background job, AUTO for app start and switching (cache only, unless nothing is stored).
     */
    suspend fun load(
        c: Context, url: String, save: Boolean, skipNetworkWhenCached: Boolean = false,
        ask: Boolean = true, resumed: Boolean = false, trigger: RefreshLogic.Trigger = RefreshLogic.Trigger.MANUAL
    ): Boolean {
        if (!loadLock.tryLock()) return rawChannels.isNotEmpty()
        try {
            return loadInner(c, url, save, skipNetworkWhenCached, ask, resumed, trigger)
        } finally {
            loading = false   // auch bei Abbruch: sonst bliebe der Ladezustand (und die Bildschirm-Wachhaltung) hängen
            heavy = false
            langAsk = null
            loadStarted = 0L
            askRequested = false
            job = null
            loadLock.unlock()
        }
    }

    private suspend fun loadInner(
        c: Context, url: String, save: Boolean, skipNetworkWhenCached: Boolean, ask: Boolean, resumed: Boolean,
        trigger: RefreshLogic.Trigger
    ): Boolean {
        ensure(c)
        val u = PlaylistLogic.normalizeUrl(url) ?: run { error = "Bitte eine gültige Adresse eingeben (beginnt mit http:// oder https://)."; return false }
        job = currentCoroutineContext()[Job]
        loading = true; error = null; heavy = false
        phase = "Gespeicherte Playlist wird gelesen …"; bytesRead = 0L; totalBytes = -1L; downloadDone = false
        var forceAsk = false
        if (save) {
            val prev = pending(c)
            // Letzter Versuch abgestürzt oder zu groß, jetzt von Hand neu gestartet: bei großer Liste die Sprachen noch einmal zeigen
            forceAsk = !resumed && prev != null && prev.url == u && (prev.interrupted || prev.msg == MSG_TOO_BIG)
            writePending(c, LoadLogic.started(prev, u, resumed))
        }
        if (askNext && ask) forceAsk = true
        // OnAir: this playlist is already on screen and only the stored copy is wanted – nothing to do
        if (skipNetworkWhenCached && rawChannels.isNotEmpty() && shownUrl == u) {
            if (save) finishSave(c, u)
            return true
        }
        var usedCache = false
        if (rawChannels.isEmpty() || shownUrl != u) {
            val r = withContext(Dispatchers.IO) { fromLocal(c, u, allowGz = skipNetworkWhenCached, ask = ask) }
            usedCache = r == Local.OK
        }
        if (usedCache && skipNetworkWhenCached) {
            if (save) finishSave(c, u)
            return true
        }
        // OnAir: rate-limit gate – only ask the provider when the trigger allows it (see RefreshLogic)
        val gateKey = RefreshGate.catalogKey(u)
        val hasCache = usedCache || (rawChannels.isNotEmpty() && shownUrl == u)
        if (!RefreshGate.allowed(c, gateKey, trigger, hasCache)) {
            val msg = waitMsg(c, gateKey, trigger, hasCache)
            if (trigger == RefreshLogic.Trigger.MANUAL || !hasCache) error = msg
            if (save) failPending(c, msg)
            return hasCache
        }
        error = null
        val before = sig(rawChannels)
        heavy = true
        if (loadStarted == 0L) loadStarted = System.currentTimeMillis()
        var fetched = false
        val ok = withContext(Dispatchers.IO) {
            try {
                val (src, cs) = downloadWithRetry(c, u)
                fetched = true
                RefreshGate.succeeded(c, gateKey)
                EventNames.clear(c)   // OnAir: the full catalog brings the current event names itself
                val gzTmp = tmp(gzFile(c, u))
                val b = build(c, src, false, cs, ask, gzTmp, forceAsk)
                val parsed = b.parsed
                if (parsed.counts.isEmpty()) throw PlaylistError("Keine Sender in der Playlist gefunden.")
                downloadDone = true
                val urls = epgFor(u, parsed.epgUrls)
                phase = "Kategorien werden sortiert …"
                val order = orderFor(u)
                phase = "Playlist wird gespeichert …"
                writeCache(c, u, parsed.items, urls, order, parsed.counts, parsed.bytes, b.hidden)
                Catchup.saveParsed(c, u, parsed.catchup)
                commitGz(c, u, gzTmp)
                val list = parsed.items
                if (sig(list) != before || !usedCache || b.hidden != shownHidden) show(c, u, list, urls, order, parsed.counts, parsed.bytes, b.hidden, trigger, true)
                else {
                    epgUrls = urls; catOrder = order; langCounts = parsed.counts; langBytes = parsed.bytes; shownUrl = u
                    Catchup.start(c, u, list, allowNetwork = true)
                    Xmltv.start(c, urls, list, trigger)
                }
                General.markRefreshed(c)
                if (save) finishSave(c, u)
                askNext = false
                true
            } catch (e: CancellationException) {
                if (!fetched) RefreshGate.attempted(c, gateKey)
                if (save) failPending(c, MSG_CANCEL)
                throw e
            } catch (e: Exception) {
                LoginDebug.error(u, "load", e)
                if (cancelled()) { if (!fetched) RefreshGate.attempted(c, gateKey); if (save) failPending(c, MSG_CANCEL); throw CancellationException("abgebrochen") }
                if (!fetched) RefreshGate.failed(c, gateKey)
                val msg = errorText(e)
                if (e is ParseStop) askNext = true
                if (rawChannels.isEmpty() || save) error = msg
                if (save) failPending(c, msg)
                usedCache
            } catch (e: OutOfMemoryError) {
                LoginDebug.error(u, "load", e)
                askNext = true
                if (save) failPending(c, MSG_TOO_BIG)
                if (rawChannels.isEmpty() || save) error = MSG_TOO_BIG
                usedCache
            }
        }
        loading = false
        return ok
    }

    /** Neu hinzugefügte Playlist erfolgreich geladen: in die Liste übernehmen und den gemerkten Auftrag löschen. */
    private fun finishSave(c: Context, u: String) {
        val idx = lists.indexOfFirst { it.url == u }
        if (idx >= 0) active = idx else { lists.add(PL(hostOf(u), u)); active = lists.size - 1 }
        persist(c)
        clearPending(c)
    }

    /**
     * Sprachfilter geändert: ohne Netz neu einlesen (aus der gespeicherten ganzen Liste, sonst aus einem alten Zwischenspeicher).
     * Gibt es beides nicht, wird normal geladen. Passt die neue Auswahl nicht in den Speicher, bleibt die alte aktiv.
     * Rückgabe false = gerade beschäftigt (später erneut versuchen), sonst true.
     */
    suspend fun reapplyLanguages(c: Context): Boolean {
        val u = savedUrl(c) ?: return true
        if (!loadLock.tryLock()) return false
        try {
            job = currentCoroutineContext()[Job]
            loading = true; error = null
            phase = "Sprachauswahl wird angewendet …"; bytesRead = 0L; totalBytes = -1L; downloadDone = false
            val prev = shownHidden
            // Wenig Speicher frei: alte Liste vorher freigeben, sonst lägen alte und neue Liste gleichzeitig im Speicher
            if (Mem.heapTight(0.45)) setItems(emptyList())
            val r = withContext(Dispatchers.IO) { fromLocal(c, u, allowGz = true, ask = false) }
            when (r) {
                Local.OK -> {}
                Local.NONE -> return loadInner(c, u, false, false, false, false, RefreshLogic.Trigger.AUTO).let { true }
                Local.FAILED -> {
                    // Neue Auswahl passt nicht: alte Auswahl wiederherstellen (und, falls schon freigegeben, wieder einlesen)
                    val msg = error
                    LangFilter.set(c, prev)
                    if (rawChannels.isEmpty()) withContext(Dispatchers.IO) { fromLocal(c, u, allowGz = true, ask = false) }
                    error = msg
                }
            }
            return true
        } finally {
            loading = false
            heavy = false
            loadStarted = 0L
            askRequested = false
            job = null
            loadLock.unlock()
        }
    }

    /**
     * Hintergrund-Aktualisierung bei geschlossener App: herunterladen, mit der gespeicherten Sprachauswahl einlesen und
     * den Zwischenspeicher schreiben. Es wird nicht gefragt; ist die Liste ohne Sprachauswahl zu groß, bleibt der alte Stand.
     */
    suspend fun refreshCacheOnly(c: Context, url: String): Boolean {
        ensure(c)
        val u = PlaylistLogic.normalizeUrl(url) ?: return false
        // OnAir: rate-limit gate (12 h since the last success, back-off after failures)
        val gateKey = RefreshGate.catalogKey(u)
        if (!RefreshGate.allowed(c, gateKey, RefreshLogic.Trigger.SCHEDULED, cacheFile(c, u).exists())) return false
        if (!loadLock.tryLock()) return false
        try {
            job = currentCoroutineContext()[Job]
            var fetched = false
            return withContext(Dispatchers.IO) {
                try {
                    val (src, cs) = downloadWithRetry(c, u)
                    fetched = true
                    RefreshGate.succeeded(c, gateKey)
                    EventNames.clear(c)   // OnAir: the full catalog brings the current event names itself
                    val gzTmp = tmp(gzFile(c, u))
                    val b = build(c, src, false, cs, false, gzTmp)
                    if (b.parsed.counts.isEmpty()) return@withContext false
                    val urls = epgFor(u, b.parsed.epgUrls)
                    writeCache(c, u, b.parsed.items, urls, orderFor(u), b.parsed.counts, b.parsed.bytes, b.hidden)
                    Catchup.saveParsed(c, u, b.parsed.catchup)
                    commitGz(c, u, gzTmp)
                    General.markRefreshed(c)
                    // OnAir: the guide is stored on the device too, so the next app start needs no download
                    Xmltv.refreshFiles(c, urls, RefreshLogic.Trigger.SCHEDULED)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LoginDebug.error(u, "background refresh", e)
                    if (!fetched) RefreshGate.failed(c, gateKey)
                    dropRaw(c, u)
                    false
                } catch (e: OutOfMemoryError) {
                    dropRaw(c, u)
                    false
                }
            }
        } finally {
            job = null
            loadLock.unlock()
        }
    }

    /** Im Hintergrund nicht verwendbare, fertig geladene Rohdatei löschen (Platz); eine Teildatei bleibt zum Fortsetzen. */
    private fun dropRaw(c: Context, u: String) {
        if (rawFile(c, u).exists()) { rawFile(c, u).delete(); infoFile(c, u).delete() }
    }

    /** true, wenn die Oberfläche gerade eine Playlist anzeigt (App läuft im selben Prozess). */
    val hasData: Boolean get() = rawChannels.isNotEmpty()

    fun clear(c: Context) {
        ensure(c)
        lists.forEach { deleteFiles(c, it.url) }
        Xmltv.deleteAll(c)
        lists.clear(); persist(c); setItems(emptyList()); langCounts = emptyMap(); langBytes = emptyMap(); shownUrl = null
    }
}

/** Zählt die gelesenen Bytes (für den Fortschrittsbalken), meldet höchstens alle 128 KB. */
private class CountingStream(inp: InputStream, private val onCount: (Long) -> Unit) : FilterInputStream(inp) {
    private var n = 0L
    private var last = 0L
    override fun read(): Int { val b = super.read(); if (b >= 0) add(1); return b }
    override fun read(b: ByteArray, off: Int, len: Int): Int { val r = super.read(b, off, len); if (r > 0) add(r.toLong()); return r }
    private fun add(k: Long) { n += k; if (n - last >= 131072) { last = n; onCount(n) } }
    override fun close() { onCount(n); super.close() }
}

/** Gibt alles Gelesene zusätzlich an [copy] weiter (Einlesen und komprimiert Speichern in einem Durchgang). */
private class TeeReader(private val inner: Reader, private val copy: Writer) : Reader() {
    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        val n = inner.read(cbuf, off, len)
        if (n > 0) copy.write(cbuf, off, n)
        return n
    }
    override fun close() = inner.close()
}

/** gzip mit schnellster Stufe: 230 MB → ~20 MB in gut einer Sekunde (Stufe 6 wäre kaum kleiner, aber doppelt so langsam). */
private class FastGzip(out: OutputStream) : GZIPOutputStream(BufferedOutputStream(out, 1 shl 16), 1 shl 16) {
    init { def.setLevel(Deflater.BEST_SPEED) }
}

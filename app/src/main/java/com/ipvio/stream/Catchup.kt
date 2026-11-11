package com.ipvio.stream

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/**
 * Archiv (Catchup) der aktiven Playlist: welche Sender vergangene Sendungen anbieten und wie weit zurück.
 * Quellen: Angaben in der M3U (catchup, catchup-days, …) und bei Xtream-Servern die API (tv_archive), einmal am Tag abgefragt.
 * Gespeichert je Playlist in pl_*.cu, damit der Start aus dem Zwischenspeicher ohne Netz auskommt. Logik in [CatchupLogic].
 */
object Catchup {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = ProviderGuard.client(Net.withTimeouts(15, 90))
    private const val REFRESH_MS = 24 * 3600_000L

    @Volatile private var map: Map<String, CatchupInfo> = emptyMap()
    @Volatile var zone: TimeZone = TimeZone.getDefault()
        private set
    private var job: Job? = null

    /** Ändert sich, sobald neue Archiv-Angaben da sind (Oberfläche zeichnet neu). */
    var version by mutableIntStateOf(0)
        private set
    /** Anzahl Sender mit Archiv (für die Einstellungen). */
    var count by mutableIntStateOf(0)
        private set

    private class Saved(val m3u: Map<String, CatchupInfo>, val xt: Map<String, Int>, val tz: String?, val xtAt: Long)

    fun of(item: Item?): CatchupInfo? = if (item != null && item.kind == Kind.LIVE) map[item.id] else null

    fun has(item: Item?): Boolean = of(item) != null

    /** Längstes Archiv unter den Sendern (für den TV-Guide: so weit lässt er zurückblättern). */
    fun maxDays(list: List<Item>): Int {
        if (map.isEmpty()) return 0
        var d = 0
        for (it in list) { val i = map[it.id] ?: continue; if (i.days > d) d = i.days }
        return d
    }

    fun canPlay(item: Item?, e: EpgEntry, now: Long = System.currentTimeMillis() / 1000): Boolean =
        CatchupLogic.canPlay(of(item), e.start, e.end, now)

    /** Adresse der Sendung im Archiv, null = nicht möglich. */
    fun urlFor(item: Item, e: EpgEntry, now: Long = System.currentTimeMillis() / 1000): String? {
        val i = of(item) ?: return null
        if (!CatchupLogic.canPlay(i, e.start, e.end, now)) return null
        return CatchupLogic.url(i, item.url, e.start, e.end, now, zone)
    }

    fun file(c: Context, url: String) = File(c.filesDir, "pl_" + url.trim().hashCode() + ".cu")

    private fun read(c: Context, url: String): Saved {
        val f = file(c, url)
        if (!f.exists()) return Saved(emptyMap(), emptyMap(), null, 0L)
        return try {
            var tz: String? = null
            var at = 0L
            val m = StringBuilder()
            val xt = HashMap<String, Int>()
            var sec = ""
            f.forEachLine { line ->
                when {
                    line.startsWith("#tz\t") -> tz = line.substring(4).ifBlank { null }
                    line.startsWith("#xt\t") -> at = line.substring(4).toLongOrNull() ?: 0L
                    line == "[m]" || line == "[x]" -> sec = line
                    sec == "[m]" -> m.append(line).append('\n')
                    sec == "[x]" -> { val p = line.split('\t'); if (p.size == 2) p[1].toIntOrNull()?.let { xt[p[0]] = it } }
                }
            }
            Saved(CatchupLogic.decode(m.toString()), xt, tz, at)
        } catch (e: Exception) {
            Saved(emptyMap(), emptyMap(), null, 0L)
        }
    }

    private fun write(c: Context, url: String, s: Saved) {
        try {
            val f = file(c, url)
            val t = File(f.path + ".tmp")
            t.bufferedWriter().use { w ->
                w.write("#cu1\n")
                if (s.tz != null) w.write("#tz\t" + s.tz + "\n")
                w.write("#xt\t" + s.xtAt + "\n")
                w.write("[m]\n"); w.write(CatchupLogic.encode(s.m3u))
                w.write("[x]\n"); for ((k, v) in s.xt) w.write("$k\t$v\n")
            }
            f.delete(); t.renameTo(f)
        } catch (e: Exception) {
        }
    }

    /** Nach dem Einlesen der Playlist: Angaben aus der M3U merken (Xtream-Stand bleibt erhalten). */
    fun saveParsed(c: Context, url: String, m3u: Map<String, CatchupInfo>) {
        val old = read(c, url)
        write(c, url, Saved(m3u, old.xt, old.tz, old.xtAt))
    }

    fun delete(c: Context, url: String) { runCatching { file(c, url).delete() } }

    /**
     * Die Playlist wird angezeigt: Archiv-Angaben übernehmen; bei Xtream-Servern höchstens einmal am Tag nachfragen.
     * OnAir: [allowNetwork] = only when the playlist itself was just refreshed – never on app start from the stored copy
     * (player_api logins already carry the archive days in the playlist).
     */
    fun start(c: Context, url: String, items: List<Item>, allowNetwork: Boolean = false) {
        val app = c.applicationContext
        job?.cancel()
        // Gespeicherte Angaben sofort (kleine Datei; der TV-Guide braucht sie gleich beim Einlesen des Programms)
        val s = read(app, url)
        val live = HashMap<String, String>()
        for (it in items) if (it.kind == Kind.LIVE) live[it.id] = it.url
        apply(CatchupLogic.merge(live, s.xt, s.m3u), s.tz)
        if (!allowNetwork) return
        val x = live.values.firstNotNullOfOrNull { CatchupLogic.xtreamLive(it) } ?: return
        if (System.currentTimeMillis() - s.xtAt < REFRESH_MS) return
        job = scope.launch {
            val api = "${x.host}/player_api.php?username=${x.user}&password=${x.pass}"
            val tz = fetchZone(api) ?: s.tz
            val xt = fetchArchive("$api&action=get_live_streams")
            if (xt == null) {
                // Kein Xtream-Server oder gerade nicht erreichbar: in 6 Stunden noch einmal fragen, nicht bei jedem Start
                write(app, url, Saved(s.m3u, s.xt, s.tz, System.currentTimeMillis() - REFRESH_MS + 6 * 3600_000L))
                return@launch
            }
            val ns = Saved(s.m3u, xt, tz, System.currentTimeMillis())
            write(app, url, ns)
            apply(CatchupLogic.merge(live, xt, s.m3u), tz)
        }
    }

    private fun apply(m: Map<String, CatchupInfo>, tz: String?) {
        map = m
        zone = tz?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        count = m.size
        version++
    }

    /** Zeitzone des Xtream-Servers (die Startzeit im Archiv-Link gilt in dieser Zone). */
    private fun fetchZone(api: String): String? = try {
        client.newCall(Request.Builder().url(api).build()).execute().use { r ->
            if (!r.isSuccessful) null
            else JSONObject(r.body?.string() ?: "").optJSONObject("server_info")?.optString("timezone")?.takeIf { it.contains('/') || it == "UTC" }
        }
    } catch (e: Exception) {
        null
    }

    /** Liste aller Live-Sender des Servers (oft mehrere MB) Stück für Stück lesen: Sender-Nummer → Archiv-Tage. */
    private fun fetchArchive(url: String): Map<String, Int>? {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val out = HashMap<String, Int>()
                JsonReader(r.body!!.charStream()).use { j ->
                    if (j.peek() != JsonToken.BEGIN_ARRAY) return null
                    j.beginArray()
                    while (j.hasNext()) {
                        if (j.peek() != JsonToken.BEGIN_OBJECT) { j.skipValue(); continue }
                        j.beginObject()
                        var id: String? = null
                        var arch = 0
                        var days = 0
                        while (j.hasNext()) {
                            when (j.nextName()) {
                                "stream_id" -> id = str(j)
                                "tv_archive" -> arch = CatchupLogic.apiInt(str(j))
                                "tv_archive_duration" -> days = CatchupLogic.apiInt(str(j))
                                else -> j.skipValue()
                            }
                        }
                        j.endObject()
                        if (id != null && arch > 0) out[id] = if (days > 0) days else CatchupLogic.DEFAULT_DAYS
                    }
                    j.endArray()
                }
                return out
            }
        } catch (e: Exception) {
            return null
        }
    }

    private fun str(j: JsonReader): String? = when (j.peek()) {
        JsonToken.NULL -> { j.nextNull(); null }
        JsonToken.STRING, JsonToken.NUMBER -> j.nextString()
        JsonToken.BOOLEAN -> if (j.nextBoolean()) "1" else "0"
        else -> { j.skipValue(); null }
    }
}

/** Laufende Archiv-Sendung im Player: Sender und Sendung. */
data class ArchiveRef(val chId: String, val chTitle: String, val prog: EpgEntry)

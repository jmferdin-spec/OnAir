package com.ipvio.stream

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Film and series information straight from the provider (Xtream player_api.php): description, cover, backdrop, rating,
 * genres, running time and trailer. Works without any API key. Only used when TMDB is not set up or does not know the title.
 */
object ProviderInfo {
    private val client = ProviderGuard.client(Net.base)   // OnAir: provider rate-limit guard
    private const val DAY = 24L * 3600 * 1000

    private fun get(url: String): okhttp3.Response? = try {
        client.newCall(Request.Builder().url(url).build()).execute()
    } catch (e: Exception) {
        null
    }

    private fun toMeta(i: ProviderLogic.Info, tv: Boolean, itemYear: Int?): Meta = Meta(
        tmdbId = 0, isTv = tv, poster = i.cover, backdrop = i.backdrop, overview = i.plot, rating = i.rating,
        year = i.year ?: itemYear?.toString(), genres = i.genres, runtime = i.runtime, trailerKey = i.trailer,
        extended = true, uiLang = Lang.code(), checked = true
    )

    /** Infos zu einem Titel vom Anbieter; null = nicht erreichbar oder nichts vorhanden. */
    suspend fun fetch(ctx: Context, i: Item): Meta? = withContext(Dispatchers.IO) {
        try {
            when (i.kind) {
                Kind.MOVIE -> movie(i)
                Kind.SERIES -> series(ctx, i)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun movie(i: Item): Meta? {
        val (acct, id) = ProviderLogic.movieRef(i.url) ?: return null
        val body = get(ProviderLogic.apiUrl(acct, "get_vod_info", "&vod_id=$id"))?.use { r ->
            if (r.isSuccessful) r.body?.string() else null
        } ?: return null
        val info = JSONObject(body).optJSONObject("info") ?: return null
        val f = HashMap<String, String>()
        for (k in info.keys()) {
            val v = info.opt(k)
            if (v is String || v is Number) f[k] = v.toString()
        }
        val back = info.optJSONArray("backdrop_path")?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.startsWith("http") } }
        val r = ProviderLogic.infoOf(f, back, listOf("movie_image", "cover_big", "cover"))
        return if (r.isEmpty()) null else toMeta(r, false, i.year)
    }

    private fun series(ctx: Context, i: Item): Meta? {
        val acct = ProviderLogic.seriesAcct(i) ?: return null
        val file = File(ctx.cacheDir, "prov_series_" + (acct.host + acct.user).hashCode() + ".json")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > DAY) {
            val tmp = File(file.path + ".tmp")
            val ok = get(ProviderLogic.apiUrl(acct, "get_series"))?.use { r ->
                val b = r.body
                if (!r.isSuccessful || b == null) false
                else { tmp.outputStream().use { o -> b.byteStream().copyTo(o) }; true }
            } ?: false
            if (ok) { file.delete(); tmp.renameTo(file) } else { tmp.delete(); if (!file.exists()) return null }
        }
        val info = findSeries(file, i.title) ?: return null
        return if (info.isEmpty()) null else toMeta(info, true, i.year)
    }

    /** Liest die (große) Serienliste Eintrag für Eintrag, ohne sie ganz in den Speicher zu laden. */
    private fun findSeries(file: File, title: String): ProviderLogic.Info? {
        JsonReader(file.bufferedReader()).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                val f = HashMap<String, String>()
                var back: String? = null
                r.beginObject()
                while (r.hasNext()) {
                    val n = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> f[n] = r.nextString()
                        JsonToken.BEGIN_ARRAY -> {
                            if (n == "backdrop_path") {
                                r.beginArray()
                                while (r.hasNext()) {
                                    if (r.peek() == JsonToken.STRING) { val v = r.nextString(); if (back == null && v.startsWith("http")) back = v }
                                    else r.skipValue()
                                }
                                r.endArray()
                            } else r.skipValue()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (ProviderLogic.sameName(title, f["name"])) return ProviderLogic.infoOf(f, back, listOf("cover", "cover_big"))
            }
        }
        return null
    }
}

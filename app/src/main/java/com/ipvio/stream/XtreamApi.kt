package com.ipvio.stream

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.Reader

/** OnAir: Android glue for [XtreamApiLogic] – streaming JSON reading (lists can be tens of MB on a 3 GB box). */
object XtreamApi {
    /**
     * Calls [onObj] for every object of a top-level JSON array, with its plain (string/number/boolean) fields only.
     * Anything else at the top (an empty object, an error object) counts as an empty list. Returns the number of objects.
     */
    fun forEachObject(reader: Reader, check: () -> Unit, onObj: (Map<String, String>) -> Unit): Int {
        var n = 0
        JsonReader(reader).use { r ->
            r.isLenient = true
            if (r.peek() != JsonToken.BEGIN_ARRAY) { r.skipValue(); return 0 }
            r.beginArray()
            val f = HashMap<String, String>(32)
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                f.clear()
                r.beginObject()
                while (r.hasNext()) {
                    val k = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> f[k] = r.nextString()
                        JsonToken.BOOLEAN -> f[k] = if (r.nextBoolean()) "1" else "0"
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                onObj(f)
                if (++n % 2000 == 0) check()
            }
            r.endArray()
        }
        return n
    }
}

/** OnAir: episodes of a show loaded through player_api (get_series_info), fetched when the show is opened. */
object SeriesInfo {
    private val cache = LruCache<String, Item>(40)
    private val client = ProviderGuard.client(Net.base)

    fun lazy(item: Item): Boolean = item.kind == Kind.SERIES && item.episodes.isEmpty() && XtreamApiLogic.refsOf(item.tvgId).isNotEmpty()

    /** The show with its episodes if they were already fetched, else unchanged. */
    fun cached(item: Item): Item = if (lazy(item)) cache[item.id] ?: item else item

    /** The show with its episodes (fetched once, then from memory); unchanged if nothing could be fetched. */
    suspend fun load(item: Item): Item {
        if (!lazy(item)) return item
        cache[item.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            val parts = XtreamApiLogic.refsOf(item.tvgId).map { r ->
                val url = XtreamApiLogic.apiUrl(r.acct, "get_series_info", "&series_id=${r.seriesId}")
                val eps = try {
                    LoginDebug.request(url)
                    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        LoginDebug.response(url, resp.code, resp.message) { body }
                        if (resp.isSuccessful) XtreamApiLogic.episodesOf(body) else emptyList()
                    }
                } catch (e: Exception) {
                    LoginDebug.error(url, "get_series_info", e)
                    emptyList()
                }
                r to eps
            }
            val eps = XtreamApiLogic.merge(parts)
            if (eps.isEmpty()) item
            else item.copy(episodes = EpisodeTable.of(eps)).also { cache[item.id] = it }
        }
    }
}

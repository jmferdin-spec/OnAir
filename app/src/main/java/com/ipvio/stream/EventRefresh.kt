package com.ipvio.stream

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * OnAir: new names of PPV / event channels from the lightweight Events refresh, applied to the playlist on top of the
 * cached catalog (PlaylistStore.setItems). Stored on the device; a full catalog reload clears it (it brings the names).
 * Keys are item ids (hashes of the stream address), never addresses or credentials.
 */
object EventNames {
    private const val PREFS = "onair_event_names"
    @Volatile var names: Map<String, String> = emptyMap()
        private set
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return
        names = EventRefreshLogic.decode(c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("names", null))
        loaded = true
    }

    fun apply(items: List<Item>): List<Item> = EventRefreshLogic.apply(items, names)

    fun put(c: Context, new: Map<String, String>) {
        load(c)
        names = EventRefreshLogic.merge(names, new)
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("names", EventRefreshLogic.encode(names)).apply()
    }

    fun clear(c: Context) {
        names = emptyMap(); loaded = true
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("names").apply()
    }
}

/**
 * OnAir: lightweight Events refresh – player_api `get_live_streams&category_id=…` for the PPV / event categories only,
 * when the home or Events screen opens, at most every 30 minutes ([RefreshLogic.Trigger.EVENTS], with the normal
 * backoff and the [ProviderGuard] cooldown). Never a full catalog reload. Rules in [EventRefreshLogic].
 */
object EventRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = ProviderGuard.client(Net.withTimeouts(15, 40))
    @Volatile private var running = false
    /** Last result for the debug screen / log: "renamed 3 of 80 (2 categories)" or the error. */
    @Volatile var lastResult: String = "not run yet"
        private set

    fun gateKey(playlistUrl: String) = "ev_" + playlistUrl.trim().hashCode()

    /** Starts a refresh in the background if the gate allows it; returns at once. */
    fun maybe(c: Context, live: List<Item>) {
        if (running || live.isEmpty() || PlaylistStore.loading || AppNav.playing) return
        val app = c.applicationContext
        val url = PlaylistStore.savedUrl(app) ?: return
        val acct = XtreamApiLogic.acctOf(url) ?: return   // M3U playlists: nothing to ask
        val chans = OnNow.indexFor(live).chans
        val cats = EventRefreshLogic.categories(chans)
        if (cats.isEmpty()) return
        val key = gateKey(url)
        if (!RefreshGate.allowed(app, key, RefreshLogic.Trigger.EVENTS, hasCache = true)) return
        running = true
        scope.launch {
            try {
                val fresh = HashMap<String, String>()
                for (cat in cats) {
                    val u = XtreamApiLogic.apiUrl(acct, "get_live_streams", "&category_id=$cat")
                    LoginDebug.request(u)
                    client.newCall(Request.Builder().url(u).header("User-Agent", PlaylistStore.UA).build()).execute().use { r ->
                        LoginDebug.response(u, r.code, r.message, runCatching { r.peekBody(1024).string() }.getOrDefault(""))
                        if (!r.isSuccessful) throw PlaylistError(PlaylistLogic.httpError(r.code))
                        val body = r.body ?: throw PlaylistError("Der Server hat keine Daten geliefert.")
                        XtreamApi.forEachObject(body.charStream(), {}) { f ->
                            val id = f["stream_id"]?.trim()
                            val n = f["name"]
                            if (!id.isNullOrEmpty() && !n.isNullOrBlank()) fresh[id] = n
                        }
                    }
                }
                RefreshGate.succeeded(app, key)
                val known = live.filter { it.kind == Kind.LIVE }.map { EventRefreshLogic.Known(it.id, it.url, it.title) }
                val renames = EventRefreshLogic.renames(known, fresh) { TitleText.decode(XtreamApiLogic.name(it)) }
                lastResult = "renamed ${renames.size} of ${fresh.size} (${cats.size} categories)"
                LoginDebug.info("events refresh: $lastResult")
                if (renames.isNotEmpty()) PlaylistStore.applyEventNames(app, renames)
            } catch (e: Exception) {
                RefreshGate.failed(app, key)
                lastResult = "failed: ${e.javaClass.simpleName}"
                LoginDebug.error("events", "events refresh", e)
            } finally {
                running = false
            }
        }
    }
}

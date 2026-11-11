package com.ipvio.stream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import coil.Coil
import coil.ImageLoader
import coil.memory.MemoryCache

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Einstellungen früh laden, damit Farben und Größe schon im ersten Bild stimmen.
        Profiles.init(this)
        val pick = Profiles.needsPicker(this)
        if (!pick) AppSettings.init(this)
        General.init(this)
        Config.init(this)
        // Frischer Start (nicht nur gedrehter/neu erstellter Bildschirm): "Zuletzt Geschautes beim Start fortsetzen" darf wieder greifen.
        // Der Prozess lebt nach Beenden oft weiter; das Merker-Flag blieb deshalb vom ersten Start stehen und nichts wurde mehr geöffnet.
        if (savedInstanceState == null) General.startupHandled = false
        Updater.apply(this)
        CrashLog.install(this)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .memoryCache { MemoryCache.Builder(this).maxSizePercent(Mem.imageCachePercent(Mem.tier(this))).build() }
                .allowRgb565(true)
                .crossfade(false)
                .build()
        )
        ScreenAwake.attach(this)
        intent?.getStringExtra(Reminders.EXTRA_CH)?.let { AppNav.openChannel = it }
        setContent { App(pick) }
    }

    /** Erinnerung angetippt, während die App schon läuft: Sender öffnen. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Reminders.EXTRA_CH)?.let { AppNav.openChannel = it }
    }

    override fun onStart() {
        super.onStart()
        Reminders.foreground = true
    }

    override fun onStop() {
        Reminders.foreground = false
        super.onStop()
    }

    /** Home-Taste (oder App-Wechsel) durch den Nutzer – nicht beim Bildschirmschoner und nicht bei Trailer/externem Player. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AppNav.leftByUser = true
    }

    /** Zurück in der App nach der Home-Taste: zur Startseite (siehe AppContent). */
    override fun onRestart() {
        super.onRestart()
        if (AppNav.leftByUser) { AppNav.leftByUser = false; AppNav.homeSignal++ }
    }

    override fun onResume() {
        super.onResume()
        // Nach Standby oder App-Wechsel das Fenster-Flag erneut setzen, falls das System es verworfen hat
        ScreenAwake.apply()
    }

    override fun onDestroy() {
        ScreenAwake.detach(this)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        val low = TRIM_MEMORY_RUNNING_LOW
        if (level >= low) Mem.trim(this)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        Mem.trim(this)
    }
}

@OptIn(ExperimentalFoundationApi::class)
val SmoothSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec = tween<Float>(280, easing = FastOutSlowInEasing)
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val peek = 90f
        val leading = offset
        val trailing = offset + size
        return when {
            leading >= 0f && trailing <= containerSize -> 0f
            leading < 0f && trailing > containerSize -> 0f
            leading < 0f -> leading - peek
            else -> trailing - containerSize + peek
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun App(pick: Boolean = false) {
    // Anzeigegröße: Entwurf ist für 960 dp Breite. Auf Geräten mit mehr dp (z. B. Fire TV) wird automatisch hochskaliert.
    val cfg = LocalConfiguration.current
    val base = LocalDensity.current
    val factor = ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp)
    val tf = General.textFactor()
    val scaled = remember(base.density, base.fontScale, factor, tf) { Density(base.density * factor, base.fontScale * tf) }
    CompositionLocalProvider(
        LocalDensity provides scaled,
        LocalBringIntoViewSpec provides SmoothSpec,
        LocalOverscrollFactory provides null
    ) {
        val c = LocalContext.current
        var picked by remember { mutableStateOf(!pick) }
        if (!picked) ProfilePicker { id -> Profiles.select(c, id); AppSettings.init(c); Parental.init(c); LangFilter.init(c); picked = true }
        else AppContent()
    }
}

private val SE_RE = Regex("""S(\d+)E(\d+)""")

@Composable
private fun AppContent() {
    val ctx = LocalContext.current
    remember {
        AppSettings.init(ctx); WatchStore.init(ctx); History.init(ctx); Library.init(ctx); QualityStore.init(ctx)
        PlaybackPrefs.init(ctx); DeadLinks.init(ctx); ChannelLists.init(ctx); Reminders.init(ctx); Reminders.schedule(ctx); 0
    }
    // Beim letzten Mal mitten im Hinzufügen unterbrochen (Absturz, Stromausfall): der Hinzufügen-Bildschirm lädt automatisch weiter
    val resumeAdd = remember { PlaylistStore.pending(ctx)?.autoResume == true }
    var adding by remember { mutableStateOf(PlaylistStore.savedUrl(ctx) == null || resumeAdd) }
    val onAirScope = rememberCoroutineScope()
    // Oberste Detailseite (Item oder PersonRef) plus Rückwärts-Stapel, damit Zurück Film → Person → Film nachvollzieht
    var top by remember { mutableStateOf<Any?>(null) }
    val trail = remember { mutableStateListOf<Any>() }
    val detail = top as? Item
    val personTop = top as? PersonRef
    fun pushTop(x: Any) { top?.let { trail.add(it) }; top = x }
    fun popTop() { top = trail.removeLastOrNull() }
    var browse by remember { mutableStateOf<BrowseState?>(null) }
    var cats by remember { mutableStateOf<CatReq?>(null) }
    var playing by remember { mutableStateOf<PlayReq?>(null) }
    var tab by remember { mutableStateOf(SettingsLogic.resolveStartTab(General.startTab, AppSettings.menuOrder.toList(), AppSettings.menuHidden.toList())) }
    val gridState = remember(browse) { LazyGridState() }
    val states = listOf(
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState(), rememberLazyListState()
    )

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { General.cleanupLegacy(ctx) }
        if (resumeAdd) return@LaunchedEffect
        // OnAir: app start never asks the provider when something is stored – refreshing is the background job's
        // (or a button's) business. Only an empty device (first start, wiped data) downloads, with back-off after failures.
        PlaylistStore.savedUrl(ctx)?.let { u -> PlaylistStore.load(ctx, u, false, true, trigger = RefreshLogic.Trigger.AUTO) }
    }

    // Nach der Home-Taste zurück in der App: zur Startseite (eine laufende Wiedergabe und das Hinzufügen bleiben, wie sie sind)
    LaunchedEffect(AppNav.homeSignal) {
        if (AppNav.homeSignal > 0 && playing == null && !adding) {
            val changed = top != null || browse != null || cats != null || tab != 0
            top = null; trail.clear(); browse = null; cats = null
            if (changed) AppNav.focusTab = 0
            if (tab != 0) { tab = 0; CatMemory.reset() }
            OnAirNav.home()   // OnAir: the five tiles are the start page
        }
    }

    // Hinzufügen verlassen, ohne dass etwas geladen ist (z. B. Fortsetzen abgebrochen): die gespeicherte Playlist laden
    var wasAdding by remember { mutableStateOf(adding) }
    LaunchedEffect(adding) {
        if (wasAdding && !adding && PlaylistStore.channels.isEmpty() && !PlaylistStore.loading) {
            PlaylistStore.savedUrl(ctx)?.let { PlaylistStore.load(ctx, it, false, true, trigger = RefreshLogic.Trigger.AUTO) }
        }
        wasAdding = adding
    }

    val all = PlaylistStore.channels
    val liveAll = remember(all) { all.filter { it.kind == Kind.LIVE } }
    // Beim Herunterladen und Einlesen der Playlist nicht in den Bildschirmschoner fallen
    KeepScreenOn("playlist", PlaylistStore.loading)
    // Genre-Erkennung im Hintergrund; startet bei jeder neuen Liste (Neuladen, Sprachfilter, Jugendschutz) sauber neu
    LaunchedEffect(all) { Classifier.restart(ctx, all) }
    // OnAir: per-playlist channel flags for the "now" screens (debug builds also log the on-now timings)
    LaunchedEffect(liveAll) { OnNow.prepare(ctx, liveAll) }

    fun start(item: Item, title: String, url: String, startMs: Long = 0L) {
        History.add(ctx, item.id)
        val sm = SE_RE.find(title)
        val season = sm?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val ep = sm?.groupValues?.get(2)?.toIntOrNull() ?: 0
        val tmdb = MetaRepo.cached(item)?.tmdbId?.takeIf { it > 0 } ?: 0
        var playUrl = url
        var alts = emptyList<Variant>()
        var note: String? = null
        if (item.kind == Kind.MOVIE) {
            // Gleicher Film in anderen Qualitäten (eigene Einträge des Anbieters): passend zum Gerät wählen, Rest zum Ausweichen
            val list = Sources.alternatives(item, all)
            if (list.size > 1) {
                alts = Sources.asVariants(list)
                val chosen = alts.indexOfFirst { it.url == url }.coerceAtLeast(0)
                val cap = PlaybackPrefs.cap(ctx)
                val best = QualityLogic.order(alts.map { it.quality }, cap).first()
                val rc = QualityLogic.rank(alts[chosen].quality)
                // Auf der Detailseite fest gewählte Qualität hat Vorrang vor der Automatik
                val vp = VariantPref.get(ctx, item.id)
                // Fest gewählte Quelle (Detailseite, Runde 14) vor fest gewählter Qualität vor Automatik
                val fixedSrc = vp.third?.let { l -> alts.indexOfFirst { it.label == l } }?.takeIf { it >= 0 }
                val fixed = vp.first?.let { q -> alts.indexOfFirst { it.quality == q } }?.takeIf { it >= 0 }
                if (fixedSrc != null && !DeadLinks.isDead(alts[fixedSrc].url)) {
                    playUrl = alts[fixedSrc].url
                } else if (fixed != null) {
                    playUrl = alts[fixed].url
                } else if (PlaybackPrefs.quality == QualityLogic.AUTO && rc > cap && QualityLogic.rank(alts[best].quality) <= cap) {
                    playUrl = alts[best].url
                    note = (alts[chosen].quality ?: "Diese Qualität") + " passt nicht zu diesem Gerät – spiele " + (alts[best].quality ?: "SD")
                } else if (DeadLinks.isDead(url)) {
                    // gewählte Quelle ist als gestört bekannt: gleich eine andere nehmen
                    alts.indices.firstOrNull { it != chosen && !DeadLinks.isDead(alts[it].url) }?.let { playUrl = alts[it].url; note = "Quelle gestört – spiele eine andere" }
                }
            }
        }
        playing = PlayReq(title, playUrl, item.id, item.kind == Kind.LIVE, startMs, tmdb, season, ep, alts, note)
    }

    /** EPG (Seitenleiste/Kachel). OnAir: opens the standalone TV guide instead of starting a stream first. */
    fun openEpg() {
        AppNav.focusTab = -1
        OnAirNav.openGuide(OnAirScreen.VOD)
    }

    /** OnAir: watch a channel picked in the standalone guide; its list becomes the zapping list (and the guide's list on return). */
    fun watchFromGuide(ch: Item, src: List<Item>) {
        ZapContext.list = src; ZapContext.key = OnAirNav.guideKey ?: "ALL"
        OnAirNav.guideList = src
        start(ch, ch.title, ch.url)
    }

    /**
     * OnAir Phase 4: watch a channel chosen on a "now" screen; the screen's channels become the zapping list.
     * Back from the player returns to that screen (it stays the OnAir screen underneath).
     */
    fun watchFromNow(ch: OnNowLogic.Chan, zap: List<OnNowLogic.Chan>) {
        val byId = liveAll.associateBy { it.id }
        val item = byId[ch.id] ?: return
        val list = zap.mapNotNull { byId[it.id] }.ifEmpty { listOf(item) }
        ZapContext.list = list; ZapContext.key = "ONAIR_" + OnAirNav.tile.name
        start(item, item.title, item.url)
    }

    /**
     * OnAir v2 (Phase 8): Multiview from the player – the channel playing now continues (in Multiview's own player, in the
     * picker's small window) and the picker opens for the second one. Accounts with 1 stream can't: say so instead.
     */
    fun startMultiview(id: String) {
        val item = liveAll.firstOrNull { it.id == id } ?: return
        val max = AccountStore.get(ctx, PlaylistStore.savedUrl(ctx))?.maxConnections
        if (!MultiviewLogic.available(max)) {
            android.widget.Toast.makeText(ctx, Lang.tr("Dein Konto erlaubt nur einen Stream – Multiview ist nicht möglich."), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        playing = null
        Multiview.begin(ctx, item)
    }

    /** OnAir: a past programme of a catch-up channel picked in the standalone guide. */
    fun archiveFromGuide(ch: Item, e: EpgEntry) {
        val url = Catchup.urlFor(ch, e)
        if (url == null) { android.widget.Toast.makeText(ctx, Lang.tr("Nicht im Archiv verfügbar"), android.widget.Toast.LENGTH_SHORT).show(); return }
        History.add(ctx, ch.id)
        playing = PlayReq(e.title, url, ch.id, false, 0L, archive = ArchiveRef(ch.id, ch.title, e))
    }

    /** Bereich wechseln; „EPG“ ist kein eigener Bildschirm, sondern öffnet den TV-Guide im Player. */
    // Bereich gewechselt (Kachel, „Alle anzeigen“, Seitenleiste): den Fokus in den neuen Inhalt setzen.
    // Sonst fällt er auf das erste Element zurück, und das ist die Seitenleiste – sie klappt von selbst auf (Runde 15).
    var jumpTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(jumpTick) {
        if (jumpTick == 0) return@LaunchedEffect
        val id = tab
        delay(150)
        repeat(20) {
            if (ContentFocus.tab == id && runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(120)
        }
    }
    fun goTab(id: Int) {
        if (id == MenuLogic.EPG) { openEpg(); return }
        tab = id; CatMemory.reset()
        jumpTick++
    }

    val open: (Item) -> Unit = { i ->
        if (i.kind == Kind.SERIES) NewEpisodes.dismiss(ctx, i.id)
        LastFocus.pending = i.id
        if (i.kind == Kind.LIVE) start(i, i.title, i.url) else pushTop(i)
    }

    // Erinnerungs-Benachrichtigung angetippt: Sender öffnen, sobald die Liste geladen ist
    LaunchedEffect(AppNav.openChannel, liveAll) {
        val id = AppNav.openChannel ?: return@LaunchedEffect
        val ch = liveAll.firstOrNull { it.id == id } ?: return@LaunchedEffect
        AppNav.openChannel = null
        General.startupHandled = true
        top = null; browse = null; cats = null; adding = false
        start(ch, ch.title, ch.url)
    }
    // Erinnerungen, während die App offen ist: Karte mit „Umschalten“ (statt Benachrichtigung)
    var remindNow by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            if (remindNow == null) Reminders.pending(System.currentTimeMillis() / 1000).firstOrNull()?.let { r ->
                Reminders.markDone(ctx, r); remindNow = r
            }
            delay(15_000)
        }
    }

    // Einstellungen "Letzten Sender / letzten Film bzw. Serie beim Start abspielen" (einmal pro App-Start).
    // Maßgeblich ist, was zuletzt lief: ein Sender oder ein Film / eine Serie.
    LaunchedEffect(all.isNotEmpty()) {
        if (all.isNotEmpty() && !General.startupHandled) {
            General.startupHandled = true
            val lastId = History.ids.firstOrNull()
            val last = if (lastId == null || playing != null) null else all.firstOrNull { it.id == lastId }
            if (last != null && last.kind == Kind.LIVE && General.resumeLive) start(last, last.title, last.url)
            else if (last != null && last.kind != Kind.LIVE && General.resumeVod) {
                // nur mit gemerkter Stelle (fertig geschaute Filme starten nicht von vorn; bei Serien ggf. die nächste Folge)
                val e = WatchStore.entries.firstOrNull { it.itemId == last.id }
                if (e != null) {
                    pushTop(last)
                    val t = WatchStore.target(ctx, last, e)
                    start(last, t.first, t.second, e.pos)
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Bg)) {
        // Maßstab des Entwurfs aus der verfügbaren Breite (siehe Design)
        val dk = maxWidth.value / Design.WIDTH
        SideEffect { if (Design.k != dk) Design.k = dk }
        val p = playing
        val d = detail
        val b = browse
        val c = cats
        when {
            // OnAir v2 (Phase 8): Multiview (picker or screens) – above everything while it runs
            Multiview.active -> Box(Modifier.fillMaxSize()) {
                MultiviewHost(liveAll) { item -> if (item != null) start(item, item.title, item.url) }
            }
            p != null -> {
                val zap = if (p.live || p.archive != null) {
                    val ctxList = ZapContext.list
                    if (ctxList.any { it.id == p.itemId }) ctxList
                    else {
                        val g = liveAll.firstOrNull { it.id == p.itemId }?.group
                        liveAll.filter { it.group == g }
                    }
                } else emptyList()
                val seriesItem = remember(p.itemId, all) {
                    if (p.live || p.archive != null) null else all.firstOrNull { it.id == p.itemId }?.let { SeriesInfo.cached(it) }
                }
                // Neuer Start von außen (z. B. Erinnerung „Umschalten“) während der Wiedergabe: Player neu aufbauen
                key(p) {
                    PlayerScreen(p, zap, liveAll, seriesItem, onMultiview = if (p.live && p.archive == null) { id -> startMultiview(id) } else null) {
                        // Live: zurück in Live TV auf den zuletzt gesehenen Sender (auch nach Umschalten im Player)
                        if (p.live || p.archive != null) LastFocus.pending = History.ids.firstOrNull { id -> liveAll.any { it.id == id } }
                        playing = null
                    }
                }
            }
            // Runde 17: jeder Bildschirm blendet weich ein (screenEnter); der Player bewusst ohne Effekt
            personTop != null -> key(personTop.id) { Box(Modifier.fillMaxSize().screenEnter()) { PersonScreen(personTop, all, onOpen = { pushTop(it) }, onBack = { popTop() }) } }
            d != null -> key(d.id) { Box(Modifier.fillMaxSize().screenEnter()) {
                DetailScreen(
                    d, all, onPlay = { url, t, pos -> start(d, t, url, pos) },
                    onPerson = { pushTop(it) }, onOpenItem = { pushTop(it) }, onBack = { popTop() }
                )
            } }
            b != null -> Box(Modifier.fillMaxSize().screenEnter()) { BrowseScreen(
                b, gridState, tab, onOpen = open,
                onBack = { browse = null },
                onGoTab = { if (it != MenuLogic.EPG) { browse = null; cats = null }; goTab(it) },
                // Aus der Kino-Ansicht gestartet: die Detailseite des Titels darunterlegen, damit „Zurück“ dort landet (nicht wieder in der Kino-Ansicht)
                all = all, onPlay = { i, t, url, pos -> if (i.kind != Kind.LIVE) pushTop(i); start(i, t, url, pos) }, onPerson = { pushTop(it) }
            ) }
            c != null -> Box(Modifier.fillMaxSize().screenEnter()) { CategoriesScreen(
                c, tab,
                onPick = { name, list, sort -> browse = BrowseState(BrowseReq(name, list, sort)) },
                onOpen = open,
                onBack = { cats = null },
                onGoTab = { if (it != MenuLogic.EPG) cats = null; goTab(it) }
            ) }
            adding -> Box(Modifier.fillMaxSize().screenEnter()) { AddPlaylistScreen { adding = false } }
            // OnAir (Phase 2): standalone TV guide, "coming in Phase 4" placeholder, five-tile home; IPVIO's home = On Demand
            OnAirNav.screen == OnAirScreen.GUIDE -> Box(Modifier.fillMaxSize().screenEnter()) {
                GuideScreen(liveAll, onWatch = { ch, src -> watchFromGuide(ch, src) }, onArchive = { ch, e -> archiveFromGuide(ch, e) }) { OnAirNav.back() }
            }
            OnAirNav.screen == OnAirScreen.DEBUG -> Box(Modifier.fillMaxSize().screenEnter()) {
                OnAirDebugScreen(liveAll) { OnAirNav.back() }
            }
            // OnAir Phase 4: Movies, Shows, Sports, Events on now
            OnAirNav.screen == OnAirScreen.NOW -> key(OnAirNav.tile) { Box(Modifier.fillMaxSize().screenEnter()) {
                NowScreen(
                    OnAirNav.tile, liveAll,
                    onWatch = { ch, zap -> watchFromNow(ch, zap) },
                    onGuide = { OnAirNav.openGuide(OnAirScreen.HOME) },
                    onBack = { OnAirNav.back() }
                )
            } }
            OnAirNav.screen == OnAirScreen.HOME -> Box(Modifier.fillMaxSize().screenEnter()) {
                OnAirHomeScreen(
                    live = liveAll, focusTile = OnAirNav.tile,
                    onTile = { OnAirNav.openTile(it) },
                    onOnDemand = { goTab(MenuLogic.HOME); OnAirNav.openVod() },
                    onSettings = { goTab(MenuLogic.SETTINGS); OnAirNav.openVod() },
                    onAdd = { adding = true },
                    onRetry = { PlaylistStore.savedUrl(ctx)?.let { u -> onAirScope.launch { PlaylistStore.load(ctx, u, false) } } }
                )
            }
            tab == 4 && all.isNotEmpty() -> Box(Modifier.fillMaxSize().screenEnter()) { SearchScreen(
                tab = tab, state = states[4],
                onOpen = open,
                onBrowse = { browse = BrowseState(it) },
                onTab = { goTab(it) }
            ) }
            else -> Box(Modifier.fillMaxSize().screenEnter(tab)) { HomeScreen(
                tab = tab, onTab = { goTab(it) }, states = states,
                onOpen = open,
                onPlayNow = { i -> LastFocus.pending = i.id; if (i.kind == Kind.SERIES) pushTop(i) else { if (i.kind != Kind.LIVE) pushTop(i); start(i, i.title, i.url) } },
                onResume = { e, i ->
                    LastFocus.pending = i.id
                    if (i.kind != Kind.LIVE) pushTop(i)
                    // aktuelle Adresse aus der geladenen Playlist statt der gespeicherten (Anbieter ändern Adressen)
                    val t = WatchStore.target(ctx, i, e)
                    start(i, t.first, t.second, e.pos)
                },
                onBrowse = { browse = BrowseState(it) },
                onCategories = { cats = it },
                onAdd = { adding = true },
                onExit = { OnAirNav.back() }   // OnAir: leaving IPVIO's home returns to the five tiles
            ) }
        }
        // Genre-Erkennung im Hintergrund: dezent oben rechts, nie im Player
        if (p == null && !adding) MetaBadge(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 22.dp))
        remindNow?.let { r ->
            ReminderCard(r, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), onClose = { remindNow = null }) {
                remindNow = null
                val ch = liveAll.firstOrNull { it.id == r.chId }
                if (ch != null) { top = null; browse = null; cats = null; start(ch, ch.title, ch.url) }
            }
        }
    }
}

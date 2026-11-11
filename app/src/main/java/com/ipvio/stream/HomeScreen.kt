package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SearchState {
    var query by mutableStateOf("")
}

private object HomeReturn {
    var returning = false
}

/** Zuletzt übernommener Genre-Stand und Trend-Liste – bleiben über das Verlassen der Startseite hinweg erhalten. */
private object HomeVer {
    var value = -1
    var trend: Set<String> = emptySet()
}

private const val SORT_NEW = 0
private const val SORT_RATING = 2
private const val SORT_PLAYLIST = 8

private fun ipvioGroups(list: List<Item>): Map<String, List<Item>> {
    val map = LinkedHashMap<String, ArrayList<Item>>()
    for (item in list) {
        val m = MetaRepo.cached(item)
        val cats = if (m != null) Genres.categoriesOf(m) else emptyList()
        if (cats.isEmpty()) map.getOrPut("Sonstige") { ArrayList() }.add(item)
        else for (c in cats) map.getOrPut(c) { ArrayList() }.add(item)
    }
    val prio = listOf("Anime", "Animation")
    val sorted = map.entries.sortedWith(
        compareBy<Map.Entry<String, ArrayList<Item>>>(
            { if (it.key in prio) prio.indexOf(it.key) else if (it.key == "Sonstige") 1000 else 10 },
            { -it.value.size }
        )
    )
    return sorted.associate { it.key to it.value }
}

private fun providerGroups(list: List<Item>): Map<String, List<Item>> {
    val g = list.groupBy { it.group }
    val order = PlaylistStore.catOrder
    if (order.isEmpty()) return g
    return g.entries.sortedBy { order[it.key] ?: 1_000_000 }.associate { it.key to it.value }
}

/**
 * Alles, was die Startseite aus der Playlist ableitet. Wird im Hintergrund-Thread berechnet (bei 100.000+ Titeln
 * dauert das spürbar), die Oberfläche zeigt solange den vorherigen Stand bzw. das Lade-Skelett.
 */
private class HomeData(
    val live: List<Item>, val movies: List<Item>, val series: List<Item>,
    val liveP: Map<String, List<Item>>, val movieP: Map<String, List<Item>>, val seriesP: Map<String, List<Item>>,
    val movieG: Map<String, List<Item>>, val seriesG: Map<String, List<Item>>,
    val topM: List<Item>, val topS: List<Item>, val byId: Map<String, Item>, val trendItems: List<Item>,
    val genresOk: Boolean
)

private fun buildHome(all: List<Item>, ipvioWanted: Boolean, trend: Set<String>): HomeData {
    val live = ArrayList<Item>(); val movies0 = ArrayList<Item>(); val series = ArrayList<Item>()
    for (i in all) when (i.kind) { Kind.LIVE -> live.add(i); Kind.MOVIE -> movies0.add(i); Kind.SERIES -> series.add(i) }
    // Derselbe Film in mehreren Fassungen (HD, 4K, …) nur einmal zeigen; die Fassung wählt man auf der Detailseite (Quelle)
    val movies = PlaylistLogic.firstPerKey(movies0) { it.mk + "|" + it.lang }
    val genresOk = MetaRepo.enabled() && Classifier.supported(movies.size + series.size)
    val ipvio = ipvioWanted && genresOk
    val movieP = providerGroups(movies)
    val seriesP = providerGroups(series)
    val trendItems = if (trend.isEmpty()) emptyList() else (movies.asSequence() + series.asSequence()).filter { Trending.norm(it.title) in trend }.toList()
    return HomeData(
        live, movies, series, providerGroups(live), movieP, seriesP,
        if (ipvio) ipvioGroups(movies) else movieP, if (ipvio) ipvioGroups(series) else seriesP,
        topRated(movies), topRated(series), all.associateBy { it.id }, trendItems, genresOk
    )
}

/** Letztes Ergebnis: Beim Zurückkehren auf die Startseite (z. B. von der Detailseite) muss nichts neu gerechnet werden. */
private object HomeCache {
    private var all: List<Item>? = null
    private var order: Map<String, Int>? = null
    private var want = false
    private var ver = -1
    private var trend: Set<String>? = null
    private var data: HomeData? = null

    @Synchronized fun get(all: List<Item>, order: Map<String, Int>, want: Boolean, ver: Int, trend: Set<String>): HomeData? =
        if (all === this.all && order === this.order && want == this.want && ver == this.ver && trend === this.trend) data else null

    @Synchronized fun put(all: List<Item>, order: Map<String, Int>, want: Boolean, ver: Int, trend: Set<String>, d: HomeData) {
        this.all = all; this.order = order; this.want = want; this.ver = ver; this.trend = trend; data = d
    }

    /** Für die Wartezeit nach einer Änderung: lieber den vorigen Stand zeigen als ein leeres Skelett. */
    @Synchronized fun last(): HomeData? = data
}

private val EMPTY_HOME = HomeData(
    emptyList(), emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(),
    emptyList(), emptyList(), emptyMap(), emptyList(), false
)

private fun topRated(list: List<Item>): List<Item> =
    list.mapNotNull { i ->
        val m = MetaRepo.cached(i)
        val sc = m?.score()
        if (m != null && (m.votes >= 300 || m.tmdbId <= 0) && sc != null) i to sc else null
    }.sortedByDescending { it.second }.map { it.first }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    tab: Int, onTab: (Int) -> Unit, states: List<LazyListState>,
    onOpen: (Item) -> Unit, onPlayNow: (Item) -> Unit,
    onResume: (Entry, Item) -> Unit, onBrowse: (BrowseReq) -> Unit,
    onCategories: (CatReq) -> Unit, onAdd: () -> Unit,
    /** OnAir: set when this screen is opened from the OnAir home ("On Demand") – leaving it returns there instead of exiting. */
    onExit: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = PlaylistStore.channels
    val order = PlaylistStore.catOrder
    // Genre-Fortschritt höchstens alle 10 s übernehmen: jede Übernahme rechnet Reihen und Top-Listen neu
    var ver by remember { mutableIntStateOf(HomeVer.value.takeIf { it >= 0 } ?: Classifier.version) }
    LaunchedEffect(Unit) { while (true) { delay(10_000); if (ver != Classifier.version) { ver = Classifier.version; HomeVer.value = ver } } }
    var trend by remember { mutableStateOf(HomeVer.trend) }
    LaunchedEffect(all.isNotEmpty()) { if (all.isNotEmpty()) { trend = Trending.titles(); HomeVer.trend = trend } }
    val wantIpvio = AppSettings.ipvioFilter
    val cached = HomeCache.get(all, order, wantIpvio, ver, trend)
    // Bei geänderten Eingaben bleibt bis zum neuen Ergebnis der bisherige Stand sichtbar (produceState behält den Wert)
    val dataState = produceState(cached, all, order, wantIpvio, ver, trend) {
        value = HomeCache.get(all, order, wantIpvio, ver, trend)
            ?: withContext(Dispatchers.Default) { buildHome(all, wantIpvio, trend) }.also { HomeCache.put(all, order, wantIpvio, ver, trend, it) }
    }
    // Während neu gerechnet wird: vorigen Stand zeigen, nur beim allerersten Mal das Lade-Skelett
    val prev = HomeCache.last()
    val data = dataState.value ?: prev?.takeIf { it.byId.isNotEmpty() && all.isNotEmpty() } ?: EMPTY_HOME
    val computing = dataState.value == null && prev == null && all.isNotEmpty()
    val live = data.live
    val movies = data.movies
    val series = data.series
    val movieP = data.movieP
    val seriesP = data.seriesP
    val liveP = data.liveP
    val movieG = data.movieG
    val seriesG = data.seriesG
    val topM = data.topM
    val topS = data.topS
    val byId = data.byId
    val cardW = AppSettings.cardW
    val hist = History.ids.mapNotNull { byId[it] }
    val watchList = Library.watch.mapNotNull { byId[it] }
    val favLive = Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE }
    val trendItems = data.trendItems
    // „Weil du X geschaut hast“: ähnliche Titel (TMDB) des zuletzt gestarteten Films/der Serie, abgeglichen mit der eigenen Playlist
    val becauseSrc = hist.firstOrNull { it.kind != Kind.LIVE }
    val because by produceState<Pair<Item, List<Item>>?>(null, becauseSrc?.id, byId.size) {
        val src = becauseSrc ?: return@produceState
        val m = MetaRepo.extended(ctx, src) ?: return@produceState
        if (m.tmdbId <= 0) return@produceState
        val c = CreditsRepo.credits(ctx, m) ?: return@produceState
        val seen = History.ids.toSet()
        val l = withContext(Dispatchers.Default) {
            LibMatch.matches(PlaylistStore.channels, c.similar, 40).filter { it.id != src.id && it.id !in seen && it.kind != Kind.LIVE }
        }
        if (l.size >= 4) value = src to l.take(20)
    }

    // Neue Folgen bei Favoriten-Serien prüfen (Glocke)
    LaunchedEffect(byId.size, Library.favs.size) {
        if (byId.isNotEmpty()) NewEpisodes.check(ctx, Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.SERIES })
    }

    SideEffect { NavState.tab = tab; ContentFocus.tab = if (tab in 0 until MenuLogic.COUNT) tab else -1 }

    // Kopfzeile (Suche, Erinnerungen, Profil) und Schnellzugriff wie im Entwurf
    var picker by remember { mutableStateOf(false) }
    val ready = remember { mutableStateListOf<Item>() }
    val candidates = remember(all, trendItems, tab) {
        when (tab) {
            1 -> (trendItems.filter { it.kind == Kind.MOVIE }.take(8) + movies.takeLast(30).reversed())
            2 -> (trendItems.filter { it.kind == Kind.SERIES }.take(8) + series.takeLast(30).reversed())
            else -> (trendItems.take(8) + movies.takeLast(25).reversed() + series.takeLast(15).reversed())
        }.distinctBy { it.id }
    }
    // Filme/Serien: Reiter oben neben der Suche (statt der großen Knöpfe unter dem Bild)
    val isVod = tab == 1 || tab == 2
    val vodBase = if (tab == 1) movies else series
    val vodTop = if (tab == 1) topM else topS
    val vodNoun = if (tab == 1) "Filme" else "Serien"
    val openCats: () -> Unit = {
        scope.launch {
            val g = if (data.genresOk) withContext(Dispatchers.Default) { ipvioGroups(vodBase) } else emptyMap()
            onCategories(CatReq("$vodNoun · Kategorien", g, if (tab == 1) movieP else seriesP, SORT_NEW))
        }
    }
    val navTabs = if (!isVod) emptyList() else listOf(
        HeaderTab("Kategorien", AIcon.Categories, "tb:cats$tab") { openCats() },
        HeaderTab("Top bewertet", AIcon.Star, "tb:top$tab") { onBrowse(BrowseReq("Top bewertet · $vodNoun", vodTop, SORT_RATING)) },
        HeaderTab("Neu hinzugefügt", AIcon.Sparkle, "tb:new$tab") { onBrowse(BrowseReq("Neu hinzugefügt · $vodNoun", vodBase, SORT_NEW)) },
        HeaderTab("Alle $vodNoun", AIcon.Grid, "tb:all$tab") { onBrowse(BrowseReq("Alle $vodNoun", vodBase, SORT_NEW)) }
    )
    var inHeader by remember { mutableStateOf(false) }
    val header: @Composable (Modifier) -> Unit = { m ->
        HeaderBar(
            m.onFocusChanged { inHeader = it.hasFocus },
            onSearch = { onTab(4) },
            onReminder = { r -> byId[r.chId]?.let(onOpen) },
            onNewEpisode = { id -> byId[id]?.let(onOpen) },
            // Nur ein Profil: direkt zur Profil-Seite der Einstellungen (vorher: Einstellungen allgemein)
            onProfile = { if (Profiles.list.size > 1) picker = true else { SettingsNav.openPage = SettingsNav.PROFILES; onTab(MenuLogic.SETTINGS) } },
            tabs = navTabs,
            showView = tab in 0..2 || tab == MenuLogic.FAVS || tab == MenuLogic.MYLIST || tab == MenuLogic.VERLAUF,
            // Ohne großes Bild (noch keine Titelbilder): der erste Reiter ist das Ziel aus der Seitenleiste
            firstIsContent = isVod && ready.isEmpty()
        )
    }
    // Kacheln mit echten Bildern aus der eigenen Bibliothek (Runde 15): Szenenbild eines Top-Titels je Bereich,
    // bei Favoriten und Meiner Liste ein Titel daraus, bei Live TV die Logos der Lieblingssender.
    val nowChannels = remember(favLive, hist, live) {
        (favLive + hist.filter { it.kind == Kind.LIVE }).distinctBy { it.id }.ifEmpty { live.take(20) }
    }
    val favVod = Library.favs.mapNotNull { byId[it] }.filter { it.kind != Kind.LIVE }
    val tileImg by produceState(emptyMap<Int, String>(), topM.size, topS.size, favVod.size, watchList.size) {
        val used = HashSet<String>()
        val out = HashMap<Int, String>()
        suspend fun pick(id: Int, list: List<Item>) {
            for (c in list.take(14)) {
                val u = MetaRepo.basic(ctx, c)?.backdrop
                if (u != null && used.add(u)) { out[id] = u; value = HashMap(out); return }
            }
        }
        pick(1, topM); pick(2, topS)
        pick(MenuLogic.FAVS, favVod.ifEmpty { topM.drop(3) })
        pick(MenuLogic.MYLIST, watchList.filter { it.kind != Kind.LIVE }.ifEmpty { topS.drop(3) })
    }
    val liveLogos = nowChannels.mapNotNull { it.logo }.take(3)
    val tiles = listOf(
        QuickTile("Live TV", "${live.size} Sender", 3, Color(0xFF6B3A0E), Color(0xFF2B1708), Color(0xFFF59E0B), logos = liveLogos) { onTab(3) },
        QuickTile("Filme", "${movies.size} Filme", 1, Color(0xFF1E3A8A), Color(0xFF0F1B45), Color(0xFF60A5FA), image = tileImg[1]) { onTab(1) },
        QuickTile("Serien", "${series.size} Serien", 2, Color(0xFF4C1D95), Color(0xFF22104A), Color(0xFFC084FC), image = tileImg[2]) { onTab(2) },
        QuickTile("Favoriten", "Deine Auswahl", 7, Color(0xFF7F1D1D), Color(0xFF3A0B0B), Color(0xFFFB7185), image = tileImg[MenuLogic.FAVS]) { onTab(MenuLogic.FAVS) },
        QuickTile("Meine Liste", "${watchList.count { it.kind != Kind.LIVE }} Titel", 8, Color(0xFF134E4A), Color(0xFF082725), Color(0xFF2DD4BF), image = tileImg[MenuLogic.MYLIST]) { onTab(MenuLogic.MYLIST) }
    )
    DisposableEffect(Unit) { onDispose { HomeReturn.returning = true } }

    LaunchedEffect(candidates) {
        ready.clear()
        for (c in candidates) {
            val m = MetaRepo.basic(ctx, c)
            if (m?.backdrop != null) ready.add(c)
            if (ready.size >= 6) break
        }
    }
    // Über die Seitenleiste von einem anderen Bildschirm (z. B. Suche) hierher gewechselt: Inhalt fokussieren
    LaunchedEffect(Unit) {
        if (AppNav.focusTab == tab && LastFocus.pending == null) {
            AppNav.focusTab = -1
            repeat(10) {
                delay(150)
                if (runCatching { ContentFocus.req.requestFocus() }.isSuccess) return@LaunchedEffect
            }
        }
    }
    LaunchedEffect(Unit) {
        if (HomeReturn.returning) {
            HomeReturn.returning = false
            delay(1200)
            if (LastFocus.pending != null) {
                LastFocus.pending = null
                val st = states[tab]
                val atTop = (tab == 0 && st.firstVisibleItemIndex == 0) || (tab in 1..2 && st.firstVisibleItemIndex <= 1)
                if (atTop) runCatching { ContentFocus.req.requestFocus() }
            }
        }
    }

    var inSidebar by remember { mutableStateOf(false) }
    val sideFocus = remember { FocusRequester() }
    // App-Start: Fokus auf „Jetzt ansehen“ statt auf der Seitenleiste. Das Bild oben erscheint erst, wenn Playlist und Infos geladen
    // sind (kann einige Sekunden dauern); darum wird bis zu 15 s wiederholt, und der Merker wird erst bei Erfolg gesetzt.
    // Nach 3 s nur noch, solange der Fokus noch in der Seitenleiste liegt (hat der Nutzer schon selbst gewählt, wird nichts gestohlen).
    LaunchedEffect(ready.isNotEmpty(), tab) {
        if (tab != 0 || HomeFlags.heroDone || LastFocus.pending != null) return@LaunchedEffect
        var waited = 0
        while (waited < 15000) {
            delay(150); waited += 150
            if (ready.isEmpty() && waited < 6000) continue
            if (waited > 3000 && !inSidebar) return@LaunchedEffect
            if (runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)) { HomeFlags.heroDone = true; return@LaunchedEffect }
        }
    }
    // Beenden: zweimal Zurück innerhalb von 2,5 s. Unabhängig davon, wo der Fokus gerade liegt (sonst führte das zweite
    // Zurück wieder auf die Startseite statt zu beenden).
    var lastBack by remember { mutableLongStateOf(0L) }
    fun exitApp() {
        if (onExit != null) { onExit(); return }   // OnAir: back to the five-tile home
        val act = generateSequence<android.content.Context>(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        act?.finishAffinity()
    }
    BackHandler(enabled = !inSidebar && all.isNotEmpty()) {
        val now = System.currentTimeMillis()
        if (tab == 0 && General.confirmExit && now - lastBack < 2500) exitApp()
        // Runde 17: Zurück geht genau eine Ebene hoch – weiter unten auf der Startseite zuerst nach oben zu „Jetzt ansehen“,
        // erst von dort in die Seitenleiste (wie in Filme/Serien)
        else if (tab == 0 && states[0].firstVisibleItemIndex > 0) scope.launch {
            runCatching { states[0].scrollToItem(0) }
            repeat(8) {
                delay(60)
                if (runCatching { ContentFocus.req.requestFocus() }.getOrDefault(false)) return@launch
            }
            runCatching { sideFocus.requestFocus() }
        }
        else runCatching { sideFocus.requestFocus() }
    }
    // Filme/Serien: Zurück aus den Reihen führt zuerst zu den Reitern oben, von dort in die Seitenleiste
    BackHandler(enabled = !inSidebar && !inHeader && isVod && all.isNotEmpty()) {
        scope.launch {
            runCatching { states[tab].scrollToItem(0) }
            repeat(8) {
                delay(60)
                if (runCatching { HeaderFocus.firstTab.requestFocus() }.getOrDefault(false)) return@launch
            }
            runCatching { sideFocus.requestFocus() }
        }
    }
    BackHandler(enabled = inSidebar && tab != 0) { onTab(0) }
    BackHandler(enabled = inSidebar && tab == 0 && (General.confirmExit || onExit != null)) {
        if (onExit != null) { onExit(); return@BackHandler }   // OnAir: no exit prompt, just back to the OnAir home
        val now = System.currentTimeMillis()
        if (now - lastBack < 2500) exitApp()
        else { lastBack = now; android.widget.Toast.makeText(ctx, Lang.tr("Noch einmal Zurück drücken zum Beenden"), android.widget.Toast.LENGTH_SHORT).show() }
    }

    val resumeList = WatchStore.entries.mapNotNull { e -> byId[e.itemId]?.let { e to it } }
    val reload: () -> Unit = { PlaylistStore.savedUrl(ctx)?.let { u -> scope.launch { PlaylistStore.load(ctx, u, false) } } }

    // Runde 17b (Cinematic Remake): Hintergrund und Infos folgen dem markierten Titel, sobald das große Bild weggescrollt ist
    val bbTab = tab in 0..2
    val bbState = states[tab.coerceIn(0, states.lastIndex)]
    // Runde 18: Auch wenn das große Bild noch teilweise zu sehen ist (z. B. erste Reihe „Beliebte Serien“), zeigt der Hintergrund
    // die Infos des markierten Titels – und das große Bild (Slideshow) pausiert.
    // Infos oben nur bei den normalen Titelreihen (ab „Top 10 heute“ bzw. „Neu hinzugefügt“), nicht bei Kacheln, Live TV, Weiterschauen,
    // Meine Liste und Zuletzt geschaut. Und nur, wenn die Reihe tief genug steht, dass sie die Infos nicht überdeckt.
    val listTop = remember { floatArrayOf(0f) }
    val noBb = remember { setOf("hero", "top", "tiles", "resume", "chnow", "hero1", "hero2", "top1", "top2", "res1", "res2", "wl1", "wl2", "hr1", "hr2", "hint1", "hint2") }
    val bbOn by remember(tab) {
        derivedStateOf {
            if (!bbTab || !Billboard.cardFocus || Billboard.focused == null) return@derivedStateOf false
            val co = Billboard.focusCo
            if (co == null || !co.isAttached) return@derivedStateOf false
            val li = bbState.layoutInfo
            val y = co.positionInRoot().y - listTop[0]
            val row = li.visibleItemsInfo.lastOrNull { it.offset <= y } ?: return@derivedStateOf false
            (row.key as? String) !in noBb && row.index > 0 &&
                row.offset >= (li.viewportEndOffset - li.viewportStartOffset) * 0.33f
        }
    }
    SideEffect { Billboard.active = bbOn }
    LaunchedEffect(inSidebar) { if (inSidebar) Billboard.cardFocus = false }
    LaunchedEffect(tab) { Billboard.cardFocus = false }
    var bbItem by remember { mutableStateOf<Item?>(Billboard.focused) }
    // 17c: erst nach kurzem Verweilen wechseln (beim schnellen Durchblättern werden keine großen Bilder geladen)
    LaunchedEffect(Billboard.focused) { if (bbItem != null) delay(450); bbItem = Billboard.focused }
    val bbA by animateFloatAsState(if (bbOn && bbItem != null) 1f else 0f, tween(if (Look.motion == 0) 0 else 520), label = "bbA")
    val pivotSpec = remember(tab) { PivotSpec(0.5f) { states[tab.coerceIn(0, states.lastIndex)] } }
    Box(Modifier.fillMaxSize()) {
        if (bbTab && bbA > 0.01f) BillboardBackdrop(bbItem, Modifier.fillMaxSize().graphicsLayer { alpha = bbA })
        if (bbTab && bbA > 0.01f) bbItem?.let { bi ->
            BillboardInfo(bi, Modifier.padding(start = DS.SidebarW + dpx(56), top = dpx(64)).graphicsLayer { alpha = bbA; translationY = (1f - bbA) * 20.dp.toPx() })
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = DS.SidebarW)) {
            // Kartenbreiten und Höhe des großen Bildes aus dem verfügbaren Platz (wie im Entwurf: alles auf einen Blick)
            val grid = remember(maxWidth, Design.k, General.cardSize) { cardGrid(maxWidth - RowPadX * 2) }
            // Entwurf: großes Bild 420 px hoch (Start), 345 px (Filme/Serien)
            val heroH = dpx(if (tab == 0) 540 else 450)
            when {
                // Download/Einlesen ohne angezeigte Liste: Ladebildschirm mit Fortschritt (und ggf. Sprachauswahl)
                PlaylistStore.loading && all.isEmpty() && PlaylistStore.heavy -> PlaylistLoadingOverlay()
                (PlaylistStore.loading && all.isEmpty()) || computing -> SkeletonHome()
                all.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        BrandMark(Modifier.size(72.dp))
                        val err = PlaylistStore.error
                        // Alles ausgeblendet (Sprachfilter passt nicht zu dieser Playlist): direkt zurückholen können
                        val allHidden = err == null && PlaylistStore.langCounts.isNotEmpty() && PlaylistStore.langCounts.keys.all { it in LangFilter.hidden }
                        Text(
                            when { err != null -> "Die Playlist konnte nicht geladen werden"; allHidden -> "Alle Sprachen dieser Playlist sind ausgeblendet"; else -> "Noch keine Sender" },
                            color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold
                        )
                        if (err != null) {
                            Text(err, color = Muted, fontSize = 16.sp)
                            Text("Prüfe die Internetverbindung, die Adresse und die Zugangsdaten.", color = Muted, fontSize = 16.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (allHidden) PillButton("Alle Sprachen zeigen", light = true) { LangFilter.setAndApply(ctx, emptySet()) }
                            if (err != null) PillButton("Erneut versuchen", light = true) { reload() }
                            PillButton("Playlist hinzufügen", light = err == null && !allHidden) { onAdd() }
                        }
                    }
                }
                tab == 3 -> LiveTab(live, liveP, favLive, onOpen)
                // Markierte Reihe bleibt in der unteren Hälfte stehen (PivotSpec); darüber werden Reihen ausgeblendet, damit oben
                // Titel und Infos des markierten Titels frei stehen (Billboard)
                else -> CompositionLocalProvider(
                    LocalCardGrid provides grid, LocalScrollTop provides { states[tab].scrollToItem(0) },
                    androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides (if (bbTab) pivotSpec else SmoothSpec)
                ) { LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { listTop[0] = it.positionInRoot().y }.drawWithContent {
                    // Solange die Infos oben stehen (auch beim Ausblenden): alles über der markierten Reihe weg, das große Bild immer.
                    val ct = if (bbTab && bbA > 0.01f) {
                        val li = states[tab].layoutInfo
                        val co = Billboard.focusCo
                        var edge = size.height * 0.5f - dpx(140).toPx()
                        if (Billboard.focused != null && co != null && co.isAttached) {
                            val y = co.positionInRoot().y - listTop[0]
                            val row = li.visibleItemsInfo.lastOrNull { it.offset <= y }
                            if (row != null && row.index > 0) edge = row.offset.toFloat()
                        }
                        li.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { edge = maxOf(edge, (it.offset + it.size).toFloat()) }
                        edge.coerceIn(0f, size.height * 0.75f)
                    } else 0f
                    if (ct > 1f) clipRect(top = ct) { this@drawWithContent.drawContent() } else drawContent()
                }, state = states[tab], contentPadding = PaddingValues(bottom = dpx(420))) {
                    when (tab) {
                        0 -> {
                            if (ready.isNotEmpty()) item(key = "hero") { Hero(ready.toList(), heroH, true, onOpen, onPlayNow, header, { heroScroll(states[0]) }) { scope.launch { states[0].animateScrollToItem(0) } } }
                            else item(key = "top") { header(Modifier) }
                            item(key = "tiles") { QuickTiles(tiles) }
                            // Weiterschauen ganz oben, direkt unter den Kacheln: wer viel schaut, will mit wenigen Klicks weiter
                            if (resumeList.isNotEmpty()) item(key = "resume") { ResumeRow(resumeList, cardW, onResume) }
                            if (nowChannels.isNotEmpty()) item(key = "chnow") { ChannelNowRow("Live TV", nowChannels, onOpen) { onTab(3) } }
                            // Top 10 immer als ganze Cover (unabhängig von der Bild-Einstellung)
                            if (trendItems.size >= 3) item(key = "trend") {
                                ItemsRow("Top 10 heute", trendItems.take(10), cardW, onOpen, style = CARD_TOP10) { onBrowse(BrowseReq("Im Trend", trendItems, SORT_PLAYLIST)) }
                            }
                            because?.let { (src, l) ->
                                item(key = "because") { ItemsRow("Weil du „" + src.title + "“ geschaut hast", l, cardW, onOpen) { onBrowse(BrowseReq("Ähnlich wie " + src.title, l, SORT_PLAYLIST)) } }
                            }
                            val wl = watchList.filter { it.kind != Kind.LIVE }
                            if (wl.isNotEmpty()) item(key = "wl") { ItemsRow("Meine Liste", wl, cardW, onOpen) { onTab(MenuLogic.MYLIST) } }
                            if (movies.isNotEmpty()) item(key = "newm") {
                                ItemsRow("Neu hinzugefügt · Filme", movies.takeLast(40).reversed(), cardW, onOpen) { onBrowse(BrowseReq("Neu hinzugefügt · Filme", movies, SORT_NEW)) }
                            }
                            if (topM.size >= 8) item(key = "topm") {
                                ItemsRow("Top bewertet · Filme", topM, cardW, onOpen) { onBrowse(BrowseReq("Top bewertet · Filme", topM, SORT_RATING)) }
                            }
                            if (series.isNotEmpty()) item(key = "news") {
                                ItemsRow("Neu hinzugefügt · Serien", series.takeLast(40).reversed(), cardW, onOpen) { onBrowse(BrowseReq("Neu hinzugefügt · Serien", series, SORT_NEW)) }
                            }
                            if (topS.size >= 8) item(key = "tops") {
                                ItemsRow("Top bewertet · Serien", topS, cardW, onOpen) { onBrowse(BrowseReq("Top bewertet · Serien", topS, SORT_RATING)) }
                            }
                            seriesG.entries.take(3).forEach { (g, l) ->
                                item(key = "s$g") { ItemsRow("Serien · $g", l, cardW, onOpen) { onBrowse(BrowseReq("Serien · $g", l, SORT_NEW)) } }
                            }
                            movieG.entries.take(3).forEach { (g, l) ->
                                item(key = "m$g") { ItemsRow("Filme · $g", l, cardW, onOpen) { onBrowse(BrowseReq("Filme · $g", l, SORT_NEW)) } }
                            }
                            if (movies.isEmpty() && series.isEmpty()) item { EmptyHint("Diese Playlist enthält keine Filme oder Serien. Live TV findest du links.") }
                        }
                        1, 2 -> {
                            val isM = tab == 1
                            val base = if (isM) movies else series
                            val top = if (isM) topM else topS
                            val noun = if (isM) "Filme" else "Serien"
                            val kind = if (isM) Kind.MOVIE else Kind.SERIES
                            val groups = if (isM) movieG else seriesG
                            if (ready.isNotEmpty()) item(key = "hero$tab") { Hero(ready.toList(), heroH, false, onOpen, onPlayNow, header, { heroScroll(states[tab]) }) { scope.launch { states[tab].animateScrollToItem(0) } } }
                            else item(key = "top$tab") { header(Modifier) }
                            // Beliebt (im Trend, sonst top bewertet) direkt unter dem großen Bild. Erste Reihe:
                            // „Alle anzeigen“ hoch = Profil oben rechts, links = „Zur Liste“ im Bild
                            val tt = trendItems.filter { it.kind == kind }
                            val pop = if (tt.size >= 5) tt else top
                            if (pop.size >= 3) item(key = "pop$tab") {
                                ItemsRow(if (isM) "Beliebte Filme" else "Beliebte Serien", pop, cardW, onOpen,
                                    upReq = HeaderFocus.profile, leftReq = if (ready.isNotEmpty()) HeroFocus.second else null) {
                                    onBrowse(BrowseReq((if (isM) "Beliebte Filme" else "Beliebte Serien"), pop, SORT_PLAYLIST))
                                }
                            }
                            if (ready.isEmpty() && pop.size < 3) item(key = "hint$tab") { EmptyHint("Oben findest du alle $noun, Neuheiten, Top bewertet und Kategorien.") }
                            val rl = resumeList.filter { it.second.kind == kind }
                            if (rl.isNotEmpty()) item(key = "res$tab") { ResumeRow(rl, cardW, onResume) }
                            val wl = watchList.filter { it.kind == kind }
                            if (wl.isNotEmpty()) item(key = "wl$tab") { ItemsRow("Meine Liste", wl, cardW, onOpen) { onBrowse(BrowseReq("Meine Liste · $noun", wl, SORT_PLAYLIST)) } }
                            val recent = hist.filter { it.kind == kind }
                            if (recent.isNotEmpty()) item(key = "hr$tab") { ItemsRow("Zuletzt geschaut", recent, cardW, onOpen) { onBrowse(BrowseReq("Zuletzt geschaut · $noun", recent, SORT_PLAYLIST)) } }
                            if (base.isEmpty()) item { EmptyHint("Keine $noun in dieser Playlist") }
                            if (base.isNotEmpty()) item(key = "new$tab") { ItemsRow("Neu hinzugefügt", base.takeLast(40).reversed(), cardW, onOpen) { onBrowse(BrowseReq("Neu hinzugefügt · $noun", base, SORT_NEW)) } }
                            if (top.size >= 8) item(key = "best$tab") { ItemsRow("Top bewertet", top, cardW, onOpen) { onBrowse(BrowseReq("Top bewertet · $noun", top, SORT_RATING)) } }
                            groups.entries.take(12).forEach { (g, l) -> item(key = "G$tab$g") { ItemsRow(g, l, cardW, onOpen) { onBrowse(BrowseReq(g, l, SORT_NEW)) } } }
                            if (groups.size > 12) item { EmptyHint("Weitere Kategorien findest du unter „Kategorien“ oben.") }

                        }
                        MenuLogic.FAVS -> {
                            // Runde 14: neben Sendern auch Filme und Serien (Detailseite > „Zu Favoriten“)
                            val favM = Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.MOVIE }
                            val favS = Library.favs.mapNotNull { byId[it] }.filter { it.kind == Kind.SERIES }
                            item(key = "favtop") { header(Modifier) }
                            if (favLive.isEmpty() && favM.isEmpty() && favS.isEmpty()) {
                                item(key = "favh") { RowTitle("Favoriten", null, null, onAll = null) }
                                item(key = "fave") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.Heart, "Noch keine Favoriten", "Auf einem Sender OK gedrückt halten, bei Filmen und Serien auf der Detailseite „Zu Favoriten“ wählen.",
                                            action = "Zu Live TV", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(3) }
                                    }
                                }
                            }
                            if (favLive.isNotEmpty()) {
                                item(key = "favh") { RowTitle("Favoriten · Sender", null, null, onAll = null) }
                                item(key = "favg") { ChannelDetailRows(favLive, ContentFocus.req, onOpen) }
                            }
                            if (favM.isNotEmpty()) {
                                item(key = "favmh") { RowTitle("Favoriten · Filme", null, null, onAll = null) }
                                item(key = "favm") { CardGridRows(favM, if (General.cardArt == 1) CARD_LAND else CARD_POSTER, if (favLive.isEmpty()) ContentFocus.req else null, onOpen) }
                            }
                            if (favS.isNotEmpty()) {
                                item(key = "favsh") { RowTitle("Favoriten · Serien", null, null, onAll = null) }
                                item(key = "favs") { CardGridRows(favS, if (General.cardArt == 1) CARD_LAND else CARD_POSTER, if (favLive.isEmpty() && favM.isEmpty()) ContentFocus.req else null, onOpen) }
                            }
                            val favCats = CatFavs.list.filter { it in liveP }
                            if (favCats.isNotEmpty()) favCats.forEach { g ->
                                item(key = "fc$g") { ItemsRow(g, liveP[g].orEmpty(), cardW, onOpen) { onTab(3) } }
                            }
                        }
                        MenuLogic.MYLIST -> {
                            item(key = "mltop") { header(Modifier) }
                            val wm = watchList.filter { it.kind == Kind.MOVIE }
                            val ws = watchList.filter { it.kind == Kind.SERIES }
                            if (wm.isEmpty() && ws.isEmpty()) {
                                item(key = "mlh") { RowTitle("Meine Liste", null, null, onAll = null) }
                                item(key = "mle") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.Plus, "Noch nichts in deiner Liste", "Auf der Seite eines Films oder einer Serie „Meine Liste“ wählen.",
                                            action = "Filme entdecken", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(1) }
                                    }
                                }
                            }
                            if (wm.isNotEmpty()) {
                                item(key = "mlmh") { RowTitle("Meine Liste · Filme", null, null, onAll = null) }
                                item(key = "mlm") { CardGridRows(wm, CARD_POSTER, ContentFocus.req, onOpen) }
                            }
                            if (ws.isNotEmpty()) {
                                item(key = "mlsh") { RowTitle("Meine Liste · Serien", null, null, onAll = null) }
                                item(key = "mls") { CardGridRows(ws, CARD_POSTER, if (wm.isEmpty()) ContentFocus.req else null, onOpen) }
                            }
                        }
                        MenuLogic.VERLAUF -> {
                            // Zuletzt Angesehenes; bei Serien nur der Serientitel (der Verlauf speichert je Serie eine Zeile)
                            item(key = "vltop") { header(Modifier) }
                            val vm = hist.filter { it.kind == Kind.MOVIE }
                            val vs = hist.filter { it.kind == Kind.SERIES }
                            if (vm.isEmpty() && vs.isEmpty()) {
                                item(key = "vlh") { RowTitle("Verlauf", null, null, onAll = null) }
                                item(key = "vle") {
                                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                        EmptyState(AIcon.History, "Noch nichts angesehen", "Hier erscheinen die zuletzt gestarteten Filme und Serien.",
                                            action = "Filme entdecken", actionModifier = Modifier.focusRequester(ContentFocus.req)) { onTab(1) }
                                    }
                                }
                            }
                            if (vm.isNotEmpty()) {
                                item(key = "vlmh") { RowTitle("Verlauf · Filme", null, null, onAll = null) }
                                item(key = "vlm") { CardGridRows(vm, CARD_POSTER, ContentFocus.req, onOpen) }
                            }
                            if (vs.isNotEmpty()) {
                                item(key = "vlsh") { RowTitle("Verlauf · Serien", null, null, onAll = null) }
                                item(key = "vls") { CardGridRows(vs, CARD_POSTER, if (vm.isEmpty()) ContentFocus.req else null, onOpen) }
                            }
                        }
                        else -> {
                            item(key = "settop") { header(Modifier) }
                            item { SettingsHub(onAdd, reload) }
                        }
                    }
                } }
            }
            // Design-Einstellungen: Vorschau steht fest oben rechts, die Einstellungen scrollen darunter weg
            if (DesignPreview.visible) DesignPreviewPanel(Modifier.align(Alignment.TopEnd).padding(top = dpx(84), end = DS.PageX))
        }
        if (picker) {
            BackHandler { picker = false }
            ProfilePicker { id -> picker = false; if (id != Profiles.active) Profiles.switchTo(ctx, id) }
        }
        Sidebar(
            tab,
            { id -> scope.launch { states.getOrNull(id)?.scrollToItem(0) }; onTab(id) },
            Modifier.align(Alignment.CenterStart), sideFocus
        ) { inSidebar = it }
    }
}

@Composable
private fun EmptyHint(text: String) {
    // Runde 17: Hinweis mit Symbol, größer und ruhiger (statt kleiner grauer Zeile)
    Row(Modifier.padding(horizontal = DS.PageX, vertical = dpx(20)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
        AppIcon(AIcon.Info, Accent2, size = dpx(26))
        Text(text, color = Tok.TextMid, fontSize = spx(19))
    }
}

object PillMemory {
    var key: String? = null
}

@Composable
private fun ItemsRow(
    title: String, list: List<Item>, cardW: Int, onOpen: (Item) -> Unit,
    firstReq: FocusRequester? = null, style: Int = -1,
    upReq: FocusRequester? = null, leftReq: FocusRequester? = null, onAll: () -> Unit
) {
    // Nach „Alle anzeigen“ und Zurück: Fokus wieder auf den Link dieser Reihe
    val key = NavState.tab.toString() + "|" + title
    val headerReq = remember { FocusRequester() }
    val firstCard = firstReq ?: remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (PillMemory.key == key) {
            delay(250)
            runCatching { headerReq.requestFocus() }
            PillMemory.key = null
        }
    }
    // Bildart aus „Ansicht“ (Standard: Cover); Top 10 immer Cover
    val st = if (style >= 0) style else if (General.cardArt == 1) CARD_LAND else CARD_POSTER
    CardRow(title, list, st, onOpen, { PillMemory.key = key; onAll() }, RowStates.get("${NavState.tab}|$title"), headerReq, firstCard,
        upReq = upReq, leftReq = leftReq)
}

@Composable
private fun ResumeRow(list: List<Pair<Entry, Item>>, cardW: Int, onResume: (Entry, Item) -> Unit) {
    val ctx = LocalContext.current
    Column {
        val g = LocalCardGrid.current
        Row(Modifier.padding(start = dpx(29), top = dpx(26), end = dpx(28)), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
            Text("Weiterschauen", color = Color.White, fontSize = spx(23), fontWeight = FontWeight.Bold)
            Text("OK lange drücken = entfernen", color = Muted, fontSize = spx(13), modifier = Modifier.padding(bottom = dpx(3)))
        }
        LazyRow(
            state = RowStates.get("${NavState.tab}|resume"),
            contentPadding = PaddingValues(start = RowPadX, end = RowPadX, top = dpx(12), bottom = dpx(10)),
            horizontalArrangement = Arrangement.spacedBy(g.gap)
        ) {
            items(list) { pair ->
                val e = pair.first
                val item = pair.second
                val se = if (e.season > 0 || e.ep > 0) e.season to e.ep else ResumeLogic.seasonEp(e.title)
                val multi = item.kind == Kind.SERIES && item.episodes.any { it.season != (se?.first ?: 1) }
                val epText = se?.let { if (multi) "S${it.first} · Folge ${it.second}" else "Folge ${it.second}" }
                val sub = when {
                    item.kind != Kind.SERIES -> "bei " + (e.pos / 60000) + " Min"
                    e.next && epText != null -> "Als Nächstes: $epText"
                    epText != null -> epText
                    else -> e.title.removePrefix(item.title).trim()
                }
                WideCard(
                    item, sub, if (e.dur > 0) (e.pos.toFloat() / e.dur).coerceIn(0f, 1f) else 0f,
                    Modifier.width(g.wide).restoreFocus(item.id).favKeys {
                        WatchStore.remove(ctx, e.itemId)
                        android.widget.Toast.makeText(ctx, Lang.tr("Aus „Weiterschauen“ entfernt"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                ) { onResume(e, item) }
            }
        }
    }
}

/** Breite Karte im Querformat (Backdrop) mit Titel und Fortschrittsbalken, wie bei "Weiterschauen" in Netflix. */
@Composable
private fun WideCard(item: Item, sub: String?, progress: Float, modifier: Modifier, onClick: () -> Unit) {
    val m = rememberMeta(item)
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.06f) else 1f, label = "wide")
    val shape = RoundedCornerShape(DS.RadiusM)
    val img = m?.backdrop ?: m?.poster ?: item.logo
    Column(modifier.graphicsLayer { scaleX = sc; scaleY = sc }.billboardFocus(item) { f = it }.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusGlow(f, shape).clip(shape)
                .background(Brush.linearGradient(listOf(colorFor(item).copy(alpha = .75f), Surf2)))
                .border(DS.FocusBorder, if (f) Accent2 else Color.Transparent, shape)
        ) {
            if (img != null) AsyncImage(img, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xDD000000))))
            AppIcon(AIcon.Play, Color.White, Modifier.align(Alignment.Center), 30.dp)
            Text(raw = true, text = item.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = if (progress > 0f) 14.dp else 10.dp))
            if (progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Accent2))
            }
        }
        if (sub != null) Text(sub, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
}

/** Zweiter Knopf im großen Bild („Mehr Infos“ / „Zur Liste“): Ziel für Links aus „Alle anzeigen“ der ersten Reihe. */
object HeroFocus { val second = FocusRequester() }

/** Wie weit das große Bild nach oben geschoben ist (für den Parallax-Effekt); 0, wenn es nicht mehr oben steht. */
private fun heroScroll(st: LazyListState): Int = if (st.firstVisibleItemIndex == 0) st.firstVisibleItemScrollOffset else 0

@Composable
private fun Hero(
    featured: List<Item>, heroH: Dp, home: Boolean, onOpen: (Item) -> Unit, onPlay: (Item) -> Unit,
    header: @Composable (Modifier) -> Unit, scrollY: () -> Int = { 0 }, onFocusTop: () -> Unit
) {
    val ctx = LocalContext.current
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(featured.size, General.heroSec) {
        while (featured.size > 1 && General.heroSec > 0) { delay(General.heroSec * 1000L); if (!Billboard.active) idx = (idx + 1) % featured.size }
    }
    val item = featured[idx.coerceIn(0, featured.lastIndex)]

    // Bild über die ganze Breite (auch hinter der Kopfzeile), links und unten in den Hintergrund ausgeblendet.
    // Runde 14: ohne Pfeile; die Beschreibung hat mehr Breite (bis etwa zur Bildmitte) und wird nicht mehr abgeschnitten (max. 250 Zeichen).
    // Runde 17c: Parallax beim Scrollen (der Dauer-Zoom wurde wegen der Leistung entfernt)
    Box(Modifier.fillMaxWidth().height(heroH).background(Bg).clipToBounds().onFocusChanged { if (it.hasFocus) Billboard.cardFocus = false }) {
        Crossfade(item, animationSpec = tween(1100), label = "heroBg") { c ->
            val m = rememberMeta(c)
            // 17c: ruhiges Bild (kein Dauer-Zoom mehr, der den Fire TV ständig neu zeichnen ließ); nur leichter Parallax beim Scrollen
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationY = if (Look.motion == 0) 0f else scrollY() * 0.4f
            }) {
                m?.backdrop?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd) }
            }
        }
        // Verläufe: links und unten in den Hintergrund, oben dezent hinter der Kopfzeile; zusätzlich eine weiche Vignette
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.28f to Bg.copy(alpha = .9f), 0.5f to Bg.copy(alpha = .45f), 0.72f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg.copy(alpha = .55f), 0.22f to Color.Transparent, 0.62f to Color.Transparent, 0.88f to Bg.copy(alpha = .85f), 1f to Bg)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Bg.copy(alpha = .35f)), radius = 2200f)))
        header(Modifier.align(Alignment.TopStart))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = dpx(39), bottom = dpx(if (home) 34 else 26)).widthIn(max = dpx(780)),
            verticalArrangement = Arrangement.spacedBy(dpx(10))
        ) {
            Crossfade(item, animationSpec = tween(500), label = "heroText") { c ->
                // mit IMDb- und Rotten-Tomatoes-Wertung (wird beim ersten Mal nachgeladen, danach auf dem Gerät gemerkt)
                val m = rememberFullMeta(c, rememberMeta(c))
                Column(verticalArrangement = Arrangement.spacedBy(dpx(if (home) 10 else 8))) {
                    Kicker(Lang.tr(kindLabel(c.kind)) + (qualityOf(c)?.let { q -> "  ·  " + q.uppercase() } ?: ""))
                    Text(
                        raw = true, text = c.title, color = Tok.TextHi, fontSize = spx(if (home) 80 else 72), lineHeight = spx(if (home) 80 else 72),
                        fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                    HeroMetaLine(c, m, dots = !home)
                    m?.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(raw = true, text = TitleText.short(it, 250), color = Tok.TextMid, fontSize = spx(19), lineHeight = spx(27), maxLines = 3)
                    }
                }
            }
            Row(
                Modifier.padding(top = dpx(if (home) 12 else 8)).onFocusChanged { if (it.hasFocus) onFocusTop() },
                horizontalArrangement = Arrangement.spacedBy(dpx(16))
            ) {
                val h = if (home) 52 else 46
                HeroButton("Jetzt ansehen", AIcon.Play, true, Modifier.focusRequester(ContentFocus.req), h) { onPlay(item) }
                if (home) HeroButton("Mehr Infos", AIcon.Info, false, Modifier.focusRequester(HeroFocus.second), h = h) { onOpen(item) }
                else {
                    val inList = Library.isWatch(item.id)
                    HeroButton(if (inList) "In Meiner Liste" else "Zur Liste", if (inList) AIcon.Check else AIcon.Plus, false, Modifier.focusRequester(HeroFocus.second), h = h) { Library.toggleWatch(ctx, item.id) }
                }
            }
        }
        if (featured.size > 1) Row(
            Modifier.align(Alignment.BottomEnd).padding(end = dpx(if (home) 33 else 42), bottom = dpx(if (home) 30 else 20)),
            horizontalArrangement = Arrangement.spacedBy(dpx(12)), verticalAlignment = Alignment.CenterVertically
        ) {
            // Aktiver Punkt füllt sich bis zum nächsten Wechsel (zeigt, wann das Bild wechselt)
            val prog = remember(idx) { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(idx, General.heroSec) {
                if (General.heroSec > 0) prog.animateTo(1f, tween(General.heroSec * 1000, easing = androidx.compose.animation.core.LinearEasing))
            }
            featured.indices.forEach { i ->
                // 17c: Fortschritt nur gezeichnet (kein Neuaufbau in jedem Bild)
                if (i == idx) Box(Modifier.width(dpx(40)).height(dpx(7)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .3f))) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        scaleX = if (General.heroSec > 0) prog.value.coerceIn(0.08f, 1f) else 1f
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                    }.background(Accent1))
                }
                else Box(Modifier.width(dpx(7)).height(dpx(7)).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = .45f)))
            }
        }
    }
}

@Composable
private fun SkeletonHome() {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.05f, 0.14f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ska")
    val col = Color.White.copy(alpha = a)
    Column(Modifier.fillMaxSize().padding(horizontal = DS.PageX).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(DS.RadiusXl)).background(col))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            repeat(5) { Box(Modifier.width(150.dp).height(225.dp).clip(RoundedCornerShape(DS.RadiusM)).background(col)) }
        }
    }
}

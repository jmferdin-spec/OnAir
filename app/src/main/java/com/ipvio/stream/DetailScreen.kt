package com.ipvio.stream

import androidx.compose.ui.graphics.lerp
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import java.net.URLEncoder

fun openTrailer(ctx: Context, key: String?, title: String) {
    val uri = if (key != null) "https://www.youtube.com/watch?v=$key"
    else "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$title Trailer", "UTF-8")
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    } catch (e: Exception) {
        Toast.makeText(ctx, Lang.tr("Keine App zum Öffnen des Trailers gefunden"), Toast.LENGTH_LONG).show()
    }
}

fun openExternal(ctx: Context, url: String, title: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").putExtra("title", title)
    try {
        ctx.startActivity(Intent.createChooser(i, "Externer Player").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
    } catch (e: Exception) {
        Toast.makeText(ctx, Lang.tr("Kein externer Player installiert (z. B. VLC)"), Toast.LENGTH_LONG).show()
    }
}

private val QORDER = listOf("4K", "FHD", "HD")

/** Position der ersten Folge in der Liste der Detailseite (0 = Kopf, 1 = Staffeln/Abschnitte). */
/** Position des Folgen-Bereichs (Staffeln links, Folgen rechts) in der Seitenliste: 0 = Kopf, 1 = Folgen */
private const val EP_PANE = 1

/** Bis zu dieser Länge wird die Beschreibung immer ganz angezeigt (kein „Mehr lesen“). */
private const val FULL_TEXT_CHARS = 800

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun DetailScreen(
    item0: Item, all: List<Item>, onPlay: (String, String, Long) -> Unit,
    onPerson: (PersonRef) -> Unit, onOpenItem: (Item) -> Unit, onBack: () -> Unit,
    // Kino-Ansicht: eingebettet unter der Titelreihe (kein eigener Zurück-Knopf, kein automatischer Fokus, Hoch am Kopf gibt weiter)
    embedded: Boolean = false, autoFocus: Boolean = true, onTopUp: (() -> Unit)? = null,
    // false = nur Infos (Kino-Vorschau): keine Knöpfe, keine Staffel-Leiste
    showActions: Boolean = true
) {
    // OnAir: shows from a player_api login get their episodes (get_series_info) when opened
    val item by produceState(SeriesInfo.cached(item0), item0) { value = SeriesInfo.load(item0) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var heroFocused by remember { mutableStateOf(false) }
    LaunchedEffect(heroFocused) {
        if (heroFocused) repeat(4) { delay(70); listState.scrollToItem(0) }
    }
    BackHandler(enabled = !embedded, onBack = onBack)
    var meta by remember(item.id) { mutableStateOf(MetaRepo.cached(item)) }
    var metaDone by remember(item.id) { mutableStateOf(!MetaRepo.usable(item) || item.kind == Kind.LIVE) }
    LaunchedEffect(item.id) {
        MetaRepo.extended(ctx, item)?.let { meta = it }
        metaDone = true
    }
    val credits by produceState<Credits?>(null, meta?.tmdbId) {
        val m = meta
        value = if (m != null && m.tmdbId > 0) CreditsRepo.credits(ctx, m) else null
    }
    // Abgleich mit der ganzen Playlist im Hintergrund (beim ersten Mal wird dafür ein Index aufgebaut)
    val similar by produceState(emptyList<Item>(), credits, all) {
        val c = credits ?: return@produceState
        value = withContext(Dispatchers.Default) { LibMatch.matches(all, c.similar, 30).filter { s -> s.id != item.id } }
    }

    val seasons = remember(item) { item.episodes.map { it.season }.distinct().sorted() }
    // Fortsetzen-Punkt: die Seite öffnet bei dieser Staffel und dem Abschnitt mit dieser Folge
    val resume = WatchStore.entries.firstOrNull { it.itemId == item.id }
    val resumeSE = remember(resume) { resume?.let { r -> if (r.season > 0 || r.ep > 0) r.season to r.ep else ResumeLogic.seasonEp(r.title) } }
    // Zurück aus dem Player: bei genau der Folge öffnen, die zuletzt lief (einmalig), sonst beim Fortsetzen-Punkt
    val retSE = remember(item.id) { DetailReturn.take(item.id)?.takeIf { it.first > 0 || it.second > 0 } }
    val startSE = retSE ?: resumeSE
    var season by remember(item.id, seasons) { mutableIntStateOf(startSE?.first?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 1) }
    val eps = remember(item, season) { item.episodes.filter { it.season == season } }
    val progress = remember(item.id) { EpProgress.of(ctx, item.id) }

    val saved = remember(item.id) { VariantPref.get(ctx, item.id) }
    var prefQ by remember(item.id) { mutableStateOf(saved.first) }
    var prefLang by remember(item.id) { mutableStateOf(saved.second) }
    var prefLabel by remember(item.id) { mutableStateOf(saved.third) }
    val allVars = remember(item) { item.episodes.flatMap { it.variants } }
    val qualities = remember(item) { QORDER.filter { q -> allVars.any { it.quality == q } } }
    val langs = remember(item) { allVars.mapNotNull { it.lang }.distinct().sorted() }
    val labels = remember(item) { allVars.map { it.label }.filter { it.isNotBlank() }.distinct() }
    val multi = item.kind == Kind.SERIES && item.episodes.any { it.variants.size > 1 }
    // Filme: derselbe Film in anderen Qualitäten (eigene Einträge des Anbieters)
    val movieAlts by produceState(listOf(item), item, all) {
        if (item.kind == Kind.MOVIE) value = withContext(Dispatchers.Default) { Sources.alternatives(item, all) }
    }
    val movieQs = remember(movieAlts) { QORDER.filter { q -> movieAlts.any { it.quality == q } } }
    // Runde 14: Quelle eines Films fest wählen (gleicher Film mehrfach in der Playlist, z. B. HD und 4K)
    val movieVars: List<Variant> = remember(movieAlts) { Sources.asVariants(movieAlts) }

    fun save() = VariantPref.set(ctx, item.id, prefQ, prefLang, prefLabel)
    val qCap = remember { PlaybackPrefs.cap(ctx) }
    fun pick(ep: Episode) = pickVariant(ep, prefQ, prefLang, prefLabel, qCap)
    val playUrl = if (item.kind == Kind.SERIES) eps.firstOrNull()?.let { pick(it).url }
        else movieVars.firstOrNull { it.label == prefLabel }?.url ?: item.url

    val tmdbId = meta?.tmdbId
    val imdbId = meta?.imdbId
    val isTv = meta?.isTv == true
    // Lange Staffeln (One Piece & Co.): TMDB-Staffeln holen, um sie als Abschnitte zu nutzen
    val needTmdbSeasons = remember(item) {
        item.kind == Kind.SERIES && item.episodes.groupBy { it.season }.values.any { it.size > EpisodeLogic.LONG || it.first().num > 1 }
    }
    val tmdbSeasons by produceState(emptyList<EpisodeLogic.TmdbSeason>(), tmdbId, isTv) {
        val id = tmdbId
        if (needTmdbSeasons && isTv && id != null && id > 0) value = MetaRepo.tvSeasons(ctx, id)
    }
    val groups = remember(eps, season, tmdbSeasons) { EpisodeLogic.groups(eps, season, seasons.size, tmdbSeasons) }
    // Beim Staffelwechsel nach links: im letzten Abschnitt landen; „Gehe zu Folge“ in einer anderen Staffel: dort hinspringen
    val enterAtEnd = remember { booleanArrayOf(false) }
    val pendingNum = remember { intArrayOf(-1) }
    // Staffelwechsel mit Rechts: bei Folge 1 der neuen Staffel beginnen (nicht bei der zuletzt gesehenen)
    val startFirst = remember { booleanArrayOf(false) }
    // Position in der Staffel, bei der die Liste steht – der angezeigte Abschnitt ergibt sich daraus
    var anchor by remember(item.id, season) {
        mutableIntStateOf(
            when {
                pendingNum[0] > 0 -> EpisodeLogic.indexOfNum(eps, pendingNum[0]).also { pendingNum[0] = -1 }
                enterAtEnd[0] -> (groups.lastOrNull()?.from ?: 0).also { enterAtEnd[0] = false }
                startFirst[0] -> 0.also { startFirst[0] = false }
                startSE != null && startSE.first == season -> EpisodeLogic.indexOfNum(eps, startSE.second)
                else -> 0
            }.coerceAtLeast(0)
        )
    }
    val gi = EpisodeLogic.groupOf(groups, anchor)
    val group = groups.getOrNull(gi)
    val shown = remember(eps, group) { if (group == null) emptyList() else eps.subList(group.from, group.to + 1) }
    // Infos aus der passenden TMDB-Staffel (bei fortlaufender Nummer nicht Staffel 1 der Playlist)
    val metaSeason = group?.tmdbSeason ?: season
    // Neu bei anderem TMDB-Treffer (die Detailseite kann einen falschen gespeicherten Treffer beim Öffnen korrigieren)
    var epCache by remember(item.id, tmdbId) { mutableStateOf<Map<Int, List<EpMeta>>>(emptyMap()) }
    var epLoading by remember(item.id, tmdbId) { mutableStateOf(false) }
    LaunchedEffect(tmdbId, imdbId, metaSeason) {
        if (item.kind == Kind.SERIES && isTv && tmdbId != null && tmdbId > 0 && metaSeason >= 0 && epCache[metaSeason] == null) {
            epLoading = true
            // IMDb-Bewertungen je Folge nur bei gleicher Zählung (IMDb teilt lange Anime anders auf)
            val sameCount = group == null || (group.offset == 0 && metaSeason == season)
            val res = MetaRepo.season(tmdbId, metaSeason, if (sameCount) imdbId else null)
            epCache = epCache + (metaSeason to res)
            epLoading = false
        }
    }
    val epMetas = epCache[metaSeason] ?: emptyList()
    fun metaOf(ep: Episode): EpMeta? {
        val g = group ?: return null
        if (g.tmdbSeason < 0) return null
        val n = EpisodeLogic.tmdbNum(g, ep.num)
        return epMetas.firstOrNull { it.num == n } ?: if (g.offset > 0) epMetas.firstOrNull { it.num == ep.num } else null
    }

    val playFocus = remember { FocusRequester() }
    // Zurück von einer Person oder einem ähnlichen Titel: dorthin zurück, nicht zu „Fortsetzen“
    var topMoreFocused by remember { mutableStateOf(false) }
    var topRowFocused by remember { mutableStateOf(false) }
    val memKey = remember(item.id) { DetailFocus.last.remove(item.id) }
    var restored by remember(item.id) { mutableStateOf(memKey == null) }
    val restoreReq = remember { FocusRequester() }
    val castState = rememberLazyListState()
    val simState = rememberLazyListState()
    LaunchedEffect(autoFocus) { if (autoFocus && memKey == null && retSE == null) runCatching { playFocus.requestFocus() } }
    LaunchedEffect(memKey, similar, credits) {
        if (memKey == null || restored) return@LaunchedEffect
        val isSim = memKey.startsWith("s:")
        val id = memKey.drop(2)
        val idx = if (isSim) similar.indexOfFirst { it.id == id } else credits?.cast?.indexOfFirst { it.id.toString() == id } ?: -1
        if (idx >= 0) {
            listState.scrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
            repeat(3) { withFrameNanos { } }
            (if (isSim) simState else castState).scrollToItem(idx)
            repeat(3) { withFrameNanos { } }
            runCatching { restoreReq.requestFocus() }
            restored = true
        }
    }
    // Findet sich das Ziel nicht (Liste hat sich geändert), nach kurzer Zeit normal auf „Abspielen“
    LaunchedEffect(memKey) {
        if (memKey == null) return@LaunchedEffect
        delay(5000)
        if (!restored) { restored = true; if (autoFocus) runCatching { playFocus.requestFocus() } }
    }

    // Beschreibung: standardmäßig 4 Zeilen, mit "Mehr lesen" aufklappbar
    var descExpanded by remember(item.id) { mutableStateOf(false) }
    var descOverflow by remember(item.id) { mutableStateOf(false) }

    // Staffel-, Abschnitts- und Episoden-Fokus (Index = Position in der Staffel)
    val seasonReq = remember { FocusRequester() }
    val seasonRowState = rememberLazyListState()
    val groupReq = remember { FocusRequester() }
    val groupRowState = rememberLazyListState()
    val epReqs = remember(item.id) { HashMap<Int, FocusRequester>() }
    val epFocusIdx = remember(item.id) { intArrayOf(0) }
    // Ziel für den nächsten Fokuswechsel (-1 = erste Folge des Abschnitts). Getrennt von epFocusIdx, weil beim
    // Staffelwechsel die alte Zeile verschwindet und Compose den Fokus kurz auf irgendeine neue Zeile setzt.
    val targetIdx = remember(item.id) { intArrayOf(-1) }
    var focusedIdx by remember(item.id) { mutableIntStateOf(-1) }
    var refocusEp by remember { mutableStateOf(false) }
    // Folgenliste (rechts im Folgen-Bereich); der Bereich selbst ist so hoch wie das Bild, die Folgen scrollen darin
    val epListState = rememberLazyListState()
    val paneH = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp - 72).dp.coerceAtLeast(360.dp)
    var focusTick by remember { mutableIntStateOf(0) }
    var jumpOpen by remember(item.id) { mutableStateOf(false) }
    var jumpText by remember(item.id) { mutableStateOf("") }
    val jumpReq = remember { FocusRequester() }

    fun epReq(i: Int): FocusRequester = epReqs.getOrPut(i) { FocusRequester() }

    fun switchSeason(delta: Int) {
        val ni = seasons.indexOf(season) + delta
        if (ni in seasons.indices) {
            enterAtEnd[0] = delta < 0
            startFirst[0] = delta > 0
            season = seasons[ni]
            // immer bei der ersten Folge (des Abschnitts) landen, nicht an der alten Position
            targetIdx[0] = -1
            refocusEp = true
        }
    }

    /** Links/Rechts in der Folgenliste: nächster Abschnitt (bei dessen erster Folge); am Ende der Staffel weiter zur nächsten Staffel. */
    fun switchGroup(delta: Int) {
        val ni = gi + delta
        if (ni in groups.indices) {
            val g = groups[ni]
            targetIdx[0] = g.from
            anchor = g.from
            refocusEp = true
        } else switchSeason(delta)
    }

    fun focusActiveSeason() {
        scope.launch {
            seasonRowState.scrollToItem((seasons.indexOf(season) - 2).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { seasonReq.requestFocus() }
        }
    }

    fun focusActiveGroup() {
        scope.launch {
            groupRowState.scrollToItem((gi - 2).coerceAtLeast(0))
            withFrameNanos { }
            withFrameNanos { }
            runCatching { groupReq.requestFocus() }
        }
    }

    /** „Gehe zu Folge“: Abschnitt wechseln und die Folge fokussieren (auch in einer anderen Staffel). */
    fun doJump() {
        val n0 = jumpText.trim().toIntOrNull() ?: return
        jumpOpen = false
        jumpText = ""
        // Nummer in der Staffel, in einer anderen Staffel oder als Gesamtnummer über alle Staffeln (z. B. 100)
        val tgt = EpisodeLogic.locate(item.episodes, season, n0)
        if (tgt == null) {
            Toast.makeText(ctx, Lang.tr("Es gibt nur ${item.episodes.size} Folgen"), Toast.LENGTH_SHORT).show()
            return
        }
        val (tSeason, n) = tgt
        if (tSeason != season) {
            pendingNum[0] = n
            targetIdx[0] = item.episodes.filter { it.season == tSeason }.sortedBy { it.num }.indexOfFirst { it.num == n }.coerceAtLeast(0)
            season = tSeason
            refocusEp = true
            focusTick++
            return
        }
        val idx = EpisodeLogic.indexOfNum(eps, n)
        if (idx < 0) return
        anchor = idx
        targetIdx[0] = idx
        refocusEp = true
        focusTick++
    }

    LaunchedEffect(jumpOpen) { if (jumpOpen) { delay(80); runCatching { jumpReq.requestFocus() } } }

    // Zurück aus dem Player: Folge markieren, die zuletzt lief (Liste und Abschnitt stehen schon dort)
    LaunchedEffect(retSE) {
        if (retSE == null) return@LaunchedEffect
        delay(120)
        val idx = EpisodeLogic.indexOfNum(eps, retSE.second)
        if (idx >= 0 && eps.isNotEmpty()) { targetIdx[0] = idx; refocusEp = true; focusTick++ }
        else runCatching { playFocus.requestFocus() }
    }

    LaunchedEffect(season, gi, focusTick) {
        seasonRowState.scrollToItem((seasons.indexOf(season) - 2).coerceAtLeast(0))
        epListState.scrollToItem(0)
        if (groups.size > 1) groupRowState.scrollToItem((gi - 2).coerceAtLeast(0))
        if (refocusEp) {
            refocusEp = false
            val g = group ?: return@LaunchedEffect
            val t = targetIdx[0]
            targetIdx[0] = -1
            val idx = (if (t < 0) g.from else t).coerceIn(g.from, g.to)
            epFocusIdx[0] = idx
            val row = idx - g.from
            withFrameNanos { }
            // Folgen-Bereich ans Bildschirmende der Seite stellen und die Zielfolge in der Folgenliste sichtbar machen
            listState.scrollToItem(EP_PANE)
            epListState.scrollToItem((row - 1).coerceAtLeast(0))
            // Mehrmals versuchen: die neue Zeile ist evtl. erst nach ein, zwei Bildern da
            focusedIdx = -1
            for (k in 0 until 10) {
                withFrameNanos { }
                if (focusedIdx == idx) break
                runCatching { epReq(idx).requestFocus() }
                delay(30)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Runde 15: Aufbau wie das große Bild der Startseite (Bild rechts, links und unten in den Hintergrund ausgeblendet)
        // 17c: ruhiges Hintergrundbild (der Dauer-Zoom kostete auf dem Fire TV zu viel Rechenzeit)
        meta?.backdrop?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopEnd) }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.3f to Bg.copy(alpha = .92f), 0.55f to Bg.copy(alpha = .5f), 0.8f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg.copy(alpha = .5f), 0.2f to Color.Transparent, 0.55f to Bg.copy(alpha = .78f), 0.8f to Bg)))
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(horizontal = 28.dp, vertical = 36.dp)) {
            item {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = dpx(if (embedded) 400 else 560))
                        .onPreviewKeyEvent { ev ->
                            if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp) {
                                if (onTopUp != null) {
                                    // Oberstes Bedienelement im Kopf („Mehr lesen“, sonst die erste Knopfreihe): Titelreihe wieder
                                    // herunterschieben. Das wird hier selbst entschieden, nicht über die Fokus-Suche (die fand
                                    // unsichtbare Ziele in der verschobenen Titelreihe).
                                    val moreShown = showActions && (descOverflow || descExpanded) && (descExpanded || (meta?.overview?.length ?: 0) > FULL_TEXT_CHARS)
                                    if (topMoreFocused || (!moreShown && topRowFocused)) { onTopUp(); return@onPreviewKeyEvent true }
                                    return@onPreviewKeyEvent false
                                }
                                scope.launch { listState.scrollToItem(0) }
                            }
                            false
                        }
                        .onFocusChanged { heroFocused = it.hasFocus }
                ) {
                    Column(Modifier.align(if (embedded && !showActions) Alignment.TopStart else Alignment.BottomStart).widthIn(max = dpx(980)).padding(bottom = dpx(18)), verticalArrangement = Arrangement.spacedBy(dpx(12))) {
                        // Runde 17b: Überzeile mit Leuchtbalken, riesiger Titel (Entwurf „Cinematic Remake“, Bild 2)
                        Row(horizontalArrangement = Arrangement.spacedBy(dpx(14)), verticalAlignment = Alignment.CenterVertically) {
                            Kicker(kindLabel(item.kind), raw = false)
                            qualityOf(item)?.let { PreviewQualityBadge(it) }
                        }
                        Text(raw = true, text = item.title, color = Tok.TextHi, fontSize = spx(84), lineHeight = spx(84), fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-1.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        HeroMetaLine(item, meta, dots = false)
                        // Film angefangen: kleiner Fortschrittsbalken mit Restzeit
                        if (item.kind != Kind.SERIES && resume != null && resume.dur > 0 && !ResumeLogic.finished(resume.pos, resume.dur)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(10))) {
                                Box(Modifier.width(dpx(180)).height(dpx(4)).clip(RoundedCornerShape(50)).background(Color(0x40FFFFFF))) {
                                    Box(Modifier.fillMaxWidth((resume.pos.toFloat() / resume.dur).coerceIn(0f, 1f)).fillMaxHeight().background(Accent2))
                                }
                                Text("noch " + ((resume.dur - resume.pos) / 60000).coerceAtLeast(1) + " Min", color = Muted, fontSize = spx(14))
                            }
                        }
                        if (meta?.overview == null && !metaDone) {
                            // Lade-Skelett statt kurzzeitig "Keine Beschreibung gefunden."
                            val sk = shimmerColor()
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SkeletonBox(Modifier.fillMaxWidth(0.92f).height(16.dp), sk)
                                SkeletonBox(Modifier.fillMaxWidth(0.86f).height(16.dp), sk)
                                SkeletonBox(Modifier.fillMaxWidth(0.6f).height(16.dp), sk)
                            }
                        } else Text(
                            raw = meta?.overview != null,
                            text = meta?.overview ?: if (MetaRepo.usable(item)) "Keine Beschreibung gefunden." else "Tipp: Unter Einstellungen > API-Schlüssel einen eigenen TMDB-Schlüssel eintragen, dann erscheinen hier Beschreibung, Cover und Bewertungen.",
                            color = Tok.TextMid, fontSize = spx(17), lineHeight = spx(25),
                            // Kurze und mittlere Beschreibungen (bis ca. 800 Zeichen) werden ganz gezeigt, ohne „Mehr lesen“.
                            // In der Kino-Vorschau (ohne Knöpfe) sind es höchstens 6 Zeilen.
                            maxLines = if (descExpanded || (showActions && (meta?.overview?.length ?: 0) <= FULL_TEXT_CHARS)) Int.MAX_VALUE
                                else if (!showActions) 8 else 4,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { if (!descExpanded) descOverflow = it.hasVisualOverflow }
                        )
                        if (showActions && (descOverflow || descExpanded) && (descExpanded || (meta?.overview?.length ?: 0) > FULL_TEXT_CHARS)) {
                            DetailPill(if (descExpanded) "Weniger" else "Mehr lesen", Modifier.onFocusChanged { topMoreFocused = it.isFocused }, trailing = if (descExpanded) AIcon.Up else AIcon.Down) { descExpanded = !descExpanded }
                        }
                        if (showActions) {
                        Spacer(Modifier.height(4.dp))
                        // Knöpfe im Stil des großen Bildes, kleiner (Runde 15). Innenabstand: der vergrößerte Knopf wird sonst abgeschnitten.
                        // Zwei Reihen: oben Abspielen/Fortsetzen/Von vorn, darunter Liste, Favorit, Trailer, Extern. So wird nichts mehr
                        // am rechten Rand abgeschnitten. Innenabstand: der vergrößerte Knopf wird sonst angeschnitten.
                        Column(
                            Modifier.offset(x = (-8).dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(dpx(12))
                        ) {
                            val bh = 44
                            Row(Modifier.onFocusChanged { topRowFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                                if (resume != null) {
                                    val oneSeason = seasons.size <= 1
                                    val label = when {
                                        item.kind != Kind.SERIES -> "Fortsetzen · " + (resume.pos / 60000) + " Min"
                                        resumeSE == null -> "Fortsetzen"
                                        resume.next -> "Weiter mit " + (if (oneSeason) "Folge ${resumeSE.second}" else "S${resumeSE.first} · Folge ${resumeSE.second}")
                                        else -> "Fortsetzen · " + (if (oneSeason) "Folge ${resumeSE.second}" else "S${resumeSE.first} · Folge ${resumeSE.second}")
                                    }
                                    HeroButton(label, AIcon.Play, true, Modifier.focusRequester(playFocus), bh) {
                                        // aktuelle Adresse aus der Playlist (die gespeicherte kann nach einer Aktualisierung veraltet sein)
                                        val t = WatchStore.target(ctx, item, resume)
                                        onPlay(t.second, t.first, resume.pos)
                                    }
                                    HeroButton("Von vorn", AIcon.Rewind, false, Modifier, bh) { playUrl?.let { onPlay(it, item.title + (if (item.kind == Kind.SERIES) " S" + season + "E" + (eps.firstOrNull()?.num ?: 1) else ""), 0L) } }
                                } else {
                                    // Gesehener Film: „Erneut abspielen“ statt „Abspielen“
                                    val seenMovie = item.kind == Kind.MOVIE && EpProgress.of(ctx, item.id)[ResumeLogic.key(0, 0)]?.done == true
                                    HeroButton(if (seenMovie) "Erneut abspielen" else "Abspielen", if (seenMovie) AIcon.Rewind else AIcon.Play, true, Modifier.focusRequester(playFocus), bh) {
                                        playUrl?.let { onPlay(it, item.title + (if (item.kind == Kind.SERIES) " S" + season + "E" + (eps.firstOrNull()?.num ?: 1) else ""), 0L) }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(dpx(12))) {
                                val inList = Library.isWatch(item.id)
                                HeroButton(if (inList) "In Meiner Liste" else "Meine Liste", if (inList) AIcon.Check else AIcon.Plus, false, Modifier, bh) { Library.toggleWatch(ctx, item.id) }
                                // Runde 14: Filme und Serien als Favorit (erscheinen unter „Favoriten“)
                                val fav = Library.isFav(item.id)
                                HeroButton(if (fav) "Favorit" else "Zu Favoriten", if (fav) AIcon.HeartFilled else AIcon.Heart, false, Modifier, bh) { Library.toggleFav(ctx, item.id) }
                                HeroButton("Trailer", AIcon.Play, false, Modifier, bh) { openTrailer(ctx, meta?.trailerKey, item.title) }
                                HeroButton("Extern", AIcon.External, false, Modifier, bh) { playUrl?.let { openExternal(ctx, it, item.title) } }
                            }
                        }
                        if (item.kind == Kind.MOVIE && movieAlts.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            // Quelle fest wählen (Vorrang vor der Qualität) oder automatisch passend zum Gerät
                            val so = listOf("Automatisch") + movieVars.map { it.label }
                            DropdownPill("Quelle", prefLabel ?: "Automatisch", so, so.indexOf(prefLabel).coerceAtLeast(0), active = prefLabel != null) {
                                prefLabel = if (it == 0) null else so[it]; save()
                            }
                            if (movieQs.size > 1 && prefLabel == null) {
                                val qo = listOf("Automatisch (passend zum Gerät)") + movieQs
                                DropdownPill("Qualität", prefQ ?: "Automatisch", qo, qo.indexOf(prefQ).coerceAtLeast(0), active = prefQ != null) {
                                    prefQ = if (it == 0) null else qo[it]; save()
                                }
                            }
                            Text("${movieAlts.size} Quellen · bei Störung wird automatisch gewechselt", color = Muted, fontSize = 13.sp)
                        }
                        if (item.kind != Kind.SERIES && DeadLinks.isDead(item.url)) IconLabel(
                            AIcon.Warning,
                            if (movieAlts.size > 1) "Diese Quelle war zuletzt nicht erreichbar – es wird eine andere genommen."
                            else "Diese Quelle war zuletzt nicht erreichbar.",
                            DS.Gold, 14.sp, iconSize = 16.dp, maxLines = 3
                        )
                        if (multi) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (qualities.size > 1) {
                                val qo = listOf("Automatisch (passend zum Gerät)") + qualities
                                DropdownPill("Qualität", prefQ ?: "Automatisch", qo, qo.indexOf(prefQ).coerceAtLeast(0), active = prefQ != null) {
                                    prefQ = if (it == 0) null else qo[it]; save()
                                }
                            }
                            if (langs.size > 1) {
                                val lo = listOf("Automatisch") + langs
                                DropdownPill("Sprache", prefLang ?: "Automatisch", lo, lo.indexOf(prefLang).coerceAtLeast(0), active = prefLang != null) {
                                    prefLang = if (it == 0) null else lo[it]; save()
                                }
                            }
                            if (labels.size > 1) {
                                val vo = listOf("Automatisch") + labels
                                DropdownPill("Quelle", prefLabel ?: "Automatisch", vo, vo.indexOf(prefLabel).coerceAtLeast(0), active = prefLabel != null) {
                                    prefLabel = if (it == 0) null else vo[it]; save()
                                }
                            }
                            Text("gilt für alle Folgen", color = Muted, fontSize = 13.sp)
                        }
                        credits?.directors?.takeIf { it.isNotEmpty() }?.let { ds ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Regie", color = Muted, fontSize = 14.sp)
                                ds.take(3).forEach { d -> DetailPill(d.name) { onPerson(PersonRef(d.id, d.name, d.photo, item.id)) } }
                            }
                        }
                        }
                    }
                }
            }
            if (item.kind == Kind.SERIES) item(key = "eppane") {
                // Folgen-Bereich (Runde 16): links die Staffeln mit Markierung der gezeigten Staffel, rechts die Folgen.
                // Rechts in der Folgenliste = nächste Staffel, Links = zur Staffelauswahl.
                Row(
                    Modifier.fillMaxWidth().height(paneH).padding(top = dpx(12))
                        .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(EP_PANE) } }
                        .focusProperties { enterTo { seasonReq } }
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(dpx(28))
                ) {
                    LazyColumn(
                        Modifier.width(dpx(250)).fillMaxHeight(),
                        state = seasonRowState,
                        verticalArrangement = Arrangement.spacedBy(dpx(6)),
                        contentPadding = PaddingValues(vertical = dpx(8))
                    ) {
                        items(seasons, key = { it }) { sn ->
                            val cnt = remember(item.id, sn) { item.episodes.count { it.season == sn } }
                            SeasonEntry(
                                sn, cnt, sn == season,
                                if (sn == season) Modifier.focusRequester(seasonReq) else Modifier,
                                onRight = {
                                    // Rechts oder OK: Staffel zeigen und zur ersten Folge springen (gleiche Staffel: zur letzten Position)
                                    if (sn != season) { enterAtEnd[0] = false; startFirst[0] = true; season = sn; targetIdx[0] = -1 }
                                    else targetIdx[0] = epFocusIdx[0]
                                    refocusEp = true
                                    focusTick++
                                }
                            )
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            Modifier.padding(bottom = dpx(6)),
                            horizontalArrangement = Arrangement.spacedBy(dpx(16)), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (epLoading) "lade Infos …" else "Rechts = nächste Staffel  ·  Links = Staffeln",
                                color = Muted, fontSize = spx(14)
                            )
                            if (item.episodes.size > EpisodeLogic.LONG) DetailPill("Zu Folge springen", icon = AIcon.Down) { jumpOpen = !jumpOpen }
                        }
                        // Lange Staffel: Abschnitte (TMDB-Staffeln oder Blöcke zu 50 Folgen) statt einer endlosen Liste
                        if (groups.size > 1) {
                            LazyRow(
                                Modifier.offset(x = (-10).dp),
                                state = groupRowState,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                itemsIndexed(groups) { k, g ->
                                    DetailPill(
                                        EpisodeLogic.label(g),
                                        if (k == gi) Modifier.focusRequester(groupReq) else Modifier,
                                        light = k == gi
                                    ) { anchor = g.from; epFocusIdx[0] = g.from }
                                }
                            }
                        }
                        if (jumpOpen) Row(
                            Modifier.padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Field(
                                jumpText, { v -> jumpText = v.filter { it.isDigit() }.take(4) }, "Folgennummer",
                                Modifier.width(280.dp), inner = Modifier.focusRequester(jumpReq), number = true, onDone = { doJump() }
                            )
                            DetailPill("Springen", light = true) { doJump() }
                            DetailPill("Abbrechen") { jumpOpen = false; jumpText = "" }
                        }
                        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = epListState, contentPadding = PaddingValues(bottom = dpx(40))) {
                            itemsIndexed(shown, key = { _, ep -> "e${ep.season}_${ep.num}" }) { j, ep ->
                                val i = (group?.from ?: 0) + j
                                val em = metaOf(ep)
                                val v = pick(ep)
                                EpisodeRow(
                                    ep, em, v, progress[ResumeLogic.key(ep.season, ep.num)],
                                    Modifier
                                        .focusRequester(epReq(i))
                                        .onPreviewKeyEvent { ev ->
                                            if (ev.type != KeyEventType.KeyDown) false
                                            else when (ev.key) {
                                                // Links: zur Staffelauswahl; Rechts: nächster Abschnitt, am Ende der Staffel die nächste Staffel
                                                Key.DirectionLeft -> { if (ev.nativeKeyEvent.repeatCount == 0) { if (groups.size > 1 && gi > 0) switchGroup(-1) else focusActiveSeason() }; true }
                                                Key.DirectionRight -> { if (ev.nativeKeyEvent.repeatCount == 0) switchGroup(1); true }
                                                Key.DirectionUp -> if (j == 0 && groups.size > 1) { focusActiveGroup(); true } else false
                                                else -> false
                                            }
                                        },
                                    onFocus = { epFocusIdx[0] = i; focusedIdx = i }
                                ) { onPlay(v.url, item.title + " S" + ep.season + "E" + ep.num, 0L) }
                            }
                        }
                    }
                }
            }
            credits?.let { c ->
                if (c.cast.isNotEmpty()) item(key = "cast") { CastRow(c.cast, castState, if (memKey?.startsWith("c:") == true) memKey.drop(2).toIntOrNull() else null, restoreReq) { p -> DetailFocus.last[item.id] = "c:" + p.id; onPerson(PersonRef(p.id, p.name, p.photo, item.id)) } }
            }
            if (credits == null && (meta?.tmdbId ?: 0) > 0 && CreditsRepo.lastError != null) item(key = "cerr") {
                Text("Besetzung nicht verfügbar: ${CreditsRepo.lastError}", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 24.dp))
            }
            if (similar.isNotEmpty()) item(key = "similar") { SimilarRow("Ähnliche Titel in deiner Playlist", similar, simState, if (memKey?.startsWith("s:") == true) memKey.drop(2) else null, restoreReq) { i -> DetailFocus.last[item.id] = "s:" + i.id; onOpenItem(i) } }
            if (MetaRepo.enabled()) item {
                Text("Dieses Produkt verwendet die TMDB-API, wird aber nicht von TMDB unterstützt oder zertifiziert.",
                    color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 32.dp))
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, em: EpMeta?, v: Variant, prog: ResumeLogic.EpPos?, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(16))
    // Runde 17b: markierte Folge schwebt hervor (Glas, Schatten, etwas größer) statt weißem Rahmen; die anderen bleiben ruhig
    val sc by androidx.compose.animation.core.animateFloatAsState(if (f) focusScale(1.02f) else 1f, label = "ep")
    Row(
        modifier.fillMaxWidth().padding(vertical = dpx(6))
            .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .then(if (f) Modifier.shadow(dpx(24), shape, ambientColor = Tok.Lift, spotColor = Tok.Lift) else Modifier)
            .clip(shape).background(if (f) Color(0x1FFFFFFF) else Color(0x08FFFFFF))
            .border(1.dp, if (f) Color(0x33FFFFFF) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(onClick = onClick)
            .padding(dpx(14)),
        horizontalArrangement = Arrangement.spacedBy(dpx(22)), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(dpx(300)).height(dpx(169)).clip(RoundedCornerShape(dpx(12)))
                .background(Brush.linearGradient(listOf(lerp(Surf2, Accent1, 0.22f), Surf2))),
            contentAlignment = Alignment.Center
        ) {
            if (em?.still != null) AsyncImage(em.still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("E${ep.num}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            // Markiert: runder Abspielknopf in der Mitte
            if (f) Box(Modifier.align(Alignment.Center).size(dpx(58)).clip(RoundedCornerShape(50)).background(Color(0xEBF4F6FB)), contentAlignment = Alignment.Center) {
                AppIcon(AIcon.Play, Color(0xFF05070D), size = dpx(26))
            }
            // Fortschritt: Balken unten, gesehen = Haken oben rechts
            if (prog != null && prog.done) Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) { Badge("Gesehen", DS.Scrim, Accent2, AIcon.Check) }
            else if (prog != null && prog.fraction > 0.02f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                Box(Modifier.fillMaxWidth(prog.fraction).fillMaxHeight().background(Accent2))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(raw = true, text = "${ep.num}. ${em?.name ?: ep.title}", color = if (f) Tok.TextHi else Tok.TextMid, fontSize = spx(if (f) 26 else 24), fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                v.quality?.let { QualityBadge(it) }
                em?.imdb?.let { Badge("IMDb $it", Color(0xFFF5C518), Color.Black) }
                if (em?.imdb == null) em?.rating?.let { RatingBadge(it, Color(0x26FFFFFF)) }
                em?.runtime?.let { Badge("$it Min") }
            }
            em?.air?.let { Text(raw = true, text = it, color = Muted, fontSize = spx(16)) }
            em?.overview?.let { Text(raw = true, text = it, color = if (f) Tok.TextMid else Tok.TextLow, fontSize = spx(18), lineHeight = spx(25), maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Eintrag der Staffelliste: Balken links und Folgenzahl bei der gezeigten Staffel, heller Rahmen bei Fokus. */
@Composable
private fun SeasonEntry(season: Int, count: Int, selected: Boolean, modifier: Modifier, onRight: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(dpx(12))
    Row(
        modifier.fillMaxWidth().clip(shape)
            // Runde 17b: gezeigte Staffel mit grünem Schimmer, markierte als helles Glas (kein weißer Rahmen)
            .background(
                if (f) Brush.horizontalGradient(listOf(Color(0x2EFFFFFF), Color(0x14FFFFFF)))
                else if (selected) Brush.horizontalGradient(listOf(Accent1.copy(alpha = 0.18f), Color.Transparent))
                else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
            )
            .onFocusChanged { f = it.isFocused }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionRight) { if (ev.nativeKeyEvent.repeatCount == 0) onRight(); true } else false
            }
            .clickable(onClick = onRight)
            .padding(horizontal = dpx(14), vertical = dpx(12)),
        horizontalArrangement = Arrangement.spacedBy(dpx(14)), verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(dpx(5)).height(dpx(if (selected) 46 else 24))
                .then(if (selected) Modifier.shadow(dpx(10), RoundedCornerShape(50), ambientColor = Accent1, spotColor = Accent1) else Modifier)
                .clip(RoundedCornerShape(50)).background(if (selected) Accent1 else Color.Transparent)
        )
        Column {
            Text("Staffel $season", color = if (selected || f) Tok.TextHi else Tok.TextLow, fontSize = spx(26), fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            if (selected) Text(text = if (count == 1) "1 Folge" else "$count Folgen", color = Muted, fontSize = spx(15))
        }
    }
}

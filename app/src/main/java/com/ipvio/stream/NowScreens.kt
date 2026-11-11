@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * OnAir Phase 4: the Movies, Shows, Sports and Events screens – what is on right now, from [OnNow] snapshots
 * (cached 60 s, computed off the main thread, local database only). Rules in [NowScreenLogic].
 *
 * - The data refreshes when the screen opens and once a minute; progress bars tick every 30 s and finished programmes
 *   drop off at once.
 * - OK on a card opens a small detail panel with Watch (one source) or a source picker (merged duplicates, best quality
 *   first and focused). Watch tunes the channel in the player; Back from the player returns here, on the same card.
 * - Back returns to the home screen with focus on the tile ([OnAirNav.tile]).
 *
 * [onWatch] gets the chosen channel and the channels of this screen in order (the player's zapping list).
 */
@Composable
fun NowScreen(
    tile: OnAirTile,
    liveAll: List<Item>,
    onWatch: (OnNowLogic.Chan, List<OnNowLogic.Chan>) -> Unit,
    onGuide: () -> Unit,
    onBack: () -> Unit,
    /** Multiview picker: room kept free on the right (the running stream's small window sits there). */
    reserveEnd: androidx.compose.ui.unit.Dp = 0.dp,
    /** Button in the detail panel ("Ansehen", in the Multiview picker "Auswählen"). */
    watchLabel: String = "Ansehen"
) {
    val ctx = LocalContext.current
    var detail by remember { mutableStateOf<OnNowItem?>(null) }
    var detailChan by remember { mutableStateOf<OnNowLogic.Chan?>(null) }   // PPV channel without guide data
    fun closePanel() { detail = null; detailChan = null; OnAirNav.panelClosed++ }
    BackHandler { if (detail != null || detailChan != null) closePanel() else onBack() }

    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowSec = System.currentTimeMillis() / 1000 } }
    val snap by produceState<OnNow.Snapshot?>(null, liveAll) {
        if (tile == OnAirTile.EVENTS) EventRefresh.maybe(ctx, liveAll)
        while (true) {
            value = OnNow.snapshot(ctx, liveAll)
            nowSec = System.currentTimeMillis() / 1000
            delay(OnNow.TTL_MS)
        }
    }

    Box(Modifier.fillMaxSize().background(Bg)) {
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp + reserveEnd, top = 28.dp)) {
            NowHeader(tile, nowSec)
            Spacer(Modifier.height(14.dp))
            val s = snap
            if (s == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(Accent2, Modifier.size(48.dp)) }
            } else when (tile) {
                OnAirTile.MOVIES, OnAirTile.SHOWS -> CardsScreen(tile, s, nowSec, onOpen = { detail = it }, onGuide = onGuide)
                OnAirTile.SPORTS -> SportsScreen(s, nowSec, panelOpen = detail != null, onOpen = { detail = it }, onGuide = onGuide)
                OnAirTile.EVENTS -> EventsScreen(s, nowSec, onOpen = { detail = it }, onOpenChannel = { detailChan = it }, onGuide = onGuide)
                OnAirTile.LIVE -> {}
            }
        }
        val d = detail
        val dc = detailChan
        val zap = remember(snap, tile) { snap?.let { zapList(tile, it) } ?: emptyList() }
        val left = reserveEnd > 0.dp
        if (d != null) DetailPanel(d, nowSec, watchLabel, left, onClose = { closePanel() }) { ch -> detail = null; onWatch(ch, zap.ifEmpty { d.sources }) }
        else if (dc != null) ChannelPanel(dc, watchLabel, left, onClose = { closePanel() }) { detailChan = null; onWatch(dc, zap.ifEmpty { listOf(dc) }) }
    }
}

/** The channels of a screen in screen order – zapping up/down in the player stays within "what's on" here. */
private fun zapList(tile: OnAirTile, s: OnNow.Snapshot): List<OnNowLogic.Chan> {
    val now = System.currentTimeMillis() / 1000
    val items = when (tile) {
        OnAirTile.EVENTS -> s.events.live + s.events.upcoming
        else -> NowScreenLogic.classOf(tile)?.let { c -> NowScreenLogic.cards(s.items, c, now, OnAirNav.sort) } ?: emptyList()
    }
    val out = LinkedHashMap<String, OnNowLogic.Chan>()
    for (i in items) i.sources.firstOrNull()?.let { out[it.id] = it }
    if (tile == OnAirTile.EVENTS) for (c in s.events.channels) out[c.id] = c
    return out.values.toList()
}

@Composable
private fun NowHeader(tile: OnAirTile, nowSec: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Image(painterResource(tileIcon(tile)), contentDescription = null, modifier = Modifier.size(36.dp), colorFilter = ColorFilter.tint(Accent2))
        Text(tile.label, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        Text(hhmm(nowSec), color = Muted, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, raw = true)
    }
}

// ====================================================================== Movies and Shows

@Composable
private fun CardsScreen(tile: OnAirTile, s: OnNow.Snapshot, now: Long, onOpen: (OnNowItem) -> Unit, onGuide: () -> Unit) {
    val cls = NowScreenLogic.classOf(tile) ?: return
    val sort = OnAirNav.sort
    val cards = remember(s, now, sort) { NowScreenLogic.cards(s.items, cls, now, sort) }
    val sortReq = remember { FocusRequester() }
    if (cards.isEmpty()) {
        EmptyState(if (cls == OnNowClass.MOVIE) "Gerade läuft nichts, das als Film erkannt wird" else "Gerade läuft nichts, das als Serie oder Show erkannt wird", onGuide)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(if (sort == NowScreenLogic.Sort.RECENT) "Sortiert: zuletzt gestartet" else "Sortiert: nach Sender", modifier = Modifier.focusRequester(sortReq)) {
            OnAirNav.sort = if (sort == NowScreenLogic.Sort.RECENT) NowScreenLogic.Sort.CHANNEL else NowScreenLogic.Sort.RECENT
        }
        Text("${cards.size} laufen gerade", color = Muted, fontSize = 16.sp)
    }
    Spacer(Modifier.height(12.dp))
    CardGrid(tile, sections = listOf(null to cards), now = now, onOpen = onOpen, posters = true, fallback = sortReq)
}

// ====================================================================== Sports

/**
 * Sports: sport groups on the left, cards of the selected group on the right.
 * - Up/Down on the left only switch the sport (focus stays in the column; the cards follow).
 * - Right or OK moves the focus into the cards; Back from the cards returns to the same sport on the left.
 * Rules in [NowScreenLogic.sportListKey] / [NowScreenLogic.sportsBack].
 */
@Composable
private fun SportsScreen(s: OnNow.Snapshot, now: Long, panelOpen: Boolean, onOpen: (OnNowItem) -> Unit, onGuide: () -> Unit) {
    val groups = remember(s, now) { NowScreenLogic.sportGroups(s.items, s.soon, now) }
    if (groups.isEmpty()) { EmptyState("Gerade laufen keine Sportübertragungen", onGuide); return }
    var sel by remember { mutableStateOf(NowScreenLogic.pickSport(groups, OnAirNav.sport)) }
    val sport = NowScreenLogic.pickSport(groups, sel)
    val groupReqs = remember(groups) { groups.associate { it.sport to FocusRequester() } }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val cardTarget = remember { FocusRequester() }
    var inCards by remember { mutableStateOf(false) }

    val live = remember(s, now, sport) { s.items.filter { it.cls == OnNowClass.SPORTS && it.sport == sport && NowScreenLogic.stillOn(it, now) } }
    val soon = remember(s, now, sport) { NowScreenLogic.startingSoon(s.soon, now).filter { it.sport == sport } }
    // Back from the player: the card watched last gets the focus again; otherwise the selected sport on the left
    val restoreCard = remember {
        val k = OnAirNav.nowFocus[OnAirTile.SPORTS]
        k != null && (live + soon).any { NowScreenLogic.key(it) == k }
    }
    var autoFocusCards by remember { mutableStateOf(restoreCard) }
    LaunchedEffect(Unit) { if (!restoreCard) sport?.let { groupReqs[it] }?.let { focusSoon(it) } }

    fun focusRow(sp: Sport) {
        val i = groups.indexOfFirst { it.sport == sp }
        scope.launch {
            if (i >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == i }) runCatching { listState.scrollToItem(i) }
            groupReqs[sp]?.let { focusSoon(it) }
        }
    }

    // Back from the cards: to the selected sport (the screen's own Back handler closes panels / leaves otherwise)
    BackHandler(enabled = NowScreenLogic.sportsBack(panelOpen, inCards) == NowScreenLogic.SportsBack.SPORT_LIST) {
        NowScreenLogic.pickSport(groups, sel)?.let { focusRow(it) }
    }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        LazyColumn(Modifier.width(250.dp).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(groups, key = { _, g -> g.sport.name }) { i, g ->
                SportRow(
                    NowScreenLogic.groupLabel(g), g.sport == sport, groupReqs.getValue(g.sport),
                    onKey = { k ->
                        when (val a = NowScreenLogic.sportListKey(k, i, groups.size)) {
                            is NowScreenLogic.SportNav.Select -> {
                                val to = groups[a.index].sport
                                sel = to; OnAirNav.sport = to
                                focusRow(to)
                                true
                            }
                            NowScreenLogic.SportNav.EnterCards -> {
                                if (live.isNotEmpty() || soon.isNotEmpty()) scope.launch { focusSoon(cardTarget) }
                                true
                            }
                            NowScreenLogic.SportNav.Stay -> true
                            NowScreenLogic.SportNav.Pass -> false
                        }
                    },
                    // focus arriving from the cards (Left) lands on the selected sport, not on whatever row is beside the card
                    onFocused = { val cur = NowScreenLogic.pickSport(groups, sel); if (cur != null && cur != g.sport) groupReqs[cur]?.requestFocus() }
                )
            }
        }
        Box(Modifier.weight(1f).onFocusChanged { inCards = it.hasFocus }) {
            androidx.compose.runtime.key(sport) {
                CardGrid(
                    OnAirTile.SPORTS,
                    sections = listOfNotNull(
                        (if (live.isNotEmpty()) "Läuft gerade" to live else null),
                        (if (soon.isNotEmpty()) "Gleich (nächste 60 Min.)" to soon else null)
                    ).map { it.first as String? to it.second },
                    now = now, onOpen = onOpen, posters = false,
                    fallback = null, target = cardTarget,
                    // switching the sport on the left must not pull the focus into the cards
                    autoFocus = autoFocusCards, onAutoFocused = { autoFocusCards = false }
                )
            }
        }
    }
}

/** One sport group on the left. Keys go to [onKey] first (Up/Down/Right/OK handled there, see [NowScreenLogic.sportListKey]). */
@Composable
private fun SportRow(label: String, selected: Boolean, req: FocusRequester, onKey: (NowScreenLogic.NavKey) -> Boolean, onFocused: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Box(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (f) Accent1 else if (selected) Surf2 else Surf)
            .border(if (f) 2.dp else 0.dp, if (f) Color.White else Color.Transparent, shape)
            .focusRequester(req)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocused() }
            .onPreviewKeyEvent { e ->
                val k = navKey(e.key)
                if (k == NowScreenLogic.NavKey.OTHER) return@onPreviewKeyEvent false
                // act on key-down; swallow the matching key-up so OK does not also "click"
                if (e.type == KeyEventType.KeyDown) onKey(k) else true
            }
            .focusable()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, color = if (f) OnAccent else Color.White, fontSize = 18.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun navKey(k: Key): NowScreenLogic.NavKey = when (k) {
    Key.DirectionUp -> NowScreenLogic.NavKey.UP
    Key.DirectionDown -> NowScreenLogic.NavKey.DOWN
    Key.DirectionLeft -> NowScreenLogic.NavKey.LEFT
    Key.DirectionRight -> NowScreenLogic.NavKey.RIGHT
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> NowScreenLogic.NavKey.OK
    else -> NowScreenLogic.NavKey.OTHER
}

// ====================================================================== Events

@Composable
private fun EventsScreen(s: OnNow.Snapshot, now: Long, onOpen: (OnNowItem) -> Unit, onOpenChannel: (OnNowLogic.Chan) -> Unit, onGuide: () -> Unit) {
    val e = s.events
    val live = remember(s, now) { e.live.filter { NowScreenLogic.stillOn(it, now) } }
    if (live.isEmpty() && e.upcoming.isEmpty() && e.channels.isEmpty()) {
        EmptyState(if (e.hiddenPlaceholders > 0) "Gerade keine Events – ${e.hiddenPlaceholders} leere PPV-Plätze ausgeblendet" else "Gerade keine Events", onGuide)
        return
    }
    CardGrid(
        OnAirTile.EVENTS,
        sections = listOfNotNull(
            (if (live.isNotEmpty()) "Läuft gerade" to live else null),
            (if (e.upcoming.isNotEmpty()) "Demnächst (24 Std.)" to e.upcoming else null)
        ).map { it.first as String? to it.second },
        now = now, onOpen = onOpen, posters = false, fallback = null,
        extraKeys = e.channels.map { "ppv_" + it.id },
        extra = if (e.channels.isEmpty() && e.hiddenPlaceholders == 0) null else { scope, focusMod ->
            scope.item(span = { GridItemSpan(maxLineSpan) }, key = "h_ppv") {
                SectionHeader("PPV-Sender", if (e.hiddenPlaceholders > 0) "${e.hiddenPlaceholders} leere PPV-Plätze ausgeblendet" else null)
            }
            for (c in e.channels) {
                val k = "ppv_" + c.id
                scope.item(key = k) {
                    ChannelCard(c, focusKey = k, modifier = focusMod(k)) { onOpenChannel(c) }
                }
            }
        }
    )
}

// ====================================================================== shared grid and cards

/**
 * Cards in sections (header = null: no header), with the focus restored to the card focused last on this screen
 * ([OnAirNav.nowFocus]) – or the first card, or [fallback] when there are no cards.
 */
@Composable
private fun CardGrid(
    tile: OnAirTile,
    sections: List<Pair<String?, List<OnNowItem>>>,
    now: Long,
    onOpen: (OnNowItem) -> Unit,
    posters: Boolean,
    fallback: FocusRequester?,
    /** Keys of the items [extra] adds after the sections (in order, after one header). */
    extraKeys: List<String> = emptyList(),
    /** Focus target (the restored or first card); pass one to move the focus into the grid from outside. */
    target: FocusRequester? = null,
    /** Take the focus when shown (false: only scroll to the restored card – the Sports list keeps the focus). */
    autoFocus: Boolean = true,
    onAutoFocused: () -> Unit = {},
    extra: ((LazyGridScope, (String) -> Modifier) -> Unit)? = null
) {
    val state = rememberLazyGridState()
    val ownTarget = remember { FocusRequester() }
    val target = target ?: ownTarget
    // one focus handle per card, so the card a detail panel was opened from can take the focus back
    val reqs = remember { HashMap<String, FocusRequester>() }
    val remembered = remember(tile) { OnAirNav.nowFocus[tile] }
    val all = sections.flatMap { it.second }
    val keys = remember(sections, extraKeys) { all.map { NowScreenLogic.key(it) } + extraKeys }
    val targetKey = remembered?.takeIf { it in keys } ?: keys.firstOrNull()
    // index of the target in the grid (headers count as items)
    val targetIndex = remember(sections, extraKeys, targetKey) {
        var idx = 0; var found = -1
        for ((h, l) in sections) {
            if (h != null) idx++
            for (i in l) { if (found < 0 && NowScreenLogic.key(i) == targetKey) found = idx; idx++ }
        }
        if (found < 0 && targetKey != null && targetKey in extraKeys) found = idx + 1 + extraKeys.indexOf(targetKey)
        found
    }
    fun focusMod(k: String): Modifier {
        val m = Modifier.focusRequester(reqs.getOrPut(k) { FocusRequester() })
        return if (k == targetKey) m.focusRequester(target) else m
    }
    // a detail panel closed: back to the card it was opened from (the card focused last)
    val closed = OnAirNav.panelClosed
    val closedAtStart = remember { closed }   // only closes while this grid is shown (Sports rebuilds it per sport)
    LaunchedEffect(closed) {
        if (closed == closedAtStart) return@LaunchedEffect
        OnAirNav.nowFocus[tile]?.let { reqs[it] }?.let { focusSoon(it) }
    }
    LaunchedEffect(tile) {
        if (targetIndex > 0) runCatching { state.scrollToItem(targetIndex) }
        if (!autoFocus) return@LaunchedEffect
        if (targetKey != null) focusSoon(target) else fallback?.let { focusSoon(it) }
        onAutoFocused()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 228.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 40.dp, start = 4.dp, end = 4.dp)
    ) {
        for ((h, list) in sections) {
            if (h != null) item(span = { GridItemSpan(maxLineSpan) }, key = "h_$h") { SectionHeader(h, null) }
            for (it in list) {
                val k = NowScreenLogic.key(it)
                item(key = k) {
                    NowCard(
                        it, now, posters,
                        modifier = focusMod(k),
                        onFocus = { OnAirNav.nowFocus[tile] = k }
                    ) { onOpen(it) }
                }
            }
        }
        extra?.invoke(this, ::focusMod)
    }
}

@Composable
private fun SectionHeader(title: String, sub: String?) {
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = Accent2, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, color = Muted, fontSize = 14.sp)
    }
}

/** Poster (TMDB, if a key is set and it finds the title) or the channel logo. */
@Composable
private fun cardImage(item: OnNowItem, posters: Boolean): Pair<String?, Boolean> {
    val ctx = LocalContext.current
    val logo = item.sources.firstOrNull { !it.logo.isNullOrBlank() }?.logo
    if (!posters || !MetaRepo.enabled()) return logo to false
    val kind = if (item.cls == OnNowClass.MOVIE) Kind.MOVIE else Kind.SERIES
    val probe = remember(item.prog.title, kind) { Item("onair:" + OnNowLogic.norm(item.prog.title), kind, item.prog.title.trim(), null, "", null, "") }
    val art by produceState(MetaRepo.cached(probe)?.let { it.backdrop ?: it.poster }, probe) {
        if (value == null) value = runCatching { MetaRepo.basic(ctx, probe) }.getOrNull()?.let { it.backdrop ?: it.poster }
    }
    return (art?.let { cardPoster(it) } ?: logo) to (art != null)
}

@Composable
private fun NowCard(item: OnNowItem, now: Long, posters: Boolean, modifier: Modifier = Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (f && Look.motion > 0) 1.05f else 1f, tween(if (Look.motion > 0) 120 else 0), label = "nowCard")
    val shape = RoundedCornerShape(DS.RadiusM)
    val upcoming = item.prog.start > now
    val (img, isArt) = cardImage(item, posters)
    val ch = item.sources.firstOrNull()
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (f) Surf2 else Surf)
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else Color(0x22FFFFFF), shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color(0xFF0E1320)), contentAlignment = Alignment.Center) {
            if (img != null) AsyncImage(
                img, contentDescription = null,
                modifier = if (isArt) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(22.dp),
                contentScale = if (isArt) ContentScale.Crop else ContentScale.Fit
            ) else Text(ch?.name ?: "", color = Muted, fontSize = 16.sp, raw = true, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
            if (!upcoming) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(item.progress(now)).fillMaxHeight().background(Accent1))
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                NowScreenLogic.eventTitle(item), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, raw = true, maxLines = 1,
                overflow = if (f) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (f && Look.motion > 0) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
            )
            val more = item.sources.size - 1
            Text((ch?.name ?: "") + (if (more > 0) "  +$more" else ""), color = Muted, fontSize = 14.sp, raw = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (upcoming) NowScreenLogic.startsIn(item.prog.start, now) + " · " + hhmm(item.prog.start) else NowScreenLogic.timeLine(item, now),
                color = if (upcoming) Accent2 else Color(0xFFD5D9E4), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A PPV / event channel with nothing in the guide: its name is the event. */
@Composable
private fun ChannelCard(c: OnNowLogic.Chan, focusKey: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Column(
        modifier.clip(shape)
            .background(if (f) Surf2 else Surf)
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else Color(0x22FFFFFF), shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) OnAirNav.nowFocus[OnAirTile.EVENTS] = focusKey }
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!c.logo.isNullOrBlank()) AsyncImage(c.logo, null, Modifier.size(40.dp), contentScale = ContentScale.Fit)
            Text(
                OnNowLogic.eventTitle(c), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, raw = true, maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(c.group, color = Muted, fontSize = 13.sp, raw = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Kein Programm im Guide", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun EmptyState(message: String, onGuide: () -> Unit) {
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(req) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 640.dp)) {
            Text(message, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            PillButton("TV-Guide öffnen", modifier = Modifier.focusRequester(req), icon = AIcon.Grid) { onGuide() }
        }
    }
}

// ====================================================================== detail panel

@Composable
private fun PanelFrame(onClose: () -> Unit, left: Boolean = false, content: @Composable () -> Unit) {
    // left: in the Multiview picker, so the running stream's window at the top right never covers the panel
    Box(Modifier.fillMaxSize().background(Color(0xCC05070C)).clickable(onClick = onClose).padding(start = if (left) 40.dp else 0.dp),
        contentAlignment = if (left) Alignment.CenterStart else Alignment.Center) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(0.8f).clip(RoundedCornerShape(DS.RadiusL)).background(Surf)
                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(DS.RadiusL)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) { content() }
    }
}

@Composable
private fun DetailPanel(item: OnNowItem, now: Long, watchLabel: String, left: Boolean, onClose: () -> Unit, onWatch: (OnNowLogic.Chan) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(item) { focusSoon(first) }
    val p = item.prog
    val upcoming = p.start > now
    PanelFrame(onClose, left) {
        Text(NowScreenLogic.eventTitle(item), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = listOfNotNull(p.episode, p.subTitle).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, color = Accent2, fontSize = 16.sp, raw = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            hhmm(p.start) + "–" + hhmm(p.stop) + "   ·   " + Lang.tr(if (upcoming) NowScreenLogic.startsIn(p.start, now) else NowScreenLogic.timeLine(item, now)),
            color = Color(0xFFD5D9E4), fontSize = 16.sp, raw = true
        )
        if (!upcoming) Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxWidth(item.progress(now)).fillMaxHeight().background(Accent1))
        }
        if (!p.desc.isNullOrBlank()) Text(p.desc, color = Muted, fontSize = 15.sp, raw = true, maxLines = 5, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
        Spacer(Modifier.height(6.dp))
        if (item.sources.size == 1) {
            val c = item.sources.first()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton(watchLabel, modifier = Modifier.focusRequester(first), light = true, icon = AIcon.Play) { onWatch(c) }
                Text(c.name, color = Muted, fontSize = 15.sp, raw = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Text("Quelle wählen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            // best quality first (and focused) – merged duplicates of the same programme
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(item.sources, key = { _, c -> c.id }) { i, c ->
                    PillButton(
                        c.name + (c.quality?.let { "  ·  $it" } ?: ""), raw = true, light = i == 0,
                        modifier = if (i == 0) Modifier.focusRequester(first) else Modifier
                    ) { onWatch(c) }
                }
            }
        }
    }
}

@Composable
private fun ChannelPanel(c: OnNowLogic.Chan, watchLabel: String, left: Boolean, onClose: () -> Unit, onWatch: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(c) { focusSoon(first) }
    PanelFrame(onClose, left) {
        Text(OnNowLogic.eventTitle(c), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, raw = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(c.group, color = Accent2, fontSize = 16.sp, raw = true)
        Text("Kein Programm im Guide", color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        PillButton(watchLabel, modifier = Modifier.focusRequester(first), light = true, icon = AIcon.Play) { onWatch() }
    }
}

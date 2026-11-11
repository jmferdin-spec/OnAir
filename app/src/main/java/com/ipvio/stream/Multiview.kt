package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * OnAir v2 (Phase 8): Multiview – two live channels at once. Rules in [MultiviewLogic]; this is the running session.
 *
 * Multiview keeps its own players (one per screen, held here, outside the composition), so the running stream goes on
 * while the owner picks a channel. Screen 1 has the normal buffer, further screens the small one. Audio follows the
 * selection (the other screens are muted; switching is instant). Leaving the app (Home, standby) ends Multiview and stops
 * every stream, so no provider connection stays open.
 */
object Multiview {
    /** Where the picker is. */
    enum class Page { HOME, GUIDE, NOW, SEARCH }

    /** Picking a channel: [replace] = screen index for "Change channel", null = add a screen. */
    data class Pick(val replace: Int?)

    /** One screen's player and what it shows on top (error, "may stutter"). */
    class Slot(val item: Item, val player: ExoPlayer) {
        var error by mutableStateOf<String?>(null)
        var note by mutableStateOf<String?>(null)
    }

    var active by mutableStateOf(false)
        private set
    /** null while the first extra channel is being picked (only the running stream exists then). */
    var state by mutableStateOf<MultiviewLogic.State?>(null)
        private set
    var pick by mutableStateOf<Pick?>(null)
        private set
    var page by mutableStateOf(Page.HOME)
    var pageTile by mutableStateOf(OnAirTile.MOVIES)
    val slots = mutableStateListOf<Slot>()
    /** Account limit for the stream-limit message (from the last catalog download; null = unknown). */
    private var maxConnections: Int? = null

    /** From the player: [item] keeps playing (in the small window), the picker opens to add the second channel. */
    fun begin(c: Context, item: Item) {
        endAll()
        maxConnections = AccountStore.get(c, PlaylistStore.savedUrl(c))?.maxConnections
        slots.add(newSlot(c, item, small = false))
        state = null
        pick = Pick(null); page = Page.HOME
        active = true
        applyAudio()
    }

    @OptIn(UnstableApi::class)
    private fun newSlot(c: Context, item: Item, small: Boolean): Slot {
        val pl = TwoStreamTest.player(c.applicationContext, small = small, audio = true)
        val slot = Slot(item, pl)
        pl.addAnalyticsListener(object : AnalyticsListener {
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                val causes = generateSequence<Throwable>(error) { it.cause }.toList()
                val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val refused = causes.any { it is java.net.ConnectException || it is java.net.SocketException }
                val others = slots.count { it !== slot && it.player.isPlaying }
                slot.error = MultiviewLogic.startFailure(http, refused, others, maxConnections)
                    ?: ("Wiedergabe fehlgeschlagen (" + (http?.let { "HTTP $it" } ?: error.errorCodeName) + ")")
                LoginDebug.error("multiview", "screen ${item.title}", error)
            }
            override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                if (isPlaying) slot.error = null
            }
        })
        pl.setMediaItem(MediaItem.fromUri(item.url))
        pl.playWhenReady = true
        pl.prepare()
        return slot
    }

    /** The selected screen has the audio; the others are muted. While picking the first channel: the running one. */
    fun applyAudio() {
        val sel = state?.selected ?: 0
        slots.forEachIndexed { i, s -> s.player.volume = if (i == sel) 1f else 0f }
    }

    /**
     * A channel was picked. Next to a 4K screen a ≤ 1080p version of the channel is used when there is one
     * ([MultiviewLogic.chooseSource]); when there is none it plays anyway with a "may stutter" note.
     */
    fun picked(c: Context, chosen: Item, liveAll: List<Item>) {
        val p = pick ?: return
        val idx = OnNow.indexFor(liveAll)
        val byId = liveAll.associateBy { it.id }
        val siblings = chosen.tvgId?.trim()?.lowercase()?.let { idx.byTvg[it] }.orEmpty()
            .map { MultiviewLogic.Source(it.id, it.quality) }.ifEmpty { listOf(MultiviewLogic.Source(chosen.id, chosen.quality)) }
        val others = slots.filterIndexed { i, _ -> i != p.replace }.map { it.item.quality }
        val choice = MultiviewLogic.chooseSource(MultiviewLogic.Source(chosen.id, chosen.quality), siblings, others)
        val item = byId[choice.source.channelId] ?: chosen
        val note = if (choice.mayStutter) "Kann ruckeln: zwei 4K-Streams" else null
        val st = state
        if (p.replace == null) {
            val slot = newSlot(c, item, small = true).also { it.note = note }
            slots.add(slot)
            state = if (st == null) MultiviewLogic.start(slots[0].item.id, item.id) else (MultiviewLogic.add(st, item.id) ?: st)
        } else {
            val i = p.replace
            slots.getOrNull(i)?.let { runCatching { it.player.release() } }
            val slot = newSlot(c, item, small = i != 0).also { it.note = note }
            if (i < slots.size) slots[i] = slot else slots.add(slot)
            state = st?.let { MultiviewLogic.change(it, i, item.id) }
        }
        pick = null
        applyAudio()
    }

    /** Back in the picker's start page: the first time Multiview ends (the running channel continues full-screen). */
    fun cancelPick(): String? {
        pick = null
        return if (state == null) slots.firstOrNull()?.item?.id else null
    }

    fun move(d: MultiviewLogic.Dir) { state = state?.let { MultiviewLogic.move(it, d) }; applyAudio() }

    /** Runs a menu action on screen [i]; returns the channel to continue full-screen when Multiview ends. */
    fun act(i: Int, a: MultiviewLogic.Action): String? {
        val st = state ?: return null
        when (val r = MultiviewLogic.act(st, i, a)) {
            is MultiviewLogic.Result.Stay -> {
                // pause / resume the screen's player; resuming a live screen jumps back to live
                val before = st.screens.getOrNull(i)?.paused
                val after = r.state.screens.getOrNull(i)?.paused
                if (before != after) slots.getOrNull(i)?.player?.let { pl ->
                    if (after == true) pl.pause() else { if (MultiviewLogic.resumeAtLive) pl.seekToDefaultPosition(); pl.play() }
                }
                // removed screen: stop its stream
                if (r.state.count < st.count) {
                    slots.getOrNull(i)?.let { runCatching { it.player.release() } }
                    slots.removeAt(i)
                }
                state = r.state
                applyAudio()
            }
            is MultiviewLogic.Result.Pick -> { pick = Pick(r.index); page = Page.HOME }
            is MultiviewLogic.Result.FullScreen -> return r.channelId
        }
        return null
    }

    /** Ends Multiview: every stream stops (the caller then plays [keep] full-screen, if any). */
    fun endAll() {
        for (s in slots) runCatching { s.player.release() }
        slots.clear()
        state = null; pick = null; active = false
    }
}

/**
 * A video view for a Multiview player – used for the picker's small window and for every screen.
 *
 * Cause of the black screens (Phase 8): PlayerView covers its own SurfaceView with an opaque black "shutter" whenever a
 * player is attached, and opens it only on the player's "first frame rendered" event. Multiview attaches a NEW PlayerView
 * to a player that is ALREADY playing (screen 1 was rendering into the picker's window, screen 2 was started while the
 * picker was open, without a surface). The decoder switched to the new surface fine (logcat: setOutputSurface …
 * Surface configure completed) and audio played, but that event did not come again, so the shutter stayed closed over
 * the video. The two-stream test and the picker window worked because there the view is attached right when the player
 * starts. Fix: transparent shutter and keep content on attach – nothing can cover the surface any more.
 */
@OptIn(UnstableApi::class)
internal fun videoView(c: Context, player: ExoPlayer): PlayerView = PlayerView(c).apply {
    useController = false
    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
    setKeepContentOnPlayerReset(true)
    this.player = player
}

/** The running stream in a small window at the top right (same place as the TV guide's picture). */
@OptIn(UnstableApi::class)
@Composable
private fun MiniWindow(slot: Multiview.Slot?) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 24.dp).width(400.dp)) {
            Box(Modifier.size(400.dp, 225.dp).clip(RoundedCornerShape(DS.RadiusM)).background(Color.Black)) {
                if (slot != null) key(slot.player) {
                    AndroidView(factory = { c -> videoView(c, slot.player) }, modifier = Modifier.fillMaxSize())
                }
            }
            if (slot != null) Text(slot.item.title, color = Muted, fontSize = 14.sp, raw = true, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/**
 * Multiview on screen: the picker while a channel is being chosen, otherwise the screens. [onFullScreen] = continue a
 * channel in the normal player (Multiview has ended and stopped every other stream).
 */
@Composable
fun MultiviewHost(liveAll: List<Item>, onFullScreen: (Item?) -> Unit) {
    val ctx = LocalContext.current
    val byId = remember(liveAll) { liveAll.associateBy { it.id } }
    fun finish(id: String?) { Multiview.endAll(); onFullScreen(id?.let { byId[it] }) }

    // Home button / standby: stop every stream (no connection stays open in the background)
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP && Multiview.active) Multiview.endAll() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    KeepScreenOn("multiview", Multiview.active)

    if (Multiview.pick != null) MultiviewPicker(liveAll, onPicked = { Multiview.picked(ctx, it, liveAll) }, onCancel = { Multiview.cancelPick()?.let { id -> finish(id) } })
    else MultiviewScreen(onFinish = { finish(it) })
}

// ====================================================================== picker

@Composable
private fun MultiviewPicker(liveAll: List<Item>, onPicked: (Item) -> Unit, onCancel: () -> Unit) {
    val byId = remember(liveAll) { liveAll.associateBy { it.id } }
    val p = Multiview.pick ?: return
    val previewIdx = p.replace ?: (Multiview.state?.selected ?: 0)
    val preview = Multiview.slots.getOrNull(previewIdx)
    val title = if (p.replace == null) "Sender für Multiview hinzufügen" else "Sender wechseln"
    Box(Modifier.fillMaxSize().background(Bg)) {
        when (Multiview.page) {
            Multiview.Page.HOME -> PickerHome(title, onPage = { Multiview.page = it }, onCancel = onCancel)
            Multiview.Page.GUIDE -> GuideScreen(
                liveAll,
                onWatch = { ch, _ -> onPicked(ch) },
                onArchive = { ch, _ -> onPicked(ch) },
                preview = false,   // the running stream is already in this corner – no third stream
                onClose = { Multiview.page = Multiview.Page.HOME }
            )
            Multiview.Page.NOW -> key(Multiview.pageTile) {
                NowScreen(
                    Multiview.pageTile, liveAll,
                    onWatch = { ch, _ -> byId[ch.id]?.let(onPicked) },
                    onGuide = { Multiview.page = Multiview.Page.GUIDE },
                    onBack = { Multiview.page = Multiview.Page.HOME },
                    reserveEnd = 430.dp, watchLabel = "Auswählen"
                )
            }
            Multiview.Page.SEARCH -> PickerSearch(liveAll, onPicked = onPicked, onBack = { Multiview.page = Multiview.Page.HOME })
        }
        // drawn last: the running stream in its window on top
        MiniWindow(preview)
    }
}

@Composable
private fun PickerHome(title: String, onPage: (Multiview.Page) -> Unit, onCancel: () -> Unit) {
    BackHandler { onCancel() }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 470.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(AIcon.Grid, Accent2, size = 32.dp)
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text("Wähle einen Sender – der laufende spielt oben rechts weiter.", color = Muted, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        val entries = listOf(
            Triple("Live TV", AIcon.Live) { onPage(Multiview.Page.GUIDE) },
            Triple("Filme", AIcon.Play) { Multiview.pageTile = OnAirTile.MOVIES; onPage(Multiview.Page.NOW) },
            Triple("Shows", AIcon.Play) { Multiview.pageTile = OnAirTile.SHOWS; onPage(Multiview.Page.NOW) },
            Triple("Sport", AIcon.Play) { Multiview.pageTile = OnAirTile.SPORTS; onPage(Multiview.Page.NOW) },
            Triple("Events", AIcon.Play) { Multiview.pageTile = OnAirTile.EVENTS; onPage(Multiview.Page.NOW) },
            Triple("Suche", AIcon.Mic) { onPage(Multiview.Page.SEARCH) }
        )
        entries.forEachIndexed { i, (label, icon, go) ->
            PillButton(label, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, icon = icon) { go() }
        }
        Spacer(Modifier.height(8.dp))
        Text("Zurück = abbrechen", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun PickerSearch(liveAll: List<Item>, onPicked: (Item) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onBack() }
    var q by remember { mutableStateOf("") }
    val chans = remember(liveAll) { liveAll.filter { it.kind == Kind.LIVE }.map { MultiviewLogic.SearchChan(it.id, it.title, it.group) } }
    val byId = remember(liveAll) { liveAll.associateBy { it.id } }
    val snap by produceState<OnNow.Snapshot?>(null, liveAll) { value = OnNow.snapshot(ctx, liveAll) }
    // results computed off the main thread, a moment after the last key press
    val hits by produceState(emptyList<MultiviewLogic.Hit>(), q, snap) {
        delay(250)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { MultiviewLogic.search(q, chans, snap?.items.orEmpty()) }
    }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(field) }
    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 36.dp, end = 470.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Suche", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Field(q, { q = it }, "Sender oder Sendung suchen", modifier = Modifier.focusRequester(field))
        if (q.trim().length >= 2 && hits.isEmpty()) Text("Keine Treffer", color = Muted, fontSize = 16.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
            items(hits, key = { it.channelId }) { h ->
                PillButton(
                    if (h.programme) "${h.label}   ·   ${h.sub}" else "${h.label}   ·   ${h.sub}",
                    raw = true, icon = if (h.programme) AIcon.Play else AIcon.Live
                ) { byId[h.channelId]?.let(onPicked) }
            }
        }
    }
}

// ====================================================================== the screens

@OptIn(UnstableApi::class)
@Composable
private fun MultiviewScreen(onFinish: (String?) -> Unit) {
    val st = Multiview.state ?: return
    var menu by remember { mutableStateOf<Int?>(null) }
    var askExit by remember { mutableStateOf(false) }
    val root = remember { FocusRequester() }
    // focus back on the screens whenever no menu / question is open
    LaunchedEffect(menu, askExit, Multiview.pick) { if (menu == null && !askExit) focusSoon(root) }
    BackHandler { if (menu != null) menu = null else askExit = !askExit }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(root)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || menu != null || askExit) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { Multiview.move(MultiviewLogic.Dir.LEFT); true }
                    Key.DirectionRight -> { Multiview.move(MultiviewLogic.Dir.RIGHT); true }
                    Key.DirectionUp -> { Multiview.move(MultiviewLogic.Dir.UP); true }
                    Key.DirectionDown -> { Multiview.move(MultiviewLogic.Dir.DOWN); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { menu = st.selected; true }
                    else -> false
                }
            }
            .focusable()
    ) {
        val pad = 24.dp
        val areaW = maxWidth - pad * 2
        val areaH = maxHeight - pad * 2
        val boxes = MultiviewLogic.boxes(st, areaW.value, areaH.value)
        Multiview.slots.forEachIndexed { i, slot ->
            val b = boxes.getOrNull(i) ?: return@forEachIndexed
            val selected = i == st.selected
            key(slot.player) {
                Box(
                    Modifier.offset(pad + b.x.dp, pad + b.y.dp).size(b.w.dp, b.h.dp)
                        .border(if (selected) 4.dp else 1.dp, if (selected) Accent1 else Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                        .padding(if (selected) 4.dp else 1.dp)
                ) {
                    AndroidView(factory = { c -> videoView(c, slot.player) }, modifier = Modifier.fillMaxSize())
                    // channel label; audio marker on the selected screen
                    Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (selected) AppIcon(AIcon.Music, Accent2, size = 16.dp)
                        Text(slot.item.title, color = Color.White, fontSize = 14.sp, raw = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (st.screens.getOrNull(i)?.paused == true) Text("Pause", color = Accent2, fontSize = 13.sp)
                    }
                    slot.note?.let {
                        Text(it, color = Color.White, fontSize = 13.sp, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                            .clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                    slot.error?.let {
                        Box(Modifier.fillMaxSize().background(Color(0xCC05070C)), contentAlignment = Alignment.Center) {
                            Text(it, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp))
                        }
                    }
                }
            }
        }

        val m = menu
        if (m != null) MultiviewMenu(st, m, onClose = { menu = null }) { a ->
            menu = null
            Multiview.act(m, a)?.let { onFinish(it) }
        }
        if (askExit) ExitQuestion(onNo = { askExit = false }) { onFinish(MultiviewLogic.exitTo(st)) }
    }
}

private fun label(a: MultiviewLogic.Action): String = when (a) {
    MultiviewLogic.Action.CHANGE_CHANNEL -> "Sender wechseln"
    MultiviewLogic.Action.PAUSE -> "Pause"
    MultiviewLogic.Action.RESUME -> "Fortsetzen"
    MultiviewLogic.Action.ENLARGE -> "Bild vergrößern"
    MultiviewLogic.Action.REDUCE -> "Bild verkleinern"
    MultiviewLogic.Action.FULL_SCREEN -> "Vollbild"
    MultiviewLogic.Action.REMOVE -> "Bild entfernen"
}

@Composable
private fun MultiviewMenu(st: MultiviewLogic.State, i: Int, onClose: () -> Unit, onAction: (MultiviewLogic.Action) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(i) { focusSoon(first) }
    val name = Multiview.slots.getOrNull(i)?.item?.title ?: ""
    Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 420.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Surf).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(DS.RadiusL)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, raw = true, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MultiviewLogic.menu(st, i).forEachIndexed { k, a ->
                PillButton(label(a), modifier = (if (k == 0) Modifier.focusRequester(first) else Modifier).fillMaxWidth()) { onAction(a) }
            }
        }
    }
}

@Composable
private fun ExitQuestion(onNo: () -> Unit, onYes: () -> Unit) {
    val yes = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(yes) }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Surf).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Multiview beenden?", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Text("Das ausgewählte Bild läuft im Vollbild weiter.", color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Beenden", modifier = Modifier.focusRequester(yes), light = true) { onYes() }
                PillButton("Abbrechen") { onNo() }
            }
        }
    }
}

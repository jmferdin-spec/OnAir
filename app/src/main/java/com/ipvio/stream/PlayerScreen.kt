package com.ipvio.stream

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

data class PlayReq(
    val title: String, val url: String, val itemId: String, val live: Boolean, val startMs: Long,
    val tmdbId: Int = 0, val season: Int = 0, val episode: Int = 0,
    /** Weitere Quellen desselben Films (andere Qualität) – zum Ausweichen bei Fehlern/Ruckeln und im Qualitäts-Menü. */
    val alts: List<Variant> = emptyList(),
    /** Kurzer Hinweis beim Start (z. B. „4K passt nicht zu diesem Gerät – spiele FHD“). */
    val note: String? = null,
    /** Sendung aus dem Archiv (Catchup) eines Live-Senders; [itemId] ist dann der Sender. */
    val archive: ArchiveRef? = null
)

/** Ziffer einer Fernbedienungstaste (0-9) oder -1. */
private fun digitOf(k: Key): Int = when (k) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4; Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9; else -> -1
}

private val SLEEP_MIN = listOf(0, 15, 30, 45, 60, 90, 120)

private enum class Panel { NONE, TRACKS, SUBSTYLE, QUALITY, SPEED, ASPECT, MENU, STEP, EPISODES, SLEEP, ARCHIVE, CLOCK }

private class Flash(val icon: PIcon?, val text: String?)

private class MenuEntry(val id: Int, val label: String, val trailing: String?, val chevron: Boolean)

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private val ASPECTS get() = General.ASPECT_NAMES
private val STEP_OPTIONS = listOf(5, 10, 15, 30, 60)

private fun applyStyle(v: PlayerView) {
    val sv = v.subtitleView ?: return
    sv.setApplyEmbeddedStyles(false)
    sv.setApplyEmbeddedFontSizes(false)
    val fg = SubStyle.COLORS[AppSettings.subColor].second.toInt()
    val bg = SubStyle.BGS[AppSettings.subBg].second.toInt()
    val edge = when (AppSettings.subEdge) {
        0 -> CaptionStyleCompat.EDGE_TYPE_NONE
        1 -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        else -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
    }
    sv.setStyle(CaptionStyleCompat(fg, bg, android.graphics.Color.TRANSPARENT, edge, android.graphics.Color.BLACK, null))
    sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SubStyle.SIZES[AppSettings.subSize].second)
}

private fun findActivity(c: Context): ComponentActivity? =
    generateSequence<Context>(c) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<ComponentActivity>().firstOrNull()

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    req: PlayReq, zapList: List<Item>, liveAll: List<Item>, seriesItem: Item? = null,
    /** OnAir v2: open Multiview with the channel playing now (live only); null = no Multiview entry. */
    onMultiview: ((String) -> Unit)? = null,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    CatFavs.init(ctx)
    // Eigene HTTP-Einstellungen: folgt Weiterleitungen auch von http nach https (viele IPTV-Server tun das)
    // und gibt nach 10 s Verbindungsaufbau bzw. 15 s Lesezeit auf, statt endlos zu warten.
    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
        ExoPlayer.Builder(ctx, SoftAudio.factory(ctx))
            .setLoadControl(Mem.loadControl(ctx))
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build()
            .also {
                // Bildrate: nur wechseln, wenn der Fernseher das nahtlos kann (kein Aufblitzen); abschaltbar unter Einstellungen > Player
                it.videoChangeFrameRateStrategy = if (AppSettings.autoFps) C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS else C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            }
    }
    val h = remember { Handler(Looper.getMainLooper()) }
    // Zustand für die Bildschirm-Wachhaltung: Sie gilt nur, solange wirklich abgespielt oder nachgeladen wird
    // (nicht bei Pause, Ende, Fehler oder im Hintergrund). Sonst startet nach ca. 5 Minuten der Bildschirmschoner.
    var pWhenReady by remember { mutableStateOf(true) }
    var pState by remember { mutableIntStateOf(Player.STATE_IDLE) }
    var pError by remember { mutableStateOf(false) }
    var foreground by remember { mutableStateOf(true) }
    KeepScreenOn("player", AwakeLogic.playerNeedsScreen(pWhenReady, pState, pError, foreground))
    var cur by remember { mutableStateOf(req) }
    var zapSrc by remember { mutableStateOf(zapList) }
    var msg by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    // Live (Runde 14): beim Öffnen gleich die Steuerleiste mit Sender und Sendung statt der kurzen Info-Box
    var controls by remember { mutableStateOf(true) }
    var focusSeek by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    // Technik-Info und Untertitel-Suche (OpenSubtitles)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var techOn by remember { mutableStateOf(false) }
    var panelParent by remember { mutableStateOf(Panel.NONE) }
    var lastPanel by remember { mutableStateOf(Panel.NONE) }
    var menuSel by remember { mutableIntStateOf(0) }
    var showChannels by remember { mutableStateOf(false) }
    var showCats by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    // Aus Live TV über „TV-Guide“ gestartet: Guide sofort zeigen
    LaunchedEffect(Unit) { if (AppNav.openGuide) { AppNav.openGuide = false; if (req.live) { controls = false; showGuide = true } } }
    var inHeader by remember { mutableStateOf(false) }
    val headerFocus = remember { FocusRequester() }
    var focusedCh by remember { mutableStateOf<Item?>(null) }
    var tracksVer by remember { mutableIntStateOf(0) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var speedIdx by remember { mutableIntStateOf(2) }
    var digitBuf by remember { mutableStateOf("") }
    var lastChId by remember { mutableStateOf<String?>(null) }
    var behindSec by remember { mutableLongStateOf(0L) }
    var sleepIdx by remember { mutableIntStateOf(0) }
    var sleepAt by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableIntStateOf(General.defaultAspect) }
    var interact by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var infoFor by remember { mutableStateOf("") }
    var badges by remember { mutableStateOf<List<String>>(emptyList()) }
    var retries by remember { mutableIntStateOf(0) }
    var scrubTarget by remember { mutableLongStateOf(-1L) }
    var scrubStart by remember { mutableLongStateOf(0L) }
    var scrubStamp by remember { mutableLongStateOf(0L) }
    var lastStepT by remember { mutableLongStateOf(0L) }
    var holdStartT by remember { mutableLongStateOf(0L) }
    var lastRepeatT by remember { mutableLongStateOf(0L) }
    var turbo by remember { mutableIntStateOf(0) }
    var ctrlLabel by remember { mutableStateOf("") }
    var segs by remember { mutableStateOf<List<Seg>>(emptyList()) }
    var epg by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    var banner by remember { mutableStateOf(false) }
    var bannerTick by remember { mutableIntStateOf(0) }
    var miniBar by remember { mutableStateOf(false) }
    var miniTick by remember { mutableIntStateOf(0) }
    var nextDismissed by remember { mutableStateOf(false) }
    // Schutz: pro Folge nur einmal „Ende“ behandeln (Ende-Meldung des Players und Zeit-Auslöser nicht doppelt)
    var endGuard by remember { mutableStateOf("") }
    // Ton/Untertitel: von Hand gewählt (dann keine Automatik mehr für diesen Titel)
    var manualTracks by remember { mutableStateOf(false) }
    // Quellen, die für diesen Titel schon gescheitert sind; Nachladepausen (Ruckeln); letzter Spulvorgang
    val triedUrls = remember { HashSet<String>() }
    var stalls by remember { mutableStateOf(emptyList<Long>()) }
    var readyOnce by remember { mutableStateOf(false) }
    var lastSeekT by remember { mutableLongStateOf(0L) }
    // Hinweis oben (Quelle gewechselt, Gerät/Qualität) für einige Sekunden
    var notice by remember { mutableStateOf(req.note) }
    // Aus einer eigenen Senderliste/Favoriten gestartet: diese Auswahl im Senderwechsel zeigen, sonst die Kategorie des Senders
    var chanCat by remember {
        mutableStateOf(ZapContext.key?.takeIf { k -> (k == FAV_CAT || ChannelLists.byKey(k) != null) && zapList.any { it.id == req.itemId } }
            ?: liveAll.firstOrNull { it.id == req.itemId }?.group ?: "ALL")
    }
    var playCat by remember { mutableStateOf(chanCat) }
    val rootFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val subsFocus = remember { FocusRequester() }
    val chanFocus = remember { FocusRequester() }
    val catsFocus = remember { FocusRequester() }
    val guideFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val liveFirst = remember { FocusRequester() }
    val playBtn = remember { FocusRequester() }
    val btn = remember { Panel.entries.associateWith { FocusRequester() } }

    val curItem = remember(cur.itemId, liveAll, zapSrc) { liveAll.firstOrNull { it.id == cur.itemId } ?: zapSrc.firstOrNull { it.id == cur.itemId } }
    val curIdx = remember(cur.itemId, zapSrc) { zapSrc.indexOfFirst { it.id == cur.itemId } }
    val step = AppSettings.seekStep * 1000L
    val firstFocus = if (cur.live) liveFirst else playBtn

    // Serien: Folgenliste für Vorherige/Nächste Folge und das Folgen-Menü
    val epList = remember(seriesItem) {
        if (seriesItem != null && seriesItem.kind == Kind.SERIES) seriesItem.episodes.sortedWith(compareBy({ it.season }, { it.num }))
        else emptyList()
    }
    val epIdx = if (cur.live) -1 else epList.indexOfFirst { it.season == cur.season && it.num == cur.episode }
    val hasPrev = epIdx > 0
    val hasNext = epIdx >= 0 && epIdx < epList.lastIndex

    // Diese Werte ändern sich nur selten, obwohl pos alle 500 ms tickt – deshalb "abgeleitet":
    // der Player wird nur neu zusammengesetzt, wenn sich das Ergebnis wirklich ändert.
    val hasDur by remember { derivedStateOf { dur > 0 } }
    val activeSeg by remember { derivedStateOf { val p = pos; segs.firstOrNull { p >= it.start && p < it.end - 1000 } } }
    val showNextCard by remember(hasNext) {
        derivedStateOf { hasNext && !cur.live && dur > 0 && dur - pos < 30_000 && !nextDismissed }
    }

    // Lambdas für Zeitleisten & Co.: gelesen wird erst beim Zeichnen, nicht beim Zusammensetzen.
    val posNow: () -> Long = { if (scrubTarget >= 0) scrubTarget else pos }
    val durNow: () -> Long = { dur }
    val bufNow: () -> Long = { buffered }
    val segsNow: () -> List<Seg> = { segs }

    fun ping() { miniBar = true; miniTick++ }

    fun openChannels() { focusedCh = curItem; showCats = false; chanCat = playCat; showChannels = true }

    fun togglePlay() { if (player.isPlaying) player.pause() else player.play() }

    // Springen: Einzeldruck = eingestellte Sprungweite. Halten beschleunigt in Stufen (Schnellspulen).
    // Mehrere Sprünge werden gesammelt und erst nach kurzer Pause ausgeführt.
    // fromBar = true: Steuerleiste verschwindet sofort, nur Spul-Anzeige und dünne Zeitleiste bleiben.
    fun stepSeek(d: Int, amount: Long = step, fromBar: Boolean = true) {
        val dd = player.duration
        val base = if (scrubTarget >= 0) scrubTarget else player.currentPosition
        if (scrubTarget < 0) scrubStart = base
        var t = (base + d * amount).coerceAtLeast(0L)
        if (dd > 0) t = t.coerceAtMost((dd - 1000).coerceAtLeast(0L))
        scrubTarget = t
        scrubStamp = System.currentTimeMillis()
        lastStepT = scrubStamp
        if (fromBar && controls && panel == Panel.NONE) { controls = false }
        ping()
    }

    fun repeatSeek(d: Int) {
        val now = System.currentTimeMillis()
        if (now - lastRepeatT > 700) { holdStartT = now; turbo = 0 }   // neue Haltephase
        lastRepeatT = now
        val lvl = turboLevel(now - holdStartT)
        turbo = lvl
        val gap = if (lvl == 0) 300L else 220L
        if (now - lastStepT >= gap) stepSeek(d, turboStepMs(lvl, step, player.duration))
    }

    fun switchTo(it: Item, src: List<Item>) {
        if ((cur.live || cur.archive != null) && cur.itemId != it.id) lastChId = cur.itemId
        History.add(ctx, it.id)
        cur = PlayReq(it.title, it.url, it.id, true, 0L)
        zapSrc = src
        showChannels = false
        showCats = false
        showGuide = false
        controls = false
        bannerTick++
    }

    fun zap(d: Int) {
        if (zapSrc.size < 2) return
        val i = if (curIdx < 0) 0 else curIdx
        switchTo(zapSrc[(i + d + zapSrc.size) % zapSrc.size], zapSrc)
    }

    fun lastChannel() {
        val it = zapSrc.firstOrNull { c -> c.id == lastChId } ?: return
        switchTo(it, zapSrc)
    }

    fun commitDigits() {
        val n = digitBuf.toIntOrNull() ?: 0
        digitBuf = ""
        if (n in 1..zapSrc.size) switchTo(zapSrc[n - 1], zapSrc)
        else if (n > 0) flash = Flash(null, "Kein Kanal mit dieser Nummer")
    }

    // Menüs: parent = Menü, zu dem "Links" zurückkehrt (NONE = ganz schließen)
    fun openPanel(p: Panel, parent: Panel = Panel.NONE) {
        if (parent == Panel.NONE) { lastPanel = p; focusSeek = false }
        panelParent = parent
        panel = p
    }

    fun closePanel() {
        val pp = panelParent
        panelParent = Panel.NONE
        panel = if (pp != Panel.NONE) pp else Panel.NONE
    }

    /** Weitere Quellen des laufenden Titels: beim Film aus der Playlist, bei Serien die Quellen der Folge. */
    fun currentAlts(): List<Variant> = when {
        cur.live -> emptyList()
        cur.alts.isNotEmpty() -> cur.alts
        cur.season > 0 || cur.episode > 0 -> epList.firstOrNull { it.season == cur.season && it.num == cur.episode }?.variants.orEmpty()
        else -> emptyList()
    }

    /** Auf eine andere (möglichst kleinere) Quelle wechseln – nach einem Fehler oder bei Ruckeln. true = gewechselt. */
    fun switchSource(reason: String, sameFirst: Boolean = false): Boolean {
        if (cur.live || !PlaybackPrefs.autoFallback) return false
        val alts = currentAlts()
        if (alts.size < 2) return false
        triedUrls.add(cur.url)
        val curI = alts.indexOfFirst { it.url == cur.url }.coerceAtLeast(0)
        val tried = alts.indices.filter { alts[it].url in triedUrls }.toSet()
        val ni = QualityLogic.fallback(alts.map { it.quality }, curI, tried, sameFirst) ?: return false
        // 4K musste wegen Ruckeln/Decoder weichen: zählt für die gelernte Gerätegrenze (ein toter Link nicht)
        if (!sameFirst && QualityLogic.rank(alts[curI].quality) >= 4 && QualityLogic.rank(alts[ni].quality) < 4) PlaybackPrefs.note4kTrouble(ctx)
        val at = player.currentPosition.coerceAtLeast(0L)
        val t = alts[ni]
        stalls = emptyList()
        cur = cur.copy(url = t.url, startMs = if (at > 0) at else cur.startMs, note = null)
        notice = reason + " – wechsle auf " + (t.quality ?: "andere Quelle")
        return true
    }

    /** Ton und Untertitel nach Einstellungen und gemerkter Wahl für diesen Titel. */
    fun applyTrackPrefs() {
        val mem = TrackMemory.get(ctx, cur.itemId)
        // Wichtig: Mit Kopfhörern (nur Stereo-Ausgang) schloss der Player sonst eine 5.1-Tonspur (oft die deutsche)
        // automatisch aus und nahm eine Stereo-Spur in anderer Sprache. Die Sprache zählt, der Decoder mischt herunter.
        // Die Option gibt es nur beim DefaultTrackSelector (Standard von ExoPlayer), nicht beim allgemeinen Parameter-Builder.
        (player.trackSelectionParameters as? androidx.media3.exoplayer.trackselection.DefaultTrackSelector.Parameters)?.let {
            player.trackSelectionParameters = it.buildUpon().setConstrainAudioChannelCountToDeviceCapabilities(false).build()
        }
        var p = player.trackSelectionParameters.buildUpon()
        val audios = TrackLogic.audioPrefs(AppSettings.audioLang, AppSettings.audioLang2, mem?.first)
        p = p.setPreferredAudioLanguages(*audios.toTypedArray())
        val subOn = if (mem != null) mem.second != null else AppSettings.subMode == TrackLogic.SUB_ALWAYS
        val subLang = mem?.second ?: AppSettings.subLangEffective()
        p = p.setPreferredTextLanguages(subLang)
            .setPreferredTextRoleFlags(if (AppSettings.ccPref) C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND else 0)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subOn)
        player.trackSelectionParameters = p.build()
        manualTracks = mem != null
    }

    /**
     * Sicherheitsnetz für die Tonsprache: ändert sich die Auswahl von allein (Kopfhörer an/aus, Ausgabegerät gewechselt),
     * wird wieder die bevorzugte Sprache gewählt, sofern der Player sie abspielen kann. Nicht während das Tonmenü offen ist.
     */
    var audioFixTries by remember { mutableIntStateOf(0) }
    fun ensureAudio() {
        if (cur.live || panel != Panel.NONE) return
        val prefs = TrackLogic.audioPrefs(AppSettings.audioLang, AppSettings.audioLang2, TrackMemory.get(ctx, cur.itemId)?.first)
        if (prefs.isEmpty()) return
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (groups.size < 2) return
        for (lang in prefs) {
            val g = groups.firstOrNull { gr -> (0 until gr.length).any { TrackLogic.guess(gr.getTrackFormat(it).language, gr.getTrackFormat(it).label) == lang } } ?: continue
            val idx = (0 until g.length).first { TrackLogic.guess(g.getTrackFormat(it).language, g.getTrackFormat(it).label) == lang }
            if (g.isSelected && g.isTrackSelected(idx)) return
            if (g.isTrackSupported(idx)) {
                if (audioFixTries >= 3) return
                audioFixTries++
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(g.mediaTrackGroup, idx)).build()
                return
            }
            // Gewünschte Sprache vorhanden, aber vom Gerät mit diesem Ausgang nicht abspielbar
            if (audioFixTries < 3) { audioFixTries++; notice = "Tonspur „" + lang.uppercase() + "“ ist mit diesem Ausgang nicht abspielbar – andere Spur im Menü wählen" }
            return
        }
    }

    /**
     * Fehler kommt vom Ton-Decoder: andere abspielbare Tonspur wählen (bevorzugt die zweite Sprache, sonst irgendeine) und neu
     * starten. Liefert true, wenn so weitergespielt wird.
     */
    var audioFallbacks by remember { mutableIntStateOf(0) }
    fun audioFallback(): Boolean {
        val e0 = player.playerError as? androidx.media3.exoplayer.ExoPlaybackException ?: return false
        val rf = e0.rendererFormat
        if (rf?.sampleMimeType?.startsWith("audio/") != true) return false
        if (audioFallbacks >= 3) return false
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val prefs = TrackLogic.audioPrefs(AppSettings.audioLang, AppSettings.audioLang2, TrackMemory.get(ctx, cur.itemId)?.first)
        var pick: Pair<androidx.media3.common.Tracks.Group, Int>? = null
        for (lang in prefs.drop(1) + listOf("")) {
            for (g in groups) for (i in 0 until g.length) {
                if (!g.isTrackSupported(i) || g.getTrackFormat(i).id == rf.id) continue
                if (lang.isEmpty() || TrackLogic.guess(g.getTrackFormat(i).language, g.getTrackFormat(i).label) == lang) { pick = g to i; break }
            }
            if (pick != null) break
        }
        val p = pick ?: return false
        audioFallbacks++
        val at = player.currentPosition
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(p.first.mediaTrackGroup, p.second)).build()
        player.prepare()
        player.seekTo(at)
        player.playWhenReady = true
        pError = false
        notice = "Diese Tonspur kann hier nicht abgespielt werden – andere Spur gewählt"
        return true
    }

    /** „Nur bei fremder Tonspur“: Untertitel an, wenn der Ton in einer Sprache ist, die man nicht eingestellt hat. */
    fun autoSubs() {
        if (manualTracks || cur.live || AppSettings.subMode != TrackLogic.SUB_FOREIGN) return
        val audio = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { TrackLogic.guess(g.getTrackFormat(it).language, g.getTrackFormat(it).label) } }
        val want = TrackLogic.subsWanted(AppSettings.subMode, audio, AppSettings.subLangEffective(), AppSettings.audioLang)
        val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        if (want == off) player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !want).build()
    }

    /** Von Hand im Menü gewählt: für diesen Titel merken und als Vorgabe für die nächste Folge setzen. */
    fun rememberTracks() {
        manualTracks = true
        fun langOf(type: Int): String? = player.currentTracks.groups.firstOrNull { it.type == type && it.isSelected }
            ?.let { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { TrackLogic.guess(g.getTrackFormat(it).language, g.getTrackFormat(it).label) } }
        val a = langOf(C.TRACK_TYPE_AUDIO)
        val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val t = if (off) null else langOf(C.TRACK_TYPE_TEXT)
        if (a == null && t == null && !off) return
        TrackMemory.set(ctx, cur.itemId, a, t)
        var p = player.trackSelectionParameters.buildUpon()
        if (a != null) p = p.setPreferredAudioLanguages(*TrackLogic.audioPrefs(AppSettings.audioLang, AppSettings.audioLang2, a).toTypedArray())
        if (t != null) p = p.setPreferredTextLanguages(t)
        player.trackSelectionParameters = p.build()
    }

    fun saveProgress() {
        if (cur.live || cur.archive != null) return
        // Dauer unbekannt (manche Anbieter-Streams melden keine): trotzdem die Stelle merken, sonst bliebe „Weiterschauen“ auf der alten Folge
        val d = player.duration.coerceAtLeast(0L)
        val p = player.currentPosition
        if (p <= 0) return
        val now = System.currentTimeMillis()
        val si = seriesItem
        var next: Entry? = null
        if (si != null && (cur.season > 0 || cur.episode > 0)) {
            // Fortschritt dieser Folge (Balken/Haken in der Folgenliste)
            EpProgress.put(ctx, cur.itemId, cur.season, cur.episode, p, d)
            // Hier frisch berechnen: diese Funktion läuft auch im 5-Sekunden-Takt mit dem Stand vom Start des Players
            val i = epList.indexOfFirst { it.season == cur.season && it.num == cur.episode }
            if (i >= 0 && i < epList.lastIndex) {
                val nx = epList[i + 1]
                val pref = VariantPref.get(ctx, si.id)
                next = Entry(cur.itemId, si.title + " S" + nx.season + "E" + nx.num, pickVariant(nx, pref.first, pref.second, pref.third, PlaybackPrefs.cap(ctx)).url,
                    0L, 0L, now, nx.season, nx.num, true)
            }
        }
        // Film: Fortschritt merken, damit die Detailseite nach dem Ansehen „Erneut abspielen“ zeigt
        if (si == null && cur.season == 0 && cur.episode == 0) EpProgress.put(ctx, cur.itemId, 0, 0, p, d)
        WatchStore.save(ctx, Entry(cur.itemId, cur.title, cur.url, p, d, now, cur.season, cur.episode), next)
    }

    fun playEpisode(ep: Episode) {
        val si = seriesItem ?: return
        saveProgress()
        val saved = VariantPref.get(ctx, si.id)
        val v = pickVariant(ep, saved.first, saved.second, saved.third, PlaybackPrefs.cap(ctx))
        History.add(ctx, si.id)
        scrubTarget = -1L
        cur = PlayReq(si.title + " S" + ep.season + "E" + ep.num, v.url, si.id, false, 0L, cur.tmdbId, ep.season, ep.num)
        player.setMediaItem(MediaItem.fromUri(v.url), 0L)
        player.prepare()
        player.playWhenReady = true
        ping()
    }

    // ---------------------------------------------------------------- Archiv (Catchup)
    // Programm des Senders, dessen Archiv-Sendung gerade läuft (für „Vorherige/Nächste Sendung“ und das Weiterspielen)
    var archProg by remember { mutableStateOf<List<EpgEntry>>(emptyList()) }
    LaunchedEffect(cur.archive?.chId) {
        archProg = emptyList()
        val ch = curItem
        if (cur.archive != null && ch != null) archProg = EpgFull.get(ch, network = true)
    }
    val archNow = System.currentTimeMillis() / 1000
    val archPrev = cur.archive?.let { a -> CatchupLogic.neighbour(archProg, a.prog.start, -1)?.takeIf { Catchup.canPlay(curItem, it, archNow) } }
    val archNext = cur.archive?.let { a -> CatchupLogic.neighbour(archProg, a.prog.start, 1)?.takeIf { Catchup.canPlay(curItem, it, archNow) } }

    /** Sendung aus dem Archiv des Senders [ch] abspielen (auch die laufende von Anfang an). */
    fun playArchive(ch: Item, e: EpgEntry) {
        val url = Catchup.urlFor(ch, e)
        if (url == null) { flash = Flash(null, "Nicht im Archiv verfügbar"); return }
        if (cur.live && cur.itemId != ch.id) lastChId = cur.itemId
        scrubTarget = -1L
        cur = PlayReq(e.title, url, ch.id, false, 0L, archive = ArchiveRef(ch.id, ch.title, e))
        showGuide = false
        showChannels = false
        showCats = false
        panel = Panel.NONE; panelParent = Panel.NONE
        controls = false
        flash = Flash(null, dayLabel(e.start) + "  " + hhmm(e.start))
    }

    /** Vom Archiv zurück zum Live-Bild desselben Senders. */
    fun backToLive() {
        val ch = curItem ?: return
        switchTo(ch, zapSrc)
        flash = Flash(PIcon.Play, "LIVE")
    }

    // Wird vom Player aufgerufen, wenn ein Film/eine Folge zu Ende ist (immer mit dem neuesten Stand)
    val onEnded by rememberUpdatedState({
        val ch = curItem
        if (endGuard != cur.url) {
            endGuard = cur.url
            if (cur.archive != null && ch != null) {
                // Archiv: nahtlos mit der nächsten Sendung weiter, sonst zurück zum Live-Bild
                val nx = archNext
                if (nx != null) playArchive(ch, nx) else backToLive()
            } else if (!cur.live && hasNext && AppSettings.autoNext && !nextDismissed) playEpisode(epList[epIdx + 1])
        }
    })

    var lastBackAt by remember { mutableLongStateOf(0L) }
    fun handleBack() {
        // Doppelte Zurück-Meldungen (Taste + System) dürfen nicht erst das Menü und gleich danach den Stream schließen
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 350) return
        lastBackAt = now
        when {
            scrubTarget >= 0 -> scrubTarget = -1L
            panel != Panel.NONE -> closePanel()
            // Guide: eigener Zurück-Schritt (Sendung → Sender → Kategorie → schließen)
            showGuide -> GuideBack.handler?.invoke() ?: run { showGuide = false }
            showCats -> showCats = false
            showChannels -> showChannels = false
            controls -> controls = false
            showNextCard -> nextDismissed = true
            cur.archive != null && curItem != null -> backToLive()
            else -> onBack()
        }
    }

    BackHandler { handleBack() }

    DisposableEffect(Unit) {
        // Hängt der Player lange im Nachladen (Verbindung tot, ohne dass ein Fehler kommt): bis zu zweimal neu verbinden
        var bufSince = 0L
        var wdTries = 0
        val tick = object : Runnable {
            override fun run() {
                saveProgress()
                val nowT = System.currentTimeMillis()
                if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady && !pError) {
                    if (bufSince == 0L) bufSince = nowT
                    else if (nowT - bufSince > 30_000L && wdTries < 2) {
                        wdTries++; bufSince = nowT
                        msg = "Verbindung hängt – neuer Versuch ($wdTries/2)"
                        player.prepare()
                    }
                } else {
                    bufSince = 0L
                    if (player.playbackState == Player.STATE_READY) wdTries = 0
                }
                h.postDelayed(this, 5000)
            }
        }
        fun showInfo(w: Int, hh: Int) {
            if (w <= 0 || hh <= 0 || cur.live) return
            val q = qualityName(maxOf(hh, w * 9 / 16))
            val prev = QualityStore.get(cur.itemId)
            if (prev == null || rankQ(q) > rankQ(prev)) QualityStore.put(ctx, cur.itemId, q)
            val key = cur.url + "|" + q
            if (infoFor == key) return
            infoFor = key
            val vf = player.videoFormat
            val fps = if (vf != null && vf.frameRate > 0) " · ${Math.round(vf.frameRate)} fps" else ""
            val vc = codecName(vf?.sampleMimeType)
            val codec = if (vc.isBlank()) "" else " · $vc"
            val af = player.audioFormat
            val au = if (af != null) " · " + (codecName(af.sampleMimeType).ifBlank { "Audio" }) + (if (af.channelCount > 0) " " + channelLabel(af.channelCount) else "") else ""
            info = "${w}×${hh}  ·  $q$codec$fps$au"
            val b = ArrayList<String>()
            b.add(q)
            if (vc.isNotBlank()) b.add(vc)
            if (af != null && af.channelCount > 2) b.add(channelLabel(af.channelCount))
            badges = b
        }
        val l = object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                pError = true
                val status = httpStatusOf(e)
                Diag.report("Wiedergabe: " + e.errorCodeName + (if (status > 0) " (HTTP $status)" else ""))
                val decoder = e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                    e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                // Gewählte Tonspur lässt sich auf diesem Gerät/Ausgang nicht decodieren: nicht aufgeben, sondern die Spur wechseln
                // und weiterspielen (Bild und Position bleiben). Nie die ganze Quelle verwerfen, nur weil der Ton nicht geht.
                if (decoder && audioFallback()) return
                if (retries < 3 && !isPermanentHttp(status) && !decoder) {
                    retries++; msg = "Verbindung unterbrochen – neuer Versuch ($retries/3)"
                    h.postDelayed({ player.prepare() }, 1500L * retries)
                } else {
                    // Endgültig gescheitert: Link merken (nur wenn es am Link liegt) und eine andere Quelle versuchen
                    if (cur.archive == null && DeadLinkLogic.counts(status, decoder, DeviceCaps.online(ctx))) DeadLinks.fail(ctx, cur.url, DeadLinkLogic.permanent(status))
                    if (switchSource(if (decoder) "Format nicht abspielbar" else "Quelle gestört", sameFirst = !decoder)) { pError = false; msg = null }
                    else if (cur.archive != null) msg = "Diese Sendung ist im Archiv des Anbieters nicht abrufbar.  Zurück = Live-Sender."
                    else msg = Lang.tr(playbackErrorText(e.errorCode, status, cur.live)) + "  " + Lang.tr("Zurück mit der Zurück-Taste.")
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                pState = state
                if (state == Player.STATE_BUFFERING || state == Player.STATE_READY) pError = false
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    msg = null; retries = 0
                    if (player.duration > 0 && player.currentPosition < player.duration - 3000) endGuard = ""
                    if (!readyOnce) { readyOnce = true; DeadLinks.ok(ctx, cur.url) }
                }
                // Ruckeln: Nachladen mitten in der Wiedergabe, nicht direkt nach dem Spulen
                if (state == Player.STATE_BUFFERING && readyOnce && player.playWhenReady &&
                    System.currentTimeMillis() - lastSeekT > 4000
                ) {
                    val now = System.currentTimeMillis()
                    stalls = StallLogic.add(stalls, now)
                    if (StallLogic.tooMany(stalls, now)) {
                        // Automatik: Puffer für die nächste Wiedergabe vergrößern (langsames Internet)
                        val before = AppSettings.bufferLearned
                        AppSettings.learnBuffer(ctx)
                        if (AppSettings.bufferLearned != before) notice = "Ruckelt – Puffer wird beim nächsten Start größer"
                        if (!cur.live) switchSource("Ruckelt") else stalls = emptyList()
                    }
                }
                if (state == Player.STATE_ENDED) onEnded()
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) lastSeekT = System.currentTimeMillis()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { pWhenReady = playWhenReady }
            override fun onTracksChanged(tracks: Tracks) {
                tracksVer++
                ensureAudio()
                autoSubs()
                val vf = player.videoFormat
                if (vf != null) showInfo(vf.width, vf.height)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) { showInfo(videoSize.width, videoSize.height) }
        }
        player.addListener(l)
        applyTrackPrefs()
        h.postDelayed(tick, 5000)
        AppNav.playing = true
        onDispose {
            AppNav.playing = false
            // Detailseite merkt sich die zuletzt gespielte Folge (auch nach „Nächste Folge“)
            if (!cur.live && cur.archive == null) DetailReturn.set(cur.itemId, cur.season, cur.episode)
            saveProgress()
            h.removeCallbacksAndMessages(null)
            player.removeListener(l)
            player.release()
        }
    }

    // Pausiert, wenn die App in den Hintergrund geht (Home-Taste, anderer Dialog), und macht danach weiter.
    val lifecycle = remember(ctx) { findActivity(ctx)?.lifecycle }
    DisposableEffect(lifecycle) {
        var resumeAfter = false
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_START -> foreground = true
                Lifecycle.Event.ON_STOP -> foreground = false
                Lifecycle.Event.ON_PAUSE -> {
                    resumeAfter = player.playWhenReady
                    player.pause()
                    saveProgress()
                }
                Lifecycle.Event.ON_RESUME -> {
                    // Rückkehr aus Standby/anderer App: hängt der Player nach einem Fehler, neu verbinden
                    if (player.playerError != null && resumeAfter) { retries = 0; player.prepare() }
                    if (resumeAfter) {
                        resumeAfter = false
                        if (cur.live) player.seekToDefaultPosition()
                        player.play()
                    }
                    ScreenAwake.apply()
                }
                else -> {}
            }
        }
        lifecycle?.addObserver(obs)
        onDispose { lifecycle?.removeObserver(obs) }
    }

    // Netz wieder da (WLAN-Abbruch, Router-Neustart): hängt der Player im Fehler, sofort neu verbinden
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                h.post {
                    if (player.playerError != null) {
                        retries = 0
                        msg = "Verbindung wieder da – lade neu …"
                        if (cur.live) player.seekToDefaultPosition()
                        player.prepare()
                    }
                }
            }
        }
        val ok = cm != null && runCatching { cm.registerDefaultNetworkCallback(cb) }.isSuccess
        onDispose { if (ok) runCatching { cm?.unregisterNetworkCallback(cb) } }
    }
    // Wachhaltung nicht möglich: Hinweis statt stillem Versagen
    var awakeWarn by remember { mutableStateOf(false) }
    LaunchedEffect(ScreenAwake.unavailable) {
        if (ScreenAwake.unavailable) { awakeWarn = true; delay(8000); awakeWarn = false }
    }

    LaunchedEffect(cur.url) {
        readyOnce = false; stalls = emptyList()
        retries = 0; msg = null; buffering = true; segs = emptyList(); pos = 0; dur = 0; buffered = 0
        infoFor = ""; info = null; badges = emptyList(); nextDismissed = false
        if (cur.live) bannerTick++
        scrubTarget = -1L
        val loaded = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (loaded != cur.url) {
            player.setMediaItem(MediaItem.fromUri(cur.url), if (cur.startMs > 0) cur.startMs else 0L)
            player.prepare(); player.playWhenReady = true
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!cur.live) {
                pos = player.currentPosition
                dur = player.duration.coerceAtLeast(0)
                buffered = player.bufferedPosition
                // Manche Server melden das Ende nie: kurz vor Schluss selbst zur nächsten Folge
                if (!cur.live && cur.archive == null && hasNext && AppSettings.autoNext && !nextDismissed && dur > 0 && dur - pos in 0..700L && player.isPlaying) onEnded()
            }
            delay(500)
        }
    }
    LaunchedEffect(scrubStamp) {
        if (scrubTarget >= 0) {
            delay(450)
            val t = scrubTarget
            if (t >= 0) { player.seekTo(t); pos = t }
            scrubTarget = -1L
            turbo = 0
        }
    }
    LaunchedEffect(cur.url, hasDur) {
        if (!cur.live && cur.tmdbId > 0 && hasDur && AppSettings.skipIntro) segs = IntroDb.get(cur.tmdbId, cur.season, cur.episode, dur)
    }
    // Optional: Intro automatisch überspringen
    LaunchedEffect(activeSeg) {
        val s = activeSeg
        if (s != null && AppSettings.autoSkipIntro && s.label.startsWith("Intro")) player.seekTo(s.end)
        // Abspann beginnt: gleich die nächste Folge (nur wenn die Abspann-Zeit bekannt ist)
        if (s != null && AppSettings.autoSkipOutro && s.label.startsWith("Abspann") && hasNext && !nextDismissed && player.isPlaying) playEpisode(epList[epIdx + 1])
    }
    // Überspringen-Knopf: 10 s sichtbar, dann ausgeblendet. Öffnet man in dieser Zeit (oder danach, solange der Abschnitt läuft)
    // die Steuerleiste, erscheint er wieder.
    var segVisible by remember { mutableStateOf(false) }
    LaunchedEffect(activeSeg) {
        segVisible = activeSeg != null
        if (activeSeg != null) { delay(10_000); segVisible = false }
    }
    // Überspringen: Abspann = gleich die nächste Folge (kaum jemand will danach noch Sekunden Abspann sehen).
    // Danach Menü zu und Fokus zurück, sonst blieb man im Menü hängen.
    fun skipSeg(sg: Seg) {
        segVisible = false
        controls = false
        if (sg.label.startsWith("Abspann") && hasNext) playEpisode(epList[epIdx + 1]) else player.seekTo(sg.end)
        runCatching { rootFocus.requestFocus() }
    }
    // Titel der Folge (TMDB, sonst Name aus der Playlist) für Einblendung und Steuerleiste
    var epName by remember(cur.itemId, cur.season, cur.episode) { mutableStateOf<String?>(null) }
    var epBanner by remember { mutableStateOf(false) }
    LaunchedEffect(cur.itemId, cur.season, cur.episode) {
        if (cur.live || (cur.season <= 0 && cur.episode <= 0)) return@LaunchedEffect
        epBanner = false
        var name: String? = null
        if (cur.tmdbId > 0) name = runCatching { MetaRepo.season(cur.tmdbId, cur.season, null).firstOrNull { it.num == cur.episode }?.name }.getOrNull()
        if (name.isNullOrBlank()) {
            val t = epList.firstOrNull { it.season == cur.season && it.num == cur.episode }?.title?.trim()
            name = t?.takeIf { it.isNotBlank() && !Regex("[Ss]\\d{1,3}\\s*[Ee]\\d{1,4}").containsMatchIn(it) && !it.equals(seriesItem?.title, true) }
        }
        epName = name
        epBanner = true
        delay(5000)
        epBanner = false
    }
    LaunchedEffect(cur.url) {
        epg = emptyList()
        val it = curItem
        if (cur.live && it != null) {
            while (true) { epg = Epg.now(it, network = true); delay(60_000) }   // OnAir: the playing channel
        }
    }
    // Steuerleiste nach einstellbarer Zeit ohne Tastendruck ausblenden. Jeder Tastendruck startet die Zeit neu.
    LaunchedEffect(controls, panel, showChannels, showGuide, playing) {
        if (controls && panel == Panel.NONE && !showChannels && !showGuide && (playing || cur.live)) {
            snapshotFlow { interact }.collectLatest {
                delay(AppSettings.hideSec * 1000L)
                controls = false
            }
        }
    }
    LaunchedEffect(controls) { if (!controls) { lastPanel = Panel.NONE; focusSeek = false; ctrlLabel = "" } }
    LaunchedEffect(controls, panel, showChannels, showCats, showGuide) {
        delay(40)
        // Der Fokus wird bis zu 6-mal versucht: Listen brauchen manchmal einen Moment, bis das Ziel gezeichnet ist.
        // Ohne Fokus würden die Tasten ins Leere laufen und die Liste wirkt "festgefahren".
        repeat(12) {
            val ok = runCatching {
                when {
                    panel != Panel.NONE -> panelFocus.requestFocus()
                    showGuide -> guideFocus.requestFocus()
                    showChannels && showCats -> catsFocus.requestFocus()
                    showChannels -> chanFocus.requestFocus()
                    controls -> (if (lastPanel != Panel.NONE) btn.getValue(lastPanel) else if (focusSeek && !cur.live) seekFocus else firstFocus).requestFocus()
                    else -> rootFocus.requestFocus()
                }
            }.getOrDefault(false)
            if (ok) return@LaunchedEffect
            delay(70)
        }
    }
    // Senderliste und TV-Guide schließen sich selbst, wenn eine Weile keine Taste gedrückt wird.
    LaunchedEffect(showChannels) {
        if (showChannels) snapshotFlow { interact }.collectLatest { delay(15_000); showChannels = false }
    }
    LaunchedEffect(showGuide) {
        if (showGuide) snapshotFlow { interact }.collectLatest { delay(180_000); showGuide = false }
    }
    LaunchedEffect(showChannels) { if (!showChannels) inHeader = false }
    LaunchedEffect(sleepAt) {
        if (sleepAt > 0L) {
            delay((sleepAt - System.currentTimeMillis()).coerceAtLeast(0L))
            if (sleepAt > 0L) {
                player.pause()
                sleepAt = 0L; sleepIdx = 0
                flash = Flash(PIcon.Pause, "Sleep-Timer")
            }
        }
    }
    // Abstand zum Live-Bild: bei HLS vom Player, sonst als Summe der Pausenzeit
    LaunchedEffect(cur.url, cur.live) {
        behindSec = 0L
        while (cur.live) {
            delay(1000)
            val off = player.currentLiveOffset
            if (off != C.TIME_UNSET) behindSec = if (off > 3000) off / 1000 else 0L
            else if (!player.playWhenReady && player.playbackState == Player.STATE_READY) behindSec++
        }
    }
    LaunchedEffect(cur.itemId, cur.url) { audioFixTries = 0 }
    LaunchedEffect(cur.itemId) {
        triedUrls.clear()
        applyTrackPrefs()
    }
    LaunchedEffect(notice) { if (notice != null) { delay(5000); notice = null } }
    LaunchedEffect(digitBuf) {
        if (digitBuf.isNotEmpty()) { delay(1800); commitDigits() }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(900); flash = null } }
    LaunchedEffect(info) { if (info != null) { delay(6000); info = null } }
    LaunchedEffect(bannerTick) { if (bannerTick > 0 && !controls) { banner = true; delay(4000); banner = false } }
    LaunchedEffect(miniTick) { if (miniTick > 0) { delay(1800); miniBar = false } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                interact = System.currentTimeMillis()
                // Zurück selbst behandeln: sonst nimmt Compose zuerst nur den Fokus weg und das Menü bleibt offen
                if (e.key == Key.Back) {
                    if (e.type == KeyEventType.KeyUp) handleBack()
                    true
                } else false
            }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val k = e.key
                if (e.nativeKeyEvent.repeatCount > 0) {
                    if ((k == Key.DirectionLeft || k == Key.DirectionRight) && !cur.live && !controls && panel == Panel.NONE && !showChannels && !showGuide) {
                        repeatSeek(if (k == Key.DirectionRight) 1 else -1)
                    }
                    return@onKeyEvent (k == Key.DirectionLeft || k == Key.DirectionRight)
                }
                when {
                    k == Key.MediaPlayPause || k == Key.Spacebar -> { togglePlay(); true }
                    k == Key.MediaPlay -> { player.play(); true }
                    k == Key.MediaPause -> { player.pause(); true }
                    k == Key.MediaFastForward && !cur.live -> { stepSeek(1); true }
                    k == Key.MediaRewind && !cur.live -> { stepSeek(-1); true }
                    k == Key.MediaNext && cur.live -> { zap(1); true }
                    k == Key.MediaPrevious && cur.live -> { zap(-1); true }
                    k == Key.MediaNext && cur.archive != null -> { val ch = curItem; val nx = archNext; if (ch != null && nx != null) playArchive(ch, nx) else backToLive(); true }
                    k == Key.MediaPrevious && cur.archive != null -> { val ch = curItem; val pv = archPrev; if (ch != null && pv != null) playArchive(ch, pv); true }
                    k == Key.MediaNext && !cur.live && hasNext -> { playEpisode(epList[epIdx + 1]); true }
                    k == Key.MediaPrevious && !cur.live && hasPrev -> { playEpisode(epList[epIdx - 1]); true }
                    cur.live && panel == Panel.NONE && !showChannels && !showGuide && digitOf(k) >= 0 -> {
                        val d = digitOf(k)
                        if (digitBuf.isEmpty() && d == 0) lastChannel()
                        else if (digitBuf.length < 4) { digitBuf += d }
                        true
                    }
                    !cur.live && !controls && panel == Panel.NONE && !showChannels && !showGuide && digitOf(k) >= 0 && player.duration > 0 -> {
                        // Zifferntaste springt zu 0–90 % des Films
                        val n = digitOf(k)
                        player.seekTo(player.duration * n / 10)
                        flash = Flash(null, (n * 10).toString() + " %")
                        ping()
                        true
                    }
                    cur.live && digitBuf.isNotEmpty() && (k == Key.DirectionCenter || k == Key.Enter) -> { commitDigits(); true }
                    controls || panel != Panel.NONE || showChannels || showGuide -> false
                    else -> when (k) {
                        Key.DirectionLeft -> { if (cur.live) openChannels() else stepSeek(-1); true }
                        Key.DirectionRight -> { if (cur.live) { controls = false; showGuide = true } else stepSeek(1); true }
                        Key.DirectionCenter, Key.Enter -> {
                            val seg = activeSeg
                            if (showNextCard) { playEpisode(epList[epIdx + 1]); true }
                            else if (seg != null && segVisible) { skipSeg(seg); true }
                            else if (cur.live) { controls = true; true }
                            // Wie bei YouTube, Netflix & Co.: Während/kurz nach dem Spulen bestätigt OK die Zielposition und spielt weiter,
                            // statt zu pausieren (das passierte, weil man das Spulen „bestätigen“ will).
                            else if (scrubTarget >= 0) {
                                val t = scrubTarget
                                player.seekTo(t); pos = t
                                scrubTarget = -1L; turbo = 0
                                player.play()
                                true
                            }
                            else if (System.currentTimeMillis() - lastStepT < 1500L) {
                                if (!player.isPlaying) player.play()
                                true
                            }
                            else {
                                val was = player.isPlaying
                                togglePlay()
                                flash = Flash(if (was) PIcon.Pause else PIcon.Play, null)
                                if (was) { focusSeek = false; controls = true }
                                true
                            }
                        }
                        Key.DirectionUp -> { if (cur.live) zap(-1) else { focusSeek = true; controls = true }; true }
                        Key.DirectionDown -> { if (cur.live) zap(1) else { focusSeek = false; controls = true }; true }
                        Key.Menu -> { focusSeek = false; controls = true; true }
                        else -> false
                    }
                }
            }
            .focusable()
    ) {
        // ---------------------------------------------------------------- Video
        // Bildformat (im verkleinerten Guide-Bild immer „Einpassen“). Zoom: Bildfläche größer als der Bildschirm, mittig;
        // 4:3: Fläche im Seitenverhältnis 4:3, mittig.
        val am = if (showGuide) 0 else aspect
        val videoMod = if (showGuide) Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 24.dp).size(width = 400.dp, height = 225.dp).clip(RoundedCornerShape(DS.RadiusM))
        else when (am) {
            3, 4 -> {
                val z = if (am == 3) 1.15f else 1.33f
                Modifier.fillMaxSize().layout { m, c ->
                    val w = (c.maxWidth * z).toInt(); val hh = (c.maxHeight * z).toInt()
                    val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, hh))
                    layout(c.maxWidth, c.maxHeight) { pl.place((c.maxWidth - w) / 2, (c.maxHeight - hh) / 2) }
                }
            }
            5 -> Modifier.align(Alignment.Center).fillMaxHeight().aspectRatio(4f / 3f)
            else -> Modifier.fillMaxSize()
        }
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    this.player = player
                }
            },
            update = { v ->
                // nur anwenden, wenn sich wirklich etwas geändert hat (spart Arbeit bei jedem Neuzeichnen)
                val styleKey = "$am|${AppSettings.subSize}|${AppSettings.subColor}|${AppSettings.subBg}|${AppSettings.subEdge}"
                if (v.tag != styleKey) {
                    v.tag = styleKey
                    v.resizeMode = when (am) {
                        1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        2, 5 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    applyStyle(v)
                }
            },
            modifier = videoMod
        )

        // ---------------------------------------------------------------- Meldungen
        if (buffering && msg == null && !showGuide) {
            Spinner(Accent2, Modifier.align(Alignment.Center).size(48.dp))
        }
        msg?.let { m ->
            Box(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(DS.RadiusM)).background(PT.PanelBg)
                    .padding(horizontal = 28.dp, vertical = 18.dp)
            ) { Text(m, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
        }
        flash?.let { fl ->
            if (fl.icon != null) {
                Box(
                    Modifier.align(Alignment.Center).size(96.dp).clip(CircleShape).background(Color(0x99000000)),
                    contentAlignment = Alignment.Center
                ) { PlayerIcon(fl.icon, Color.White, size = 44.dp) }
            }
            if (fl.text != null) {
                Text(
                    fl.text, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x99000000))
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }
        if (!cur.live) {
            ScrubCard({ scrubTarget }, { scrubStart }, { turbo }, Modifier.align(Alignment.Center))
        }
        if (AppSettings.showInfo && !showGuide && !cur.live && !controls) {
            info?.let {
                Text(
                    it, color = Color(0xD9FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.Medium, style = TNUM,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = PT.MarginX, top = PT.MarginY)
                        .clip(RoundedCornerShape(DS.RadiusS)).background(Color(0x80000000)).padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // Folgentitel beim Start einer Folge
        if (epBanner && !controls && panel == Panel.NONE && !showGuide && !cur.live) {
            Column(
                Modifier.align(Alignment.TopStart).padding(start = PT.MarginX, top = PT.MarginY)
                    .clip(RoundedCornerShape(DS.RadiusS)).background(Color(0x80000000)).padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("Staffel " + cur.season + " · Folge " + cur.episode, color = Color(0xB3FFFFFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                epName?.let { Text(it, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true) }
            }
        }

        // Mini-Zeitleiste beim Spulen (wenn die Steuerleiste nicht sichtbar ist)
        if (!controls && !cur.live) {
            MiniSeekDock({ (miniBar || scrubTarget >= 0) && dur > 0 }, posNow, durNow, bufNow, segsNow)
        }

        notice?.let { n ->
            Text(
                n, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = PT.MarginY + 48.dp)
                    .clip(RoundedCornerShape(DS.RadiusS)).background(Color(0xCC1F2937)).padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }
        if (awakeWarn) {
            Text(
                "Bildschirm kann nicht wach gehalten werden – der Bildschirmschoner kann starten.",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = PT.MarginY)
                    .clip(RoundedCornerShape(DS.RadiusS)).background(Color(0xCC8B1A1A)).padding(horizontal = 18.dp, vertical = 10.dp)
            )
        }

        if (digitBuf.isNotEmpty()) {
            Text(
                digitBuf, color = Color.White, fontSize = 56.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = PT.MarginY, end = PT.MarginX)
                    .clip(RoundedCornerShape(DS.RadiusM)).background(Color(0xCC000000)).padding(horizontal = 28.dp, vertical = 12.dp)
            )
        }

        // Kurzer Sender-Banner beim Umschalten
        if (banner && cur.live && !controls && !showChannels && !showGuide) {
            LiveInfoBlock(
                curItem, cur.title, if (curIdx >= 0) curIdx + 1 else 0, epg,
                Modifier.align(Alignment.BottomStart).padding(start = PT.MarginX, bottom = PT.MarginY)
                    .clip(RoundedCornerShape(DS.RadiusL)).background(Color(0xCC000000)).padding(20.dp)
            )
        }

        if (panel == Panel.SUBSTYLE) {
            val fg = Color(SubStyle.COLORS[AppSettings.subColor].second.toInt())
            val bg = Color(SubStyle.BGS[AppSettings.subBg].second.toInt())
            Text(
                "So sehen deine Untertitel aus", color = fg, fontSize = (22 * SubStyle.SIZES[AppSettings.subSize].second).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp, end = 480.dp).background(bg).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        if (AppSettings.clockOn && !controls && panel == Panel.NONE) PClock()
        if (techOn) PTechOverlay(player)

        // ---------------------------------------------------------------- Überspringen & Nächste Folge
        val seg0 = activeSeg?.takeIf { segVisible || controls }
        if ((seg0 != null || showNextCard) && panel == Panel.NONE && !showChannels && !showGuide) {
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = PT.MarginX, bottom = if (controls) 236.dp else 84.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Nur EIN Knopf: Abspann-Marke und „Nächste Folge“ zeigen dasselbe und werden zusammengelegt
                val outro = seg0 != null && seg0.label.startsWith("Abspann") && hasNext
                if (seg0 != null && !outro) PSkipPill(seg0.label + (if (!controls) "  (OK)" else "")) { skipSeg(seg0) }
                if (outro || (showNextCard && hasNext && !controls)) {
                    NextPill({ (dur - pos).coerceAtLeast(0L) }, AppSettings.autoNext, !controls) { playEpisode(epList[epIdx + 1]) }
                }
            }
        }

        // ---------------------------------------------------------------- Steuerleiste
        AnimatedVisibility(
            // Unter dem TV-Guide keine Steuerleiste: sonst konnte der Fokus aus dem Guide in dieses Menü rutschen
            visible = controls && !showGuide,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120))
        ) {
            Box(Modifier.fillMaxSize()) {
                // Abdunklung oben und unten
                Box(
                    Modifier.fillMaxWidth().height(200.dp).align(Alignment.TopCenter)
                        .background(Brush.verticalGradient(listOf(PT.ScrimTop, Color.Transparent)))
                )
                Box(
                    Modifier.fillMaxWidth().height(320.dp).align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color(0x85000000), 1f to PT.ScrimBottom))
                )

                // Titel (oben links) – bei Live übernimmt der Infoblock unten
                if (!cur.live) {
                    val baseTitle = if (cur.season > 0 && cur.episode > 0) cur.title.substringBeforeLast(" S" + cur.season + "E" + cur.episode) else cur.title
                    Column(
                        Modifier.align(Alignment.TopStart).padding(start = PT.MarginX, top = PT.MarginY, end = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(baseTitle, color = Color.White, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = cur.archive != null)
                        cur.archive?.let { a ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PChip("Archiv", icon = AIcon.Catchup)
                                PChip(a.chTitle, raw = true)
                                PChip(dayLabel(a.prog.start) + "  " + hhmm(a.prog.start) + "–" + hhmm(a.prog.end), raw = true)
                            }
                        }
                        if (cur.season > 0 && cur.episode > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                PChip("Staffel " + cur.season)
                                PChip("Folge " + cur.episode)
                            }
                            epName?.let { Text(it, color = Color(0xD9FFFFFF), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true) }
                        }
                    }
                }

                // Marken oben rechts
                Row(
                    Modifier.align(Alignment.TopEnd).padding(end = PT.MarginX, top = PT.MarginY),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cur.live) PLiveChip(behindSec = behindSec)
                    else badges.forEachIndexed { i, b -> PChip(b, filled = i == 0 && b == "4K") }
                }

                val setLabel: (String) -> Unit = { ctrlLabel = it }

                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(start = PT.MarginX, end = PT.MarginX, bottom = PT.MarginY)
                ) {
                    if (cur.live) {
                        LiveInfoBlock(curItem, cur.title, if (curIdx >= 0) curIdx + 1 else 0, epg)
                        Spacer(Modifier.height(20.dp))
                        val fav = Library.isFav(cur.itemId)
                        Row(
                            Modifier.onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { controls = false; true } else false
                            },
                            horizontalArrangement = Arrangement.spacedBy(PT.BtnGap), verticalAlignment = Alignment.CenterVertically
                        ) {
                            PRoundButton(Modifier.focusRequester(liveFirst), label = "Senderliste", onLabel = setLabel, onClick = { openChannels() }) {
                                PlayerIcon(PIcon.Channels, it)
                            }
                            PRoundButton(label = "TV-Guide", onLabel = setLabel, onClick = { controls = false; showGuide = true }) {
                                PlayerIcon(PIcon.Guide, it)
                            }
                            // OnAir v2: a second live channel next to this one
                            if (onMultiview != null && cur.archive == null) PRoundButton(label = "Multiview", onLabel = setLabel, onClick = { onMultiview(cur.itemId) }) {
                                AppIcon(AIcon.Grid, it, size = 26.dp)
                            }
                            PRoundButton(label = "Zurück zu Live", onLabel = setLabel, onClick = {
                                if (player.isCurrentMediaItemSeekable) player.seekToDefaultPosition()
                                else { player.stop(); player.prepare() }
                                player.play()
                                behindSec = 0L
                                controls = false
                                flash = Flash(PIcon.Play, "LIVE")
                            }) {
                                PlayerIcon(PIcon.Play, it)
                            }
                            if (Catchup.has(curItem)) {
                                PRoundButton(label = "Von Anfang an", onLabel = setLabel, onClick = {
                                    val ch = curItem
                                    val e = Epg.current(epg)
                                    if (ch != null && e != null) playArchive(ch, e) else flash = Flash(null, "Kein Programm bekannt")
                                }) { AppIcon(AIcon.Catchup, it, size = 26.dp) }
                                PRoundButton(Modifier.focusRequester(btn.getValue(Panel.ARCHIVE)), label = "Archiv: verpasste Sendungen", onLabel = setLabel, onClick = { openPanel(Panel.ARCHIVE) }) {
                                    AppIcon(AIcon.History, it, size = 26.dp)
                                }
                            }
                            // Mit eigenen Senderlisten: Auswahl, wohin (Favoriten oder eine eigene Liste); sonst direkt Favorit an/aus
                            Box {
                                var favMenu by remember { mutableStateOf(false) }
                                PRoundButton(
                                    label = if (ChannelLists.lists.isNotEmpty()) "Favoriten & Listen" else if (fav) "Aus Favoriten entfernen" else "Zu Favoriten hinzufügen", onLabel = setLabel,
                                    onClick = { if (ChannelLists.lists.isNotEmpty() && curItem != null) favMenu = true else Library.toggleFav(ctx, cur.itemId) }
                                ) { c -> PlayerIcon(if (fav) PIcon.StarOn else PIcon.StarOff, if (fav && c == Color.White) PT.Gold else c) }
                                val ci = curItem
                                if (favMenu && ci != null) ChannelMenu(ci, null, null) { favMenu = false }
                            }
                            PRoundButton(Modifier.focusRequester(btn.getValue(Panel.TRACKS)), label = "Audio & Untertitel", onLabel = setLabel, onClick = { openPanel(Panel.TRACKS) }) {
                                PlayerIcon(PIcon.AudioSubs, it)
                            }
                            PRoundButton(Modifier.focusRequester(btn.getValue(Panel.ASPECT)), label = "Bildformat", onLabel = setLabel, onClick = { openPanel(Panel.ASPECT) }) {
                                PlayerIcon(PIcon.Aspect, it)
                            }
                            PRoundButton(label = "Extern öffnen", onLabel = setLabel, onClick = { player.pause(); openExternal(ctx, cur.url, cur.title) }) {
                                PlayerIcon(PIcon.External, it)
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).height(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ctrlLabel.ifEmpty { "Senderliste" }, color = Muted, fontSize = 14.sp, maxLines = 1)
                            Text("Hoch und Runter wechseln den Sender", color = Muted, fontSize = 14.sp, maxLines = 1)
                        }
                    } else {
                        val stepS = (step / 1000).toString()
                        PSeekBar(
                            Modifier.fillMaxWidth().focusRequester(seekFocus),
                            pos = posNow, dur = durNow, buffered = bufNow, segs = segsNow,
                            scrubbing = { scrubTarget >= 0 },
                            onFocus = { if (it) ctrlLabel = "Zeitleiste  ·  Links/Rechts = $stepS s springen  ·  Halten = weiter springen" },
                            onDown = { runCatching { firstFocus.requestFocus() } },
                            onSeekKey = { stepSeek(it) },
                            onRepeat = { repeatSeek(it) }
                        )
                        PTimeRow(posNow, durNow)
                        Spacer(Modifier.height(14.dp))
                        val arch = cur.archive != null
                        val prevLabel = if (arch) (archPrev?.let { "Vorherige Sendung: " + hhmm(it.start) + "  " + it.title } ?: "Vorherige Sendung")
                            else if (hasPrev) "Vorherige Folge: Staffel " + epList[epIdx - 1].season + ", Folge " + epList[epIdx - 1].num else "Vorherige Folge"
                        val nextLabel = if (arch) (archNext?.let { "Nächste Sendung: " + hhmm(it.start) + "  " + it.title } ?: "Nächste Sendung: Live")
                            else if (hasNext) "Nächste Folge: Staffel " + epList[epIdx + 1].season + ", Folge " + epList[epIdx + 1].num else "Nächste Folge"
                        Box(
                            Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { runCatching { seekFocus.requestFocus() }; true }
                                else if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) { controls = false; true }
                                else false
                            }
                        ) {
                            Row(
                                Modifier.fillMaxWidth().height(PT.BtnPrimary),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(PT.BtnGap), verticalAlignment = Alignment.CenterVertically) {
                                    PRoundButton(enabled = if (arch) archPrev != null else hasPrev, label = prevLabel, onLabel = setLabel, onClick = {
                                        val ch = curItem
                                        val pv = archPrev
                                        if (arch) { if (ch != null && pv != null) playArchive(ch, pv) }
                                        else if (hasPrev) playEpisode(epList[epIdx - 1])
                                    }) {
                                        PlayerIcon(PIcon.PrevEp, it)
                                    }
                                    PRoundButton(label = "$stepS Sekunden zurück", onLabel = setLabel, onClick = { stepSeek(-1, fromBar = false) }) { c ->
                                        Box(contentAlignment = Alignment.Center) {
                                            PlayerIcon(PIcon.Replay, c, size = 30.dp)
                                            Text(stepS, color = c, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.offset(y = 2.dp))
                                        }
                                    }
                                    PRoundButton(
                                        Modifier.focusRequester(playBtn), size = PT.BtnPrimary,
                                        label = if (playing) "Pause" else "Abspielen", onLabel = setLabel, onClick = { togglePlay() }
                                    ) { PlayerIcon(if (playing) PIcon.Pause else PIcon.Play, it, size = 32.dp) }
                                    PRoundButton(label = "$stepS Sekunden vor", onLabel = setLabel, onClick = { stepSeek(1, fromBar = false) }) { c ->
                                        Box(contentAlignment = Alignment.Center) {
                                            PlayerIcon(PIcon.Forward, c, size = 30.dp)
                                            Text(stepS, color = c, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.offset(y = 2.dp))
                                        }
                                    }
                                    PRoundButton(enabled = arch || hasNext, label = nextLabel, onLabel = setLabel, onClick = {
                                        val ch = curItem
                                        val nx = archNext
                                        if (arch) { if (ch != null && nx != null) playArchive(ch, nx) else backToLive() }
                                        else if (hasNext) playEpisode(epList[epIdx + 1])
                                    }) {
                                        PlayerIcon(PIcon.NextEp, it)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(PT.BtnGap), verticalAlignment = Alignment.CenterVertically) {
                                    if (arch) {
                                        PRoundButton(label = "Zurück zu Live", onLabel = setLabel, onClick = { backToLive() }) {
                                            AppIcon(AIcon.Live, it, size = 26.dp)
                                        }
                                        PRoundButton(Modifier.focusRequester(btn.getValue(Panel.ARCHIVE)), label = "Archiv: verpasste Sendungen", onLabel = setLabel, onClick = { openPanel(Panel.ARCHIVE) }) {
                                            AppIcon(AIcon.History, it, size = 26.dp)
                                        }
                                    }
                                    PRoundButton(Modifier.focusRequester(btn.getValue(Panel.TRACKS)), label = "Audio & Untertitel", onLabel = setLabel, onClick = { openPanel(Panel.TRACKS) }) {
                                        PlayerIcon(PIcon.AudioSubs, it)
                                    }
                                    if (epList.size > 1) {
                                        PRoundButton(Modifier.focusRequester(btn.getValue(Panel.EPISODES)), label = "Folgen", onLabel = setLabel, onClick = { openPanel(Panel.EPISODES) }) {
                                            PlayerIcon(PIcon.Episodes, it)
                                        }
                                    }
                                    PRoundButton(Modifier.focusRequester(btn.getValue(Panel.MENU)), label = "Einstellungen", onLabel = setLabel, onClick = { openPanel(Panel.MENU) }) {
                                        PlayerIcon(PIcon.Settings, it)
                                    }
                                }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).height(20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ctrlLabel, color = Muted, fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
                            if (AppSettings.showEndTime) PEndsAt(posNow, durNow) { SPEEDS[speedIdx] }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- Senderliste & TV-Guide
        if (showChannels) {
            ChannelOverlay(
                liveAll, cur.itemId, curItem,
                chanCat, { chanCat = it },
                showCats, { showCats = it },
                focusedCh, { focusedCh = it },
                inHeader, { inHeader = it },
                chanFocus, catsFocus, headerFocus,
                { showChannels = false }
            ) { item, list -> playCat = chanCat; switchTo(item, list) }
        }

        if (showGuide) {
            Guide(
                zapSrc, cur.itemId, guideFocus, onClose = { showGuide = false }, onArchive = { ch, e -> playArchive(ch, e) },
                all = liveAll.ifEmpty { zapSrc }, onPickIn = { ch, src -> switchTo(ch, src) }
            ) { switchTo(it, zapSrc) }
        }

        // ---------------------------------------------------------------- Menüs
        if (panel != Panel.NONE) {
            val back = if (panelParent != Panel.NONE) "Links = zurück" else "Links schließt das Menü"
            when (panel) {
                Panel.TRACKS -> TracksPanel(
                    player, tracksVer, { tracksVer++; h.postDelayed({ rememberTracks() }, 400) }, panelFocus, subsFocus,
                    { closePanel() }, { openPanel(Panel.SUBSTYLE, Panel.TRACKS) }
                )

                Panel.SUBSTYLE -> PSimplePanel(440.dp, "Untertitel-Stil", back, { closePanel() }) {
                    Column(verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                        PPanelRow("Größe", false, Modifier.focusRequester(panelFocus), trailing = SubStyle.SIZES[AppSettings.subSize].first, chevron = true) {
                            AppSettings.setSub(ctx, (AppSettings.subSize + 1) % SubStyle.SIZES.size, AppSettings.subColor, AppSettings.subBg, AppSettings.subEdge)
                        }
                        PPanelRow("Farbe", false, trailing = SubStyle.COLORS[AppSettings.subColor].first, chevron = true) {
                            AppSettings.setSub(ctx, AppSettings.subSize, (AppSettings.subColor + 1) % SubStyle.COLORS.size, AppSettings.subBg, AppSettings.subEdge)
                        }
                        PPanelRow("Hintergrund", false, trailing = SubStyle.BGS[AppSettings.subBg].first, chevron = true) {
                            AppSettings.setSub(ctx, AppSettings.subSize, AppSettings.subColor, (AppSettings.subBg + 1) % SubStyle.BGS.size, AppSettings.subEdge)
                        }
                        PPanelRow("Rand", false, trailing = SubStyle.EDGES[AppSettings.subEdge], chevron = true) {
                            AppSettings.setSub(ctx, AppSettings.subSize, AppSettings.subColor, AppSettings.subBg, (AppSettings.subEdge + 1) % SubStyle.EDGES.size)
                        }
                        Text("OK = nächster Wert. Die Vorschau zeigt das Ergebnis.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                Panel.QUALITY -> PSimplePanel(440.dp, "Videoqualität", back, { closePanel() }) {
                    val video = remember(tracksVer) { collectTracks(player, C.TRACK_TYPE_VIDEO) }
                    val videoAuto = player.trackSelectionParameters.overrides.keys.none { it.type == C.TRACK_TYPE_VIDEO }
                    // Mehrere Quellen desselben Titels (4K/FHD/HD als eigene Einträge): hier von Hand wechseln
                    val alts = remember(cur.url, cur.itemId, cur.season, cur.episode) { currentAlts() }
                    val altsShown = alts.size > 1
                    if (altsShown) {
                        PColumnHeader("Quelle")
                        val curI = alts.indexOfFirst { it.url == cur.url }.coerceAtLeast(0)
                        alts.forEachIndexed { i, a ->
                            PPanelRow(
                                a.label.ifBlank { a.quality ?: "Quelle ${i + 1}" }, i == curI,
                                if (i == curI) Modifier.focusRequester(panelFocus) else Modifier,
                                trailingIcon = if (DeadLinks.isDead(a.url)) AIcon.Warning else null
                            ) {
                                if (a.url != cur.url) {
                                    val at = player.currentPosition.coerceAtLeast(0L)
                                    stalls = emptyList()
                                    cur = cur.copy(url = a.url, startMs = at, note = null)
                                }
                                closePanel()
                            }
                        }
                        if (video.size >= 2) PColumnHeader("Bildspur")
                    }
                    if (altsShown && video.size < 2) {
                        // nur Quellen – keine Bildspuren zur Auswahl
                    } else if (video.size < 2) {
                        Text(
                            "Keine Auswahl verfügbar (feste Qualität).", color = Muted, fontSize = 15.sp,
                            modifier = Modifier.focusRequester(panelFocus).focusable().padding(16.dp)
                        )
                    } else {
                        val labels = listOf("Automatisch") + video.map { it.label }
                        val sel = if (videoAuto) 0 else 1 + video.indexOfFirst { it.selected }.coerceAtLeast(0)
                        val trackFocus = remember { FocusRequester() }
                        PChoiceList(labels, sel, if (altsShown) trackFocus else panelFocus) { i ->
                            player.trackSelectionParameters = if (i == 0) {
                                player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                            } else {
                                val t = video[i - 1]
                                player.trackSelectionParameters.buildUpon()
                                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.idx)).build()
                            }
                            tracksVer++
                        }
                    }
                }

                Panel.ARCHIVE -> {
                    // Wie die Senderliste: Sender mit Archiv | Tage | Sendungen (Zurück schließt über den Player)
                    val archChannels = remember(zapSrc, liveAll, Catchup.version) {
                        zapSrc.filter { Catchup.has(it) }.ifEmpty { liveAll.filter { Catchup.has(it) } }
                    }
                    ArchivePanel(curItem, archChannels, cur.archive?.prog?.start, panelFocus, { closePanel() }) { ch, e -> playArchive(ch, e) }
                }

                Panel.SPEED -> PSimplePanel(400.dp, "Wiedergabe-Tempo", back, { closePanel() }) {
                    PChoiceList(SPEEDS.map { speedLabel(it) }, speedIdx, panelFocus) { i ->
                        speedIdx = i
                        player.setPlaybackSpeed(SPEEDS[i])
                    }
                }

                Panel.SLEEP -> PSimplePanel(400.dp, "Sleep-Timer", back, { closePanel() }) {
                    PChoiceList(SLEEP_MIN.map { if (it == 0) "Aus" else "$it min" }, sleepIdx, panelFocus) { i ->
                        sleepIdx = i
                        sleepAt = if (i == 0) 0L else System.currentTimeMillis() + SLEEP_MIN[i] * 60_000L
                    }
                }

                Panel.ASPECT -> PSimplePanel(440.dp, "Bildformat", back, { closePanel() }) {
                    PChoiceList(ASPECTS, aspect, panelFocus) { aspect = it; flash = Flash(null, ASPECTS[it]) }
                }

                Panel.CLOCK -> PSimplePanel(440.dp, "Uhrzeit im Bild", back, { closePanel() }) {
                    val poss = listOf("Oben rechts", "Oben links", "Unten rechts", "Unten links")
                    val sizes = listOf("Klein", "Mittel", "Groß")
                    val ops = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100)
                    Column(verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                        ClockSample(Modifier.align(Alignment.CenterHorizontally))
                        PPanelRow("Anzeigen", false, Modifier.focusRequester(panelFocus), trailing = if (AppSettings.clockOn) "An" else "Aus", chevron = true) {
                            AppSettings.setClock(ctx, !AppSettings.clockOn, AppSettings.clockPos, AppSettings.clockOpacity, AppSettings.clockSize)
                        }
                        PPanelRow("Ort", false, trailing = poss[AppSettings.clockPos], chevron = true) {
                            AppSettings.setClock(ctx, AppSettings.clockOn, (AppSettings.clockPos + 1) % 4, AppSettings.clockOpacity, AppSettings.clockSize)
                        }
                        PPanelRow("Schrift: Deckkraft", false, trailing = "${AppSettings.clockOpacity} %", chevron = true) {
                            val i = ops.indexOf(AppSettings.clockOpacity).let { if (it < 0) 5 else it }
                            AppSettings.setClock(ctx, AppSettings.clockOn, AppSettings.clockPos, ops[(i + 1) % ops.size], AppSettings.clockSize)
                        }
                        PPanelRow("Größe", false, trailing = sizes[AppSettings.clockSize], chevron = true) {
                            AppSettings.setClock(ctx, AppSettings.clockOn, AppSettings.clockPos, AppSettings.clockOpacity, (AppSettings.clockSize + 1) % 3)
                        }
                        PPanelRow("Hintergrund", false, trailing = if (AppSettings.clockBg) "An" else "Aus", chevron = true) {
                            AppSettings.setClockBg(ctx, !AppSettings.clockBg, AppSettings.clockBgOpacity)
                        }
                        if (AppSettings.clockBg) PPanelRow("Hintergrund: Deckkraft", false, trailing = "${AppSettings.clockBgOpacity} %", chevron = true) {
                            val i = ops.indexOf(AppSettings.clockBgOpacity).let { if (it < 0) 1 else it }
                            AppSettings.setClockBg(ctx, true, ops[(i + 1) % ops.size])
                        }
                        Text("OK = nächster Wert. Die Vorschau zeigt das Ergebnis.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                Panel.STEP -> PSimplePanel(400.dp, "Sprungweite", back, { closePanel() }) {
                    Text("Gilt für Links/Rechts, Halten und die Zeitleiste.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
                    PChoiceList(STEP_OPTIONS.map { "$it s" }, STEP_OPTIONS.indexOf(AppSettings.seekStep).coerceAtLeast(0), panelFocus) { i ->
                        AppSettings.setPlayer(ctx, AppSettings.skipIntro, AppSettings.showInfo, STEP_OPTIONS[i])
                    }
                }

                Panel.MENU -> PSimplePanel(440.dp, "Einstellungen", back, { closePanel() }) {
                    val entries = buildList<MenuEntry> {
                        add(MenuEntry(0, "Sprungweite", "${step / 1000} s", true))
                        add(MenuEntry(1, "Untertitel-Stil", null, true))
                        add(MenuEntry(2, "Videoqualität", currentAlts().size.takeIf { it > 1 }?.let { "$it Quellen" }, true))
                        add(MenuEntry(3, "Wiedergabe-Tempo", speedLabel(SPEEDS[speedIdx]), true))
                        add(MenuEntry(4, "Bildformat", ASPECTS[aspect].substringBefore(" ("), true))
                        if (epList.size > 1) add(MenuEntry(5, "Nächste Folge automatisch", if (AppSettings.autoNext) "An" else "Aus", false))
                        if (epList.size > 1) add(MenuEntry(13, "Abspann überspringen", if (AppSettings.autoSkipOutro) "An" else "Aus", false))
                        add(MenuEntry(6, "Endzeit anzeigen", if (AppSettings.showEndTime) "An" else "Aus", false))
                        add(MenuEntry(10, "Uhrzeit im Bild", if (AppSettings.clockOn) "An" else "Aus", true))
                        add(MenuEntry(11, "Technik-Info", if (techOn) "An" else "Aus", false))
                                        add(MenuEntry(8, "Sleep-Timer", if (sleepIdx == 0) "Aus" else SLEEP_MIN[sleepIdx].toString() + " min", true))
                        if (cur.live && lastChId != null) add(MenuEntry(9, "Letzter Kanal", null, false))
                        if (cur.live && cur.archive == null && onMultiview != null) add(MenuEntry(14, "Multiview", null, true))
                        add(MenuEntry(7, "Extern öffnen", null, false))
                    }
                    val focusAt = menuSel.coerceIn(0, entries.lastIndex)
                    // Startet so, dass die zuletzt gewählte Zeile gezeichnet ist (sonst findet der Fokus sie nicht und die Tasten laufen ins Leere)
                    LazyColumn(state = rememberLazyListState(maxOf(focusAt - 3, 0)), verticalArrangement = Arrangement.spacedBy(PT.RowGap)) {
                        itemsIndexed(entries) { i, e ->
                            PPanelRow(
                                e.label, false, if (i == focusAt) Modifier.focusRequester(panelFocus) else Modifier,
                                trailing = e.trailing, chevron = e.chevron
                            ) {
                                menuSel = i
                                when (e.id) {
                                    0 -> openPanel(Panel.STEP, Panel.MENU)
                                    1 -> openPanel(Panel.SUBSTYLE, Panel.MENU)
                                    2 -> openPanel(Panel.QUALITY, Panel.MENU)
                                    3 -> openPanel(Panel.SPEED, Panel.MENU)
                                    4 -> openPanel(Panel.ASPECT, Panel.MENU)
                                    8 -> openPanel(Panel.SLEEP, Panel.MENU)
                                    10 -> openPanel(Panel.CLOCK, Panel.MENU)
                                    11 -> techOn = !techOn
                                    13 -> AppSettings.setAutoOutro(ctx, !AppSettings.autoSkipOutro)
                                    9 -> { panel = Panel.NONE; panelParent = Panel.NONE; lastChannel() }
                                    14 -> { panel = Panel.NONE; panelParent = Panel.NONE; onMultiview?.invoke(cur.itemId) }
                                    5 -> AppSettings.setPlayerExtra(ctx, !AppSettings.autoNext, AppSettings.hideSec, AppSettings.showEndTime, AppSettings.autoSkipIntro)
                                    6 -> AppSettings.setPlayerExtra(ctx, AppSettings.autoNext, AppSettings.hideSec, !AppSettings.showEndTime, AppSettings.autoSkipIntro)
                                    else -> {
                                        player.pause()
                                        openExternal(ctx, cur.url, cur.title)
                                        panel = Panel.NONE
                                        panelParent = Panel.NONE
                                    }
                                }
                            }
                        }
                    }
                }

                Panel.EPISODES -> EpisodesPanel(epList, cur.season, cur.episode, panelFocus, { closePanel() }) { ep ->
                    panel = Panel.NONE
                    panelParent = Panel.NONE
                    controls = false
                    playEpisode(ep)
                }

                Panel.NONE -> {}
            }
        }
    }
}

/** Mittige Spul-Anzeige: nur Richtung und tatsächlich vorgemerkte Zeit ("+0:30"), ohne Zähler. */
@Composable
private fun ScrubCard(target: () -> Long, start: () -> Long, turbo: () -> Int, modifier: Modifier) {
    val t = target()
    if (t < 0) return
    val delta = t - start()
    val shape = RoundedCornerShape(DS.RadiusL)
    Row(
        modifier.clip(shape).background(Color(0xE612141C)).border(1.dp, Color(0x1FFFFFFF), shape)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        PlayerIcon(if (delta >= 0) PIcon.Forward else PIcon.Replay, Color.White, size = 32.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(fmtDelta(delta), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, style = TNUM, maxLines = 1)
            val lv = turbo()
            if (lv > 0) Text("Schnell " + "»".repeat(lv), color = Accent2, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Zeitleiste unten, solange gespult wird und die große Steuerleiste nicht offen ist. */
@Composable
private fun BoxScope.MiniSeekDock(
    visible: () -> Boolean, pos: () -> Long, dur: () -> Long, buffered: () -> Long, segs: () -> List<Seg>
) {
    if (!visible()) return
    Box(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(200.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, PT.ScrimBottom)))
    )
    PMiniSeek(
        pos, dur, buffered, segs,
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = PT.MarginX, vertical = PT.MarginY)
    )
}

/**
 * „Nächste Folge“ als einzige Pille im Stil von „Intro überspringen“. Läuft das automatische Weiterspielen,
 * zählt sie in den letzten 10 Sekunden herunter. OK startet die Folge sofort.
 */
@Composable
private fun NextPill(remain: () -> Long, auto: Boolean, showOk: Boolean, onClick: () -> Unit) {
    val r = remain()
    val label = if (auto && r in 1..10_000L) Lang.tr("Nächste Folge") + " · " + ((r + 999) / 1000) + " s" else Lang.tr("Nächste Folge")
    PSkipPill(label + (if (showOk) "  (OK)" else ""), onClick = onClick)
}

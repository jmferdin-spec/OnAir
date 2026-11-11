package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * OnAir navigation state (Phase 2). Lives in an object so IPVIO's Live TV ("TV-Guide" row) and the sidebar can open the
 * standalone guide without new parameters through IPVIO's screens. Rules in [OnAirHomeLogic].
 */
object OnAirNav {
    var screen by mutableStateOf(OnAirScreen.HOME)
    /** Last tile opened from the home screen – gets the focus when the home screen shows again. */
    var tile by mutableStateOf(OnAirHomeLogic.INITIAL)
    /** Screen the guide / placeholder was opened from (Back returns there). */
    var openedFrom = OnAirScreen.HOME
        private set
    /** Channels the guide shows as its current list (null = all live channels) and the matching ZapContext key. */
    var guideList: List<Item>? = null
    var guideKey: String? = null

    fun openTile(t: OnAirTile) {
        tile = t
        if (OnAirHomeLogic.isReady(t)) openGuide(OnAirScreen.HOME) else { openedFrom = OnAirScreen.HOME; screen = OnAirScreen.SOON }
    }

    fun openGuide(from: OnAirScreen, list: List<Item>? = null, key: String? = null) {
        openedFrom = from; guideList = list; guideKey = key
        screen = OnAirScreen.GUIDE
    }

    fun openVod() { openedFrom = OnAirScreen.HOME; screen = OnAirScreen.VOD }

    /** Hidden debug screen, opened from Settings → About (long-press the version); Back returns to Settings. */
    fun openDebug() { openedFrom = OnAirScreen.VOD; screen = OnAirScreen.DEBUG }

    fun back() {
        val to = OnAirHomeLogic.backFrom(screen, openedFrom)
        if (to != OnAirScreen.GUIDE) { guideList = null; guideKey = null }
        screen = to
    }

    /** Home button / app resumed: straight to the five tiles. */
    fun home() { guideList = null; guideKey = null; openedFrom = OnAirScreen.HOME; screen = OnAirScreen.HOME }
}

private fun tileIcon(t: OnAirTile): Int = when (t) {
    OnAirTile.LIVE -> R.drawable.ic_tile_live
    OnAirTile.MOVIES -> R.drawable.ic_tile_movies
    OnAirTile.SHOWS -> R.drawable.ic_tile_shows
    OnAirTile.SPORTS -> R.drawable.ic_tile_sports
    OnAirTile.EVENTS -> R.drawable.ic_tile_events
}

/** Asks [req] for focus a few times – lists and rows sometimes need a frame or two before the target exists. */
private suspend fun focusSoon(req: FocusRequester) {
    delay(40)
    repeat(12) {
        if (runCatching { req.requestFocus() }.getOrDefault(false)) return
        delay(70)
    }
}

/**
 * OnAir start screen: five large tiles (Live TV, Movies, Shows, Sports, Events) plus "On Demand" (IPVIO's film and
 * series browsing) and Settings. Focus starts on [focusTile]; Back exits the app (with IPVIO's exit confirmation).
 */
@Composable
fun OnAirHomeScreen(
    liveCount: Int,
    focusTile: OnAirTile,
    onTile: (OnAirTile) -> Unit,
    onOnDemand: () -> Unit,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit
) {
    val ctx = LocalContext.current
    val reqs = remember { OnAirTile.entries.associateWith { FocusRequester() } }
    LaunchedEffect(focusTile) { focusSoon(reqs.getValue(focusTile)) }

    // Back: leave the app – twice within 2.5 s when IPVIO's "confirm exit" setting is on (default)
    var lastBack by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        val act = generateSequence<android.content.Context>(ctx) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        if (!General.confirmExit || now - lastBack < 2500) act?.finishAffinity()
        else { lastBack = now; android.widget.Toast.makeText(ctx, Lang.tr("Noch einmal Zurück drücken zum Beenden"), android.widget.Toast.LENGTH_SHORT).show() }
    }

    // The clock moves on while the screen is open
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); nowSec = System.currentTimeMillis() / 1000 } }

    if (PlaylistStore.loading && liveCount == 0 && PlaylistStore.heavy) { PlaylistLoadingOverlay(); return }

    Column(Modifier.fillMaxSize().background(Bg).padding(horizontal = 48.dp, vertical = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BrandMark(Modifier.size(44.dp))
            Text("OnAir", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, raw = true)
            Spacer(Modifier.weight(1f))
            Text(hhmm(nowSec), color = Muted, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, raw = true)
        }

        // No channels (yet): say why and offer the way out; the tiles stay usable
        val err = PlaylistStore.error
        if (liveCount == 0 && !PlaylistStore.loading) {
            Spacer(Modifier.height(16.dp))
            Text(if (err != null) "Die Playlist konnte nicht geladen werden" else "Noch keine Sender", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (err != null) Text(err, color = Muted, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        Spacer(Modifier.weight(1f))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 20.dp
            val cols = OnAirHomeLogic.columns(maxWidth.value, gapDp = gap.value)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                for (row in OnAirHomeLogic.rows(cols)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        for (t in row) {
                            HomeTile(t, OnAirHomeLogic.subtitle(t, liveCount), reqs.getValue(t), Modifier.weight(1f)) { onTile(t) }
                        }
                        // second row of a 3 + 2 layout: same tile width as above
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PillButton("On Demand", icon = AIcon.Grid) { onOnDemand() }
            PillButton("Einstellungen", icon = AIcon.Gear) { onSettings() }
            if (liveCount == 0 && !PlaylistStore.loading) {
                if (err != null) PillButton("Erneut versuchen", icon = AIcon.Refresh) { onRetry() }
                PillButton("Playlist hinzufügen") { onAdd() }
            }
            Spacer(Modifier.weight(1f))
            Text("Filme und Serien deines Anbieters findest du unter „On Demand“.", color = Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun HomeTile(t: OnAirTile, subtitle: String?, req: FocusRequester, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused && Look.motion > 0) 1.06f else 1f, tween(if (Look.motion > 0) 140 else 0), label = "tile")
    val shape = RoundedCornerShape(DS.RadiusL)
    Column(
        modifier
            .aspectRatio(0.86f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(if (focused) Accent1 else Surf)
            .border(if (focused) 3.dp else 1.dp, if (focused) Color.White else Color(0x22FFFFFF), shape)
            .focusRequester(req)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Image(
            painterResource(tileIcon(t)), contentDescription = null, modifier = Modifier.size(56.dp),
            colorFilter = ColorFilter.tint(if (focused) OnAccent else Accent2)
        )
        Column {
            Text(t.label, color = if (focused) OnAccent else Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(
                subtitle, color = if (focused) OnAccent.copy(alpha = 0.85f) else Muted, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Movies, Shows, Sports and Events until Phase 4: what is coming, Back to the home screen, or the TV guide meanwhile. */
@Composable
fun PhasePlaceholder(tile: OnAirTile, onBack: () -> Unit, onGuide: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusSoon(first) }
    BackHandler { onBack() }
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.widthIn(max = 640.dp)) {
            Image(painterResource(tileIcon(tile)), contentDescription = null, modifier = Modifier.size(72.dp), colorFilter = ColorFilter.tint(Accent2))
            Text(tile.label, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Text("Kommt in Phase 4", color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Hier siehst du bald, was gerade läuft – mit Sender, Fortschritt und direkt zum Ansehen.",
                color = Muted, fontSize = 16.sp, textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                PillButton("Zurück", modifier = Modifier.focusRequester(first), light = true) { onBack() }
                PillButton("TV-Guide öffnen", icon = AIcon.Grid) { onGuide() }
            }
        }
    }
}

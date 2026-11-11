package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

/**
 * OnAir: IPVIO's TV guide ([Guide] from PlayerGuide.kt) as a screen of its own, made to look and work like the guide inside
 * the player:
 *
 * - Top right, where the player shows its shrunk picture, the **live preview** of the channel the guide opens at (the most
 *   recently watched channel of the list), with sound – one stream, made with Multiview's [videoView] (transparent
 *   shutter, so it can't stay black). Black behind the guide, like in the player.
 * - The preview stops when the guide is left (and while the app is in the background). On OK the preview stops *before*
 *   the player starts, so only one stream is ever open.
 * - OK on a channel or a running programme → [onWatch] (the player plays it full-screen; Back returns here, at that
 *   channel, which then is the preview). OK on a past programme of a catch-up channel → [onArchive].
 * - Back walks out like in the player (programme → channel → category → close); closing calls [onClose] (home screen –
 *   unlike the player's guide, which returns to the running channel).
 * - [preview] false: no preview (the Multiview picker shows its own running stream in that corner – no third stream).
 */
@OptIn(UnstableApi::class)
@Composable
fun GuideScreen(
    liveAll: List<Item>,
    onWatch: (Item, List<Item>) -> Unit,
    onArchive: (Item, EpgEntry) -> Unit,
    preview: Boolean = true,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val list = OnAirNav.guideList?.takeIf { it.isNotEmpty() } ?: liveAll
    val curId = remember(list) { OnAirHomeLogic.guideStart(History.ids.toList(), list.map { it.id }) }
    val focus = remember { FocusRequester() }

    // Back: the guide's own steps first (it sets GuideBack.handler while shown), then close
    BackHandler { GuideBack.handler?.invoke() ?: onClose() }

    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Noch keine Sender", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("Zurück = Startseite", color = Muted, fontSize = 16.sp)
            }
        }
        return
    }

    LaunchedEffect(curId) {
        delay(40)
        repeat(12) {
            if (runCatching { focus.requestFocus() }.getOrDefault(false)) return@LaunchedEffect
            delay(80)
        }
    }

    // ------------------------------------------------------------------ live preview (one stream)
    val previewItem = remember(curId, list) { if (preview) list.firstOrNull { it.id == curId } else null }
    var previewError by remember(previewItem) { mutableStateOf(false) }
    val player = remember(previewItem) {
        previewItem?.let { item ->
            TwoStreamTest.player(ctx.applicationContext, small = false, audio = true).also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        previewError = true
                        LoginDebug.error("guide preview", "preview ${item.title}", error)
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { if (isPlaying) previewError = false }
                })
                p.setMediaItem(MediaItem.fromUri(item.url))
                p.playWhenReady = true
                p.prepare()
            }
        }
    }
    var released by remember(player) { mutableStateOf(false) }
    fun stopPreview() {
        if (player != null && !released) { released = true; runCatching { player.release() } }
    }
    // leaving the guide stops the preview
    DisposableEffect(player) { onDispose { stopPreview() } }
    // app in the background (Home, standby): no stream open; back in front: the preview starts again
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, player) {
        val obs = LifecycleEventObserver { _, e ->
            if (player == null || released) return@LifecycleEventObserver
            when (e) {
                Lifecycle.Event.ON_STOP -> player.stop()
                Lifecycle.Event.ON_START -> { player.prepare(); player.play() }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    KeepScreenOn("guide-preview", player != null && !released)

    // only one stream: the preview stops before the player (or the archive) starts
    val watch: (Item, List<Item>) -> Unit = { ch, src -> stopPreview(); onWatch(ch, src) }
    val archive: (Item, EpgEntry) -> Unit = { ch, e -> stopPreview(); onArchive(ch, e) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Where the player shows its picture (Guide leaves a 400 × 225 dp hole at the top right)
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 24.dp).size(width = 400.dp, height = 225.dp)
                .clip(RoundedCornerShape(DS.RadiusM)).background(if (player != null) Color.Black else Surf),
            contentAlignment = Alignment.Center
        ) {
            if (player != null && !released) {
                AndroidView(factory = { c -> videoView(c, player) }, modifier = Modifier.fillMaxSize())
                if (previewError) Text("Vorschau nicht verfügbar", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
            } else if (preview) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BrandMark(Modifier.size(52.dp))
                    Text("OK: Sender ansehen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text("Zurück = Startseite", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
        Guide(
            list = list, curId = curId, focus = focus, onClose = onClose,
            onArchive = archive,
            all = liveAll.ifEmpty { list },
            // picked in another category of the guide: that category becomes the zapping list (and the guide's list on return)
            onPickIn = { ch, src -> watch(ch, src) }
        ) { ch -> watch(ch, list) }
    }
}

package com.ipvio.stream

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * OnAir Phase 2: IPVIO's TV guide ([Guide] from PlayerGuide.kt) as a screen of its own – no stream starts when it opens.
 *
 * - Opens at the most recently watched channel of the list ([OnAirNav.guideList], else all live channels).
 * - OK on a channel or a running programme → [onWatch] (the player tunes it; Back from the player returns here,
 *   at that channel). OK on a past programme of a catch-up channel → [onArchive].
 * - Back walks out like in the player (programme → channel → category → close); closing calls [onClose].
 * - No video preview in v1: the area IPVIO uses for the picture shows a short hint instead.
 */
@Composable
fun GuideScreen(
    liveAll: List<Item>,
    onWatch: (Item, List<Item>) -> Unit,
    onArchive: (Item, EpgEntry) -> Unit,
    onClose: () -> Unit
) {
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

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Where the player would show its picture (Guide leaves a 400 × 225 dp hole at the top right)
        Box(
            Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 24.dp).size(width = 400.dp, height = 225.dp)
                .clip(RoundedCornerShape(DS.RadiusM)).background(Surf),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrandMark(Modifier.size(52.dp))
                Text("OK: Sender ansehen", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text("Zurück = Startseite", color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
        Guide(
            list = list, curId = curId, focus = focus, onClose = onClose,
            onArchive = onArchive,
            all = liveAll.ifEmpty { list },
            // picked in another category of the guide: that category becomes the zapping list (and the guide's list on return)
            onPickIn = { ch, src -> onWatch(ch, src) }
        ) { ch -> onWatch(ch, list) }
    }
}

package com.ipvio.stream

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/*
 * Runde 17b – Live TV im „Cinematic“-Stil (Entwurf Bild 3): Hintergrund in der Farbe des markierten Senders mit großem,
 * blassem Logo; rechts groß, was gerade läuft (LIVE, Titel, Beschreibung, Zeitleiste) und was danach kommt.
 */

/** Hintergrund der Live-Seite: Farbe und Logo des markierten Senders, weich überblendet. */
@Composable
fun LiveBackdrop(ch: Item?, modifier: Modifier = Modifier) {
    Box(modifier.background(Bg)) {
        Crossfade(ch, animationSpec = tween(if (Look.motion == 0) 0 else 700), label = "liveBg") { c ->
            if (c != null) {
                val col = colorFor(c)
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(col.copy(alpha = 0.55f), Color.Transparent), radius = 1400f, center = androidx.compose.ui.geometry.Offset(1500f, 380f))))
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(lerp(col, Color.White, 0.2f).copy(alpha = 0.22f), Color.Transparent), radius = 900f, center = androidx.compose.ui.geometry.Offset(1900f, 900f))))
                if (c.logo != null) AsyncImage(
                    c.logo, null,
                    Modifier.align(Alignment.TopEnd).padding(top = dpx(60), end = dpx(80)).size(dpx(360)).graphicsLayer { alpha = 0.12f },
                    contentScale = ContentScale.Fit
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.3f to Bg.copy(alpha = .9f), 0.55f to Bg.copy(alpha = .35f), 1f to Bg.copy(alpha = .15f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg.copy(alpha = .9f))))
    }
}

/** Was auf dem markierten Sender läuft: groß, mit Zeitleiste und den nächsten Sendungen. */
@Composable
fun LiveNowPanel(ch: Item?, modifier: Modifier = Modifier) {
    if (ch == null) return
    Crossfade(ch, animationSpec = tween(if (Look.motion == 0) 0 else 400), label = "liveNow", modifier = modifier) { c ->
        val epg by produceState(emptyList<EpgEntry>(), c.id) { value = Epg.now(c, network = true) }   // OnAir: focused channel only
        val nowE = Epg.current(epg)
        val next = if (nowE != null) epg.filter { it.start >= nowE.end }.take(2) else epg.take(2)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(dpx(18))) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(14))) {
                LiveBadge()
                Text(raw = true, text = c.title + (nowE?.let { "  ·  " + hhmm(it.start) + " – " + hhmm(it.end) } ?: ""),
                    color = Tok.TextMid, fontSize = spx(20), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(raw = true, text = nowE?.title ?: c.title, color = Tok.TextHi, fontSize = spx(68), lineHeight = spx(70),
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            nowE?.desc?.takeIf { it.isNotBlank() }?.let {
                Text(raw = true, text = it, color = Tok.TextMid, fontSize = spx(21), lineHeight = spx(30), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (nowE != null) {
                val frac = ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                    Text(raw = true, text = hhmm(nowE.start), color = Muted, fontSize = spx(17))
                    Box(Modifier.weight(1f).height(dpx(6)).clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(Accent1))
                    }
                    Text(raw = true, text = hhmm(nowE.end), color = Muted, fontSize = spx(17))
                }
            }
            if (next.isNotEmpty()) Row(Modifier.padding(top = dpx(12)), horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                next.forEachIndexed { i, e ->
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(dpx(18))).background(Color(0x12FFFFFF))
                            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(dpx(18))).padding(dpx(18)),
                        verticalArrangement = Arrangement.spacedBy(dpx(6))
                    ) {
                        Text((if (i == 0) Lang.tr("Danach") + " · " else "") + hhmm(e.start), raw = true,
                            color = if (i == 0) Accent2 else Muted, fontSize = spx(15), fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
                        Text(raw = true, text = e.title, color = Tok.TextHi, fontSize = spx(21), fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (nowE == null && epg.isEmpty()) Text("Für diesen Sender gibt es kein Programm.", color = Muted, fontSize = spx(18))
        }
    }
}

/** Rotes „LIVE“ mit pulsierendem Punkt (ohne Animationen ruhig). */
@Composable
fun LiveBadge() {
    val t = rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(1f, 0.3f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "liveA")
    Row(
        Modifier.clip(RoundedCornerShape(dpx(8))).background(Color(0xFFE11D48)).padding(horizontal = dpx(12), vertical = dpx(4)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(8))
    ) {
        Box(Modifier.size(dpx(9)).graphicsLayer { alpha = if (Look.motion == 0) 1f else a }.clip(RoundedCornerShape(50)).background(Color.White))
        Text(raw = true, text = "LIVE", color = Color.White, fontSize = spx(15), fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp)
    }
}

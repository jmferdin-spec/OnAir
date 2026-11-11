package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** What the debug screen shows (computed once per open / "Recompute"). */
private class DebugData(
    val importedAt: Long, val rows: Int, val withCats: Int, val withEpisode: Int, val withSub: Int,
    val guideChannels: Int, val live: Int, val liveWithTvg: Int, val liveWithGuide: Int,
    val storedAt: Long, val status: String, val snap: OnNow.Snapshot,
    val byClass: Map<OnNowClass, Int>, val bySport: Map<Sport, Int>,
    val other: List<OnNowItem>, val sports: List<OnNowItem>, val otherSports: List<OnNowItem>
)

/** Up to [n] items spread over the whole list (not just the first few), so the sample shows variety. */
private fun <T> spread(l: List<T>, n: Int): List<T> {
    if (l.size <= n) return l
    val step = l.size.toDouble() / n
    return (0 until n).map { l[(it * step).toInt()] }
}

private suspend fun computeDebug(c: Context, liveAll: List<Item>, force: Boolean): DebugData {
    val snap = OnNow.snapshot(c, liveAll, force)
    return withContext(Dispatchers.IO) {
        val info = runCatching { GuideDb.info(c) }.getOrNull()
        val rows = runCatching { GuideDb.count(c) }.getOrDefault(info?.rows ?: 0)
        val cov = runCatching { GuideDb.coverage(c) }.getOrDefault(intArrayOf(0, 0, 0))
        val live = liveAll.filter { it.kind == Kind.LIVE }
        val items = snap.items
        val sports = items.filter { it.cls == OnNowClass.SPORTS }
        DebugData(
            importedAt = info?.importedAt ?: 0L, rows = rows, withCats = cov[0], withEpisode = cov[1], withSub = cov[2],
            guideChannels = Xmltv.channelCount, live = live.size, liveWithTvg = live.count { !it.tvgId.isNullOrBlank() },
            liveWithGuide = live.count { Xmltv.has(it.tvgId) }, storedAt = Xmltv.storedAt, status = Xmltv.status, snap = snap,
            byClass = OnNowLogic.countByClass(items), bySport = OnNowLogic.countBySport(items),
            other = spread(items.filter { it.cls == OnNowClass.OTHER }, 20),
            sports = spread(sports, 20),
            otherSports = spread(sports.filter { it.sport == Sport.OTHER }, 20)
        )
    }
}

private fun stamp(ms: Long): String = if (ms <= 0) "never" else dayLabel(ms / 1000) + " " + hhmm(ms / 1000)
private fun pct(part: Int, whole: Int): String = if (whole <= 0) "–" else String.format(java.util.Locale.ROOT, "%.1f %%", part * 100.0 / whole)

/**
 * Hidden debug screen (Settings → About → long-press the version number): guide import time and size, which XMLTV fields
 * the provider fills, counts per class right now, counts per sport, events, and samples from "Other", "Sports" and
 * "Other Sports" with channel and Xtream category – to tune the rules (Phase 5). Reads only the database: no provider
 * requests. English only, never translated.
 */
@Composable
fun OnAirDebugScreen(liveAll: List<Item>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler { onBack() }
    var tick by remember { mutableIntStateOf(0) }
    val data by produceState<DebugData?>(null, tick) { value = null; value = computeDebug(ctx, liveAll, force = tick > 0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); repeat(10) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; delay(80) } }

    LazyColumn(
        Modifier.fillMaxSize().background(Bg),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("OnAir debug", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, raw = true)
                PillButton("Recompute (no network)", modifier = Modifier.focusRequester(first), raw = true) { tick++ }
                Text("Back = Settings", color = Muted, fontSize = 14.sp, raw = true)
            }
            Spacer(Modifier.height(12.dp))
        }
        val d = data
        if (d == null) {
            item { DebugLine("Computing …") }
            return@LazyColumn
        }
        val s = d.snap
        item { DebugHeader("Guide database") }
        item { DebugLine("Imported: ${stamp(d.importedAt)}   ·   programmes: ${d.rows}   ·   channels with guide data: ${d.guideChannels}") }
        item {
            DebugLine(
                "XMLTV fields: category ${pct(d.withCats, d.rows)}   ·   episode-num ${pct(d.withEpisode, d.rows)}   ·   sub-title ${pct(d.withSub, d.rows)}",
                "Classification uses titles, Xtream category and channel name when these are empty."
            )
        }
        item { DebugLine("XMLTV file downloaded: ${stamp(d.storedAt)}   ·   status: ${Lang.tr(d.status)}") }
        item { DebugLine("Live channels: ${d.live}   ·   with tvg-id: ${d.liveWithTvg}   ·   with guide rows: ${d.liveWithGuide}") }

        item { DebugHeader("On now (${hhmm(s.at)}) – ${s.items.size} cards after merging, computed in ${s.ms} ms (cached up to 60 s)") }
        item { DebugLine(OnNowClass.entries.joinToString("   ·   ") { "${it.name.lowercase().replaceFirstChar { c -> c.uppercase() }}: ${d.byClass[it] ?: 0}" }) }

        item { DebugHeader("Sports by group") }
        if (d.bySport.isEmpty()) item { DebugLine("No sports on now") }
        else item { DebugLine(d.bySport.entries.joinToString("   ·   ") { "${it.key.label}: ${it.value}" }) }

        item { DebugHeader("Events – live ${s.events.live.size}   ·   coming up (24 h) ${s.events.upcoming.size}   ·   PPV channels with nothing listed ${s.events.channels.size}") }
        items(s.events.live.take(10), key = { "el" + it.prog.tvgId + it.prog.start + it.title }) { DebugItem(it, s.at) }
        items(s.events.upcoming.take(10), key = { "eu" + it.prog.tvgId + it.prog.start + it.title }) { DebugItem(it, s.at, upcoming = true) }
        items(s.events.channels.take(20), key = { "ec" + it.id }) { ch ->
            DebugLine("[PPV channel] ${OnNowLogic.eventTitle(ch)}", "Xtream: ${ch.group}" + (ch.catId?.let { " (#$it)" } ?: "") + "   ·   " + (if (ch.tvgId.isNullOrBlank()) "no tvg-id" else "tvg-id ${ch.tvgId}, nothing listed"))
        }

        item { DebugHeader("Other – ${d.other.size} samples") }
        items(d.other, key = { "o" + it.prog.tvgId + it.prog.start + it.title }) { DebugItem(it, s.at) }

        item { DebugHeader("Sports – ${d.sports.size} samples") }
        items(d.sports, key = { "s" + it.prog.tvgId + it.prog.start + it.title }) { DebugItem(it, s.at) }

        item { DebugHeader("Other Sports – ${d.otherSports.size} samples (sport keywords to tune)") }
        items(d.otherSports, key = { "x" + it.prog.tvgId + it.prog.start + it.title }) { DebugItem(it, s.at) }
    }
}

@Composable
private fun DebugHeader(text: String) {
    Text(text, color = Accent2, fontSize = 19.sp, fontWeight = FontWeight.Bold, raw = true, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

/** One focusable line (the remote scrolls by moving the focus). */
@Composable
private fun DebugLine(text: String, sub: String? = null) {
    var f by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusS)).background(if (f) RowFocus else Color.Transparent)
            .onFocusChanged { f = it.isFocused }.focusable().padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(text, color = Color.White, fontSize = 15.sp, raw = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = Muted, fontSize = 13.sp, raw = true, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DebugItem(it: OnNowItem, now: Long, upcoming: Boolean = false) {
    val ch = it.sources.firstOrNull()
    // merged duplicates – spelled out so it can't be mistaken for a timeshift "(+1)" in a channel name
    val more = when (val n = it.sources.size - 1) { 0 -> ""; 1 -> "   ·   +1 more source"; else -> "   ·   +$n more sources" }
    val sport = it.sport?.let { s -> "[${s.label}] " } ?: ""
    val cats = it.prog.categories.joinToString(", ").ifEmpty { "–" }
    val time = if (upcoming) "starts ${hhmm(it.prog.start)}" else "${hhmm(it.prog.start)}–${hhmm(it.prog.stop)}, ${it.remainingMin(now)} min left"
    DebugLine(
        "$sport${it.title}" + (it.prog.episode?.let { e -> "  $e" } ?: "") + "   ·   $time",
        "${ch?.name ?: "?"}$more   ·   Xtream: ${ch?.group ?: "?"}" + (ch?.catId?.let { id -> " (#$id)" } ?: "") + "   ·   EPG: $cats"
    )
}

package com.ipvio.stream

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.lerp
import coil.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Kurz nach dem Schließen eines Auswahlfensters im Guide (Zurück) keinen weiteren Zurück-Schritt ausführen. */
object GuideBack {
    @Volatile var mutedUntil = 0L
    /** Zurück-Schritt des offenen Guides (der Player fängt die Zurück-Taste zuerst ab und ruft das hier auf). */
    var handler: (() -> Unit)? = null
}

/** Merkt sich die Ansicht des TV-Guides (Zoom, Zeilenhöhe, vergangene Sendungen). */
object GuidePrefs {
    // Standard (Runde 14, Wunsch des Nutzers): Zoom „Groß“, Zeilenhöhe „Kompakt“
    var zoom by mutableIntStateOf(2)
    var rowSize by mutableIntStateOf(0)
    var hidePast by mutableStateOf(false)
    private var inited = false

    fun init(c: Context) {
        if (inited) return
        inited = true
        val p = Profiles.sp(c, "ipvio_guide")
        // Einmalig: neue Standard-Ansicht (Groß / Kompakt) auch für alle, die schon gespeichert hatten
        if (p.getInt("ver", 1) < 2) p.edit().putInt("zoom", 2).putInt("row", 0).putInt("ver", 2).apply()
        zoom = p.getInt("zoom", 2).coerceIn(0, 2)
        rowSize = p.getInt("row", 0).coerceIn(0, 2)
        hidePast = p.getBoolean("past", false)
    }

    fun save(c: Context) {
        Profiles.sp(c, "ipvio_guide").edit()
            .putInt("zoom", zoom).putInt("row", rowSize).putBoolean("past", hidePast).apply()
    }
}

@Composable
fun Guide(
    list: List<Item>, curId: String, focus: FocusRequester, onClose: () -> Unit,
    onArchive: (Item, EpgEntry) -> Unit = { _, _ -> },
    /** Alle Sender (für die Kategorien links); Standard = nur die übergebene Liste. */
    all: List<Item> = list,
    /** Sender aus einer anderen Kategorie gewählt: mit dieser Liste weiterschalten. */
    onPickIn: ((Item, List<Item>) -> Unit)? = null,
    onPick: (Item) -> Unit
) {
    val ctx = LocalContext.current
    GuidePrefs.init(ctx)
    val openedAt = remember { System.currentTimeMillis() / 1000 }
    val base = (openedAt - 1800) - ((openedAt - 1800) % 1800)
    // Sender mit Archiv (Catchup): so viele Tage lässt sich zurückblättern
    val pastDays = remember(list, Catchup.version) { Catchup.maxDays(list) }
    val minStart = base - pastDays * 86400L
    // "Jetzt" wandert mit: die Markierung und der Status der Sendungen werden alle 30 s aktualisiert
    var nowSec by remember { mutableLongStateOf(openedAt) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); nowSec = System.currentTimeMillis() / 1000 } }
    // dp je Minute: „Klein“ zeigt gut 3 Stunden auf einmal, „Mittel“ gut 2 Stunden
    val zooms = listOf(3.5f, 5f, 8f)
    val zoomNames = listOf("Klein", "Mittel", "Groß")
    val rowNames = listOf("Kompakt", "Normal", "Groß")
    val rowHeights = listOf(54, 66, 82)
    val dpm = zooms[GuidePrefs.zoom]
    val rowH = rowHeights[GuidePrefs.rowSize]
    val maxSpan = 2880L * 60
    var viewMin by remember { mutableIntStateOf(150) }
    var winStart by remember { mutableLongStateOf(base) }
    var infoE by remember { mutableStateOf<EpgEntry?>(null) }
    var infoItem by remember { mutableStateOf<Item?>(null) }
    var inBlock by remember { mutableStateOf(false) }
    var inCats by remember { mutableStateOf(false) }
    var rowReq by remember { mutableStateOf<FocusRequester?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    // Kategorien links (wie im Entwurf): aktuelle Liste, alle Sender, Favoriten, eigene Listen, Gruppen des Anbieters
    val cats = remember(list, all, Library.favs.size, ChannelLists.lists.size) { guideCategories(list, all) }
    var catIdx by remember { mutableIntStateOf(0) }
    val shown = cats.getOrNull(catIdx)?.items ?: list
    val catReq = remember { FocusRequester() }
    val idx = shown.indexOfFirst { it.id == curId }.coerceAtLeast(0)
    val ls = key(catIdx) { rememberLazyListState(maxOf(idx - 1, 0)) }

    // OnAir Phase 3: no bulk prefetch any more – rows read the guide database; the provider is asked only for a focused
    // channel without guide rows (see GuideRow)

    fun shiftFor(e: EpgEntry) {
        val center = e.start + minOf(e.end - e.start, viewMin * 60L) / 2
        winStart = (center - viewMin * 30L).coerceIn(minStart, base + maxSpan - viewMin * 60L)
    }

    fun jumpNow() {
        winStart = (nowSec - 20 * 60L).coerceIn(minStart, base + maxSpan - viewMin * 60L)
        runCatching { focus.requestFocus() }
    }

    // Zurück (Wunsch des Nutzers, Runde 14): in den Sendungen → zum Sender dieser Zeile (dessen Programm man gerade ansieht);
    // in der Senderspalte → zur gewählten Kategorie; in den Kategorien → schließen (Links dort ebenso).
    // Der Player fängt die Zurück-Taste ab und ruft diesen Schritt über GuideBack.handler auf (vorher schloss er den Guide sofort).
    val backStep by androidx.compose.runtime.rememberUpdatedState<() -> Unit> {
        if (System.currentTimeMillis() >= GuideBack.mutedUntil) when {
            inBlock -> {
                val r = rowReq
                if (r == null || !runCatching { r.requestFocus() }.isSuccess) { if (!runCatching { catReq.requestFocus() }.isSuccess) onClose() }
            }
            !inCats -> if (!runCatching { catReq.requestFocus() }.isSuccess) onClose()
            else -> onClose()
        }
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        GuideBack.handler = { backStep() }
        onDispose { GuideBack.handler = null }
    }
    LaunchedEffect(GuidePrefs.zoom) { infoE?.let { shiftFor(it) } }

    // Oben: links Kopf + markierte Sendung, rechts das laufende Bild (PlayerScreen verkleinert es auf 400 × 225 dp, oben rechts).
    // Darunter: Kategorien und Programmraster über die ganze Breite – so sieht man mehrere Stunden auf einmal.
    Column(
        Modifier.fillMaxSize()
            .drawBehind {
                val l = size.width - 424.dp.toPx(); val t = 20.dp.toPx()
                val hole = androidx.compose.ui.geometry.RoundRect(l, t, l + 400.dp.toPx(), t + 225.dp.toPx(), androidx.compose.ui.geometry.CornerRadius(DS.RadiusM.toPx()))
                val path = androidx.compose.ui.graphics.Path().apply {
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                    addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                    addRoundRect(hole)
                }
                drawPath(path, Color(0xF7090B12))
            }
            .padding(start = 24.dp, top = 14.dp, bottom = 10.dp, end = 24.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(238.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(end = 20.dp)) {
                // Kopf: Titel, Datum und Uhrzeit, Jetzt und Ansicht
                Row(Modifier.fillMaxWidth().height(58.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MenuIcon(3, Accent1, Modifier.size(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text("TV-Guide", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Live TV · Jetzt und in den nächsten Stunden", color = Muted, fontSize = 13.sp, maxLines = 1)
                    }
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(Color(0x1FFFFFFF)).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(raw = true, text = dayLabel(nowSec), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Box(Modifier.width(1.dp).height(14.dp).background(Color(0x44FFFFFF)))
                        Text(raw = true, text = hhmm(nowSec), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, style = TNUM)
                    }
                    SmallPill("Jetzt", icon = AIcon.Dot) { jumpNow() }
                    Box {
                        SmallPill("", icon = AIcon.Gear) { showSettings = true }
                        if (showSettings) {
                            val first = remember { FocusRequester() }
                            LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
                            Popup(
                                alignment = Alignment.TopStart, offset = IntOffset(0, 80),
                                onDismissRequest = { showSettings = false }, properties = PopupProperties(focusable = true)
                            ) {
                                Column(
                                    Modifier.width(380.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Tok.GlassStrong).padding(18.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text("TV-Guide – Ansicht", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("Zoom", color = Color.White, fontSize = 15.sp)
                                        SmallPill(zoomNames[GuidePrefs.zoom], Modifier.focusRequester(first)) { GuidePrefs.zoom = (GuidePrefs.zoom + 1) % 3; GuidePrefs.save(ctx) }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("Zeilenhöhe", color = Color.White, fontSize = 15.sp)
                                        SmallPill(rowNames[GuidePrefs.rowSize]) { GuidePrefs.rowSize = (GuidePrefs.rowSize + 1) % 3; GuidePrefs.save(ctx) }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                        Text("Vergangene Sendungen", color = Color.White, fontSize = 15.sp)
                                        SmallPill(if (GuidePrefs.hidePast) "Ausblenden" else "Abdunkeln") { GuidePrefs.hidePast = !GuidePrefs.hidePast; GuidePrefs.save(ctx) }
                                    }
                                    Text("Zurück: Sendung, Sender, Kategorie, dann schließen", color = Muted, fontSize = 13.sp)
                                    Text("Jetzt ${hhmm(nowSec)} · Zeitzone ${epgZone().id} (änderbar unter Einstellungen > Sprache & Region)", color = Muted, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                GuideDetailPanel(infoE, infoItem, nowSec, Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp))
            }
            Spacer(Modifier.width(400.dp))
        }
        Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            // Kategorien
            LazyColumn(
                Modifier.width(206.dp).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusL)).background(Color(0x0FFFFFFF)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                itemsIndexed(cats) { ci, c ->
                    GuideCatRow(c, ci == catIdx, if (ci == catIdx) Modifier.focusRequester(catReq) else Modifier,
                        onFocus = { inCats = true; inBlock = false; if (catIdx != ci) catIdx = ci }, onLeft = onClose) { runCatching { focus.requestFocus() } }
                }
            }
            Spacer(Modifier.width(12.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(DS.RadiusL)).background(Color(0x0AFFFFFF)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                val vm = ((maxWidth - 208.dp).value / dpm).toInt().coerceAtLeast(40)
                SideEffect { if (viewMin != vm) viewMin = vm }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(raw = true, text = cats.getOrNull(catIdx)?.name?.let { Lang.tr(it) } ?: "", color = Muted, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(208.dp).padding(start = 6.dp))
                        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                            // nur die sichtbaren Zeitmarken (mit Archiv reicht das Raster Tage zurück)
                            var t = maxOf(minStart, winStart - winStart % 1800 - 3600)
                            val tEnd = minOf(base + maxSpan, winStart + viewMin * 60L + 3600)
                            while (t < tEnd) {
                                val x = (t - winStart) / 60f * dpm
                                if (x > -100f && x < viewMin * dpm + 40f) {
                                    val isDay = SettingsLogic.isMidnight(t, epgZone())
                                    val label = if (isDay) dayLabel(t) else hhmm(t)
                                    Text(
                                        label, color = if (isDay) Accent2 else Muted, fontSize = 13.sp,
                                        fontWeight = if (isDay || t % 3600L == 0L) FontWeight.Bold else FontWeight.Medium, style = TNUM,
                                        modifier = Modifier.offset(x = x.dp).align(Alignment.CenterStart)
                                    )
                                }
                                t += 1800
                            }
                            val nx = (nowSec - winStart) / 60f * dpm
                            if (nx > 0f && nx < viewMin * dpm) Box(Modifier.offset(x = (nx - 3f).dp).size(6.dp).clip(RoundedCornerShape(50)).background(Accent2).align(Alignment.BottomStart))
                        }
                    }
                    if (shown.isEmpty()) Text("Keine Sender", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(16.dp))
                    LazyColumn(state = ls, modifier = Modifier.weight(1f)) {
                        itemsIndexed(shown) { i, ch ->
                            val cellReq = if (i == idx) focus else remember { FocusRequester() }
                            GuideRow(
                                ch, ch.id == curId, winStart, viewMin, dpm, nowSec, rowH.dp, GuidePrefs.hidePast, cellReq,
                                onInfo = { e -> infoE = e; infoItem = ch },
                                onShift = { e -> shiftFor(e) },
                                onBlock = { inB, r -> inBlock = inB; inCats = false; rowReq = r },
                                // Links aus der Senderspalte: zu den Kategorien (früher: schließen)
                                onClose = { if (!runCatching { catReq.requestFocus() }.isSuccess) onClose() },
                                onArchive = { e -> onArchive(ch, e) },
                                onPick = { val p = onPickIn; if (p != null) p(ch, shown) else onPick(ch) }
                            )
                        }
                    }
                }
            }
        }
        // Tastenhilfe
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            GuideHint(null, "Sendungen", AIcon.Left, AIcon.Right)
            GuideHint(null, "Sender", AIcon.Up, AIcon.Down)
            // Was OK bei der markierten Sendung tut (früher ein Knopf im Detailfeld, der bei langen Texten wegfiel)
            val okText = guideOkAction(infoE, infoItem, nowSec)
            GuideHint("OK", okText)
            if (pastDays > 0) GuideHint(null, "1 Tag", AIcon.Rewind, AIcon.FastForward)
            GuideHint("Zurück", "Sender, Kategorie, schließen")
        }
}
}

/** Deckende Farben der Sendungsfelder (keine Transparenz, siehe GuideRow). */
private val BlockBase = Color(0xFF1C202B)
private val BlockPast = Color(0xFF15181F)
private val BlockLine = Color(0xFF2A2F3B)

/** Eine Kategorie links im TV-Guide. */
class GuideCat(val name: String, val items: List<Item>, val icon: AIcon?)

/** Kategorien für den TV-Guide: aktuelle Liste (falls anders als alle), alle Sender, Favoriten, eigene Listen, Gruppen. */
fun guideCategories(list: List<Item>, all: List<Item>): List<GuideCat> {
    val out = ArrayList<GuideCat>()
    val allIds = all.size
    if (list.size != allIds && list.isNotEmpty()) out.add(GuideCat("Aktuelle Liste", list, AIcon.Play))
    out.add(GuideCat("Alle Sender", all, AIcon.Grid))
    val byId = all.associateBy { it.id }
    val favs = Library.favs.mapNotNull { byId[it] }
    if (favs.isNotEmpty()) out.add(GuideCat("Favoriten", favs, AIcon.Star))
    for (l in ChannelLists.lists.toList()) {
        val items = l.ids.mapNotNull { byId[it] }
        if (items.isNotEmpty()) out.add(GuideCat(l.name, items, AIcon.Lists))
    }
    val groups = LinkedHashMap<String, ArrayList<Item>>()
    for (it in all) groups.getOrPut(it.group) { ArrayList() }.add(it)
    if (groups.size > 1) for ((g, l) in groups) out.add(GuideCat(g, l, null))
    return out
}

@Composable
private fun GuideCatRow(c: GuideCat, selected: Boolean, modifier: Modifier, onFocus: () -> Unit, onLeft: () -> Unit, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) RowFocus else if (selected) Accent1.copy(alpha = .18f) else Color.Transparent)
            .border(if (f) DS.Border else 1.dp, if (f) Accent2 else if (selected) Accent1.copy(alpha = .55f) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            // Links in den Kategorien: Guide schließen (wie Zurück)
            .onPreviewKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onLeft(); true } else false }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (c.icon != null) AppIcon(c.icon, if (c.icon == AIcon.Star) DS.Gold else if (selected) Accent1 else Muted, size = 15.dp)
        // Namen des Anbieters bleiben, wie sie sind; eigene Bezeichnungen der App werden übersetzt
        Text(c.name, color = Color.White, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), raw = c.icon == null || c.icon == AIcon.Lists)
        Text(raw = true, text = c.items.size.toString(), color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun GuideHint(key: String?, text: String, vararg keys: AIcon) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(Color(0x2EFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            if (key != null) Text(key, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 2.dp))
            for (k in keys) AppIcon(k, Color.White, size = 14.dp)
        }
        Text(text, color = Muted, fontSize = 13.sp)
    }
}

/**
 * Rechte Spalte (Entwurf Bild 3): markierte Sendung mit Status, Titel, Sender · Zeit · Dauer, Beschreibung,
 * was OK tut (ansehen / aus dem Archiv / erinnern) und die nächsten Sendungen des Senders.
 */
@Composable
private fun GuideDetailPanel(e: EpgEntry?, ch: Item?, nowSec: Long, modifier: Modifier) {
    val shape = RoundedCornerShape(DS.RadiusL)
    Row(
        modifier.clip(shape).background(Color(0x0FFFFFFF)).border(1.dp, Color(0x14FFFFFF), shape).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (e == null || ch == null) {
                Text("TV-Guide", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                Text("Wähle eine Sendung mit den Pfeiltasten, OK schaltet auf den Sender.", color = PT.T2, fontSize = 14.sp)
                Text("Zurück: zum Sender, dann zur Kategorie, dann schließen.", color = Muted, fontSize = 13.sp)
                return@Column
            }
            val isNow = nowSec >= e.start && nowSec < e.end
            val past = e.end <= nowSec
            val untilMin = (e.start - nowSec) / 60
            val archived = past && Catchup.canPlay(ch, e, nowSec)
            val status = when {
                isNow -> "JETZT"
                archived -> "IM ARCHIV"
                past -> "BEENDET"
                untilMin < 180 -> "in $untilMin Min"
                else -> dayLabel(e.start)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    status, color = if (isNow) OnAccent else Tok.TextMid, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.clip(RoundedCornerShape(DS.RadiusXs)).background(if (isNow) Accent1 else Color(0x2EFFFFFF)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
                Text(raw = true, text = ch.title, color = PT.T2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text(raw = true, text = "·  ${hhmm(e.start)} – ${hhmm(e.end)}", color = PT.T2, fontSize = 13.sp, style = TNUM, maxLines = 1)
                Text("·  ${(e.end - e.start) / 60} Min", color = PT.T2, fontSize = 13.sp, maxLines = 1)
                if (Reminders.isSet(ch.id, e.start)) AppIcon(AIcon.Bell, Accent2, size = 14.dp)
            }
            Text(raw = true, text = e.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isNow) {
                val frac = ((nowSec - e.start).toFloat() / (e.end - e.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(DS.Overlay3)) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent1))
                    }
                    Text("noch ${((e.end - nowSec) / 60).coerceAtLeast(0)} Min", color = Accent2, fontSize = 13.sp, fontWeight = FontWeight.Bold, style = TNUM)
                }
            }
            // Die ganze Breite gehört der Beschreibung (die Spalte „Nächste Sendungen“ ist auf Wunsch des Nutzers entfallen)
            if (e.desc.isNotBlank()) Text(raw = true, text = e.desc, color = DS.TextSoft, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
        }
    }
}

/** Beschriftung für OK in der Tastenhilfe: ansehen, aus dem Archiv ansehen oder Erinnerung setzen/löschen. */
private fun guideOkAction(e: EpgEntry?, ch: Item?, nowSec: Long): String {
    if (e == null || ch == null) return "Ansehen / Erinnerung"
    return when {
        ReminderLogic.canRemind(e.start, nowSec) -> if (Reminders.isSet(ch.id, e.start)) "Erinnerung löschen" else "Erinnerung setzen"
        e.end <= nowSec && Catchup.canPlay(ch, e, nowSec) -> "Aus dem Archiv ansehen"
        else -> "Ansehen"
    }
}

@Composable
private fun GuideRow(
    ch: Item, current: Boolean, winStart: Long, viewMin: Int, dpm: Float, nowSec: Long, rowH: Dp, hidePast: Boolean,
    cellReq: FocusRequester, onInfo: (EpgEntry?) -> Unit, onShift: (EpgEntry) -> Unit,
    onBlock: (Boolean, FocusRequester) -> Unit, onClose: () -> Unit, onArchive: (EpgEntry) -> Unit, onPick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val cu = remember(ch.id, Catchup.version) { Catchup.of(ch) }
    // null = wird noch geladen (zeigt "lädt …" statt fälschlich "Kein Programm verfügbar")
    // OnAir Phase 3: from the guide database; only when the focus rests on this channel (0.7 s) and it has no guide rows is
    // the provider asked – leaving the row cancels that
    var rowFocused by remember(ch.id) { mutableStateOf(false) }
    val raw by produceState<List<EpgEntry>?>(null, ch.id, rowFocused) {
        value = EpgFull.get(ch)
        if (rowFocused && value.isNullOrEmpty() && !Xmltv.has(ch.tvgId)) {
            kotlinx.coroutines.delay(700)
            value = EpgFull.get(ch, network = true)
        }
    }
    // „Vergangene ausblenden“ lässt Sendungen im Archiv stehen: die kann man noch ansehen
    val entries = remember(raw, hidePast, cu) {
        cleanEpg(raw.orEmpty()).let { l -> if (hidePast) l.filter { it.end > nowSec || CatchupLogic.canPlay(cu, it.start, it.end, nowSec) } else l }
    }
    val blockReqs = remember(entries) { HashMap<Long, FocusRequester>() }
    val from = winStart - 120 * 60L
    val to = winStart + (viewMin + 120) * 60L
    Row(Modifier.height(rowH).padding(vertical = 3.dp)) {
        var f by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(DS.RadiusS)
        Row(
            Modifier.focusRequester(cellReq).width(208.dp).fillMaxHeight().padding(end = 8.dp).clip(shape)
                .background(if (f) Accent1.copy(alpha = .30f) else if (current) DS.Overlay2 else Color(0x12FFFFFF))
                .border(if (f) DS.Border else 1.dp, if (f) Accent1 else Color(0x0FFFFFFF), shape)
                .onFocusChanged {
                    f = it.isFocused
                    rowFocused = it.isFocused
                    if (it.isFocused) {
                        onBlock(false, cellReq)
                        val now = Epg.current(entries)
                        onInfo(now)
                        if (now != null) onShift(now)
                    }
                }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onClose(); true } else false
                }
                .clickable(onClick = onPick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(DS.RadiusXs)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
                if (ch.logo != null) AsyncImage(ch.logo, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
            }
            Text(
                ch.title, color = Color.White, fontSize = 14.sp,
                fontWeight = if (current || f) FontWeight.ExtraBold else FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val shown = remember(entries, from, to) { entries.filter { it.end > from && it.start < to } }
            if (shown.isEmpty()) Text(
                if (raw == null) "lädt …" else "Kein Programm verfügbar", color = Muted, fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp).align(Alignment.CenterStart)
            )
            shown.forEach { e ->
                key(e.start) {
                    val x = (e.start - winStart) / 60f * dpm
                    val w = ((e.end - e.start) / 60f * dpm - 3f).coerceIn(3f, 6000f)
                    val isNow = nowSec >= e.start && nowSec < e.end
                    val past = e.end <= nowSec
                    var bf by remember { mutableStateOf(false) }
                    val reminded = Reminders.isSet(ch.id, e.start)
                    val inArchive = past && CatchupLogic.canPlay(cu, e.start, e.end, nowSec)
                    val dw = if (bf) maxOf(w, 200f) else w
                    val frac = if (isNow) ((nowSec - e.start).toFloat() / (e.end - e.start)).coerceIn(0f, 1f) else 0f
                    val req = blockReqs.getOrPut(e.start) { FocusRequester() }
                    val blockShape = RoundedCornerShape(DS.RadiusS)
                    Box(
                        Modifier.offset(x = x.dp).zIndex(if (bf) 2f else 0f).width(dw.dp).fillMaxHeight().clip(blockShape)
                            .then(
                                // Alle Felder deckend (Runde 14): die markierte Sendung wird breiter und liegt über den Nachbarn –
                                // durchscheinend überlagerten sich sonst die Texte kurzer Sendungen
                                if (bf) Modifier.background(Brush.horizontalGradient(listOf(lerp(BlockBase, Accent1, .62f), lerp(BlockBase, Accent1, .42f))))
                                else if (isNow) Modifier.background(lerp(BlockBase, Accent1, .16f))
                                else Modifier.background(if (past && !inArchive) BlockPast else BlockBase)
                            )
                            .border(if (bf) 2.dp else 1.dp, if (bf) Accent1 else if (isNow) lerp(BlockBase, Accent1, .40f) else BlockLine, blockShape)
                            .focusRequester(req)
                            .onPreviewKeyEvent { ev ->
                                // Spulen-Tasten: einen Tag zurück/vor (im Archiv schnell zu älteren Sendungen)
                                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.MediaRewind || ev.key == Key.MediaFastForward)) {
                                    val want = e.start + (if (ev.key == Key.MediaFastForward) 86400L else -86400L)
                                    val target = entries.minByOrNull { kotlin.math.abs(it.start - want) }
                                    if (target != null && target.start != e.start) {
                                        onShift(target)
                                        scope.launch {
                                            androidx.compose.runtime.withFrameNanos { }
                                            androidx.compose.runtime.withFrameNanos { }
                                            runCatching { blockReqs[target.start]?.requestFocus() }
                                        }
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionRight || ev.key == Key.DirectionLeft)) {
                                    val i = entries.indexOfFirst { it.start == e.start }
                                    val ni = i + (if (ev.key == Key.DirectionRight) 1 else -1)
                                    if (ni < 0) { runCatching { cellReq.requestFocus() } }
                                    else if (ni <= entries.lastIndex) {
                                        val target = entries[ni]
                                        onShift(target)
                                        scope.launch {
                                            androidx.compose.runtime.withFrameNanos { }
                                            androidx.compose.runtime.withFrameNanos { }
                                            runCatching { blockReqs[target.start]?.requestFocus() }
                                        }
                                    }
                                    true
                                } else false
                            }
                            .onFocusChanged { bf = it.isFocused; if (it.isFocused) { onBlock(true, cellReq); onInfo(e); onShift(e) } }
                            // Spätere Sendung: OK setzt/löscht die Erinnerung (Runde 14: ohne Auswahlfenster);
                            // im Archiv: Sendung abspielen; sonst Sender ansehen
                            .clickable {
                                val n = System.currentTimeMillis() / 1000
                                if (ReminderLogic.canRemind(e.start, n)) {
                                    val on = Reminders.toggle(ctx, Reminder(ch.id, ch.title, e.title, e.start, e.end))
                                    if (on) Reminders.askPermission(ctx as? android.app.Activity)
                                    android.widget.Toast.makeText(
                                        ctx,
                                        if (on) Lang.tr("Erinnerung gesetzt – ${Reminders.leadMin} Min vorher") else Lang.tr("Erinnerung gelöscht"),
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                else if (e.end <= n && CatchupLogic.canPlay(cu, e.start, e.end, n)) onArchive(e)
                                else onPick()
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (reminded) AppIcon(AIcon.Bell, if (bf) Color.White else Accent2, Modifier.align(Alignment.TopEnd), 13.dp)
                        if (inArchive && dw >= 30f) AppIcon(AIcon.Catchup, if (bf) Color.White else Accent2, Modifier.align(Alignment.TopEnd), 13.dp)
                        // Entwurf: Titel oben, Zeit darunter
                        if (dw >= 44f) Column {
                            if (dw >= 72f) Text(
                                e.title, color = if (past && !inArchive && !bf) Color(0xFF9AA0B2) else Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, raw = true
                            )
                            Text(
                                raw = true, text = hhmm(e.start) + " – " + hhmm(e.end), color = if (bf) Color(0xE6FFFFFF) else Muted,
                                fontSize = 13.sp, style = TNUM, maxLines = 1
                            )
                        }
                        if (isNow) Box(Modifier.align(Alignment.BottomStart).height(3.dp).width((dw * frac).dp).background(if (bf) Color.White else Accent1))
                    }
                }
            }
            val nx = (nowSec - winStart) / 60f * dpm
            if (nx > 0f && nx < viewMin * dpm) Box(Modifier.offset(x = nx.dp).width(2.dp).fillMaxHeight().background(Accent2.copy(alpha = 0.8f)))
        }
    }
}

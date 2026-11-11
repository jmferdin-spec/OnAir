package com.ipvio.stream

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

object ZapContext {
    var list: List<Item> = emptyList()
    /** Kategorie, aus der gestartet wurde (z. B. eigene Senderliste „L:3“) – der Player zeigt sie im Senderwechsel. */
    var key: String? = null
}

/** Merkt sich die zuletzt gewählte Kategorie / Liste in Live TV (auch über einen Neustart). */
object LiveMemory {
    fun sel(c: android.content.Context): String? = Profiles.sp(c, "ipvio_live").getString("sel", null)
    fun setSel(c: android.content.Context, k: String) { Profiles.sp(c, "ipvio_live").edit().putString("sel", k).apply() }
}

/** Eingabe eines Namens (neue Senderliste, Umbenennen). */
private class NameAsk(val title: String, val initial: String, val onSave: (String) -> Unit)

@Composable
fun LiveTab(live: List<Item>, groups: Map<String, List<Item>>, favs: List<Item>, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    CatFavs.init(ctx)
    ChannelLists.init(ctx)
    // Zuletzt gewählte Kategorie / Liste (Runde 14): nach dem Zurück aus dem Player und nach einem Neustart wieder dieselbe
    var sel by remember {
        val saved = LiveMemory.sel(ctx)?.takeIf { k ->
            (k == FAV_CAT && favs.isNotEmpty()) || k == "ALL" || k in groups || ChannelLists.byKey(k) != null
        }
        mutableStateOf(saved ?: if (favs.isNotEmpty()) FAV_CAT else "ALL")
    }
    val favCats = CatFavs.list.filter { it in groups }
    val myLists = ChannelLists.lists.toList()
    val byId = remember(live) { live.associateBy { it.id } }
    // Reihenfolge: Favoriten, eigene Senderlisten, „Neue Senderliste“, alle Sender, Kategorien
    val names = listOf("GUIDE", FAV_CAT) + myLists.map { ChannelListLogic.key(it) } + listOf("NEW", "ALL") + favCats + groups.keys.filter { it !in favCats }
    val curList = ChannelLists.byKey(sel)
    val list = when {
        sel == FAV_CAT -> favs
        sel == "ALL" -> live
        curList != null -> ChannelListLogic.resolve(curList, byId)
        else -> groups[sel].orEmpty()
    }
    val firstChan = remember { FocusRequester() }
    // Zurück aus dem Player: der zuletzt gesehene Sender bekommt den Fokus (nicht die Seitenleiste)
    val chanState = remember { LazyListState() }
    val restoreReq = remember { FocusRequester() }
    val restoreId = remember { LastFocus.pending }
    LaunchedEffect(Unit) {
        val id = restoreId ?: return@LaunchedEffect
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return@LaunchedEffect
        runCatching { chanState.scrollToItem(maxOf(i - 2, 0)) }
        repeat(15) {
            kotlinx.coroutines.delay(80)
            if (runCatching { restoreReq.requestFocus() }.isSuccess) { LastFocus.pending = null; return@LaunchedEffect }
        }
    }
    var askDelete by remember { mutableStateOf(false) }
    val catState = remember { LazyListState() }
    var ask by remember { mutableStateOf<NameAsk?>(null) }
    SideEffect { ContentFocus.tab = 3 }
    // Auswahl zeigt auf eine gelöschte Liste: zurück zu „Alle Sender“
    LaunchedEffect(sel, myLists.size) { if (ChannelListLogic.idOfKey(sel) != null && curList == null) sel = "ALL" }

    // Sender zu einer eigenen Liste hinzufügen: in „Alle Sender“/Kategorien fügt OK den Sender hinzu (statt ihn zu starten)
    var addTo by remember { mutableStateOf<Int?>(null) }
    val addList = addTo?.let { id -> myLists.firstOrNull { it.id == id } }
    fun finishAdd() { val id = addTo; addTo = null; if (id != null) sel = ChannelListLogic.PREFIX + id }
    androidx.activity.compose.BackHandler(enabled = addList != null) { finishAdd() }

    // Ohne eigenes Fenster und ohne Tastatur: die Liste wird gleich mit Standardnamen angelegt (umbenennen geht jederzeit)
    fun newList(first: String?) {
        val id = ChannelLists.create(ctx, Lang.tr("Senderliste " + (myLists.size + 1)), first)
        sel = ChannelListLogic.PREFIX + id
        if (first == null) Toast.makeText(ctx, Lang.tr("Liste angelegt. Mit „Sender hinzufügen“ füllen."), Toast.LENGTH_SHORT).show()
    }

    // Runde 17b: markierter Sender bestimmt Hintergrund und die große Anzeige rechts („Was läuft gerade“)
    var focusedCh by remember { mutableStateOf<Item?>(null) }
    // 17c: erst nach kurzem Verweilen wechseln (beim schnellen Durchblättern kein Laden von Logo und Programm)
    var focusTarget by remember { mutableStateOf<Item?>(null) }
    LaunchedEffect(focusTarget) { if (focusedCh != null) kotlinx.coroutines.delay(300); focusedCh = focusTarget }
    Box(Modifier.fillMaxSize()) {
        LiveBackdrop(focusedCh, Modifier.fillMaxSize())
        Row(Modifier.fillMaxSize().padding(top = 20.dp)) {
            LazyColumn(Modifier.width(270.dp).fillMaxHeight().padding(start = 24.dp, end = 8.dp), state = catState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(names) { _, n ->
                    val ml = ChannelLists.byKey(n)
                    if (n == "GUIDE") {
                        // TV-Guide wie im Entwurf: startet den zuletzt gesehenen (sonst ersten) Sender der Auswahl und öffnet den Guide
                        CatRow("TV-Guide", -1, false, Modifier, onFocus = {}, onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Grid) {
                            val src = list.ifEmpty { live }
                            // OnAir: standalone guide on this category (no stream starts); Back returns to Live TV
                            if (src.isNotEmpty()) { ZapContext.list = src; ZapContext.key = sel; OnAirNav.openGuide(OnAirScreen.VOD, src, sel) }
                        }
                        return@itemsIndexed
                    }
                    if (n == "NEW") {
                        CatRow("Neue Senderliste", -1, false, Modifier, onFocus = {}, onRight = { runCatching { firstChan.requestFocus() } }, onFav = {}, icon = AIcon.Plus) { newList(null) }
                        return@itemsIndexed
                    }
                    val label = when {
                        n == FAV_CAT -> "Favoriten"
                        n == "ALL" -> "Alle Sender"
                        ml != null -> ml.name
                        else -> n
                    }
                    val icon = when {
                        n == FAV_CAT || n in CatFavs.list -> AIcon.Star
                        ml != null -> AIcon.Lists
                        else -> null
                    }
                    val count = when {
                        n == FAV_CAT -> favs.size
                        n == "ALL" -> live.size
                        ml != null -> ChannelListLogic.resolve(ml, byId).size
                        else -> groups[n]?.size ?: 0
                    }
                    var listMenu by remember { mutableStateOf(false) }
                    Box {
                        CatRow(
                            label, count, n == sel,
                            if (n == sel) Modifier.focusRequester(ContentFocus.req) else Modifier,
                            onFocus = { sel = n }, onRight = { runCatching { firstChan.requestFocus() } },
                            onFav = { if (ml != null) listMenu = true else if (n != FAV_CAT && n != "ALL") CatFavs.toggle(ctx, n) },
                            icon = icon, raw = ml != null
                        ) { runCatching { firstChan.requestFocus() } }
                        // Menü einer eigenen Liste: umbenennen, löschen
                        if (listMenu && ml != null) OptionPopup(listOf("Umbenennen", "Liste löschen"), -1, { listMenu = false }) { i ->
                            listMenu = false
                            if (i == 0) ask = NameAsk("Liste umbenennen", ml.name) { ChannelLists.rename(ctx, ml.id, it) }
                            else { ChannelLists.delete(ctx, ml.id); sel = "ALL" }
                        }
                    }
                }
            }
            Column(Modifier.weight(0.9f).fillMaxHeight().padding(end = 24.dp)) {
                // Eigene Liste: Umbenennen und Löschen gut sichtbar über den Sendern (vorher nur über langes OK auf der Liste)
                if (curList != null) Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppIcon(AIcon.Lists, Accent1, size = 22.dp)
                    Text(raw = true, text = curList.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PillButton("Sender hinzufügen", if (list.isEmpty()) Modifier.focusRequester(firstChan) else Modifier, icon = AIcon.Plus) { addTo = curList.id; sel = "ALL" }
                    PillButton("Umbenennen", icon = AIcon.Edit) { ask = NameAsk("Liste umbenennen", curList.name) { ChannelLists.rename(ctx, curList.id, it) } }
                    Box {
                        PillButton("Liste löschen", icon = AIcon.Close) { askDelete = true }
                        if (askDelete) OptionPopup(listOf("Löschen", "Abbrechen"), -1, { askDelete = false }, listOf(AIcon.Close, AIcon.Left)) { i ->
                            askDelete = false
                            if (i == 0) { ChannelLists.delete(ctx, curList.id); sel = "ALL"; LiveMemory.setSel(ctx, "ALL") }
                        }
                    }
                }
                if (addList != null) Row(
                    Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppIcon(AIcon.Plus, Accent1, size = 22.dp)
                    Text(raw = true, text = Lang.tr("Zu „") + addList.name + Lang.tr("“ hinzufügen: OK auf einem Sender fügt ihn hinzu bzw. entfernt ihn wieder"),
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    PillButton("Fertig", light = true) { finishAdd() }
                }
                if (list.isEmpty()) EmptyState(
                    if (sel == FAV_CAT) AIcon.Heart else AIcon.Live,
                    if (sel == FAV_CAT) "Noch keine Favoriten" else if (curList != null) "Diese Liste ist noch leer" else "Keine Sender",
                    when {
                        sel == FAV_CAT -> "Auf einem Sender OK gedrückt halten und „Zu Favoriten“ wählen."
                        curList != null -> "In „Alle Sender“ oder einer Kategorie auf einem Sender OK gedrückt halten und „Zu …“ wählen."
                        else -> "In dieser Kategorie gibt es keine Sender. Links eine andere Kategorie wählen."
                    }
                ) else LazyColumn(state = chanState, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                    // In eigenen Listen wandert der Fokus beim Verschieben mit dem Sender mit (Schlüssel = Sender)
                    itemsIndexed(list, key = if (curList != null) { _, ch -> ch.id } else null) { i, ch ->
                        ChannelLine(
                            ch, (if (i == 0) Modifier.focusRequester(firstChan) else Modifier)
                                .then(if (ch.id == restoreId) Modifier.focusRequester(restoreReq) else Modifier), curList,
                            onNewList = { newList(ch.id) },
                            onFocus = { focusTarget = ch }
                        ) {
                            if (addList != null) {
                                val has = ch.id in addList.ids
                                if (has) ChannelLists.remove(ctx, addList.id, ch.id) else ChannelLists.add(ctx, addList.id, ch.id)
                                Toast.makeText(ctx, (if (has) Lang.tr("Entfernt: ") else Lang.tr("Hinzugefügt: ")) + ch.title, Toast.LENGTH_SHORT).show()
                            } else { ZapContext.list = list; ZapContext.key = sel; LiveMemory.setSel(ctx, sel); onOpen(ch) }
                        }
                    }
                }
            }
            // Rechts: was auf dem markierten Sender läuft
            Box(Modifier.weight(1.1f).fillMaxHeight().padding(start = dpx(30), end = dpx(60), top = dpx(150))) {
                LiveNowPanel(focusedCh ?: list.firstOrNull())
            }
        }
        ask?.let { a -> NameDialog(a.title, a.initial, onDismiss = { ask = null }) { a.onSave(it); ask = null } }
    }
}

/** Kleiner Dialog: Name eingeben (Bildschirmtastatur), Speichern oder Abbrechen. */
@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { onDismiss() }
    // Fokus auf „Speichern“, nicht ins Textfeld: sonst geht die Bildschirmtastatur sofort auf und „Zurück“ schließt erst sie, dann erst das Fenster
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(520.dp).clip(RoundedCornerShape(DS.RadiusL)).background(Surf).border(1.dp, DS.Overlay3, RoundedCornerShape(DS.RadiusL)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Field(text, { text = it.take(ChannelListLogic.MAX_NAME) }, "Name", onDone = { onSave(text) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Speichern", Modifier.focusRequester(fr), light = true) { onSave(text) }
                PillButton("Abbrechen") { onDismiss() }
            }
        }
    }
}

@Composable
private fun CatRow(
    label: String, count: Int, selected: Boolean, modifier: Modifier,
    onFocus: () -> Unit, onRight: () -> Unit, onFav: () -> Unit, icon: AIcon? = null, raw: Boolean = false, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().clip(shape)
            .background(if (f) Color(0x33FFFFFF) else if (selected) Accent1.copy(alpha = 0.16f) else Color.Transparent)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && e.nativeKeyEvent.repeatCount == 0) { onRight(); true } else false
            }
            .favKeys { onFav() }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) AppIcon(icon, if (icon == AIcon.Star) DS.Gold else Color.White, size = 17.dp)
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, raw = raw)
        }
        if (count >= 0) Text("$count", color = Muted, fontSize = 13.sp)
    }
}

@Composable
private fun ChannelLine(item: Item, modifier: Modifier, inList: ChList?, onNewList: () -> Unit, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var f by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Box {
    if (menu) ChannelMenu(item, inList, onNewList) { menu = false }
    val shape = RoundedCornerShape(DS.RadiusM)
    val epg by produceState(emptyList<EpgEntry>(), item.id) { value = Epg.now(item) }
    val nowE = Epg.current(epg)
    val nextE = epg.firstOrNull { nowE != null && it.start >= nowE.end }
    // Runde 17b: markierter Sender schwebt als helles Glas mit Schatten hervor (statt Rahmen)
    val sc by androidx.compose.animation.core.animateFloatAsState(if (f) focusScale(1.03f) else 1f, label = "chl")
    Row(
        modifier.fillMaxWidth()
            .graphicsLayer { scaleX = sc; scaleY = sc; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .then(if (f) Modifier.shadow(18.dp, shape, ambientColor = Tok.Lift, spotColor = Tok.Lift) else Modifier)
            .clip(shape)
            .background(if (f) Color(0x29FFFFFF) else Color(0x0AFFFFFF))
            .border(1.dp, if (f) Color(0x40FFFFFF) else Color.Transparent, shape)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .menuKeys(onMenu = { menu = true }, onLong = { menu = true })
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(DS.RadiusS)).background(DS.Overlay2), contentAlignment = Alignment.Center) {
            if (item.logo != null) AsyncImage(item.logo, null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
            else Text(raw = true, text = item.title.take(2).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(raw = true, text = item.title, color = Tok.TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (nowE != null) {
                val frac = ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start)).coerceIn(0f, 1f)
                Text(raw = true, text = "${hhmm(nowE.start)}–${hhmm(nowE.end)}  ${nowE.title}", color = Accent2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(DS.Overlay3)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent2))
                }
                if (nextE != null) Text(raw = true, text = "${hhmm(nextE.start)}  ${nextE.title}", color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text(raw = true, text = item.group, color = Muted, fontSize = 13.sp, maxLines = 1)
        }
        if (Catchup.version >= 0 && Catchup.has(item)) AppIcon(AIcon.Catchup, Accent2, size = 20.dp)
        if (DeadLinks.isDead(item.url)) AppIcon(AIcon.Warning, DS.Gold, size = 20.dp)
        if (Library.isFav(item.id)) AppIcon(AIcon.Star, DS.Gold, size = 20.dp)
    }
    }
}

/**
 * Menü eines Senders (Menü-Taste oder OK gedrückt halten): Favorit, eigene Listen (hinzufügen/entfernen),
 * in der Liste verschieben, neue Liste. [onNewList] = null blendet „Neue Senderliste“ aus (Player).
 */
@Composable
fun ChannelMenu(item: Item, inList: ChList?, onNewList: (() -> Unit)?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val acts = ArrayList<Triple<String, AIcon?, () -> Unit>>()
    acts.add(Triple(if (Library.isFav(item.id)) "Aus Favoriten entfernen" else "Zu Favoriten", AIcon.Star) { Library.toggleFav(ctx, item.id) })
    if (inList != null) {
        acts.add(Triple("Nach oben", AIcon.Up) { ChannelLists.move(ctx, inList.id, item.id, -1) })
        acts.add(Triple("Nach unten", AIcon.Down) { ChannelLists.move(ctx, inList.id, item.id, 1) })
        acts.add(Triple("Aus „${inList.name}“ entfernen", AIcon.Close) { ChannelLists.remove(ctx, inList.id, item.id) })
    }
    for (l in ChannelLists.lists.toList()) {
        if (l.id == inList?.id) continue
        if (item.id in l.ids) acts.add(Triple("In „${l.name}“ – entfernen", AIcon.Check) { ChannelLists.remove(ctx, l.id, item.id) })
        else acts.add(Triple("Zu „${l.name}“", AIcon.Plus) { ChannelLists.add(ctx, l.id, item.id) })
    }
    if (onNewList != null) acts.add(Triple("Neue Senderliste …", AIcon.Plus, onNewList))
    OptionPopup(acts.map { it.first }, -1, onClose, acts.map { it.second }) { i -> onClose(); acts[i].third() }
}

/**
 * Erinnerung, während die App offen ist (auch im Player): „In 5 Min: Tagesschau · Das Erste“ mit Umschalten/Schließen.
 * Verschwindet nach einer Minute von selbst.
 */
@Composable
fun ReminderCard(r: Reminder, modifier: Modifier, onClose: () -> Unit, onSwitch: () -> Unit) {
    val fr = remember { FocusRequester() }
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    androidx.activity.compose.BackHandler { onClose() }
    LaunchedEffect(r.key) {
        kotlinx.coroutines.delay(150)
        runCatching { fr.requestFocus() }
        repeat(4) { kotlinx.coroutines.delay(15_000); nowSec = System.currentTimeMillis() / 1000 }
        onClose()
    }
    val shape = RoundedCornerShape(DS.RadiusL)
    Row(
        modifier.widthIn(max = 760.dp).clip(shape).background(Tok.GlassStrong).border(1.dp, Accent2, shape).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        AppIcon(AIcon.Bell, Accent2, size = 30.dp)
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(raw = true, text = Reminders.whenText(r, nowSec), color = Accent2, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(raw = true, text = r.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(raw = true, text = r.chTitle, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PillButton("Umschalten", Modifier.focusRequester(fr), light = true) { onSwitch() }
        PillButton("Schließen") { onClose() }
    }
}

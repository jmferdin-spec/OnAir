package com.ipvio.stream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Sender-Infoblock: Logo, Nummer, Name, laufende Sendung mit Fortschritt und "Danach". */
@Composable
fun LiveInfoBlock(item: Item?, title: String, number: Int, epg: List<EpgEntry>, modifier: Modifier = Modifier) {
    val nowE = Epg.current(epg)
    val nextE = if (nowE != null) epg.firstOrNull { it.start >= nowE.end } else null
    val logo = item?.logo
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(DS.RadiusM)).background(Color(0x24FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            if (logo != null) AsyncImage(logo, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
            else Text(raw = true, text = title.take(2).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (number > 0) Text("$number", color = Accent2, fontSize = 22.sp, fontWeight = FontWeight.Bold, style = TNUM)
                Text(raw = true, text = title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (nowE != null) {
                val frac = ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${hhmm(nowE.start)}–${hhmm(nowE.end)}", color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold, style = TNUM, maxLines = 1)
                    Text(raw = true, text = nowE.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(DS.Overlay3)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent1))
                }
                if (nextE != null) {
                    Text("Danach ${hhmm(nextE.start)}  ${nextE.title}", color = PT.T2, fontSize = 15.sp, style = TNUM, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                val supported = item != null && Epg.supported(item)
                Text(
                    if (supported) "Kein Programm für diesen Sender" else "Kein Programm verfügbar",
                    color = Muted, fontSize = 15.sp, maxLines = 1
                )
            }
        }
    }
}

/**
 * Senderliste (Links im Live-Player): optional Rubriken-Spalte, Sender-Spalte und rechts ein Infofenster
 * als flache Leiste unten im Bild. Das Verhalten ist 1:1 wie vorher – nur ausgelagert und neu gestaltet.
 */
@Composable
fun ChannelOverlay(
    liveAll: List<Item>, curId: String, curItem: Item?,
    chanCat: String, onChanCat: (String) -> Unit,
    showCats: Boolean, onShowCats: (Boolean) -> Unit,
    focusedCh: Item?, onFocusedCh: (Item) -> Unit,
    inHeader: Boolean, onInHeader: (Boolean) -> Unit,
    chanFocus: FocusRequester, catsFocus: FocusRequester, headerFocus: FocusRequester,
    onClose: () -> Unit, onPick: (Item, List<Item>) -> Unit
) {
    val ctx = LocalContext.current
    val byId = remember(liveAll) { liveAll.associateBy { it.id } }
    val groups = remember(liveAll) { liveAll.groupBy { it.group } }
    val favItems = Library.favs.mapNotNull { byId[it] }
    val favCats = CatFavs.list.filter { it in groups }
    val catNames = favCats + groups.keys.filter { it !in favCats }
    // Eigene Senderlisten (angelegt im Live-TV-Bereich mit der Menü-Taste)
    val myLists = ChannelLists.lists.toList()
    val curList = ChannelLists.byKey(chanCat)
    val chanList = when {
        chanCat == FAV_CAT -> favItems
        chanCat == "ALL" -> liveAll
        curList != null -> ChannelListLogic.resolve(curList, byId)
        else -> groups[chanCat].orEmpty()
    }
    val startIdx = chanList.indexOfFirst { it.id == curId }
    val ls = rememberLazyListState()
    LaunchedEffect(chanCat) { ls.scrollToItem(maxOf(startIdx - 2, 0)) }
    val fc = focusedCh ?: curItem
    val fe by produceState(emptyList<EpgEntry>(), fc?.id) {
        value = if (fc != null) EpgFull.get(fc) else emptyList()
        // OnAir: provider only for a channel the focus rests on (no guide rows); moving on cancels this
        if (fc != null && value.isEmpty() && !Xmltv.has(fc.tvgId)) { kotlinx.coroutines.delay(700); value = EpgFull.get(fc, network = true) }
    }
    val panelShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)

    Row(Modifier.fillMaxSize()) {
        if (showCats) {
            Column(
                Modifier.fillMaxHeight().width(300.dp).background(Color(0xF50C0D16)).padding(16.dp)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false
                        else if (e.key == Key.DirectionRight) { onShowCats(false); true }
                        else if (e.key == Key.DirectionLeft) true
                        else false
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Kategorien", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("OK halten = Kategorie als Favorit", color = Muted, fontSize = 13.sp)
                LazyColumn {
                    item {
                        PCatRow("Favoriten", favItems.size, chanCat == FAV_CAT, true, if (chanCat == FAV_CAT) Modifier.focusRequester(catsFocus) else Modifier, null, { onChanCat(FAV_CAT) }) { onShowCats(false) }
                    }
                    items(myLists) { l ->
                        val k = ChannelListLogic.key(l)
                        PCatRow(l.name, ChannelListLogic.resolve(l, byId).size, chanCat == k, false, if (chanCat == k) Modifier.focusRequester(catsFocus) else Modifier, null, { onChanCat(k) }, icon = AIcon.Lists, raw = true) { onShowCats(false) }
                    }
                    item {
                        PCatRow("Alle Sender", liveAll.size, chanCat == "ALL", false, if (chanCat == "ALL") Modifier.focusRequester(catsFocus) else Modifier, null, { onChanCat("ALL") }) { onShowCats(false) }
                    }
                    items(catNames) { g ->
                        PCatRow(g, groups[g]?.size ?: 0, chanCat == g, g in CatFavs.list, if (chanCat == g) Modifier.focusRequester(catsFocus) else Modifier, { CatFavs.toggle(ctx, g) }, { onChanCat(g) }) { onShowCats(false) }
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxHeight().width(430.dp).clip(panelShape)
                .background(PT.PanelBg).padding(16.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) false
                    else if (e.key == Key.DirectionRight && !showCats && !inHeader) { onClose(); true }
                    else if (e.key == Key.DirectionLeft && !showCats && !inHeader) { onShowCats(true); true }
                    else false
                },
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconLabel(
                when { chanCat == FAV_CAT -> AIcon.Star; curList != null -> AIcon.Lists; else -> null },
                when { chanCat == FAV_CAT -> "Favoriten"; chanCat == "ALL" -> "Alle Sender"; curList != null -> curList.name; else -> chanCat },
                Color.White, 24.sp, FontWeight.ExtraBold, iconSize = 22.dp, iconTint = if (chanCat == FAV_CAT) PT.Gold else Color.White,
                raw = curList != null
            )
            val headerItems = listOf("ALL", FAV_CAT) + myLists.map { ChannelListLogic.key(it) } + favCats
            LazyRow(Modifier.onFocusChanged { onInHeader(it.hasFocus) }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(headerItems) { index, name ->
                    val hl = ChannelLists.byKey(name)
                    FilterPill(
                        if (name == "ALL") "Alle" else if (name == FAV_CAT) "Favoriten" else hl?.name ?: name, chanCat == name,
                        (if (name == chanCat) Modifier.focusRequester(headerFocus) else Modifier).onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) false
                            else if (ev.key == Key.DirectionDown) { runCatching { chanFocus.requestFocus() }; true }
                            else if (index == 0 && ev.key == Key.DirectionLeft) { onShowCats(true); true }
                            else if (index == headerItems.lastIndex && ev.key == Key.DirectionRight) { onClose(); true }
                            else false
                        },
                        aicon = if (name == FAV_CAT || name in CatFavs.list) AIcon.Star else if (hl != null) AIcon.Lists else null,
                        raw = hl != null
                    ) { onChanCat(name) }
                }
            }
            Text("OK = ansehen  ·  halten = Favorit  ·  Links = Kategorien", color = Muted, fontSize = 13.sp)
            LazyColumn(state = ls) {
                itemsIndexed(chanList) { i, it ->
                    ChannelRow(
                        it, it.id == curId,
                        if (i == startIdx || (startIdx < 0 && i == 0)) Modifier.focusRequester(chanFocus) else Modifier,
                        onFocus = { onFocusedCh(it) }
                    ) { onPick(it, chanList) }
                }
            }
        }
        // Programminfo: flache Leiste unten über dem Bild (statt großer Karte mitten im Video)
        Box(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 28.dp), contentAlignment = Alignment.BottomCenter) {
            if (fc != null) {
                val nowE = Epg.current(fe)
                val barShape = RoundedCornerShape(DS.RadiusL)
                Row(
                    Modifier.fillMaxWidth().clip(barShape).background(PT.PanelBg).border(1.dp, PT.PanelBorder, barShape).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(DS.RadiusS)).background(Color(0x24FFFFFF)), contentAlignment = Alignment.Center) {
                        val lg = fc.logo
                        if (lg != null) AsyncImage(lg, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                        else Text(raw = true, text = fc.title.take(2).uppercase(), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (nowE != null) {
                            val frac = ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(raw = true, text = fc.title, color = Muted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Text("${hhmm(nowE.start)}–${hhmm(nowE.end)}", color = Accent2, fontSize = 14.sp, fontWeight = FontWeight.Bold, style = TNUM)
                            }
                            Text(raw = true, text = nowE.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Box(Modifier.padding(top = 3.dp, bottom = 3.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(DS.Overlay3)) {
                                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent1))
                            }
                            if (nowE.desc.isNotBlank()) {
                                Text(raw = true, text = nowE.desc, color = Tok.TextMid, fontSize = 15.sp, lineHeight = 19.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text(raw = true, text = fc.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Kein Programm für diesen Sender", color = Muted, fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PCatRow(
    label: String, count: Int, selected: Boolean, fav: Boolean, modifier: Modifier,
    onFav: (() -> Unit)?, onFocus: () -> Unit, icon: AIcon? = null, raw: Boolean = false, onClick: () -> Unit
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    Row(
        modifier.fillMaxWidth().padding(vertical = 2.dp).clip(shape)
            .background(if (f) Color.White else if (selected) DS.Overlay2 else Color.Transparent)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .favKeys { onFav?.invoke() }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val ic = if (fav) AIcon.Star else icon
            if (ic != null) AppIcon(ic, if (f) PT.InkSoft else if (fav) PT.Gold else Color.White, size = 17.dp)
            Text(
                label, color = if (f) PT.Ink else Color.White, fontSize = 16.sp,
                fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1, overflow = TextOverflow.Ellipsis, raw = raw
            )
        }
        Text("$count", color = if (f) PT.InkSoft else Muted, fontSize = 13.sp, style = TNUM)
    }
}

@Composable
private fun ChannelRow(item: Item, current: Boolean, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusS)
    val epg by produceState(emptyList<EpgEntry>(), item.id) { value = Epg.now(item) }
    val nowE = Epg.current(epg)
    // Menü-Taste (falls vorhanden): Favorit und eigene Senderlisten. OK gedrückt halten = sofort Favorit (Wunsch des Nutzers).
    var menu by remember { mutableStateOf(false) }
    if (menu) Box { ChannelMenu(item, null, null) { menu = false } }
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp).clip(shape)
            .background(if (f) Color.White else if (current) DS.Overlay2 else Color.Transparent)
            .onFocusChanged { f = it.isFocused; if (it.isFocused) onFocus() }
            .menuKeys(onMenu = { menu = true }, onLong = {
                // Mit eigenen Senderlisten: Auswahl (Favoriten oder eine Liste); sonst sofort Favorit an/aus
                if (ChannelLists.lists.isNotEmpty()) { menu = true; return@menuKeys }
                val on = !Library.isFav(item.id)
                Library.toggleFav(ctx, item.id)
                android.widget.Toast.makeText(ctx, Lang.tr(if (on) "Zu Favoriten hinzugefügt" else "Aus Favoriten entfernt"), android.widget.Toast.LENGTH_SHORT).show()
            })
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(DS.RadiusXs)).background(if (f) Color(0x14000000) else DS.Overlay2), contentAlignment = Alignment.Center) {
            if (item.logo != null) AsyncImage(item.logo, null, Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                item.title, color = if (f) PT.Ink else Color.White, fontSize = 17.sp,
                fontWeight = if (current || f) FontWeight.ExtraBold else FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (nowE != null) {
                val frac = ((System.currentTimeMillis() / 1000 - nowE.start).toFloat() / (nowE.end - nowE.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                Text(
                    "${hhmm(nowE.start)}  ${nowE.title}", color = if (f) PT.InkSoft else Accent2, fontSize = 13.sp,
                    style = TNUM, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).background(if (f) Color(0x26000000) else DS.Overlay3)) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Accent1))
                }
            } else Text(raw = true, text = item.group, color = if (f) PT.InkSoft else Muted, fontSize = 13.sp, maxLines = 1)
        }
        if (Catchup.version >= 0 && Catchup.has(item)) AppIcon(AIcon.Catchup, if (f) PT.InkSoft else Accent2, size = 18.dp)
        if (DeadLinks.isDead(item.url)) AppIcon(AIcon.Warning, if (f) PT.InkSoft else PT.Gold, size = 18.dp)
        if (Library.isFav(item.id)) AppIcon(AIcon.Star, if (f) PT.InkSoft else PT.Gold, size = 18.dp)
    }
}

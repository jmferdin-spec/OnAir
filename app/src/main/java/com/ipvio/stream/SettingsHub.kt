package com.ipvio.stream

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

private class Cat(val icon: AIcon, val title: String, val sub: String)

private val CATS = listOf(
    Cat(AIcon.Gear, "Allgemein", "Start, Beenden, Autostart"),
    Cat(AIcon.Globe, "Sprache & Region", "Sprache, Uhrzeit, Zeitzone"),
    Cat(AIcon.Grid, "Ansicht", "Kartengröße, Raster"),
    Cat(AIcon.Sparkle, "Design", "Farben, Schrift, Animationen"),
    Cat(AIcon.Lists, "Menü", "Reihenfolge und Einträge"),
    Cat(AIcon.Play, "Player", "Spulen, Intro, Qualität, defekte Links"),
    Cat(AIcon.Music, "Ton & Untertitel", "Sprachen, CC, Untertitel-Stil"),
    Cat(AIcon.Tag, "Genres & Kategorien", "Automatische Genres, Kategorien"),
    Cat(AIcon.Live, "Live-TV & Guide", "TV-Programm, Senderlisten, Erinnerungen"),
    Cat(AIcon.Download, "Playlists", "Verwalten, Sprachen, Aktualisieren"),
    Cat(AIcon.Person, "Profile", "Verlauf, Favoriten, Jugendschutz je Person"),
    Cat(AIcon.Star, "API-Schlüssel", "TMDB, IMDb und Rotten Tomatoes"),
    Cat(AIcon.Lock, "Jugendschutz", "PIN, Inhalte ab 18"),
    Cat(AIcon.Storage, "Speicher", "Cache, Verlauf, Fortsetzen"),
    Cat(AIcon.Pulse, "Diagnose", "Status und Fehlersuche"),
    Cat(AIcon.Info, "Über IPVIO Stream", "Version, Quellen, Zurücksetzen")
)

/** Direkt eine Seite der Einstellungen öffnen (z. B. Profil-Kreis oben bei nur einem Profil: Seite „Profile“). */
object SettingsNav {
    const val PROFILES = 10
    var openPage by androidx.compose.runtime.mutableIntStateOf(-1)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsHub(onAdd: () -> Unit, reload: () -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(-1) }
    var last by remember { mutableIntStateOf(0) }
    LaunchedEffect(SettingsNav.openPage) {
        val p = SettingsNav.openPage
        if (p >= 0) { SettingsNav.openPage = -1; last = p; page = p }
    }
    val cardFocus = remember { List(CATS.size) { if (it == 0) ContentFocus.req else FocusRequester() } }
    val backFocus = remember { FocusRequester() }
    BackHandler(enabled = page >= 0) { page = -1 }
    LaunchedEffect(page) {
        runCatching { if (page < 0) cardFocus[last].requestFocus() else backFocus.requestFocus() }
    }

    // Kein eigenes verticalScroll: Der Einstellungs-Tab liegt in einer LazyColumn, die selbst scrollt.
    Column(Modifier.padding(horizontal = DS.PageX, vertical = dpx(20)), verticalArrangement = Arrangement.spacedBy(dpx(18))) {
        if (page < 0) {
            // Runde 15: im Stil der Startseite (Kacheln mit eigener Farbe je Bereich, ruhige Schrift, weißer Fokus)
            Column(verticalArrangement = Arrangement.spacedBy(dpx(4))) {
                Text("Einstellungen", color = Color.White, fontSize = spx(38), fontWeight = FontWeight.ExtraBold)
                Text("Alles rund um Aussehen, Wiedergabe und Konto", color = Tok.TextLow, fontSize = spx(16))
            }
            CATS.chunked(3).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(dpx(16))) {
                    row.forEachIndexed { c, cat ->
                        val idx = r * 3 + c
                        SettingsCard(cat, idx, Modifier.weight(1f).focusRequester(cardFocus[idx])) { last = idx; page = idx }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(dpx(18))) {
                SettingsBack(Modifier.focusRequester(backFocus)) { page = -1 }
                Text(CATS[page].title, color = Color.White, fontSize = spx(36), fontWeight = FontWeight.ExtraBold)
            }
            // Unter dem letzten anwählbaren Element stehen oft noch Hinweise: Runter am Ende holt sie ins Bild
            val fm = LocalFocusManager.current
            val endReq = remember { BringIntoViewRequester() }
            val endScope = rememberCoroutineScope()
            Column(
                Modifier.onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown) {
                        if (!fm.moveFocus(FocusDirection.Down)) endScope.launch { runCatching { endReq.bringIntoView() } }
                        true
                    } else false
                }.fillMaxWidth().clip(RoundedCornerShape(dpx(18))).background(Color(0x0DFFFFFF))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(dpx(18))).padding(dpx(26)),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            when (page) {
                0 -> GeneralPage(ctx)
                1 -> RegionPage(ctx)
                2 -> ViewPage(ctx)
                3 -> DesignPage(ctx)
                4 -> MenuPage(ctx)
                5 -> PlayerPage(ctx)
                6 -> AudioPage(ctx)
                7 -> FilterPage(ctx) { page = 9 }
                8 -> LivePage(ctx)
                9 -> PlaylistPage(ctx, onAdd, reload)
                10 -> ProfilesPage(ctx) { page = -1 }
                11 -> ApiKeysPage(ctx)
                12 -> ParentalPage(ctx) { page = -1 }
                13 -> StoragePage(ctx)
                14 -> DiagPage(ctx, reload)
                else -> AboutPage(ctx)
            }
            Spacer(Modifier.height(8.dp).bringIntoViewRequester(endReq))
            }
        }
    }
}

/** Farbe je Bereich: gleicher Aufbau wie die Kacheln der Startseite (Farbverlauf, Symbol in der Bereichsfarbe, Pfeil). */
private fun catColors(i: Int): Triple<Color, Color, Color> {
    val h = (i * 360f / CATS.size + 18f) % 360f
    return Triple(Color.hsv(h, 0.62f, 0.34f), Color.hsv(h, 0.70f, 0.14f), Color.hsv(h, 0.45f, 1f))
}

@Composable
private fun SettingsCard(cat: Cat, idx: Int, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.04f) else 1f, label = "sc")
    val shape = RoundedCornerShape(dpx(14))
    val (c1, c2, ink) = catColors(idx)
    Row(
        modifier.height(dpx(96)).graphicsLayer { scaleX = s; scaleY = s }.clip(shape)
            .background(Brush.horizontalGradient(listOf(c1, c2)))
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else ink.copy(alpha = .22f), shape)
            .onFocusChanged { f = it.isFocused }
            .trackFocus()
            .clickable(onClick = onClick)
            .padding(horizontal = dpx(20)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dpx(16))
    ) {
        Box(Modifier.size(dpx(48)).clip(CircleShape).background(ink.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            AppIcon(cat.icon, ink, size = dpx(28))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(dpx(3))) {
            Text(cat.title, color = Color.White, fontSize = spx(20), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cat.sub, color = Tok.TextMid, fontSize = spx(13), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AppIcon(AIcon.Right, Color.White, size = dpx(18))
    }
}

/** Zurück-Knopf der Unterseiten: runder Knopf wie in der Kopfzeile. */
@Composable
private fun SettingsBack(modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Box(
        modifier.size(dpx(52)).clip(CircleShape)
            .background(if (f) DS.FocusFill else Tok.Glass)
            .border(1.dp, if (f) DS.FocusFill else Color(0x26FFFFFF), CircleShape)
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { AppIcon(AIcon.Left, if (f) DS.FocusInk else Color.White, size = dpx(24)) }
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = Muted, fontSize = DS.TextSmall, lineHeight = 20.sp, modifier = Modifier.widthIn(max = 680.dp))
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, color = Color.White, fontSize = spx(22), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
}

/** Auswahl-Chip mit optionalen Farbpunkten. Der gewählte Eintrag trägt einen Haken und einen hellen Rand. */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, dots: List<Color> = emptyList(), modifier: Modifier = Modifier, onFocus: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.05f) else 1f, label = "chip")
    val shape = RoundedCornerShape(50)
    // Fokus wie alle Knöpfe: weiße Fläche, dunkle Schrift. Ausgewählt (ohne Fokus): hellere Fläche, heller Rand und Haken.
    Row(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.height(46.dp).clip(shape)
            .background(if (f) DS.FocusFill else if (selected) DS.Overlay3 else Surf)
            .border(DS.Border, if (f) DS.FocusFill else if (selected) Color(0x99FFFFFF) else DS.Hairline, shape)
            .onFocusChanged { if (f != it.isFocused) { f = it.isFocused; onFocus?.invoke(it.isFocused) } }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        dots.forEach { Box(Modifier.size(18.dp).clip(CircleShape).background(it).border(1.dp, if (f) DS.FocusInk.copy(alpha = .3f) else DS.Overlay3, CircleShape)) }
        Text(label, color = if (f) DS.FocusInk else Color.White, fontSize = DS.TextBody, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        if (selected) AppIcon(AIcon.Check, if (f) DS.FocusInk else Accent2, size = 18.dp)
    }
}

/** Rundes Farbfeld für die eigene Farbe. */
@Composable
private fun Swatch(rgb: Int, selected: Boolean, onFocus: ((Boolean) -> Unit)? = null, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) focusScale(1.18f) else 1f, label = "sw")
    val col = Color(0xFF000000.toInt() or rgb)
    Box(
        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }.size(44.dp).clip(CircleShape).background(col)
            .border(if (f) 3.dp else 1.dp, if (f) Color.White else Color(0x44FFFFFF), CircleShape)
            .onFocusChanged { if (f != it.isFocused) { f = it.isFocused; onFocus?.invoke(it.isFocused) } }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) AppIcon(AIcon.Check, if (ThemeMath.luminance(rgb) > 0.45) Color.Black else Color.White, size = 22.dp)
    }
}

/** Schalter-Zeile wie in Standard-Apps: Titel, Erklärung und ein An/Aus-Schalter. OK schaltet um. */
@Composable
internal fun ToggleRow(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Row(
        Modifier.fillMaxWidth().widthIn(max = 760.dp).clip(shape)
            .background(if (f) RowFocus else Surf)
            .border(DS.Border, if (f) Accent2 else DS.Hairline, shape)
            .onFocusChanged { f = it.isFocused }
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            if (desc != null) Text(desc, color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Box(
            Modifier.width(54.dp).height(30.dp).clip(RoundedCornerShape(50))
                .background(if (checked) Accent1 else Color(0x44FFFFFF)).padding(3.dp)
        ) {
            Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(24.dp).clip(CircleShape).background(Color.White))
        }
        Text(if (checked) "An" else "Aus", color = if (checked) Accent2 else Muted, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(30.dp))
    }
}

/** Gefährliche Aktion mit Rückfrage: erster Druck fragt nach, zweiter führt aus (nach 4 s verfällt die Rückfrage). */
@Composable
internal fun ConfirmPill(label: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4000); armed = false } }
    PillButton(if (armed) "Wirklich? OK zum Bestätigen" else label, light = armed) {
        if (armed) { armed = false; onConfirm() } else armed = true
    }
}

private fun appVersion(ctx: Context): String =
    runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"

/** Auswahl-Zeile im selben Stil wie ToggleRow: Titel links, aktueller Wert rechts, OK öffnet die Liste. */
@Composable
internal fun SelectRow(label: String, value: String, options: List<String>, selected: Int, active: Boolean = false, onPick: (Int) -> Unit) {
    var f by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(DS.RadiusM)
    Box {
        Row(
            Modifier.fillMaxWidth().widthIn(max = 760.dp).clip(shape)
                .background(if (f) RowFocus else Surf)
                .border(DS.Border, if (f) Accent2 else DS.Hairline, shape)
                .onFocusChanged { f = it.isFocused }
                .clickable { open = true }
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(value, color = if (active) Accent2 else DS.TextSoft, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp))
            AppIcon(AIcon.Caret, Muted, size = 18.dp)
        }
        if (open) OptionPopup(options, selected, { open = false }) { onPick(it); open = false }
    }
}

/** Ziffernblock für die PIN: Pfeiltasten + OK oder direkt die Zifferntasten der Fernbedienung. */
@Composable
internal fun PinPad(title: String, hint: String?, error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    val start = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { start.requestFocus() } }
    LaunchedEffect(pin) { if (pin.length == 4) { val p = pin; pin = ""; onSubmit(p) } }
    BackHandler { onCancel() }
    fun add(d: Char) { if (pin.length < 4) pin += d }
    Column(
        Modifier.onPreviewKeyEvent { e ->
            val ne = e.nativeKeyEvent
            if (ne.action != android.view.KeyEvent.ACTION_DOWN) false
            else {
                val kc = ne.keyCode
                val d = when {
                    kc in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> '0' + (kc - android.view.KeyEvent.KEYCODE_0)
                    kc in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (kc - android.view.KeyEvent.KEYCODE_NUMPAD_0)
                    else -> null
                }
                if (d != null) { add(d); true } else false
            }
        },
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (hint != null) Hint(hint)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            repeat(4) { i ->
                Box(Modifier.size(22.dp).clip(CircleShape).background(if (i < pin.length) Accent2 else DS.Overlay3))
            }
            if (error != null) Text(error, color = DS.Danger, fontSize = 15.sp)
        }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "DEL", "0", "ESC")
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    val ic = when (k) { "DEL" -> AIcon.Backspace; "ESC" -> AIcon.Close; else -> null }
                    SmallPill(if (ic != null) "" else k, Modifier.width(78.dp).then(if (k == "5") Modifier.focusRequester(start) else Modifier), icon = ic) {
                        when (k) {
                            "DEL" -> pin = pin.dropLast(1)
                            "ESC" -> onCancel()
                            else -> add(k[0])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RegionPage(ctx: Context) {
    val langOpts = listOf("Automatisch (Gerät)") + I18nCore.NATIVE
    val li = if (General.lang.isBlank()) 0 else I18nCore.CODES.indexOf(General.lang) + 1
    SectionTitle("Sprache")
    SelectRow("Sprache der App", if (li == 0) "Automatisch (Gerät)" else I18nCore.NATIVE[li - 1], langOpts, li) {
        General.setLang(ctx, if (it == 0) "" else I18nCore.CODES[it - 1])
    }
    Hint("Alle Menüs und Hinweise erscheinen in dieser Sprache. Beschreibungen, Besetzung und Titel-Infos kommen von TMDB und werden in derselben Sprache geladen, sobald du einen Titel öffnest. Namen von Sendern, Filmen und Kategorien stammen von deinem Anbieter und bleiben unverändert.")
    SectionTitle("Uhrzeit")
    val fmts = listOf("Automatisch (Gerät)", "24 Stunden (14:30)", "12 Stunden (2:30 PM)")
    SelectRow("Zeitformat", fmts[General.timeFmt], fmts, General.timeFmt) { General.setTimeFmt(ctx, it) }
    val zoneOpts = SettingsLogic.ZONES.map { if (it.id.isEmpty()) "Systemzeit (Gerät: " + java.util.TimeZone.getDefault().id + ")" else it.label }
    val zi = SettingsLogic.ZONES.indexOfFirst { it.id == General.tzId }.coerceAtLeast(0)
    SelectRow("Zeitzone", zoneOpts[zi], zoneOpts, zi) { General.setTz(ctx, SettingsLogic.ZONES[it].id) }
    Text("Uhrzeit jetzt: " + hhmm(System.currentTimeMillis() / 1000), color = Accent2, fontSize = 16.sp)
    Hint("Gilt für alle Uhrzeiten, zum Beispiel im TV-Guide und in der Live-TV-Infoleiste. „Systemzeit“ nutzt die Einstellung deines Geräts. Zeigt die App Uhrzeiten falsch an, steht dein Fernseher vermutlich auf UTC; wähle dann hier deine Zeitzone.")
}

@Composable
private fun ParentalPage(ctx: Context, onExit: () -> Unit) {
    // Schritte der PIN-Eingabe: 0 = keine, 1 = Entsperren, 2 = neue PIN, 3 = neue PIN bestätigen, 4 = alte PIN bei Entfernen
    var step by remember { mutableIntStateOf(if (Parental.pinSet && !Parental.unlocked) 1 else 0) }
    var first by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { Parental.unlocked = false } }
    // Zählen per Regex über alle Titel: im Hintergrund, nicht beim Zeichnen
    val src = PlaylistStore.channels
    val hideNow = Parental.hideAdult
    val hiddenCount by produceState(0, Parental.version, src, hideNow) {
        value = if (hideNow) 0 else withContext(Dispatchers.Default) { src.count { ParentalLogic.isAdult(it.group, it.title) } }
    }
    when (step) {
        1 -> PinPad("PIN eingeben", "Die Jugendschutz-Einstellungen sind mit einer PIN geschützt.", err,
            {
                if (Parental.verify(ctx, it)) { Parental.unlocked = true; err = null; step = 0 }
                else err = Parental.pinError()
            },
            { onExit() })
        2 -> PinPad("Neue PIN festlegen", "Wähle eine 4-stellige PIN.", err, { first = it; err = null; step = 3 }, { step = 0 })
        3 -> PinPad("PIN bestätigen", "Gib die PIN noch einmal ein.", err,
            { if (it == first) { Parental.setPin(ctx, it); Parental.unlocked = true; err = null; step = 0 } else { err = "Die PINs stimmen nicht überein"; step = 2 } },
            { step = 0 })
        else -> {
            ToggleRow(
                "Inhalte für Erwachsene ausblenden",
                "Blendet Kategorien und Titel mit Begriffen wie XXX, Adult oder 18+ in der ganzen App aus: Startseite, Suche, Listen und Live-TV.",
                Parental.hideAdult
            ) { Parental.setHide(ctx, it) }
            if (Parental.hideAdult) Text("Der Schutz ist aktiv.", color = Accent2, fontSize = 15.sp)
            else Text(if (hiddenCount > 0) "$hiddenCount Einträge in deiner Playlist würden ausgeblendet." else "Kein Erwachsenen-Inhalt erkannt.", color = Muted, fontSize = 15.sp)
            SectionTitle("PIN")
            Text(if (Parental.pinSet) "PIN ist gesetzt" else "Keine PIN gesetzt", color = if (Parental.pinSet) Accent2 else Muted, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton(if (Parental.pinSet) "PIN ändern" else "PIN festlegen", light = !Parental.pinSet) { err = null; step = 2 }
                if (Parental.pinSet) ConfirmPill("PIN entfernen") { Parental.clearPin(ctx) }
            }
            Hint("Mit einer PIN lässt sich der Schutz nur nach Eingabe der PIN ändern. Die Erkennung arbeitet mit Stichwörtern in Kategorie- und Titelnamen und ist kein vollständiger Filter. Beim Zurücksetzen aller Einstellungen bleibt die PIN erhalten.")
        }
    }
}

@Composable
private fun GeneralPage(ctx: Context) {
    var tick by remember { mutableIntStateOf(0) }
    SectionTitle("Start")
    ToggleRow(
        "Beim Einschalten des Geräts öffnen",
        "IPVIO Stream startet automatisch, sobald der Fernseher oder die Box hochgefahren ist.",
        General.autoStart
    ) { on ->
        General.setAutoStart(ctx, on)
        if (on && !canStartFromBoot(ctx)) openOverlaySettings(ctx)
    }
    val granted = remember(tick, General.autoStart) { canStartFromBoot(ctx) }
    if (General.autoStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconLabel(
                if (granted) AIcon.Check else AIcon.Warning,
                if (granted) "Berechtigung erteilt" else "Berechtigung „Über anderen Apps einblenden“ fehlt",
                if (granted) Accent2 else Color(0xFFFFB454), 15.sp, iconSize = 16.dp, maxLines = 2
            )
            if (!granted) SmallPill("Einstellung öffnen") { openOverlaySettings(ctx) }
            SmallPill("Prüfen") { tick++ }
        }
        Hint("Ab Android 10 erlaubt das System den Autostart nur mit dieser Berechtigung. Manche Geräte (z. B. einzelne Fire-TV-Modelle) blockieren den Autostart trotzdem; das kann die App nicht ändern.")
    }
    val tabs = listOf("Start", "Filme", "Serien", "Live TV", "Suche")
    val shown = SettingsLogic.resolveStartTab(General.startTab, AppSettings.menuOrder.toList(), AppSettings.menuHidden.toList())
    SelectRow("Startseite", tabs[shown], tabs, shown) { General.setStartTab(ctx, it) }
    Hint("Diese Seite öffnet sich beim Start der App. Versteckte Menüpunkte sind nicht wählbar.")
    ToggleRow("Letzten Sender beim Start abspielen", "Wenn du zuletzt Live TV gesehen hast, läuft dieser Sender direkt nach dem Start.", General.resumeLive) { General.setResumeLive(ctx, it) }
    ToggleRow("Letzten Film / letzte Serie beim Start fortsetzen", "Wenn du zuletzt einen Film oder eine Serie gesehen hast, läuft sie nach dem Start an der gemerkten Stelle weiter (bei einer fertigen Folge mit der nächsten).", General.resumeVod) { General.setResumeVod(ctx, it) }

    SectionTitle("Bedienung")
    ToggleRow("Beenden bestätigen", "Auf der Startseite muss „Zurück“ zweimal gedrückt werden, damit die App nicht aus Versehen schließt.", General.confirmExit) { General.setConfirmExit(ctx, it) }
}

private fun canStartFromBoot(ctx: Context): Boolean =
    android.os.Build.VERSION.SDK_INT < 29 || android.provider.Settings.canDrawOverlays(ctx)

private fun openOverlaySettings(ctx: Context) {
    try {
        ctx.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        )
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, Lang.tr("Dieses Gerät bietet die Einstellung nicht an. Autostart funktioniert evtl. trotzdem."), android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun StoragePage(ctx: Context) {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    // Größen und Löschen laufen im Hintergrund: der Metadaten-Ordner kann zehntausende Dateien enthalten
    var sizes by remember { mutableStateOf(List(General.AREAS.size) { 0L }) }
    LaunchedEffect(tick) { sizes = withContext(Dispatchers.IO) { General.AREAS.map { General.areaSize(ctx, it) } } }
    SectionTitle("Zwischenspeicher")
    General.AREAS.forEachIndexed { i, a ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.width(420.dp)) {
                Text(a.name + "  ·  " + SettingsLogic.fmtBytes(sizes[i]), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(a.note, color = Muted, fontSize = 13.sp)
            }
            ConfirmPill("Leeren") { scope.launch { withContext(Dispatchers.IO) { General.clearArea(ctx, a) }; tick++ } }
        }
    }
    Text("Gesamt: " + SettingsLogic.fmtBytes(sizes.sum()), color = Accent2, fontSize = 15.sp)
    Hint("Geleerte Daten lädt die App bei Bedarf neu. Titel-Infos und Bilder erscheinen danach kurz später. Der Playlist-Zwischenspeicher macht den Start schnell; ohne ihn wartet die App auf den Download.")
    SectionTitle("Verlauf")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ConfirmPill("Suchverlauf löschen") { AppSettings.clearSearch(ctx) }
        ConfirmPill("Zuletzt geschaut löschen") { General.clearHistory(ctx) }
        ConfirmPill("Weiterschauen löschen") { General.clearResume(ctx) }
    }
    Hint("Meine Liste und Favoriten bleiben immer erhalten.")
    SectionTitle("Sicherung")
    var backupMsg by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Sicherung erstellen") {
            scope.launch {
                val f = withContext(Dispatchers.IO) { Backup.export(ctx) }
                backupMsg = if (f != null) "Gespeichert: " + f.absolutePath else "Sicherung fehlgeschlagen."
            }
        }
        ConfirmPill("Sicherung wiederherstellen") {
            scope.launch {
                val n = withContext(Dispatchers.IO) { Backup.restore(ctx) }
                if (n >= 0) Profiles.restart(ctx) else backupMsg = "Keine gültige Sicherung gefunden."
            }
        }
    }
    backupMsg?.let { Text(it, color = Accent2, fontSize = 14.sp, modifier = Modifier.widthIn(max = 680.dp)) }
    Hint("Gesichert werden Einstellungen, Favoriten, Verlauf, Weiterschauen und Profile. Playlists und Zugangsdaten sind nicht enthalten. Beim Wiederherstellen startet die App neu.")
}

@Composable
private fun ViewPage(ctx: Context) {
    CatPrefs.init(ctx); BrowsePrefs.init(ctx)
    SectionTitle("Filme, Serien und Sender")
    val views = listOf("Raster", "Kino")
    SelectRow("Standard-Ansicht", views[if (BrowsePrefs.detail) 1 else 0], views, if (BrowsePrefs.detail) 1 else 0) { BrowsePrefs.set(ctx, it == 1) }
    Hint("Raster zeigt viele Titel auf einmal. Kino zeigt oben die Details zum markierten Titel und unten eine Reihe zum Durchblättern.")
    StepRow("Spalten (Raster)", AppSettings.gridCols.toString(),
        { AppSettings.setGrid(ctx, AppSettings.gridCols - 1, AppSettings.gridRows) },
        { AppSettings.setGrid(ctx, AppSettings.gridCols + 1, AppSettings.gridRows) })
    StepRow("Zeilen (Raster)", AppSettings.gridRows.toString(),
        { AppSettings.setGrid(ctx, AppSettings.gridCols, AppSettings.gridRows - 1) },
        { AppSettings.setGrid(ctx, AppSettings.gridCols, AppSettings.gridRows + 1) })
    SectionTitle("Startseite")
    val heroOpts = listOf(0, 6, 9, 15)
    SelectRow("Banner wechselt", if (General.heroSec == 0) "nie" else "alle ${General.heroSec} s", heroOpts.map { if (it == 0) "nie" else "alle $it s" }, heroOpts.indexOf(General.heroSec).coerceAtLeast(0)) {
        General.setHeroSec(ctx, heroOpts[it])
    }
    StepRow("Kartengröße (Reihen)", AppSettings.cardW.toString(),
        { AppSettings.setCardW(ctx, AppSettings.cardW - 10) },
        { AppSettings.setCardW(ctx, AppSettings.cardW + 10) })
    SectionTitle("Kategorien-Ansicht")
    val cv = listOf("Liste mit Vorschau", "Kacheln")
    SelectRow("Ansicht", cv[if (CatPrefs.listView) 0 else 1], cv, if (CatPrefs.listView) 0 else 1) { CatPrefs.set(ctx, it == 0) }
    Hint("Das Raster kannst du zusätzlich direkt in der Rasteransicht mit dem Raster-Knopf ändern.")
}

@Composable
private fun MenuPage(ctx: Context) {
    Hint("Verschiebe Einträge mit den Pfeil-Knöpfen oder blende sie aus. Start und Einstellungen bleiben immer sichtbar.")
    AppSettings.menuOrder.forEach { id ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(MENU_LABELS[id], color = if (id in AppSettings.menuHidden) Muted else Color.White,
                fontSize = 17.sp, modifier = Modifier.width(190.dp))
            SmallPill("", icon = AIcon.Up) { AppSettings.moveMenu(ctx, id, -1) }
            SmallPill("", icon = AIcon.Down) { AppSettings.moveMenu(ctx, id, 1) }
            if (id != 0 && id != 5) { val hidden = id in AppSettings.menuHidden; SmallPill(if (hidden) "versteckt" else "sichtbar", icon = if (hidden) AIcon.EyeOff else AIcon.Eye) { AppSettings.toggleMenu(ctx, id) } }
        }
    }
    PillButton("Standard-Reihenfolge wiederherstellen") { AppSettings.setMenuDefault(ctx) }
}

@Composable
private fun PlayerPage(ctx: Context) {
    val steps = listOf(5, 10, 15, 30, 60)
    SelectRow("Sprung links/rechts", "${AppSettings.seekStep} s", steps.map { "$it s" }, steps.indexOf(AppSettings.seekStep).coerceAtLeast(0)) {
        AppSettings.setPlayer(ctx, AppSettings.skipIntro, AppSettings.showInfo, steps[it])
    }
    val asp = General.ASPECT_NAMES
    SelectRow("Standard-Bildformat", asp[General.defaultAspect], asp, General.defaultAspect) { General.setAspect(ctx, it) }
    ToggleRow("Intro überspringen anbieten", "Zeigt bei Serien einen Knopf „Intro überspringen“, wenn Intro-Zeiten bekannt sind.", AppSettings.skipIntro) {
        AppSettings.setPlayer(ctx, it, AppSettings.showInfo, AppSettings.seekStep)
    }
    ToggleRow("Stream-Info am Start", "Zeigt kurz Auflösung, Ton und Format beim Abspielen von Filmen und Serien.", AppSettings.showInfo) {
        AppSettings.setPlayer(ctx, AppSettings.skipIntro, it, AppSettings.seekStep)
    }
    val hides = listOf(3, 4, 5, 6, 8, 10, 15)
    SelectRow("Steuerleiste ausblenden nach", "${AppSettings.hideSec} s", hides.map { "$it s" }, hides.indexOf(AppSettings.hideSec).coerceAtLeast(0)) {
        AppSettings.setPlayerExtra(ctx, AppSettings.autoNext, hides[it], AppSettings.showEndTime, AppSettings.autoSkipIntro)
    }
    ToggleRow("Nächste Folge automatisch", "Startet die nächste Folge nach dem Abspann von selbst.", AppSettings.autoNext) {
        AppSettings.setPlayerExtra(ctx, it, AppSettings.hideSec, AppSettings.showEndTime, AppSettings.autoSkipIntro)
    }
    ToggleRow("Abspann automatisch überspringen", "Startet die nächste Folge, sobald der Abspann beginnt. Geht nur, wenn für die Serie Abspann-Zeiten bekannt sind (TheIntroDB); sonst bleibt es beim Hinweis in den letzten 30 Sekunden.", AppSettings.autoSkipOutro) {
        AppSettings.setAutoOutro(ctx, it)
    }
    ToggleRow("Endzeit anzeigen", "Zeigt, um wie viel Uhr der Film voraussichtlich endet.", AppSettings.showEndTime) {
        AppSettings.setPlayerExtra(ctx, AppSettings.autoNext, AppSettings.hideSec, it, AppSettings.autoSkipIntro)
    }
    ToggleRow("Intro automatisch überspringen", "Überspringt bekannte Intros ohne Rückfrage.", AppSettings.autoSkipIntro) {
        AppSettings.setPlayerExtra(ctx, AppSettings.autoNext, AppSettings.hideSec, AppSettings.showEndTime, it)
    }
    ToggleRow("Uhrzeit im Bild", "Zeigt die aktuelle Uhrzeit beim Abspielen in einer Ecke (nicht, solange die Steuerleiste offen ist).", AppSettings.clockOn) {
        AppSettings.setClock(ctx, it, AppSettings.clockPos, AppSettings.clockOpacity, AppSettings.clockSize)
    }
    if (AppSettings.clockOn) {
        val poss = listOf("Oben rechts", "Oben links", "Unten rechts", "Unten links")
        SelectRow("Uhrzeit: Ort", poss[AppSettings.clockPos], poss, AppSettings.clockPos) {
            AppSettings.setClock(ctx, true, it, AppSettings.clockOpacity, AppSettings.clockSize)
        }
        val ops = listOf(20, 30, 40, 50, 60, 70, 80, 90, 100)
        SelectRow("Uhrzeit: Schrift-Deckkraft", "${AppSettings.clockOpacity} %", ops.map { "$it %" }, ops.indexOf(AppSettings.clockOpacity).let { if (it < 0) 5 else it }) {
            AppSettings.setClock(ctx, true, AppSettings.clockPos, ops[it], AppSettings.clockSize)
        }
        val sizes = listOf("Klein", "Mittel", "Groß")
        SelectRow("Uhrzeit: Größe", sizes[AppSettings.clockSize], sizes, AppSettings.clockSize) {
            AppSettings.setClock(ctx, true, AppSettings.clockPos, AppSettings.clockOpacity, it)
        }
        ToggleRow("Uhrzeit: Hintergrund", "Dunkler Kasten hinter der Uhr. Aus = nur die Zahlen.", AppSettings.clockBg) {
            AppSettings.setClockBg(ctx, it, AppSettings.clockBgOpacity)
        }
        if (AppSettings.clockBg) {
            val bops = listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
            SelectRow("Uhrzeit: Hintergrund-Deckkraft", "${AppSettings.clockBgOpacity} %", bops.map { "$it %" }, bops.indexOf(AppSettings.clockBgOpacity).let { if (it < 0) 3 else it }) {
                AppSettings.setClockBg(ctx, true, bops[it])
            }
        }
        Text("Vorschau", color = Muted, fontSize = 14.sp)
        ClockSample()
        Hint("Die Uhr folgt der Zeitzone und dem 12/24-Stunden-Format aus den Einstellungen. Im Player geht es auch über Einstellungen > Uhrzeit.")
    }
    val bufNames = listOf("Automatisch", "Normal", "Groß", "Sehr groß")
    SelectRow("Puffer (bei langsamem Internet)", bufNames[AppSettings.bufferMode], bufNames, AppSettings.bufferMode) { AppSettings.setBufferMode(ctx, it) }
    Hint("Ein größerer Puffer lädt mehr Film im Voraus und verhindert Ruckeln bei schwacher Leitung; dafür dauert der Start etwas länger und es wird mehr Speicher gebraucht. „Automatisch“ beginnt normal und vergrößert den Puffer von selbst, wenn es mehrfach ruckelt (aktuell Stufe ${AppSettings.bufferLearned}). Gilt ab dem nächsten gestarteten Titel.")
    ToggleRow("Bildrate anpassen", "Passt die Bildwiederholrate des Fernsehers an den Film an (z. B. 24 Bilder), wenn der Fernseher das ohne Bildaussetzer kann. Bei Fire TV zusätzlich in den Geräteeinstellungen „Originale Bildrate anpassen“ prüfen.", AppSettings.autoFps) {
        AppSettings.setAutoFps(ctx, it)
    }
    Hint("Intro-Zeiten stammen aus der Community-Datenbank TheIntroDB und gibt es nicht für jede Serie. Die Stream-Info erscheint nur bei Filmen und Serien.")
    SourceSettingsSection(ctx)
    DeadLinksSection(ctx)
}

@Composable
private fun DesignPage(ctx: Context) {
    fun save(accent: Int = Look.accentIdx, custom: Int = Look.custom, bg: Int = Look.bgIdx, motion: Int = Look.motion, scale: Int = Look.uiScale, custom2: Int = Look.custom2) =
        AppSettings.setLook(ctx, accent, custom, bg, motion, scale, custom2)
    fun col(rgb: Int) = Color(0xFF000000.toInt() or rgb)
    // Vorschau oben rechts einblenden (steht fest, scrollt nicht mit); markierte Farben nur dort zeigen
    DisposableEffect(Unit) {
        DesignPreview.clear(); DesignPreview.visible = true
        onDispose { DesignPreview.visible = false; DesignPreview.clear() }
    }
    // Beim Weiterwandern kommt „Fokus weg“ evtl. erst nach „Fokus da“ des nächsten Eintrags: nur die eigene Vorschau löschen
    fun hover(on: Boolean, label: String, set: () -> Unit) {
        if (on) { DesignPreview.clear(); set(); DesignPreview.label = label }
        else if (DesignPreview.label == label) DesignPreview.clear()
    }

    // Rechts bleibt Platz für die Vorschau
    Column(Modifier.padding(end = DesignPreview.WIDTH + 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
    SectionTitle("Farbthema")
    val ids = Look.ACCENTS.indices.toList() + Look.CUSTOM
    ids.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { i ->
                if (i == Look.CUSTOM) {
                    val c2 = if (Look.custom2 >= 0) Look.custom2 else Look.autoHighRgb()
                    ChoiceChip("Eigene Farben", Look.accentIdx == i, listOf(col(Look.custom), col(c2)),
                        onFocus = { hover(it, Lang.tr("Eigene Farben")) { DesignPreview.accent = i } }) { save(accent = i) }
                } else {
                    val p = Look.ACCENTS[i]
                    ChoiceChip(p.name, Look.accentIdx == i, listOf(col(p.fill), col(p.highlight)),
                        onFocus = { hover(it, Lang.tr(p.name)) { DesignPreview.accent = i } }) { save(accent = i) }
                }
            }
        }
    }

    if (Look.accentIdx == Look.CUSTOM) {
        SectionTitle("Eigene Farben")
        // Beide Farben frei wählbar: erst die Farbe oben auswählen, dann Farbfeld oder Hex-Code
        var slot by remember { mutableIntStateOf(0) }
        val c2 = if (Look.custom2 >= 0) Look.custom2 else Look.autoHighRgb()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceChip("Farbe 1 · Flächen", slot == 0, listOf(col(Look.custom))) { slot = 0 }
            ChoiceChip(if (Look.custom2 >= 0) "Farbe 2 · Hervorhebung" else "Farbe 2 · automatisch", slot == 1, listOf(col(c2))) { slot = 1 }
        }
        val current = if (slot == 0) Look.custom else c2
        fun pick(rgb: Int) { if (slot == 0) save(custom = rgb) else save(custom2 = rgb) }
        Look.SWATCHES.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    Swatch(c, c == current, onFocus = { on ->
                        hover(on, ThemeMath.hexOf(c)) { if (slot == 0) DesignPreview.custom = c else DesignPreview.custom2 = c }
                    }) { pick(c) }
                }
            }
        }
        var hex by remember(slot, current) { mutableStateOf(ThemeMath.hexOf(current)) }
        val parsed = ThemeMath.parseHex(hex)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(240.dp)) { Field(hex, { hex = it.take(7) }, "#RRGGBB") }
            PillButton(if (parsed != null) "Übernehmen" else "Ungültig", light = parsed != null) { parsed?.let { pick(it) } }
            if (slot == 1 && Look.custom2 >= 0) PillButton("Automatisch") { save(custom2 = -1) }
        }
        if (Look.custom2 >= 0 && ThemeMath.contrast(Look.custom2, Look.BGS[Look.bgIdx.coerceIn(0, Look.BGS.lastIndex)].bg) < 3.0) {
            Text("Farbe 2 ist auf diesem Hintergrund schwer lesbar.", color = Color(0xFFFFB454), fontSize = 15.sp)
        }
        Hint("Farbe 1 färbt Knöpfe, Balken und markierte Flächen – die Schrift darauf wird automatisch hell oder dunkel, damit sie lesbar bleibt. Farbe 2 färbt Hervorhebungen, Rahmen und farbige Schrift. „Automatisch“ berechnet Farbe 2 passend zu Farbe 1.")
    }

    SectionTitle("Hintergrund")
    Look.BGS.indices.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { i ->
                val b = Look.BGS[i]
                ChoiceChip(b.name, Look.bgIdx == i, listOf(Color(0xFF000000.toInt() or b.surf2)),
                    onFocus = { hover(it, Lang.tr(b.name)) { DesignPreview.bg = i } }) { save(bg = i) }
            }
        }
    }

    // Runde 14: Auswahl als Felder (wie die Farben) – markieren zeigt die Wirkung in der Vorschau, OK übernimmt
    SectionTitle("Animationen")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Look.MOTION.forEachIndexed { i, n ->
            ChoiceChip(n, Look.motion == i, onFocus = { hover(it, Lang.tr(n)) { DesignPreview.motion = i } }) { save(motion = i) }
        }
    }
    SectionTitle("Anzeigegröße")
    Look.SCALES.indices.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { i ->
                val n = Look.SCALES[i]
                ChoiceChip(n, Look.uiScale == i, onFocus = { hover(it, Lang.tr(n)) { DesignPreview.scale = i } }) { save(scale = i) }
            }
        }
    }
    val cfg = LocalConfiguration.current
    val factor = ThemeMath.uiFactor(Look.uiScale, cfg.screenWidthDp)
    Hint("Oberfläche aktuell ×" + String.format(java.util.Locale.US, "%.2f", factor) + " (Bildschirm " + cfg.screenWidthDp + " dp breit).")
    Hint("Standard ist 85 %: mehr Inhalt, Elemente bleiben gut lesbar. „Automatisch“ vergrößert die App auf sehr breiten Bildschirmen.")
    Hint("Animationen „Aus“ schaltet die Vergrößerung beim Fokus ab, „Dezent“ macht sie schwächer. Das schont schwache Geräte.")

    SectionTitle("Schriftart")
    General.FONTS.indices.chunked(3).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { i ->
                val n = General.FONTS[i]
                ChoiceChip(n, General.fontIdx == i, onFocus = { hover(it, Lang.tr(n)) { DesignPreview.font = i } }) { General.setFont(ctx, i) }
            }
        }
    }
    Hint("Outfit (Standard) und Inter sind in die App eingebaut, die übrigen Schriften stammen von deinem Gerät.")
    SectionTitle("Schriftgröße")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        General.TEXT_SCALES.forEachIndexed { i, n ->
            ChoiceChip(n, General.textScaleIdx == i, onFocus = { hover(it, Lang.tr(n)) { DesignPreview.textScale = i } }) { General.setTextScale(ctx, i) }
        }
    }
    Hint("Fünf klare Systemschriften, die auf jedem Gerät vorhanden sind. Die Schriftgröße wirkt zusätzlich zur Anzeigegröße.")
    PillButton("Design zurücksetzen") { Look.reset(); save() }
    }
}

@Composable
private fun DiagPage(ctx: Context, reload: () -> Unit) {
    val cfg = LocalConfiguration.current
    val items = PlaylistStore.channels
    val counts = remember(items) { Triple(items.count { it.kind == Kind.LIVE }, items.count { it.kind == Kind.MOVIE }, items.count { it.kind == Kind.SERIES }) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val dens = ctx.resources.displayMetrics.density
    val err = Diag.lastError?.let { it + "  (" + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(Diag.lastErrorAt)) + ")" } ?: "keiner seit dem Start"
    val lines = listOf(
        "Version" to version,
        "Installiert" to runCatching { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime)) }.getOrDefault("?"),
        "Sprache" to (Lang.code() + " · Index " + Lang.index),
        "Bildschirm" to "${cfg.screenWidthDp} × ${cfg.screenHeightDp} dp · Dichte ${"%.2f".format(java.util.Locale.US, dens)}",
        "Playlist" to (PlaylistStore.lists.getOrNull(PlaylistStore.active)?.name ?: "keine"),
        "Inhalte" to "${counts.first} Sender · ${counts.second} Filme · ${counts.third} Serien",
        "Ladezustand" to (if (PlaylistStore.loading) "lädt …" else PlaylistStore.error ?: "bereit"),
        "Programm (EPG)" to "${Xmltv.status} · ${Xmltv.channelCount} Sender",
        "Metadaten" to (if (MetaRepo.enabled()) (if (Classifier.running) "lädt ${Classifier.progress}/${Classifier.total}" else "aktiv") else "kein TMDB-Schlüssel"),
        "Speicher" to (Mem.totalMb(ctx).toString() + " MB · " + listOf("wenig", "mittel", "viel")[Mem.tier(ctx)]),
        "Letzter Fehler" to err,
        "Fehler seit Start" to Diag.errorCount.toString(),
        "Bildschirm wach" to (if (ScreenAwake.isOn) Lang.tr("an") + " (" + ScreenAwake.reasons + ")" else Lang.tr("aus")) +
            ScreenAwake.history.joinToString("") {
                "\n" + DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(it.first)) + "  " +
                    (if (it.second == "aus") Lang.tr("aus") else Lang.tr("an") + it.second.removePrefix("an"))
            },
        "Weiterschauen" to (ResumeLog.also { it.init(ctx) }.lines.take(12).joinToString("\n").ifBlank { "noch nichts gemerkt" }),
        "Letzter Absturz" to (CrashLog.last(ctx)?.let { it.first + "  (" + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.second)) + ")" } ?: "keiner")
    )
    lines.forEach { (k, v) ->
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(k, color = Muted, fontSize = 16.sp, modifier = Modifier.width(170.dp))
            Text(v, color = Color.White, fontSize = 16.sp, modifier = Modifier.widthIn(max = 640.dp))
        }
    }
    // Hinweis vor den Knöpfen: Text unter dem letzten Knopf scrollt die Liste nie ins Bild (nichts darunter ist anwählbar)
    Hint("Der Bericht enthält keine Adressen, Benutzernamen oder Passwörter. Er wird nur in die Zwischenablage kopiert und nirgendwohin gesendet.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Playlist neu laden", light = true, icon = AIcon.Refresh) { reload() }
        PillButton("Bericht kopieren") {
            val text = lines.joinToString("\n") { it.first + ": " + it.second }
            runCatching {
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("IPVIO Diagnose", text))
            }
        }
    }
}

@Composable
private fun AudioPage(ctx: Context) {
    TrackSettingsSection(ctx)
    // Runde 14: Untertitel-Stil mit Live-Vorschau. Markieren zeigt die Wirkung sofort im Bild oben, OK übernimmt.
    Text("Untertitel-Stil", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    var pSize by remember { mutableStateOf<Int?>(null) }
    var pColor by remember { mutableStateOf<Int?>(null) }
    var pBg by remember { mutableStateOf<Int?>(null) }
    var pEdge by remember { mutableStateOf<Int?>(null) }
    SubtitlePreview(pSize ?: AppSettings.subSize, pColor ?: AppSettings.subColor, pBg ?: AppSettings.subBg, pEdge ?: AppSettings.subEdge)
    fun setSub(size: Int = AppSettings.subSize, color: Int = AppSettings.subColor, bg: Int = AppSettings.subBg, edge: Int = AppSettings.subEdge) =
        AppSettings.setSub(ctx, size, color, bg, edge)
    SubChoiceRow("Größe", SubStyle.SIZES.map { it.first }, AppSettings.subSize, { i -> pSize = i }) { setSub(size = it) }
    SubChoiceRow("Farbe", SubStyle.COLORS.map { it.first }, AppSettings.subColor, { i -> pColor = i }) { setSub(color = it) }
    SubChoiceRow("Hintergrund", SubStyle.BGS.map { it.first }, AppSettings.subBg, { i -> pBg = i }) { setSub(bg = it) }
    SubChoiceRow("Rand", SubStyle.EDGES, AppSettings.subEdge, { i -> pEdge = i }) { setSub(edge = it) }
}

/** Eine Reihe Auswahlfelder; [onProbe] bekommt den markierten Eintrag (null = keiner markiert). */
@Composable
private fun SubChoiceRow(title: String, names: List<String>, selected: Int, onProbe: (Int?) -> Unit, onPick: (Int) -> Unit) {
    Text(title, color = Muted, fontSize = 15.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        names.forEachIndexed { i, n ->
            ChoiceChip(n, selected == i, onFocus = { on -> if (on) onProbe(i) else onProbe(null) }) { onPick(i) }
        }
    }
}

/** Kleines „Fernsehbild“ mit einer Untertitelzeile im gewählten Stil. */
@Composable
private fun SubtitlePreview(size: Int, color: Int, bg: Int, edge: Int) {
    val shape = RoundedCornerShape(DS.RadiusM)
    Box(
        Modifier.fillMaxWidth().height(170.dp).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3B4A5C), Color(0xFF1B2330), Color(0xFF52412F))))
            .border(1.dp, DS.Overlay3, shape),
        contentAlignment = Alignment.BottomCenter
    ) {
        AppIcon(AIcon.Play, Color(0x33FFFFFF), Modifier.align(Alignment.Center), 46.dp)
        val shadow = when (edge) {
            1 -> androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(2f, 2f), 6f)
            2 -> androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(0f, 0f), 3f)
            else -> null
        }
        Text(
            "So sehen deine Untertitel aus", color = Color(SubStyle.COLORS[color.coerceIn(0, SubStyle.COLORS.lastIndex)].second.toInt()),
            fontSize = (20 * SubStyle.SIZES[size.coerceIn(0, SubStyle.SIZES.lastIndex)].second).sp,
            style = androidx.compose.ui.text.TextStyle(shadow = shadow),
            modifier = Modifier.padding(bottom = 16.dp).background(Color(SubStyle.BGS[bg.coerceIn(0, SubStyle.BGS.lastIndex)].second.toInt()))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun FilterPage(ctx: Context, onLanguages: () -> Unit) {
    ToggleRow("Automatische Genres", "Aus = Kategorien deines Anbieters (Standard). Umschalten geht auch direkt in der Ansicht „Kategorien“.", AppSettings.ipvioFilter) { AppSettings.set(ctx, it) }
    val all = PlaylistStore.channels
    val vod = remember(all) { all.count { it.kind != Kind.LIVE } }
    if (!Classifier.supported(vod)) Hint("Deine Bibliothek ist für automatische Genres zu groß (über 60.000 Filme und Serien). Es werden die Kategorien deines Anbieters verwendet. Blende unter „Playlists“ > „Sprachen der Inhalte“ Sprachen aus, die du nicht brauchst – dann passt es wieder.")
    Hint("Automatische Genres sortieren Titel nach Genre (Anime = Animation aus Japan). Beim ersten Mal dauert das einige Minuten, danach ist alles gespeichert. Aus = die Kategorien deines Anbieters in der Reihenfolge deines Anbieters. In der Ansicht „Kategorien“ kannst du jederzeit zwischen beiden wechseln.")
    if (!MetaRepo.enabled()) Text("Automatische Genres brauchen einen TMDB-Schlüssel (Einstellungen > API-Schlüssel).", color = DS.Danger, fontSize = 15.sp)
    if (Classifier.running) Text("${Classifier.phase} werden geladen … ${Classifier.progress} / ${Classifier.total}", color = Accent2, fontSize = 15.sp)
    SectionTitle("Sprachen der Inhalte")
    Hint("Welche Sprachen geladen werden, stellst du unter „Playlists“ ein – das bestimmt, was aus der Playlist geladen wird.")
    PillButton("Sprachen der Inhalte öffnen", icon = AIcon.Globe) { onLanguages() }
}

/**
 * Sprachfilter: Sprachen der Playlist mit Anzahl. Auswahl erst als Entwurf (mehrere Sprachen an-/abhaken),
 * geladen wird erst mit „Übernehmen“ – sonst startete nach jedem Haken ein neues Einlesen.
 */
@Composable
private fun LanguageFilterSection(ctx: Context) {
    val counts = PlaylistStore.langCounts
    val list = remember(counts) {
        counts.entries.sortedWith(compareBy<Map.Entry<String, Int>>({ it.key == LangTagLogic.UNKNOWN }, { -it.value })).map { it.key to it.value }
    }
    val hidden = LangFilter.hidden
    var draft by remember(hidden) { mutableStateOf(hidden) }
    var warn by remember { mutableStateOf(false) }
    var big by remember { mutableStateOf(false) }
    val changed = draft != hidden
    fun edit(h: Set<String>) { draft = h; warn = false }
    fun apply() {
        // Nie alles ausblenden: dann wäre die Playlist leer und man käme kaum zurück
        if (list.isNotEmpty() && list.all { it.first in draft }) { warn = true; big = false; return }
        warn = false
        // Sehr große Auswahl: erlaubt, aber mit Hinweis (wird der Speicher zu knapp, bleibt die alte Auswahl aktiv)
        big = !PlaylistStore.fits(draft)
        LangFilter.setAndApply(ctx, draft)
    }
    SectionTitle("Sprachen der Inhalte")
    Hint("Blende Sprachen aus, die du nicht brauchst – sie verschwinden dann überall: Startseite, Filme, Serien, Live-TV und Suche. Erkannt wird die Kennzeichnung deines Anbieters (z. B. „DE |“, „[TR]“, „GERMAN“). Das ist bei IPTV-Anbietern die Sprache der Tonspur, nicht das Herkunftsland des Films.")
    if (list.isEmpty()) { Hint("Noch keine Playlist geladen."); return }
    Hint("Ausgeblendete Sprachen werden gar nicht erst geladen. Das spart viel Arbeitsspeicher und macht große Playlists schneller.")
    if (PlaylistStore.loading) {
        val t = PlaylistStore.totalBytes
        val pct = if (t > 0) "  " + (PlaylistStore.bytesRead * 100 / t).coerceIn(0, 100) + " %" else ""
        Text(Lang.tr("Sprachauswahl wird angewendet …") + pct, color = Accent2, fontSize = 15.sp, raw = true)
    } else if (PlaylistStore.error == PlaylistStore.MSG_TOO_BIG) Text(PlaylistStore.MSG_TOO_BIG, color = DS.Danger, fontSize = 15.sp)
    if (big) Text("Diese Auswahl ist sehr groß für dieses Gerät – das Laden kann lange dauern.", color = DS.Gold, fontSize = 15.sp)
    val mine = Lang.code()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Nur meine Sprache") { edit(list.map { it.first }.filter { it != mine && it != LangTagLogic.UNKNOWN }.toSet()) }
        PillButton("Alle an") { edit(emptySet()) }
        PillButton("Alle aus") { edit(list.map { it.first }.toSet()) }
    }
    @Composable
    fun ApplyRow() {
        if (warn) Text("Mindestens eine Sprache muss sichtbar bleiben.", color = DS.Danger, fontSize = 15.sp)
        if (changed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Übernehmen und laden", light = true, icon = AIcon.Check) { apply() }
            PillButton("Verwerfen") { edit(hidden) }
            val n = list.count { it.first !in draft }
            Text("$n von ${list.size} Sprachen gewählt", color = Muted, fontSize = 14.sp)
        }
    }
    ApplyRow()
    for ((code, n) in list) {
        ToggleRow(
            if (code == LangTagLogic.UNKNOWN) "Ohne Kennung" else LangTagLogic.name(code),
            n.toString() + " Titel",
            code !in draft
        ) { edit(if (code in draft) draft - code else draft + code) }
    }
    // Nach langer Liste: Übernehmen auch unten, damit man nicht zurückscrollen muss
    if (list.size > 4) ApplyRow()
}

@Composable
private fun LivePage(ctx: Context) {
    var url by remember { mutableStateOf(Xmltv.customUrl(ctx)) }
    val all = PlaylistStore.channels
    val chCount = Xmltv.channelCount
    val counts by produceState(0 to 0, all, chCount) {
        value = withContext(Dispatchers.Default) { val live = all.filter { it.kind == Kind.LIVE }; live.size to live.count { Epg.supported(it) } }
    }
    val xtream = counts.second
    Text("TV-Programm (EPG)", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text("Programm bei $xtream von ${counts.first} Sendern möglich", color = Muted, fontSize = 15.sp)
    Text("XMLTV: ${Xmltv.status}", color = Accent2, fontSize = 15.sp)
    // OnAir: the guide is stored on the device and renewed with the playlist (at most every 12 hours)
    if (Xmltv.storedAt > 0) Text("Programm gespeichert: " + dayLabel(Xmltv.storedAt / 1000) + " " + hhmm(Xmltv.storedAt / 1000), color = Muted, fontSize = 14.sp)
    Hint("Die App sucht die Programmdaten automatisch (Xtream-Schnittstelle oder XMLTV-Adresse aus der Playlist). Wenn dein Anbieter eine eigene XMLTV-Adresse nennt, trage sie hier ein.")
    Field(url, { url = it }, "https://…/xmltv.php?username=…&password=…")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Speichern & laden", light = true) {
            Xmltv.setCustom(ctx, url)
            // AUTO: only the new address is downloaded; the provider's guide comes from the device
            Xmltv.start(ctx, PlaylistStore.epgUrls, PlaylistStore.channels, RefreshLogic.Trigger.AUTO)
        }
        PillButton("Neu laden") { Xmltv.start(ctx, PlaylistStore.epgUrls, PlaylistStore.channels, RefreshLogic.Trigger.MANUAL) }
    }
    Spacer(Modifier.height(4.dp))
    Text("Favoriten: ${Library.favs.size} Sender · ${CatFavs.list.size} Kategorien", color = Color.White, fontSize = 17.sp)
    Hint("Sender als Favorit: auf dem Sender OK gedrückt halten > „Zu Favoriten“. Kategorie als Favorit: auf der Kategorie OK gedrückt halten.")
    ChannelListsSection(ctx)
    CatchupSection()
    ReminderSettingsSection(ctx)
}

@Composable
private fun PlaylistPage(ctx: Context, onAdd: () -> Unit, reload: () -> Unit) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf("") }
    PlaylistStore.savedUrl(ctx)
    Text("Meine Playlists (${PlaylistStore.lists.size})", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    PlaylistStore.lists.forEachIndexed { i, pl ->
        val isActive = i == PlaylistStore.active
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(if (isActive) DS.Overlay2 else Color(0x12FFFFFF)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(pl.name + if (isActive) "  ·  aktiv" else "", color = if (isActive) Accent2 else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(PlaylistStore.hostOf(pl.url), color = Muted, fontSize = 13.sp, maxLines = 1)
            }
            if (!isActive) SmallPill("Aktivieren") {
                PlaylistStore.activate(ctx, i)
                scope.launch { PlaylistStore.load(ctx, pl.url, false, true, trigger = RefreshLogic.Trigger.AUTO) }
            }
            SmallPill("Umbenennen") { editing = i; name = pl.name }
            SmallPill("Löschen") {
                val wasActive = isActive
                PlaylistStore.remove(ctx, i)
                if (PlaylistStore.lists.isEmpty()) onAdd()
                else if (wasActive) {
                    val u = PlaylistStore.lists[PlaylistStore.active].url
                    scope.launch { PlaylistStore.load(ctx, u, false, true, trigger = RefreshLogic.Trigger.AUTO) }
                }
            }
        }
    }
    if (editing >= 0) {
        Field(name, { name = it }, "Name der Playlist")
        PillButton("Name speichern", light = true) { PlaylistStore.rename(ctx, editing, name); editing = -1 }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("+ Weitere Playlist hinzufügen", light = true) { onAdd() }
        PillButton("Aktive Playlist neu laden", icon = AIcon.Refresh) { reload() }
    }
    LanguageFilterSection(ctx)
    SectionTitle("Automatisch aktualisieren")
    // OnAir: no "at every start" (rate limits) – modes 1..4 only, shown without the old first entry
    val modes = listOf("Alle paar Stunden", "Täglich", "Wöchentlich", "Nur von Hand")
    val modeIdx = (General.refreshMode - 1).coerceIn(0, modes.lastIndex)
    SelectRow("Playlist aktualisieren", modes[modeIdx], modes, modeIdx) { General.setPlan(ctx, mode = it + 1) }
    if (General.refreshMode == 1) {
        val ivs = listOf(12, 24, 48)
        SelectRow("Abstand", "alle ${General.refreshInterval} Stunden", ivs.map { "alle $it Stunden" }, ivs.indexOf(General.refreshInterval).coerceAtLeast(0)) {
            General.setPlan(ctx, interval = ivs[it])
        }
    }
    if (General.refreshMode == 2 || General.refreshMode == 3) {
        if (General.refreshMode == 3) {
            val names = java.text.DateFormatSymbols(Lang.locale()).weekdays
            val dows = listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY, java.util.Calendar.THURSDAY,
                java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)
            SelectRow("Wochentag", names[General.refreshDow], dows.map { names[it] }, dows.indexOf(General.refreshDow).coerceAtLeast(0)) {
                General.setPlan(ctx, dow = dows[it])
            }
        }
        val hours = (0..23).map { String.format("%02d", it) }
        SelectRow("Stunde", hours[General.refreshHour], hours, General.refreshHour) { General.setPlan(ctx, hour = it) }
        val mins = listOf(0, 15, 30, 45)
        SelectRow("Minute", String.format("%02d", General.refreshMinute), mins.map { String.format("%02d", it) }, mins.indexOf(General.refreshMinute).coerceAtLeast(0)) {
            General.setPlan(ctx, minute = mins[it])
        }
    }
    val nowMs = System.currentTimeMillis()
    Text(
        "Zuletzt aktualisiert: " + (if (General.lastRefresh > 0) dayLabel(General.lastRefresh / 1000) + " " + hhmm(General.lastRefresh / 1000) else "noch nie"),
        color = Muted, fontSize = 14.sp
    )
    SettingsLogic.nextDue(General.plan(), General.lastRefresh, nowMs, General.zone())?.let { planned ->
        // OnAir: never earlier than the rate-limit gate allows (12 h since the last success, back-off after errors)
        val gate = PlaylistStore.savedUrl(ctx)?.let { RefreshGate.nextAllowedAt(ctx, RefreshGate.catalogKey(it), RefreshLogic.Trigger.SCHEDULED, true) } ?: 0L
        val at = maxOf(planned, gate)
        Text("Nächste Aktualisierung: " + dayLabel(at / 1000) + " " + hhmm(at / 1000), color = Accent2, fontSize = 14.sp)
    }
    Hint("Playlist und TV-Programm werden auf dem Gerät gespeichert. Beim Öffnen der App wird nichts heruntergeladen; die Aktualisierung läuft im Hintergrund nach Plan, höchstens alle 12 Stunden, solange das Gerät eingeschaltet und online ist. Nach einem Fehler wartet die App immer länger, bevor sie es erneut versucht. „Nur von Hand“ nutzt immer den gespeicherten Stand; Neu laden geht jederzeit mit dem Knopf oben. Die Uhrzeit richtet sich nach deiner Zeitzone unter „Sprache & Region“.")
    Hint("Läuft gerade ein Film oder Sender, wartet die Aktualisierung, bis du fertig bist – so ruckelt nichts.")
    Hint("Zugangsdaten werden nur auf diesem Gerät gespeichert und hier nicht angezeigt. Es ist immer eine Playlist aktiv. Mit „Aktivieren“ wechselst du die Liste.")
}

@Composable
private fun ProfilesPage(ctx: Context, onExit: () -> Unit) {
    var editing by remember { mutableIntStateOf(-1) }
    var name by remember { mutableStateOf("") }
    Profiles.init(ctx)
    // Gesperrtes Profil (Jugendschutz mit PIN): Wechseln und Bearbeiten der Profile nur nach PIN-Eingabe
    var unlocked by remember { mutableStateOf(!Parental.profileLocked(ctx, Profiles.active)) }
    var pinErr by remember { mutableStateOf<String?>(null) }
    if (!unlocked) {
        PinPad(
            "PIN eingeben", "Dieses Profil ist durch den Jugendschutz gesperrt. Profile wechseln und bearbeiten geht nur mit der PIN.", pinErr,
            { if (Parental.verifyFor(ctx, Profiles.active, it)) { unlocked = true; pinErr = null } else pinErr = Parental.pinError() },
            { onExit() }
        )
        return
    }
    Text("Aktiv: " + Profiles.activeName(), color = Accent2, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Hint("Jedes Profil hat eigenen Verlauf, eigene Favoriten, Ansicht, Player-Einstellungen und eigenen Jugendschutz. Playlist, Sprache und Aktualisierung sind für alle gleich.")
    Profiles.list.toList().forEach { p ->
        val isActive = p.id == Profiles.active
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(if (isActive) DS.Overlay2 else Color(0x12FFFFFF)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            androidx.compose.foundation.text.BasicText(
                Profiles.displayName(p) + if (isActive) "  ·  " + Lang.tr("aktiv") else "",
                modifier = Modifier.weight(1f),
                style = androidx.compose.ui.text.TextStyle(color = if (isActive) Accent2 else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!isActive) SmallPill("Wechseln") { Profiles.switchTo(ctx, p.id) }
            SmallPill("Umbenennen") { editing = p.id; name = p.name }
            if (!isActive && p.id != 0) ConfirmPill("Löschen") { Profiles.remove(ctx, p.id) }
        }
    }
    if (editing >= 0) {
        Field(name, { name = it }, "Name des Profils")
        PillButton("Name speichern", light = true) { Profiles.rename(ctx, editing, name); editing = -1 }
    }
    if (Profiles.list.size < Profiles.MAX) PillButton("Profil hinzufügen") { Profiles.add(ctx) }
    ToggleRow("Beim Start fragen: Wer schaut?", "Zeigt beim Öffnen der App die Profilauswahl, sobald es mehr als ein Profil gibt.", Profiles.askAtStart) { Profiles.setAsk(ctx, it) }
    Hint("Beim Wechseln startet die App kurz neu.")
}

/**
 * Eigene API-Schlüssel (Open Source: es ist kein Schlüssel eingebaut). Ohne Schlüssel funktioniert die App, zeigt aber
 * nur, was die Playlist liefert.
 */
@Composable
private fun ApiKeysPage(ctx: Context) {
    val scope = rememberCoroutineScope()
    var tmdb by remember { mutableStateOf(Config.ownTmdb(ctx)) }
    var omdb by remember { mutableStateOf(Config.ownOmdb(ctx)) }
    var tmdbStatus by remember { mutableStateOf("") }
    var omdbStatus by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Hint("Die App enthält keine eigenen Schlüssel. Mit deinen eigenen, kostenlosen Schlüsseln zeigt sie Poster, Beschreibungen, Besetzung, Trailer, automatische Genres sowie IMDb- und Rotten-Tomatoes-Wertungen. Ohne Schlüssel übernimmt sie Beschreibung, Cover und Bewertung von deinem Anbieter (bei Xtream-Zugängen).")

    SectionTitle("TMDB (Film-Infos)")
    Field(tmdb, { tmdb = it; tmdbStatus = "" }, "TMDB API-Schlüssel (v3)")
    Hint("Kostenlos unter themoviedb.org: Konto anlegen, anmelden, dann Einstellungen > API > API-Schlüssel anfordern und den 32-stelligen „API Key“ eintragen. Dieses Produkt verwendet die TMDB-API, wird aber nicht von TMDB unterstützt oder zertifiziert.")
    if (tmdbStatus.isNotBlank()) Text(tmdbStatus, color = if (tmdbStatus == "Schlüssel gültig") Accent2 else Muted, fontSize = 16.sp)

    SectionTitle("OMDb (IMDb- und Rotten-Tomatoes-Wertung)")
    Field(omdb, { omdb = it; omdbStatus = "" }, "OMDb API-Schlüssel")
    Hint("Kostenlos unter omdbapi.com > API Key (FREE). Danach den Aktivierungslink in der E-Mail anklicken, sonst funktioniert der Schlüssel nicht. Geht auch ohne TMDB-Schlüssel.")
    if (omdbStatus.isNotBlank()) Text(omdbStatus, color = if (omdbStatus == "Schlüssel gültig") Accent2 else Muted, fontSize = 16.sp)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton(if (busy) "Bitte warten …" else "Speichern und prüfen", light = true, icon = AIcon.Check) {
            if (busy) return@PillButton
            busy = true
            Config.save(ctx, tmdb, omdb)
            tmdb = Config.ownTmdb(ctx); omdb = Config.ownOmdb(ctx)
            scope.launch {
                tmdbStatus = if (tmdb.isEmpty()) "" else KeyCheck.tmdb(tmdb) ?: "Schlüssel gültig"
                omdbStatus = if (omdb.isEmpty()) "" else KeyCheck.omdb(omdb) ?: "Schlüssel gültig"
                busy = false
            }
        }
        if (tmdb.isNotEmpty() || omdb.isNotEmpty()) ConfirmPill("Schlüssel entfernen") {
            tmdb = ""; omdb = ""; tmdbStatus = ""; omdbStatus = ""; Config.save(ctx, "", "")
        }
    }
    Hint("Die Schlüssel bleiben nur auf diesem Gerät. Nach dem Eintragen die App einmal neu starten, damit alle Bereiche die Infos laden. Intro- und Abspann-Zeiten kommen von TheIntroDB und brauchen keinen Schlüssel.")
}

@Composable
private fun AboutPage(ctx: Context) {
    Text("IPVIO Stream · Version " + appVersion(ctx), color = Color.White, fontSize = 18.sp)
    Hint("IPVIO Stream ist ein Player und liefert selbst keine Inhalte. Nutze nur Quellen, für die du eine Berechtigung hast.")
    Hint("Dieses Produkt verwendet die TMDB-API, wird aber nicht von TMDB unterstützt oder zertifiziert. Wertungen: OMDb. Intro-Zeiten: TheIntroDB.")
    Hint("IPVIO Stream ist freie Software unter der GNU General Public License v3 (GPL-3.0). Verwendete Bibliotheken: AndroidX/Jetpack Compose, Media3 (Apache 2.0), OkHttp, Coil (Apache 2.0), Jellyfin FFmpeg-Decoder (GPL-3.0), Schriften Outfit und Inter (SIL Open Font License).")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PillButton("Systemeinstellungen der App") {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + ctx.packageName))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                )
            }
        }
    }
    UpdateSection(ctx)
    SectionTitle("Zurücksetzen")
    ConfirmPill("Alle Einstellungen zurücksetzen") { AppSettings.resetAll(ctx); General.resetAll(ctx) }
    Hint("Setzt Ansicht, Design, Menü, Player, Ton und Start auf die Standardwerte. Playlists, Favoriten, Verlauf und Fortsetzen-Positionen bleiben erhalten. Zwischenspeicher leerst du unter „Speicher“.")
}

/** App-Aktualisierung aus der App heraus. Ohne eingetragene Adresse gibt es nur das Eingabefeld. */
@Composable
private fun UpdateSection(ctx: Context) {
    SectionTitle("App-Aktualisierung")
    var url by remember { mutableStateOf(AppUpdate.url(ctx)) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    val scope = rememberCoroutineScope()
    Field(url, { url = it; AppUpdate.setUrl(ctx, it); info = null }, "Adresse der Update-Datei (https://…/update.json)")
    if (url.isNotBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton(if (busy) "Bitte warten …" else "Nach Update suchen") {
                if (busy) return@PillButton
                busy = true; status = "Suche …"; info = null
                scope.launch {
                    AppUpdate.check(ctx)
                        .onSuccess { r -> info = r; status = if (r == null) "Du hast die neueste Version." else "Neu: Version ${r.name}" + (if (r.notes.isNotBlank()) " – " + r.notes else "") }
                        .onFailure { status = it.message ?: "Fehler" }
                    busy = false
                }
            }
            info?.let { u ->
                PillButton("Herunterladen und installieren") {
                    if (busy) return@PillButton
                    busy = true
                    scope.launch {
                        AppUpdate.download(ctx, u) { p -> status = if (p >= 0) "Lade … $p %" else "Lade …" }
                            .onSuccess { f -> status = if (AppUpdate.install(ctx, f)) "Installation gestartet" else "Erlaube der App einmal „Apps installieren“ und tippe dann erneut." }
                            .onFailure { status = it.message ?: "Fehler" }
                        busy = false
                    }
                }
            }
        }
        if (status.isNotBlank()) Text(status, color = Muted, fontSize = 16.sp)
    }
    Hint("Trage die Adresse einer kleinen Datei update.json ein, die du selbst bereitstellst: {\"versionCode\": 2, \"versionName\": \"1.1\", \"apk\": \"ipvio.apk\"}. Die neue APK muss mit demselben Schlüssel signiert sein wie die installierte, sonst lehnt Android sie ab.")
}


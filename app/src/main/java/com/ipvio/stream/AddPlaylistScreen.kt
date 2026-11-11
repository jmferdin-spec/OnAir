package com.ipvio.stream

import androidx.compose.ui.graphics.lerp
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.os.Handler
import android.os.Looper
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AddPlaylistScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Zuletzt eingegebene Playlist, die noch nicht fertig geladen wurde (Absturz, Stromausfall, Abbruch): vorausfüllen
    val pend = remember { PlaylistStore.pending(ctx) }
    val pendX = remember { pend?.let { LoadLogic.xtreamParts(it.url) } }
    var xtream by remember { mutableStateOf(pendX != null) }
    var url by remember { mutableStateOf(if (pend != null && pendX == null) pend.url else "") }
    var host by remember { mutableStateOf(pendX?.first ?: "") }
    var user by remember { mutableStateOf(pendX?.second ?: "") }
    var pass by remember { mutableStateOf(pendX?.third ?: "") }
    var showPass by remember { mutableStateOf(false) }
    // Vom Handy senden: lokaler Mini-Server, solange dieser Bildschirm offen ist
    val ip = remember { PhoneImport.localIp() }
    val receiver = remember {
        PhoneImport { inp ->
            Handler(Looper.getMainLooper()).post {
                val u = if (inp.isXtream) PlaylistStore.xtreamUrl(inp.host, inp.user, inp.pass) else inp.url
                if (inp.isXtream) { xtream = true; host = inp.host; user = inp.user; pass = inp.pass } else { xtream = false; url = inp.url }
                if (u == null) PlaylistStore.error = "Angaben unvollständig: Server, Benutzername und Passwort nötig."
                else scope.launch { if (PlaylistStore.load(ctx, u, true, skipNetworkWhenCached = true)) onDone() }   // OnAir: same account stored → no download
            }
        }
    }
    var serving by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        serving = receiver.start()
        onDispose { receiver.stop() }
    }
    val canCancel = PlaylistStore.lists.isNotEmpty() || PlaylistStore.channels.isNotEmpty()
    BackHandler(enabled = canCancel) { PlaylistStore.clearPending(ctx); onDone() }

    // Beim letzten Mal mitten im Laden unterbrochen: automatisch weitermachen, ohne etwas neu einzugeben
    LaunchedEffect(Unit) {
        val p = pend ?: return@LaunchedEffect
        when {
            p.autoResume && !PlaylistStore.loading -> { if (PlaylistStore.load(ctx, p.url, true, skipNetworkWhenCached = true, resumed = true, trigger = RefreshLogic.Trigger.AUTO)) onDone() }
            p.interrupted -> PlaylistStore.error = "Das Laden wurde mehrmals unterbrochen. Deine Angaben sind gespeichert – drücke „Hinzufügen“, um es erneut zu versuchen."
            p.msg != null -> PlaylistStore.error = p.msg
        }
    }

    if (PlaylistStore.loading) { PlaylistLoadingOverlay(); return }

    // Start-Fokus auf die Auswahl M3U/Xtream (nicht auf ein Eingabefeld: das würde sofort die Bildschirmtastatur öffnen)
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); runCatching { startFocus.requestFocus() } }

    Row(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(lerp(Bg, Accent1, 0.14f), Bg), radius = 1400f))
            .padding(horizontal = 46.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Links: das Formular
        Column(
            // Innenabstand im Scrollbereich: der fokussierte Knopf wird vergrößert und darf nicht abgeschnitten werden
            Modifier.weight(1.1f).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BrandMark(Modifier.size(48.dp))
                Text("OnAir", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text("Playlist hinzufügen", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton("M3U-Link", Modifier.focusRequester(startFocus), primary = !xtream) { xtream = false }
                FocusButton("Xtream-Login", primary = xtream) { xtream = true }
            }
            if (!xtream) {
                Field(url, { url = it }, "http://…/playlist.m3u")
                FocusButton("Aus Zwischenablage einfügen") {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.primaryClip?.getItemAt(0)?.text?.let { url = it.toString() }
                }
            } else {
                Field(host, { host = it }, "Server (z. B. http://server.tld:8080)", credential = KeyboardType.Uri)
                Field(user, { user = it }, "Benutzername", credential = KeyboardType.Ascii)
                // Always a password input (keyboard stays in password mode, nothing learned); "show" only changes the dots
                Field(pass, { pass = it }, "Passwort", password = !showPass, credential = KeyboardType.Password)
                FocusButton(if (showPass) "Passwort verbergen" else "Passwort anzeigen") { showPass = !showPass }
            }
            PlaylistStore.error?.let { Text(it, color = DS.Danger, fontSize = 18.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FocusButton(if (PlaylistStore.loading) "Lade…" else "Hinzufügen", primary = true) {
                    if (PlaylistStore.loading) return@FocusButton
                    val finalUrl = if (xtream) PlaylistStore.xtreamUrl(host, user, pass) else url
                    if (finalUrl == null) PlaylistStore.error = "Angaben unvollständig: Server, Benutzername und Passwort nötig."
                    else scope.launch { if (PlaylistStore.load(ctx, finalUrl, true, skipNetworkWhenCached = true)) onDone() }   // OnAir: same account stored → no download
                }
                if (canCancel) FocusButton("Abbrechen") { PlaylistStore.clearPending(ctx); onDone() }
            }
        }
        // Rechts: Anleitung für die Eingabe am Handy
        PhoneCard(Modifier.weight(1f), if (serving && ip != null) "http://" + ip + ":" + receiver.port else null, receiver.code)
    }
}

/** Karte mit Anleitung: Playlist bequem am Handy eingeben statt mit der Fernbedienung. */
@Composable
private fun PhoneCard(modifier: Modifier, address: String?, code: String) {
    val shape = RoundedCornerShape(DS.RadiusXl)
    Column(
        modifier.clip(shape).background(Surf).border(2.dp, Surf2, shape).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Einfacher mit dem Handy", color = Accent2, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Mit der Fernbedienung zu tippen ist mühsam. Schick die Zugangsdaten lieber vom Handy:",
            color = Muted, fontSize = 16.sp, lineHeight = 22.sp
        )
        if (address == null) {
            Text(
                "Die Übertragung vom Handy ist gerade nicht verfügbar. Das Gerät muss mit dem WLAN verbunden sein.",
                color = Color(0xFFFFB86B), fontSize = 17.sp, lineHeight = 24.sp
            )
        } else {
            Step(1, "Handy mit demselben WLAN verbinden wie dieses Gerät.")
            Step(2, "Im Browser des Handys diese Adresse öffnen:")
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(Bg).padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                Text(address, color = Accent2, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
            }
            Step(3, "Den Code von hier eingeben:")
            Row(
                Modifier.clip(RoundedCornerShape(DS.RadiusM)).background(Bg).padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Code", color = Muted, fontSize = 18.sp)
                Text(code, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
            }
            Step(4, "Zugangsdaten eintragen und senden. Die Playlist wird danach automatisch geladen.")
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(Accent1),
            contentAlignment = Alignment.Center
        ) {
            Text(n.toString(), color = OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(text, color = Color.White, fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

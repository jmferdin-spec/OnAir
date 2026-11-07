package com.ipvio.stream

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Farben kommen aus dem Gestaltungs-Zustand (Look.kt): beim Umschalten des Themes ändert sich die ganze App sofort.
val Bg: Color get() = Look.bg
val Surf: Color get() = Look.surf
val Surf2: Color get() = Look.surf2
val Accent1: Color get() = Look.accent1
val Accent2: Color get() = Look.accent2
/** Schriftfarbe auf Accent1-Flächen (bei sehr hellen Farben wie IPVIO-Grün dunkel). */
val OnAccent: Color get() = Look.onAccent
/** Metadaten-Grau (Runde 17 heller: Kontrast ≥ 6:1 auf allen Flächen, auch auf Glas über Bildern lesbar). */
val Muted = Color(0xFFA0A6B8)

/**
 * Farb-Tokens (Runde 17). Neutrale Text-Stufen gelten in jedem Design; Flächen leiten sich aus dem gewählten Design (Look) ab.
 * Kontrast auf IPVIO Navy #0B0F19 (WCAG): TextHi 17:1, TextMid 12:1, TextLow/Muted 7,9:1; auf der hellsten Fläche immer ≥ 5,9:1.
 * Fokus: Knöpfe weiß mit dunkler Schrift, Karten weißer Rahmen mit Schatten.
 */
object Tok {
    val TextHi = Color(0xFFF4F6FB)
    val TextMid = Color(0xFFD3D7E2)
    val TextLow = Color(0xFFA0A6B8)
    /** Ruhende Karte/Zeile, markierte Zeile */
    val Card: Color get() = lerp(Look.surf, Color.White, 0.03f)
    val CardFocus: Color get() = lerp(Look.surf2, Color.White, 0.08f)
    /** Glas über Bildern (Leisten, Pillen), stark für Karten mit viel Text */
    val Glass: Color get() = Look.bg.copy(alpha = 0.8f)
    val GlassStrong: Color get() = Look.bg.copy(alpha = 0.94f)
    val Line = Color(0x26FFFFFF)
    /** Schatten für gehobene (markierte) Karten */
    val Lift = Color(0xE6000000)
}

/**
 * Designsystem: zentrale Maße und Farben. Neue Oberflächen nutzen nur diese Werte.
 *
 * Fokus (Fernbedienung), überall gleich:
 *  - Knöpfe und Pillen: weiße Fläche, dunkle Schrift, leicht größer ([ButtonColors]).
 *  - Karten und Poster: Rahmen in der Hervorhebungsfarbe, größer, weicher Schein ([focusGlow]).
 *  - Listen- und Einstellungszeilen: hellere Fläche plus Rahmen in der Hervorhebungsfarbe ([RowFocus]).
 */
object DS {
    // Abstände (dp)
    val SpaceXs = 4.dp
    /** Seitlicher Rand der Inhalte (TV-sicherer Bereich). */
    val PageX = 48.dp
    /** Oberer/unterer Rand: mindestens 27 dp (Android-TV-Richtlinie gegen Überscan). */
    val PageY = 28.dp
    /** Breite der Seitenleiste (wie im Entwurf immer mit Texten: 230 von 1658 Entwurfs-Pixeln). */
    /** Platz der eingeklappten Seitenleiste (Runde 14: nur Symbole, klappt beim Hineingehen über den Inhalt auf). */
    val SidebarW: Dp get() = dpx(92)
    /** Breite der aufgeklappten Seitenleiste. */
    val SidebarOpenW: Dp get() = dpx(250)

    // Ecken
    val RadiusXs = 8.dp
    val RadiusS = 12.dp
    val RadiusM = 16.dp
    val RadiusL = 20.dp
    val RadiusXl = 24.dp

    // Rahmen
    val Border = 2.dp
    val FocusBorder = 3.dp

    // Schriftgrößen
    val TextCaption = 12.sp
    val TextSmall = 14.sp
    val TextBody = 16.sp
    val TextLead = 18.sp

    // Ebenen über dem Hintergrund (weiß mit Deckkraft)
    val Overlay1 = Color(0x14FFFFFF)
    val Overlay2 = Color(0x22FFFFFF)
    val Overlay3 = Color(0x33FFFFFF)
    val Overlay4 = Color(0x40FFFFFF)
    val Hairline = Color(0x1AFFFFFF)
    val Scrim = Color(0xAA000000)

    // Schrift- und Signalfarben
    val TextSoft = Color(0xFFD7DAE5)
    val Danger = Color(0xFFFF6B6B)
    val Gold = Color(0xFFFFD166)

    // Fokus für Knöpfe
    val FocusFill = Color.White
    val FocusInk = Color(0xFF0B0F19)
}

/** Fläche einer Zeile mit Fokus (aus dem Theme abgeleitet, passt zu jedem Hintergrund). */
val RowFocus: Color get() = lerp(Look.surf, Color.White, 0.08f)

/** Farben eines Knopfs: fokussiert weiß, sonst Akzent (primär) oder halbtransparent (sekundär). */
class ButtonColors(val bg: Color, val text: Color)

fun buttonColors(focused: Boolean, primary: Boolean): ButtonColors = when {
    focused -> ButtonColors(DS.FocusFill, DS.FocusInk)
    // Runde 14: Hauptknopf nicht mehr voll farbig (sah aus wie markiert) – nur leicht getönt; markiert ist allein die weiße Fläche
    primary -> ButtonColors(lerp(DS.Overlay3, Look.accent1, 0.32f), Color.White)
    else -> ButtonColors(DS.Overlay3, Color.White)
}

/** Weicher Schein in der Hervorhebungsfarbe um fokussierte Karten (Android 9+ farbig, darunter neutral). */
fun Modifier.focusGlow(focused: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    // Runde 14 (Wunsch des Nutzers): kein farbiger Schein mehr um markierte Felder, nur ein dezenter dunkler Schatten
    if (!focused) this else this.shadow(20.dp, shape, clip = false, ambientColor = Tok.Lift, spotColor = Tok.Lift)

@Composable
fun FocusButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (f) focusScale(1.07f) else 1f, label = "btn")
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .clip(shape)
            .background(if (f) SolidColor(DS.FocusFill) else if (primary) SolidColor(lerp(DS.Overlay3, Look.accent1, 0.32f)) else SolidColor(DS.Overlay3))
            .onFocusChanged { f = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) { Text(text, color = if (f) DS.FocusInk else Color.White, fontSize = DS.TextLead, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, hint: String,
    modifier: Modifier = Modifier, inner: Modifier = Modifier, password: Boolean = false,
    /** Nur Ziffern (Zahlen-Tastatur), z. B. „Gehe zu Folge“. */
    number: Boolean = false,
    /** „Fertig“ auf der Bildschirmtastatur. */
    onDone: (() -> Unit)? = null,
    /** OnAir: login data (server, user, password) – no autocorrect, no auto-capitalisation, given keyboard type. */
    credential: KeyboardType? = null
) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .fillMaxWidth().height(62.dp)
            .clip(shape).background(Surf)
            .border(DS.Border, if (f) Accent2 else Surf2, shape)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
            cursorBrush = SolidColor(Accent2),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (credential != null) KeyboardOptions(
                    capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                    keyboardType = credential, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next)
                else if (number) KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done)
                else if (onDone != null) KeyboardOptions(imeAction = ImeAction.Done) else KeyboardOptions.Default,
            // Nur mit eigener Aktion überschreiben; sonst bleibt das Standardverhalten (Tastatur schließen)
            keyboardActions = if (onDone != null) KeyboardActions(onDone = { onDone(); defaultKeyboardAction(ImeAction.Done) }) else KeyboardActions.Default,
            modifier = inner.fillMaxWidth().onFocusChanged { f = it.isFocused }
        )
        if (value.isEmpty()) Text(hint, color = Muted, fontSize = 20.sp)
    }
}

/** Schlichter Lade-Kreis (ersetzt den aus material3). */
@Composable
fun Spinner(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "spinA")
    Canvas(modifier.rotate(if (Look.motion > 0) a else 0f)) {
        val w = size.minDimension * 0.09f
        val tl = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
        val sz = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(color, -90f, 100f, false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Platzhalter-Farbe, die sanft pulsiert (Lade-Skelett statt Spinner). Bei "Bewegung: aus" ruhig. */
@Composable
fun shimmerColor(): Color {
    val t = rememberInfiniteTransition(label = "sk")
    val a by t.animateFloat(0.05f, 0.12f, infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "ska")
    return Color.White.copy(alpha = if (Look.motion > 0) a else 0.08f)
}

/** Grauer Platzhalter in Form des späteren Inhalts. */
@Composable
fun SkeletonBox(modifier: Modifier, color: Color = shimmerColor(), shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(DS.RadiusXs)) {
    Box(modifier.clip(shape).background(color))
}

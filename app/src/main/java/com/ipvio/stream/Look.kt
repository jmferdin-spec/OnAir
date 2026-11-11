package com.ipvio.stream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Ein Farbthema: Name, Fläche (unter weißer Schrift) und Highlight (auf dunklem Grund). */
class AccentPreset(val name: String, val fill: Int, val highlight: Int)

/** Farben eines Designs (Grund, Flächen, Farbe 1, Farbe 2, Schrift auf Farbe 1). */
class LookPalette(val bg: Color, val surf: Color, val surf2: Color, val fill: Color, val high: Color, val onFill: Color)

/** Hintergrund-Thema: Name, Grund, Fläche, Fläche 2. */
class BgPreset(val name: String, val bg: Int, val surf: Int, val surf2: Int)

/**
 * Zentraler Zustand der Gestaltung. Die Farben in Ui.kt (Bg, Surf, Accent1 …) lesen von hier,
 * dadurch ändert sich beim Umschalten die ganze App sofort, ohne dass irgendein Bildschirm angepasst werden muss.
 */
object Look {
    /** Upstream green (#4ADE80 / #86EFAC) – still offered as the preset „Grün“, no longer the default. */
    const val BRAND_GREEN = 0x4ADE80
    const val BRAND_GREEN_HI = 0x86EFAC
    /**
     * OnAir default: live red on navy. Fill #D32F2F carries white text (contrast ≈ 5:1); highlight #FF6B6B is the
     * readable red for text and focus frames on navy (≈ 7:1 on #0B0F19).
     */
    const val BRAND_RED = 0xD32F2F
    const val BRAND_RED_HI = 0xFF6B6B
    const val BRAND_NAVY = 0x0B0F19
    /** Standard: OnAir red on navy (index 0 of [ACCENTS] / [BGS]). */
    const val DEFAULT_ACCENT = 0
    const val DEFAULT_BG = 0
    /** Starting value of the custom colour picker. */
    const val DEFAULT_CUSTOM = BRAND_RED

    val ACCENTS = listOf(
        AccentPreset("OnAir", BRAND_RED, BRAND_RED_HI),
        AccentPreset("Violett & Türkis", 0x7C5CFF, 0x00D4C8),
        AccentPreset("Ozean", 0x2F6FE0, 0x5CD6FF),
        AccentPreset("Smaragd", 0x0A7F58, 0x4ADE80),
        AccentPreset("Sonnenuntergang", 0xD93F46, 0xFFB454),
        AccentPreset("Rosé", 0xC72F8F, 0xFF9ED2),
        AccentPreset("Gold", 0x9A6414, 0xFFD166),
        AccentPreset("Orange", 0xC2410C, 0xFFA64D),
        AccentPreset("Eisblau", 0x0E7490, 0x67E8F9),
        AccentPreset("Limette", 0x4D7C0F, 0xBEF264),
        AccentPreset("Purpur", 0x7E22CE, 0xE879F9),
        AccentPreset("Schiefer", 0x475569, 0xE2E8F0),
        // OnAir: upstream's green, appended so the indices above stay as they were
        AccentPreset("Grün", BRAND_GREEN, BRAND_GREEN_HI)
    )
    /** Farbfelder für die eigene Farbe (2 Reihen à 10). */
    val SWATCHES = listOf(
        0xE53935, 0xF4511E, 0xFB8C00, 0xFFB300, 0xFDD835, 0x9CCC65, 0x4ADE80, 0x00C98D, 0x00BFA5, 0x26C6DA,
        0x29B6F6, 0x3D7BFF, 0x5C6BFF, 0x8E5CFF, 0xB44CF0, 0xE040C0, 0xFF4F8B, 0xFF6F61, 0xCFD8DC, 0x78909C
    )
    /** Index nach den Voreinstellungen = eigene Farbe (frei gewählt, siehe [custom]). */
    val CUSTOM get() = ACCENTS.size

    val BGS = listOf(
        BgPreset("OnAir Navy", BRAND_NAVY, 0x151B2A, 0x1F2739),
        BgPreset("Mitternacht", 0x07080C, 0x12141C, 0x1B1E2A),
        BgPreset("OLED-Schwarz", 0x000000, 0x0B0C10, 0x15171E),
        BgPreset("Graphit", 0x121316, 0x1C1E24, 0x272A32),
        BgPreset("Tiefblau", 0x070B14, 0x101828, 0x1A2540)
    )

    /** Standard: 85 % (Index 1) – zeigt mehr Inhalt, Elemente bleiben gut lesbar. */
    const val DEFAULT_SCALE = 1

    val MOTION = listOf("Aus", "Dezent", "Normal")
    val SCALES = listOf("Automatisch", "85 %", "100 %", "115 %", "130 %")

    var accentIdx by mutableIntStateOf(DEFAULT_ACCENT)
    /** Eigene Farbe 1 (Flächen, Knöpfe, Balken). */
    var custom by mutableIntStateOf(DEFAULT_CUSTOM)
    /** Eigene Farbe 2 (Hervorhebung, Schrift und Rahmen auf dem Hintergrund); -1 = automatisch aus Farbe 1. */
    var custom2 by mutableIntStateOf(-1)
    var bgIdx by mutableIntStateOf(DEFAULT_BG)
    var motion by mutableIntStateOf(2)
    var uiScale by mutableIntStateOf(DEFAULT_SCALE)

    private fun bgRgb(): Int = BGS[bgIdx.coerceIn(0, BGS.lastIndex)].bg

    /** Farbe 1: Voreinstellung oder genau die gewählte eigene Farbe (die Schrift darauf passt sich an, siehe onAccent). */
    fun fillRgb(): Int = if (accentIdx in ACCENTS.indices) ACCENTS[accentIdx].fill else custom

    /** Farbe 2 automatisch: aus Farbe 1 so abgeleitet, dass sie auf dem Hintergrund gut lesbar ist. */
    fun autoHighRgb(): Int = ThemeMath.accentFromRgb(custom, bgRgb()).second

    /** Farbe 2: Voreinstellung, die gewählte eigene Farbe 2 oder automatisch. */
    fun highRgb(): Int = when {
        accentIdx in ACCENTS.indices -> ACCENTS[accentIdx].highlight
        custom2 >= 0 -> custom2
        else -> autoHighRgb()
    }

    private fun c(rgb: Int) = Color(0xFF000000.toInt() or rgb)

    val bg: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].bg)
    val surf: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].surf)
    val surf2: Color get() = c(BGS[bgIdx.coerceIn(0, BGS.lastIndex)].surf2)
    val accent1: Color get() = c(fillRgb())
    val accent2: Color get() = c(highRgb())
    /** Schrift auf Farbe-1-Flächen (Weiß oder Navy, je nachdem, was lesbar ist). */
    val onAccent: Color get() = c(ThemeMath.onAccent(fillRgb()))

    /** Farben eines (auch nur probehalber gewählten) Designs – für die Vorschau in den Einstellungen, ändert nichts an der App. */
    fun palette(accent: Int = accentIdx, cust: Int = custom, cust2: Int = custom2, bgI: Int = bgIdx): LookPalette {
        val b = BGS[bgI.coerceIn(0, BGS.lastIndex)]
        val fill = if (accent in ACCENTS.indices) ACCENTS[accent].fill else cust
        val high = when {
            accent in ACCENTS.indices -> ACCENTS[accent].highlight
            cust2 >= 0 -> cust2
            else -> ThemeMath.accentFromRgb(cust, b.bg).second
        }
        return LookPalette(c(b.bg), c(b.surf), c(b.surf2), c(fill), c(high), c(ThemeMath.onAccent(fill)))
    }

    fun reset() { accentIdx = DEFAULT_ACCENT; custom = BRAND_GREEN; custom2 = -1; bgIdx = DEFAULT_BG; motion = 2; uiScale = DEFAULT_SCALE }
}

/** Fokus-Vergrößerung, abhängig von der gewählten Animationsstufe. */
fun focusScale(target: Float): Float = ThemeMath.focusScale(target, Look.motion)

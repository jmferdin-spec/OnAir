package com.ipvio.stream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Allgemeine App-Einstellungen: Start, Beenden, Aktualisierung. Eigene Datei, damit sie beim Zurücksetzen getrennt behandelt werden. */
object General {
    private const val PREFS = "ipvio_general"
    var autoStart by mutableStateOf(false)
    var startTab by mutableIntStateOf(0)
    var resumeLive by mutableStateOf(false)
    /** Zuletzt gesehenen Film / zuletzt gesehene Serie beim Start an der gemerkten Stelle fortsetzen. */
    var resumeVod by mutableStateOf(false)
    var confirmExit by mutableStateOf(true)
    /** Playlist-Aktualisierung: 1 = alle X Stunden, 2 = täglich, 3 = wöchentlich, 4 = nur von Hand (OnAir: 0 „bei jedem Start“ entfällt) */
    var refreshMode by mutableIntStateOf(1)
    var refreshInterval by mutableIntStateOf(12)
    var refreshHour by mutableIntStateOf(4)
    var refreshMinute by mutableIntStateOf(0)
    var refreshDow by mutableIntStateOf(java.util.Calendar.MONDAY)
    var lastRefresh by mutableStateOf(0L)
    /** Sprache: "" = Gerät, sonst Code aus Lang.CODES */
    var lang by mutableStateOf("")
    /** Zeitzone: "" = Systemzeit des Geräts */
    var tzId by mutableStateOf("")
    /** Zeitformat: 0 = System, 1 = 24 Stunden, 2 = 12 Stunden */
    var timeFmt by mutableIntStateOf(0)
    var sys24 by mutableStateOf(true)
    var fontIdx by mutableIntStateOf(0)
    var textScaleIdx by mutableIntStateOf(1)
    /** Standard-Bildformat im Player (Index in [ASPECT_NAMES]). */
    var defaultAspect by mutableIntStateOf(0)
    /**
     * Bildformate (Runde 14). 0–2 unterscheiden sich nur, wenn das Bild ein anderes Seitenverhältnis als der Fernseher hat
     * (bei 16:9-Sendern sah man daher keinen Unterschied). 3–5 ändern das Bild immer sichtbar.
     */
    val ASPECT_NAMES = listOf("Einpassen", "Bildschirm füllen", "Strecken", "Zoom 115 %", "Zoom 133 %", "4:3")
    /** Kartengröße in den Reihen (Knopf „Ansicht“ oben): 0 = klein, 1 = normal, 2 = groß. */
    var cardSize by mutableIntStateOf(2)
    /** Titel pro Zeile in Reihen, Raster und Kategorien (Knopf „Ansicht“): 0 = automatisch nach Kartengröße. */
    var perRow by mutableIntStateOf(0)
    /** Titel pro Zeile in der Kino-Ansicht (getrennt vom Raster, 0 = Automatisch) */
    var kinoPerRow by mutableIntStateOf(0)
    /** Vorschau beim Markieren eines Titels in den Reihen: 0 = Infos auf dem Poster (Karte bleibt, nichts verschiebt sich), 1 = breites Szenenbild (alt). */
    var previewStyle by mutableIntStateOf(0)
    /** Bilder der Film-/Serienkarten: 0 = Cover (Hochformat), 1 = Szenenbild (Querformat). */
    var cardArt by mutableIntStateOf(0)
    /** Wechselzeit des Start-Banners in Sekunden, 0 = nicht automatisch wechseln */
    var heroSec by mutableIntStateOf(9)

    fun plan() = UpdatePlan(refreshMode, refreshInterval, refreshHour, refreshMinute, refreshDow)
    fun zone(): java.util.TimeZone = SettingsLogic.zoneOf(tzId)

    /**
     * Schriften (Runde 17): Outfit (Standard, eingebaut, geometrisch und cinematisch), Inter (eingebaut, sehr gut lesbar auf
     * Distanz) und die Systemschriften des Geräts. Beide eingebauten Schriften: SIL Open Font License (OFL_*.txt im Projekt).
     */
    val FONTS = listOf("Outfit", "Inter", "System", "Kompakt", "Serif", "Leicht", "Monospace")
    private val FONT_FAMILIES = listOf(null, null, null, "sans-serif-condensed", "serif", "sans-serif-light", "monospace")
    private val familyCache = arrayOfNulls<FontFamily>(7)
    private val OUTFIT by lazy {
        FontFamily(Font(R.font.outfit_regular, FontWeight.Normal), Font(R.font.outfit_bold, FontWeight.Bold))
    }
    private val INTER by lazy {
        FontFamily(
            Font(R.font.inter_regular, FontWeight.Normal), Font(R.font.inter_medium, FontWeight.Medium),
            Font(R.font.inter_semibold, FontWeight.SemiBold), Font(R.font.inter_bold, FontWeight.Bold),
            Font(R.font.inter_extrabold, FontWeight.ExtraBold)
        )
    }
    val TEXT_SCALES = listOf("Klein", "Normal", "Groß", "Sehr groß")
    private val TEXT_FACTORS = floatArrayOf(0.9f, 1f, 1.15f, 1.3f)
    fun textFactor(i: Int = textScaleIdx): Float = TEXT_FACTORS[i.coerceIn(0, TEXT_FACTORS.lastIndex)]

    /** Schriftfamilie der Auswahl. Es sind Systemschriften, die jedes Android-Gerät mitbringt. */
    fun fontFamily(idx: Int = fontIdx): FontFamily? {
        val i = idx.coerceIn(0, FONTS.lastIndex)
        if (i == 0) return OUTFIT
        if (i == 1) return INTER
        val name = FONT_FAMILIES[i] ?: return null
        return familyCache[i] ?: FontFamily(
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold)
                .map { Font(DeviceFontFamilyName(name), it) }
        ).also { familyCache[i] = it }
    }

    /** Einmal pro Prozess: Startaktionen (letzten Sender abspielen) nicht bei jedem Zurückkehren wiederholen. */
    @Volatile var startupHandled = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(c: Context) {
        val p = prefs(c)
        autoStart = p.getBoolean("auto_start", false)
        startTab = p.getInt("start_tab", 0).coerceIn(0, 4)
        resumeLive = p.getBoolean("resume_live", false)
        resumeVod = p.getBoolean("resume_vod", false)
        confirmExit = p.getBoolean("confirm_exit", true)
        // OnAir: "at every start" (0) is gone (provider rate limits) – old setting becomes "every 12 hours"; intervals ≥ 12 h
        refreshMode = p.getInt("refresh_mode", 1).let { if (it == 0) 1 else it }.coerceIn(1, 4)
        refreshInterval = p.getInt("refresh_interval", 12).coerceIn(12, 168)
        refreshHour = p.getInt("refresh_hour", 4).coerceIn(0, 23)
        refreshMinute = p.getInt("refresh_minute", 0).coerceIn(0, 59)
        refreshDow = p.getInt("refresh_dow", java.util.Calendar.MONDAY).coerceIn(1, 7)
        lastRefresh = p.getLong("last_refresh", 0L)
        lang = p.getString("lang", "") ?: ""
        tzId = p.getString("tz_id", "") ?: ""
        timeFmt = p.getInt("time_fmt", 0).coerceIn(0, 2)
        sys24 = android.text.format.DateFormat.is24HourFormat(c)
        // Neue Schriftliste (Runde 17): eigener Schlüssel. Wer früher eine andere Systemschrift als „Standard“ gewählt hatte, behält sie.
        fontIdx = if (p.contains("font2")) p.getInt("font2", 0).coerceIn(0, FONTS.lastIndex)
            else p.getInt("font", 0).let { old -> if (old in 1..4) old + 2 else 0 }
        textScaleIdx = p.getInt("text_scale", 1).coerceIn(0, TEXT_SCALES.lastIndex)
        defaultAspect = p.getInt("aspect", 0).coerceIn(0, ASPECT_NAMES.lastIndex)
        heroSec = p.getInt("hero_sec", 9).coerceIn(0, 60)
        cardSize = p.getInt("card_size", 2).coerceIn(0, 2)
        perRow = p.getInt("per_row", 0).let { if (it == 0) 0 else it.coerceIn(3, 14) }
        kinoPerRow = p.getInt("kino_per_row", 0).let { if (it == 0) 0 else it.coerceIn(3, 14) }
        cardArt = p.getInt("card_art", 0).coerceIn(0, 1)
        previewStyle = p.getInt("preview_style", 0).coerceIn(0, 1)
        Parental.init(c)
        LangFilter.init(c)
        PlayerLocale.locale = Lang.locale()
    }

    fun setAutoStart(c: Context, v: Boolean) { autoStart = v; prefs(c).edit().putBoolean("auto_start", v).apply() }
    fun setStartTab(c: Context, v: Int) { startTab = v.coerceIn(0, 4); prefs(c).edit().putInt("start_tab", startTab).apply() }
    fun setResumeLive(c: Context, v: Boolean) { resumeLive = v; prefs(c).edit().putBoolean("resume_live", v).apply() }
    fun setResumeVod(c: Context, v: Boolean) { resumeVod = v; prefs(c).edit().putBoolean("resume_vod", v).apply() }
    fun setConfirmExit(c: Context, v: Boolean) { confirmExit = v; prefs(c).edit().putBoolean("confirm_exit", v).apply() }
    fun setPlan(c: Context, mode: Int = refreshMode, interval: Int = refreshInterval, hour: Int = refreshHour, minute: Int = refreshMinute, dow: Int = refreshDow) {
        refreshMode = mode.coerceIn(1, 4); refreshInterval = interval.coerceIn(12, 168)   // OnAir: see init
        refreshHour = hour.coerceIn(0, 23); refreshMinute = minute.coerceIn(0, 59); refreshDow = dow.coerceIn(1, 7)
        prefs(c).edit().putInt("refresh_mode", refreshMode).putInt("refresh_interval", refreshInterval).putInt("refresh_hour", refreshHour)
            .putInt("refresh_minute", refreshMinute).putInt("refresh_dow", refreshDow).apply()
        Updater.apply(c)
    }
    fun setLang(c: Context, v: String) { lang = v; prefs(c).edit().putString("lang", v).apply(); PlayerLocale.locale = Lang.locale() }
    fun setTz(c: Context, id: String) { tzId = id; prefs(c).edit().putString("tz_id", id).apply() }
    fun setTimeFmt(c: Context, v: Int) { timeFmt = v.coerceIn(0, 2); prefs(c).edit().putInt("time_fmt", timeFmt).apply() }
    fun setFont(c: Context, v: Int) { fontIdx = v.coerceIn(0, FONTS.lastIndex); prefs(c).edit().putInt("font2", fontIdx).apply() }
    fun setTextScale(c: Context, v: Int) { textScaleIdx = v.coerceIn(0, TEXT_SCALES.lastIndex); prefs(c).edit().putInt("text_scale", textScaleIdx).apply() }
    fun setAspect(c: Context, v: Int) { defaultAspect = v.coerceIn(0, ASPECT_NAMES.lastIndex); prefs(c).edit().putInt("aspect", defaultAspect).apply() }
    fun setKinoPerRow(c: Context, v: Int) { kinoPerRow = if (v == 0) 0 else v.coerceIn(3, 14); prefs(c).edit().putInt("kino_per_row", kinoPerRow).apply() }
    fun setPerRow(c: Context, v: Int) { perRow = if (v == 0) 0 else v.coerceIn(3, 14); prefs(c).edit().putInt("per_row", perRow).apply() }
    fun setCardView(c: Context, size: Int, art: Int) {
        cardSize = size.coerceIn(0, 2); cardArt = art.coerceIn(0, 1)
        prefs(c).edit().putInt("card_size", cardSize).putInt("card_art", cardArt).apply()
    }
    fun setPreviewStyle(c: Context, v: Int) { previewStyle = v.coerceIn(0, 1); prefs(c).edit().putInt("preview_style", previewStyle).apply() }
    fun setHeroSec(c: Context, v: Int) { heroSec = v.coerceIn(0, 60); prefs(c).edit().putInt("hero_sec", heroSec).apply() }
    fun markRefreshed(c: Context) { lastRefresh = System.currentTimeMillis(); prefs(c).edit().putLong("last_refresh", lastRefresh).apply() }

    fun resetAll(c: Context) {
        // Jugendschutz (PIN) bleibt beim Zurücksetzen bestehen, sonst wäre er mit einem Klick umgehbar
        val keep = prefs(c).all.filterKeys { it.startsWith("parental_") }
        val e = prefs(c).edit().clear()
        for ((k, v) in keep) when (v) { is Boolean -> e.putBoolean(k, v); is String -> e.putString(k, v) }
        e.apply(); init(c); Updater.apply(c)
    }

    /** Wird nach dem Systemstart vom BootReceiver gefragt. */
    fun autoStartEnabled(c: Context): Boolean = prefs(c).getBoolean("auto_start", false)

    // ---- Speicher ----
    fun sizeOf(f: File): Long = if (!f.exists()) 0L else if (f.isFile) f.length() else (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L)

    class Area(val name: String, val note: String, val files: (Context) -> List<File>)

    val AREAS = listOf(
        Area("Titel-Infos", "Cover, Beschreibungen, Bewertungen") { listOf(File(it.filesDir, "meta3")) },
        Area("Besetzung", "Schauspieler, Regie, ähnliche Titel") { listOf(File(it.filesDir, "credits1")) },
        Area("Playlist-Zwischenspeicher", "Schneller Start ohne Wartezeit") { c -> c.filesDir.listFiles { f -> f.name.startsWith("pl_") }?.toList() ?: emptyList() },
        Area("Bilder", "Zwischengespeicherte Cover und Hintergründe") { listOf(File(it.cacheDir, "image_cache")) }
    )

    /** Einmalig: Dateien früherer Versionen löschen (alter IMDb-Datensatz, rund 20 MB). Läuft im Hintergrund. */
    fun cleanupLegacy(c: Context) {
        val p = prefs(c)
        if (p.getInt("cleanup_v", 0) >= 1) return
        listOf("imdb_ratings.bin", "imdb_ratings.bin.tmp").forEach { runCatching { File(c.filesDir, it).delete() } }
        p.edit().putInt("cleanup_v", 1).apply()
    }

    fun areaSize(c: Context, a: Area): Long = a.files(c).sumOf { sizeOf(it) }
    fun clearArea(c: Context, a: Area) { a.files(c).forEach { runCatching { it.deleteRecursively() } } }

    fun clearHistory(c: Context) {
        History.ids.clear()
        Profiles.sp(c, "ipvio_hist").edit().clear().apply()
    }

    fun clearResume(c: Context) {
        WatchStore.entries.clear()
        Profiles.sp(c, "ipvio_watch").edit().clear().apply()
        EpProgress.clear(c)
    }
}

/** Startet die App nach dem Einschalten des Geräts, wenn der Nutzer es in den Einstellungen erlaubt hat. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = i.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        // Erinnerungen an Sendungen: Wecker nach dem Neustart neu stellen
        runCatching { Reminders.schedule(c) }
        if (!General.autoStartEnabled(c)) return
        try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
        }
    }
}

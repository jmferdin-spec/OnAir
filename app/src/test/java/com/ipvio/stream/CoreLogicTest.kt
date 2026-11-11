package com.ipvio.stream

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests für Kernlogik ohne Oberfläche: Playlist-Einlesen, Farben/Kontrast, Menü-Regeln, Fehlertexte. */
class CoreLogicTest {

    // ---------------------------------------------------------------- Playlist
    private val sample = """#EXTM3U url-tvg="http://a.b/x.xml,http://c.d/y.xml"
#EXTINF:-1 tvg-id="ard" tvg-logo="http://l/a.png" group-title="DE | News",DE | Das Erste HD
http://h/live/u/p/1.ts
#EXTINF:-1 group-title="Filme",Matrix (1999) 4K
http://h/movie/u/p/2.mp4
#EXTINF:-1 group-title="Serien",Show S01E02 FHD
http://h/series/u/p/3.mkv
#EXTINF:-1 group-title="Serien",Show S01E01
http://h/series/u/p/4.mkv
#EXTINF:-1 group-title="x",*** Trenner ***
http://h/x
"""

    @Test fun playlist_streamUndTextLiefernDasGleiche() {
        val a = M3uParser.parse(sample)
        val b = M3uParser.parseStream(java.io.StringReader(sample))
        assertEquals(a.map { it.id }, b.items.map { it.id })
    }

    @Test fun playlist_epgAdressenAusDemKopf() {
        val b = M3uParser.parseStream(java.io.StringReader(sample))
        assertEquals(listOf("http://a.b/x.xml", "http://c.d/y.xml"), b.epgUrls)
    }

    @Test fun playlist_artenUndTrenner() {
        val items = M3uParser.parse(sample)
        assertEquals(1, items.count { it.kind == Kind.LIVE })
        assertEquals(1, items.count { it.kind == Kind.MOVIE })
        assertEquals(1, items.count { it.kind == Kind.SERIES })
        assertEquals(3, items.size) // der Trenner "*** Trenner ***" fällt weg
    }

    @Test fun playlist_folgenSortiert() {
        val s = M3uParser.parse(sample).first { it.kind == Kind.SERIES }
        assertEquals(listOf(1, 2), s.episodes.map { it.num })
    }

    @Test fun playlist_leereOderKaputteListeStuerztNichtAb() {
        assertEquals(0, M3uParser.parse("").size)
        assertEquals(0, M3uParser.parse("kein m3u\nhttp://x\n").size)
        assertEquals(0, M3uParser.parseStream(java.io.StringReader("#EXTINF:-1,Name ohne URL\n")).items.size)
    }

    @Test fun item_cacheSchluesselBleibtBeiCopy() {
        val m = M3uParser.parse(sample).first { it.kind == Kind.MOVIE }
        assertEquals("MOVIE|matrix|1999", m.mk)
        assertEquals(m.mk, m.copy(logo = "x").mk)
    }

    // ---------------------------------------------------------------- Farben
    @Test fun farben_weissAufSchwarzIst21() = assertEquals(21.0, ThemeMath.contrast(0xFFFFFF, 0x000000), 0.01)

    @Test fun farben_voreinstellungenSindLesbar() {
        for (p in Look.ACCENTS) {
            assertTrue(p.name, ThemeMath.contrast(ThemeMath.onAccent(p.fill), p.fill) >= 4.0)
            for (bg in Look.BGS) assertTrue(p.name + " / " + bg.name, ThemeMath.contrast(p.highlight, bg.bg) >= 4.5)
        }
    }

    @Test fun farben_eigeneFarbeIstFuerJedenFarbtonLesbar() {
        for (bg in Look.BGS) for (h in 0 until 360) {
            val (fill, hi) = ThemeMath.accentFromHue(h, bg.bg)
            assertTrue("Fläche h=$h", ThemeMath.contrast(0xFFFFFF, fill) >= 4.5)
            assertTrue("Highlight h=$h auf ${bg.name}", ThemeMath.contrast(hi, bg.bg) >= 4.5)
        }
    }

    @Test fun farben_beliebigeRgbFarbeWirdLesbar() {
        val samples = Look.SWATCHES + listOf(0x000000, 0xFFFFFF, 0x808080, 0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00, 0x123456)
        val grid = ArrayList<Int>(samples)
        for (r in 0..255 step 51) for (g in 0..255 step 51) for (b in 0..255 step 51) grid.add((r shl 16) or (g shl 8) or b)
        for (bg in Look.BGS) for (c in grid) {
            val (fill, hi) = ThemeMath.accentFromRgb(c, bg.bg)
            assertTrue("Fläche " + ThemeMath.hexOf(c), ThemeMath.contrast(0xFFFFFF, fill) >= 4.5)
            assertTrue("Highlight " + ThemeMath.hexOf(c) + " auf " + bg.name, ThemeMath.contrast(hi, bg.bg) >= 4.5)
        }
    }

    @Test fun farben_firmenfarbenSindStandard() {
        // OnAir: red on navy is the default; white text on the red fill, the red highlight readable on navy
        assertEquals(Look.BRAND_RED, Look.ACCENTS[0].fill)
        assertEquals(0x0B0F19, Look.BGS[0].bg)
        assertEquals(0xFFFFFF, ThemeMath.onAccent(Look.BRAND_RED))
        assertTrue(ThemeMath.contrast(0xFFFFFF, Look.BRAND_RED) >= 4.5)
        assertTrue(ThemeMath.contrast(Look.BRAND_RED_HI, 0x0B0F19) >= 4.5)
        for (bg in Look.BGS) assertTrue(bg.name, ThemeMath.contrast(Look.BRAND_RED_HI, bg.bg) >= 4.5)
        // upstream green remains available as a preset (checks below still apply)
        // Neon-Grün bekommt dunkle Schrift, dunkle Flächen weiterhin weiße
        assertEquals(ThemeMath.INK, ThemeMath.onAccent(Look.BRAND_GREEN))
        assertEquals(0xFFFFFF, ThemeMath.onAccent(0x7C5CFF))
        assertTrue(ThemeMath.contrast(ThemeMath.onAccent(Look.BRAND_GREEN), Look.BRAND_GREEN) >= 7.0)
        assertTrue(ThemeMath.contrast(Look.BRAND_GREEN, 0x0B0F19) >= 7.0)   // Grün als Schrift auf Navy
        for (rgb in Look.SWATCHES) assertTrue(ThemeMath.hexOf(rgb), ThemeMath.contrast(ThemeMath.onAccent(rgb), rgb) >= 3.0)
    }

    @Test fun farben_hexEingabe() {
        assertEquals(0x2F6FE0, ThemeMath.parseHex("#2F6FE0"))
        assertEquals(0x2F6FE0, ThemeMath.parseHex(" 2f6fe0 "))
        assertEquals(0x2266EE, ThemeMath.parseHex("26E"))
        assertNull(ThemeMath.parseHex("zzz000"))
        assertNull(ThemeMath.parseHex("12345"))
        assertEquals("#00BFA5", ThemeMath.hexOf(0x00BFA5))
    }

    @Test fun farben_rgbHsvRundreise() {
        val h = ThemeMath.rgbToHsv(0xFF0000)
        assertEquals(0f, h[0], 0.01f); assertEquals(1f, h[1], 0.01f); assertEquals(1f, h[2], 0.01f)
        assertEquals(120f, ThemeMath.rgbToHsv(0x00FF00)[0], 0.01f)
        assertEquals(240f, ThemeMath.rgbToHsv(0x0000FF)[0], 0.01f)
    }

    @Test fun animation_stufen() {
        assertEquals(1f, ThemeMath.focusScale(1.08f, 0), 0f)
        assertEquals(1.032f, ThemeMath.focusScale(1.08f, 1), 0.0001f)
        assertEquals(1.08f, ThemeMath.focusScale(1.08f, 2), 0f)
    }

    @Test fun groesse_autoSkaliertBreiteGeraeteAufEntwurfsbreite() {
        assertEquals(1f, ThemeMath.uiFactor(0, 960), 0f)
        assertEquals(1.3333f, ThemeMath.uiFactor(0, 1280), 0.001f)
        assertEquals(2f, ThemeMath.uiFactor(0, 5000), 0f)
        assertEquals(1f, ThemeMath.uiFactor(2, 1280), 0f)
    }

    // ---------------------------------------------------------------- Menü
    @Test fun menue_standard() = assertEquals(listOf(0, 3, 1, 2, 6, 7, 8, 9, 4, 5), MenuLogic.parseOrder(null))

    @Test fun menue_alteReihenfolge_neueBereicheVorEinstellungen() {
        assertEquals(listOf(0, 1, 2, 3, 4, 6, 7, 8, 9, 5), MenuLogic.parseOrder("0,1,2,3,4,5"))
        assertEquals(listOf(6, 7, 8, 9, 5, 0, 1, 2, 3, 4), MenuLogic.parseOrder("5,0,1,2,3,4"))
        assertEquals(listOf(0, 4, 1, 2, 3, 6, 7, 8, 9, 5), MenuLogic.parseOrder("0,4,1,2,3,6,5"))
    }

    @Test fun menue_kaputteWerteWerdenRepariert() {
        val o = MenuLogic.parseOrder("10,3,3,x,-1,2")
        assertEquals(listOf(3, 2, 0, 1, 4, 6, 7, 8, 9, 5), o)
        assertEquals(MenuLogic.COUNT, o.toSet().size)
    }

    @Test fun menue_startUndEinstellungenBleibenImmerSichtbar() {
        assertEquals(listOf(1, 2, 6), MenuLogic.parseHidden("0,1,2,5,6,19"))
        assertEquals(emptyList<Int>(), MenuLogic.toggle(emptyList(), 0))
        assertEquals(emptyList<Int>(), MenuLogic.toggle(emptyList(), 5))
        val all = MenuLogic.visible(MenuLogic.DEFAULT_ORDER, listOf(1, 2, 3, 4, 6, 7, 8, 9))
        assertEquals(listOf(0, 5), all)
    }

    @Test fun menue_verschiebenUndGrenzen() {
        val o = MenuLogic.DEFAULT_ORDER
        assertEquals(listOf(3, 0, 1, 2, 6, 7, 8, 9, 4, 5), MenuLogic.move(o, 0, 1))
        assertEquals(o, MenuLogic.move(o, 0, -1))
        assertEquals(o, MenuLogic.move(o, 5, 1))
        assertEquals(o, MenuLogic.move(o, 99, 1))
    }

    @Test fun menue_umschaltenFunktioniert() {
        val h = MenuLogic.toggle(emptyList(), 3)
        assertEquals(listOf(3), h)
        assertEquals(emptyList<Int>(), MenuLogic.toggle(h, 3))
    }

    // ---------------------------------------------------------------- Fehlertexte
    @Test fun fehler_zugangAbgelehnt() {
        assertTrue(playbackErrorText(0, 401, true).contains("Zugang abgelehnt"))
        assertTrue(isPermanentHttp(403))
        assertFalse(isPermanentHttp(503))
    }

    @Test fun fehler_senderNichtMehrVorhanden() {
        assertTrue(playbackErrorText(0, 404, true).contains("Sender"))
        assertTrue(playbackErrorText(0, 404, false).contains("Titel"))
    }

    @Test fun fehler_keineVerbindungUndFormat() {
        assertTrue(playbackErrorText(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, 0, true).contains("Keine Verbindung"))
        assertTrue(playbackErrorText(PlaybackException.ERROR_CODE_DECODING_FAILED, 0, false).contains("Videoformat"))
        assertEquals("Wiedergabe nicht möglich.", playbackErrorText(-1, 0, false))
    }

    @Test fun fehler_textZeigtNieEineAdresse() {
        for (st in listOf(0, 401, 404, 429, 500, 418)) assertFalse(playbackErrorText(1, st, true).contains("http"))
    }
}

package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Einlesen (Sprache, Speicher sparen) und Zwischenspeicher der Playlist. */
class PlaylistCacheTest {
    private val m3u = """#EXTM3U
#EXTINF:-1 tvg-id="ard" group-title="DE | News",DE: Das Erste HD
http://h/live/u/p/1.ts
#EXTINF:-1 group-title="TR | Ulusal",TRT 1
http://h/live/u/p/2.ts
#EXTINF:-1 group-title="Sport" xc-category-id="77",beIN Sports 1
http://h/live/u/p/3.ts
#EXTINF:-1 group-title="VOD | DE | Action",Matrix (1999)
http://h/movie/u/p/4.mp4
#EXTINF:-1 group-title="Netflix",EN - Glass Onion (2022)
http://h/movie/u/p/5.mp4
#EXTINF:-1 group-title="DE | Serien",Show S01E01
http://h/series/u/p/6.mkv
#EXTINF:-1 group-title="DE | Serien",Show S01E02
http://h/series/u/p/7.mkv
#EXTINF:-1 group-title="DE | Serien",Show S01E02 FHD
http://h/series/u/p/8.mkv
"""
    private val items = M3uParser.parse(m3u)

    @Test fun einlesen_spracheJeEintrag() {
        val byTitle = items.associate { it.title to it.lang }
        assertEquals("de", byTitle["DE: Das Erste HD"])
        assertEquals("tr", byTitle["TRT 1"])
        assertEquals(LangTagLogic.UNKNOWN, byTitle["beIN Sports 1"])
        assertEquals("de", byTitle["Matrix"])
        assertEquals("en", byTitle["Glass Onion"])
        assertEquals("de", byTitle["Show"])
    }

    @Test fun einlesen_einzelneQuelleOhneListe_mehrereMitListe() {
        val show = items.first { it.kind == Kind.SERIES }
        val e1 = show.episodes.first { it.num == 1 }
        val e2 = show.episodes.first { it.num == 2 }
        assertTrue(e1.variants.isEmpty())
        assertEquals("http://h/series/u/p/6.mkv", pickVariant(e1, null, null, null).url)
        assertEquals(2, e2.variants.size)
        assertEquals("FHD", e2.quality)          // beste Quelle zuerst
        assertEquals("http://h/series/u/p/8.mkv", e2.url)
    }

    @Test fun einlesen_gleicheKategorieNurEinmalImSpeicher() {
        val a = M3uParser.parse(m3u + "#EXTINF:-1 group-title=\"DE | News\",ZDF\nhttp://h/live/u/p/9.ts\n")
        val g = a.filter { it.group == "DE | News" }
        assertEquals(2, g.size)
        assertSame(g[0].group, g[1].group)
    }

    private fun roundTrip(hidden: Set<String>): PlaylistCacheIO.Cached? {
        val out = ByteArrayOutputStream()
        PlaylistCacheIO.write(out, "http://x/get.php", items, listOf("http://epg"), mapOf("DE | News" to 0))
        return PlaylistCacheIO.read(ByteArrayInputStream(out.toByteArray()), "http://x/get.php", hidden)
    }

    @Test fun cache_rundreiseOhneFilter() {
        val c = roundTrip(emptySet())!!
        assertEquals(items, c.items)
        // OnAir: the Xtream category_id survives the cache (version 6)
        assertEquals("77", c.items.single { it.title == "beIN Sports 1" }.catId)
        assertEquals("Sport", c.items.single { it.title == "beIN Sports 1" }.group)
        assertEquals(listOf("http://epg"), c.epg)
        assertEquals(mapOf("DE | News" to 0), c.order)
        assertEquals(items.size, c.counts.values.sum())
    }

    @Test fun cache_ausgeblendeteSprachenWerdenUebersprungenAberGezaehlt() {
        val c = roundTrip(setOf("tr", "en", LangTagLogic.UNKNOWN))!!
        assertTrue(c.items.all { it.lang == "de" })
        assertEquals(items.count { it.lang == "de" }, c.items.size)
        assertEquals(1, c.counts["tr"])
        assertEquals(1, c.counts["en"])
        assertEquals(items.size, c.counts.values.sum())
        // nach dem Überspringen stimmen die folgenden Einträge noch (Serie mit Varianten kommt zuletzt)
        assertEquals(2, c.items.first { it.kind == Kind.SERIES }.episodes.size)
    }

    @Test fun cache_andereAdresseOderVersion() {
        val out = ByteArrayOutputStream()
        PlaylistCacheIO.write(out, "http://a", items, emptyList(), emptyMap())
        assertNull(PlaylistCacheIO.read(ByteArrayInputStream(out.toByteArray()), "http://b", emptySet()))
        val bad = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(99) }
        assertNull(PlaylistCacheIO.read(ByteArrayInputStream(bad.toByteArray()), "http://a", emptySet()))
    }

    /** Version 3 (ältere App, ohne Sprache) wird weiter gelesen; die Sprache wird aus Kategorie/Name berechnet. */
    @Test fun cache_version3WirdGelesen() {
        val out = ByteArrayOutputStream()
        val o = DataOutputStream(out)
        fun str(s: String?) { if (s == null) o.writeInt(-1) else { val b = s.toByteArray(); o.writeInt(b.size); o.write(b) } }
        o.writeInt(3); str("http://a"); o.writeInt(0); o.writeInt(0); o.writeInt(2)
        // Live-Sender in türkischer Kategorie
        str("1"); o.writeInt(Kind.LIVE.ordinal); str("TRT 1"); o.writeInt(-1); str("http://h/1"); str(null); str("TR | Ulusal"); str(null); str(null); o.writeInt(0)
        // Film in deutscher Kategorie mit einer Einzel-Quelle als Liste (alte Form)
        str("2"); o.writeInt(Kind.SERIES.ordinal); str("Show"); o.writeInt(-1); str(""); str(null); str("DE | Serien"); str(null); str(null); o.writeInt(1)
        o.writeInt(1); o.writeInt(1); str("Episode 1"); str("http://h/e1"); str(null); o.writeInt(1); str("http://h/e1"); str("DE"); str(null); str("DE")
        val c = PlaylistCacheIO.read(ByteArrayInputStream(out.toByteArray()), "http://a", setOf("tr"))!!
        assertEquals(1, c.items.size)
        assertEquals("de", c.items[0].lang)
        assertTrue(c.items[0].episodes[0].variants.isEmpty())
        assertEquals(mapOf("tr" to 1, "de" to 1), c.counts)
    }
}

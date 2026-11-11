package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Invented sample data only – no real provider data or credentials. */
class XtreamApiLogicTest {
    private val getUrl = "http://iptv.example.test:8080/get.php?username=demo&password=p%40ss+w&type=m3u_plus&output=ts"
    private val a = XtreamApiLogic.acctOf(getUrl)!!

    @Test fun accountFromGetPhp() {
        assertEquals("http://iptv.example.test:8080", a.host)
        assertEquals("demo", a.user)
        assertEquals("p%40ss+w", a.pass)
        assertNull(XtreamApiLogic.acctOf("http://h/list.m3u"))
        assertNull(XtreamApiLogic.acctOf("http://h/get.php?username=u"))
        // Same form PlaylistLogic builds
        assertNotNull(XtreamApiLogic.acctOf(PlaylistLogic.xtreamUrl("h.test:80", "u", "p")!!))
    }

    @Test fun apiUrls() {
        assertEquals("http://iptv.example.test:8080/player_api.php?username=demo&password=p%40ss+w", XtreamApiLogic.apiUrl(a))
        assertEquals("http://iptv.example.test:8080/player_api.php?username=demo&password=p%40ss+w&action=get_live_streams",
            XtreamApiLogic.apiUrl(a, "get_live_streams"))
    }

    @Test fun streamUrlsUsePathEncoding() {
        assertEquals("http://iptv.example.test:8080/live/demo/p%40ss%20w/101.ts", XtreamApiLogic.streamUrl(a, "live", "101", "ts"))
        assertEquals("http://iptv.example.test:8080/movie/demo/p%40ss%20w/7.mkv", XtreamApiLogic.streamUrl(a, "movie", "7", "mkv"))
    }

    @Test fun liveExtension() {
        assertEquals("ts", XtreamApiLogic.liveExt(emptyList()))
        assertEquals("ts", XtreamApiLogic.liveExt(listOf("m3u8", "ts", "rtmp")))
        assertEquals("m3u8", XtreamApiLogic.liveExt(listOf("m3u8")))
    }

    @Test fun accountCheck() {
        val ok = XtreamApiLogic.account("""{"user_info":{"auth":1,"status":"Active","allowed_output_formats":["m3u8","ts"]},"server_info":{}}""")
        assertTrue(ok.ok); assertEquals(listOf("m3u8", "ts"), ok.formats)
        assertFalse(XtreamApiLogic.account("""{"user_info":{"auth":0}}""").ok)
        assertFalse(XtreamApiLogic.account("""[]""").ok)
        assertFalse(XtreamApiLogic.account("<html>404</html>").ok)
    }

    @Test fun categoriesKeepNames() {
        val c = XtreamApiLogic.categories("""[{"category_id":"5","category_name":"US| SPORTS","parent_id":0},{"category_id":9,"category_name":"PPV EVENTS"}]""")
        assertEquals("US| SPORTS", c["5"]); assertEquals("PPV EVENTS", c["9"])
        assertTrue(XtreamApiLogic.categories("not json").isEmpty())
    }

    private val cats = mapOf("5" to "US| SPORTS", "9" to "US| MOVIE CHANNELS", "20" to "Action \"Hits\"", "30" to "Drama")

    @Test fun categoriesKeepServerOrder() {
        val c = XtreamApiLogic.categories("""[{"category_id":"30","category_name":"Z LAST"},{"category_id":"2","category_name":"A FIRST"},{"category_id":"11","category_name":"M MIDDLE"}]""")
        assertEquals(listOf("Z LAST", "A FIRST", "M MIDDLE"), c.values.toList())
    }

    /** OnAir: the sort order comes from the category lists of the download itself (no 3 extra requests). */
    @Test fun categoryOrderFilmsSeriesLiveFirstWins() {
        val o = XtreamApiLogic.categoryOrder(
            vod = listOf("ACTION", "COMEDY"),
            series = listOf("DRAMA", "COMEDY"),
            live = listOf("US| SPORTS", "", "ACTION")
        )
        assertEquals(mapOf("ACTION" to 0, "COMEDY" to 1, "DRAMA" to 0, "US| SPORTS" to 0), o)
        assertTrue(XtreamApiLogic.categoryOrder(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test fun liveEntryLine() {
        val s = XtreamApiLogic.liveEntry(mapOf("stream_id" to "101", "name" to "US: Sports Net HD", "epg_channel_id" to "sportsnet.us",
            "stream_icon" to "http://img.test/s.png", "category_id" to "5", "tv_archive" to "1", "tv_archive_duration" to "3"), cats, a, "ts")!!
        val (inf, url) = s.trimEnd().split("\n")
        assertTrue(inf.startsWith("#EXTINF:-1 "))
        assertTrue(inf.contains("tvg-id=\"sportsnet.us\""))
        assertTrue(inf.contains("group-title=\"US| SPORTS\""))
        assertTrue(inf.contains("catchup=\"xc\" catchup-days=\"3\""))
        assertTrue(inf.endsWith(",US: Sports Net HD"))
        assertEquals("http://iptv.example.test:8080/live/demo/p%40ss%20w/101.ts", url)
        assertNull(XtreamApiLogic.liveEntry(mapOf("name" to "x"), cats, a, "ts"))
    }

    @Test fun unknownCategoryFallsBack() {
        val s = XtreamApiLogic.liveEntry(mapOf("stream_id" to "1", "name" to "X", "category_id" to "999"), cats, a, "ts")!!
        assertTrue(s.contains("group-title=\"${XtreamApiLogic.NO_GROUP}\""))
    }

    @Test fun movieEntryAddsYearAndSanitises() {
        val s = XtreamApiLogic.movieEntry(mapOf("stream_id" to "7", "name" to "The Long Road, Home", "year" to "2021",
            "container_extension" to "mkv", "category_id" to "20"), cats, a)!!
        assertTrue(s.contains("group-title=\"Action 'Hits'\""))
        assertTrue(s.contains(",The Long Road‚ Home (2021)\n"))
        assertTrue(s.endsWith("/movie/demo/p%40ss%20w/7.mkv\n"))
        val noYear = XtreamApiLogic.movieEntry(mapOf("stream_id" to "8", "name" to "Old Film (1999)", "year" to "2001"), cats, a)!!
        assertTrue(noYear.contains(",Old Film (1999)\n"))
        assertTrue(noYear.endsWith(".mp4\n"))
    }

    @Test fun parsedThroughM3uParser() {
        val text = "#EXTM3U\n" +
            XtreamApiLogic.liveEntry(mapOf("stream_id" to "101", "name" to "Sports Net", "epg_channel_id" to "sn.us", "category_id" to "5"), cats, a, "ts") +
            // A live channel in a movie-channel group stays a channel (not a film)
            XtreamApiLogic.liveEntry(mapOf("stream_id" to "102", "name" to "Cinema One", "category_id" to "9"), cats, a, "ts") +
            XtreamApiLogic.movieEntry(mapOf("stream_id" to "7", "name" to "Quiet Harbor", "year" to "2019", "category_id" to "20"), cats, a) +
            XtreamApiLogic.seriesEntry(mapOf("series_id" to "55", "name" to "Night Shift Diaries", "cover" to "http://img.test/c.jpg", "category_id" to "30"), cats, a)
        val p = M3uParser.parseLines(text.lineSequence())
        val live = p.items.filter { it.kind == Kind.LIVE }
        assertEquals(listOf("Sports Net", "Cinema One"), live.map { it.title })
        assertEquals("US| SPORTS", live[0].group)
        assertEquals("sn.us", live[0].tvgId)
        assertEquals("US| MOVIE CHANNELS", live[1].group)
        val movie = p.items.single { it.kind == Kind.MOVIE }
        assertEquals("Quiet Harbor", movie.title); assertEquals(2019, movie.year)
        val show = p.items.single { it.kind == Kind.SERIES }
        assertEquals("Night Shift Diaries", show.title)
        assertEquals("Drama", show.group)
        assertTrue(show.episodes.isEmpty())
        val refs = XtreamApiLogic.refsOf(show.tvgId)
        assertEquals(1, refs.size)
        assertEquals("55", refs[0].seriesId)
        assertEquals("demo", refs[0].acct.user)
        // Provider info / get_series_info can find the account from the reference
        assertNotNull(ProviderLogic.seriesAcct(show))
    }

    @Test fun sameShowFromTwoCategoriesKeepsBothRefs() {
        val text = "#EXTM3U\n" +
            XtreamApiLogic.seriesEntry(mapOf("series_id" to "55", "name" to "EN| Night Shift", "category_id" to "30"), cats, a) +
            XtreamApiLogic.seriesEntry(mapOf("series_id" to "56", "name" to "EN| Night Shift", "category_id" to "30"), cats, a)
        val show = M3uParser.parseLines(text.lineSequence()).items.single()
        assertEquals(listOf("55", "56"), XtreamApiLogic.refsOf(show.tvgId).map { it.seriesId })
    }

    @Test fun episodesFromSeriesInfo() {
        val json = """{"info":{"name":"X"},"episodes":{"1":[{"id":"9001","episode_num":1,"season":1,"container_extension":"mkv"},
            {"id":"9002","episode_num":"2","season":1,"container_extension":"mkv"}],"2":[{"id":"9101","episode_num":1}]}}"""
        val eps = XtreamApiLogic.episodesOf(json)
        assertEquals(3, eps.size)
        assertEquals(listOf(1 to 1, 1 to 2, 2 to 1), eps.map { it.season to it.num }.sortedWith(compareBy({ it.first }, { it.second })))
        assertEquals("mp4", eps.single { it.id == "9101" }.ext)
        // Some panels send episodes as a list of lists
        val nested = XtreamApiLogic.episodesOf("""{"episodes":[[{"id":"1","episode_num":3,"season":4}]]}""").single()
        assertEquals(4 to 3, nested.season to nested.num)
        assertTrue(XtreamApiLogic.episodesOf("""{"episodes":[]}""").isEmpty())
        assertTrue(XtreamApiLogic.episodesOf("broken").isEmpty())
    }

    @Test fun mergeBuildsUrlsAndVariants() {
        val r1 = XtreamApiLogic.parseRef(XtreamApiLogic.ref(XtreamApiLogic.streamUrl(a, "series", "55", XtreamApiLogic.SERIES_EXT), "EN · Drama", null, "EN"))!!
        val r2 = XtreamApiLogic.parseRef(XtreamApiLogic.ref(XtreamApiLogic.streamUrl(a, "series", "56", XtreamApiLogic.SERIES_EXT), "EN · 4K · Drama", "4K", "EN"))!!
        assertEquals("EN · 4K · Drama", r2.label); assertEquals("4K", r2.q); assertEquals("EN", r2.lang)
        val eps = XtreamApiLogic.merge(listOf(
            r1 to listOf(XtreamApiLogic.Ep(1, 2, "11", "mkv"), XtreamApiLogic.Ep(1, 1, "10", "mkv")),
            r2 to listOf(XtreamApiLogic.Ep(1, 1, "20", "mp4"))
        ))
        assertEquals(listOf(1, 2), eps.map { it.num })
        val e1 = eps[0]
        assertEquals(2, e1.variants.size)
        assertEquals("4K", e1.quality)   // highest quality first
        assertEquals("http://iptv.example.test:8080/series/demo/p%40ss%20w/20.mp4", e1.url)
        assertEquals("http://iptv.example.test:8080/series/demo/p%40ss%20w/11.mkv", eps[1].url)
        assertEquals(EpTitles.of(2), eps[1].title)
    }

    @Test fun seriesRefIsNotARealEpisode() {
        assertTrue(XtreamApiLogic.isSeriesRef("http://h/series/u/p/55.${XtreamApiLogic.SERIES_EXT}"))
        assertFalse(XtreamApiLogic.isSeriesRef("http://h/series/u/p/55.mkv"))
        assertTrue(XtreamApiLogic.refsOf(null).isEmpty())
        assertTrue(XtreamApiLogic.refsOf("sn.us").isEmpty())
    }
}

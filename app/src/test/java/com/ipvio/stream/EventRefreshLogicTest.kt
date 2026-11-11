package com.ipvio.stream

import com.ipvio.stream.OnNowLogic.Chan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir: lightweight Events refresh – which categories, which names change, the stored overlay. Invented data only. */
class EventRefreshLogicTest {
    private val base = "http://iptv.example.test:8080/live/demo/p%40ss/"

    @Test fun streamIdFromLiveAddress() {
        assertEquals("4711", EventRefreshLogic.streamIdOf(base + "4711.ts"))
        assertEquals("4711", EventRefreshLogic.streamIdOf(base + "4711.m3u8"))
        assertEquals("4711", EventRefreshLogic.streamIdOf(base + "4711"))
        assertNull(EventRefreshLogic.streamIdOf("http://iptv.example.test:8080/movie/demo/p/7.mkv"))
        assertNull(EventRefreshLogic.streamIdOf("http://other.test/stream.m3u8"))
    }

    @Test fun onlyPpvCategoriesAreAskedForMostChannelsFirst() {
        val chans = (1..80).map { Chan("e$it", "[PPV] EVENTS %02d:".format(it), "EVENTS", catId = "7") } +
            (1..5).map { Chan("d$it", "DAZN PPV $it", "DAZN PPV", catId = "12") } +
            listOf(
                Chan("s1", "PPV 5: Title Night", "US| SPORTS", catId = "3"),     // PPV by name only: its sports category is not reloaded
                Chan("x1", "Sports Net", "US| SPORTS", catId = "3"),
                Chan("n1", "PPV 9", "PAY PER VIEW", catId = null)                // no category id: nothing to ask
            )
        assertEquals(listOf("7", "12"), EventRefreshLogic.categories(chans))
        assertEquals(listOf("7"), EventRefreshLogic.categories(chans, max = 1))
        assertTrue(EventRefreshLogic.categories(listOf(Chan("x", "Sports Net", "US| SPORTS", catId = "3"))).isEmpty())
    }

    @Test fun renamesOnlyWhatChanged() {
        val known = listOf(
            EventRefreshLogic.Known("i1", base + "101.ts", "[PPV] EVENTS 01:"),
            EventRefreshLogic.Known("i2", base + "102.ts", "[PPV] EVENTS 02:"),
            EventRefreshLogic.Known("i3", base + "103.ts", "[PPV] EVENTS 03:"),
            EventRefreshLogic.Known("i4", "http://other.test/x.m3u8", "Other")
        )
        val fresh = mapOf(
            "101" to "[PPV] EVENTS 01: Title Fight Night 9PM ET",   // renamed near show time
            "102" to "[PPV] EVENTS 02:",                            // unchanged
            "103" to "   ",                                         // empty answer: keep the old name
            "999" to "Not in the playlist"
        )
        assertEquals(mapOf("i1" to "[PPV] EVENTS 01: Title Fight Night 9PM ET"), EventRefreshLogic.renames(known, fresh))
        // the parser's cleaning rule is applied before comparing (e.g. "," in names)
        val r = EventRefreshLogic.renames(known, mapOf("102" to "[PPV] EVENTS 02: Ring Kings, Live")) { XtreamApiLogic.name(it) }
        assertEquals("[PPV] EVENTS 02: Ring Kings‚ Live", r["i2"])
        assertTrue(EventRefreshLogic.renames(known, emptyMap()).isEmpty())
    }

    @Test fun overlayRoundTripAndMerge() {
        val m = mapOf("i2" to "B\tname", "i1" to "A\nname")
        val back = EventRefreshLogic.decode(EventRefreshLogic.encode(m))
        assertEquals(mapOf("i1" to "A name", "i2" to "B name"), back)
        assertTrue(EventRefreshLogic.decode(null).isEmpty())
        assertTrue(EventRefreshLogic.decode("garbage").isEmpty())
        val merged = EventRefreshLogic.merge(mapOf("i1" to "old", "i2" to "keep"), mapOf("i1" to "new"))
        assertEquals(mapOf("i1" to "new", "i2" to "keep"), merged)
        assertEquals(3, EventRefreshLogic.merge((1..5).associate { "k$it" to "v" }, emptyMap(), max = 3).size)
    }

    private fun item(id: String, title: String, kind: Kind = Kind.LIVE) = Item(id, kind, title, null, base + "$id.ts", null, "EVENTS")

    @Test fun overlayAppliesToLiveChannelsOnly() {
        val items = listOf(item("1", "[PPV] EVENTS 01:"), item("2", "Movie", Kind.MOVIE), item("3", "[PPV] EVENTS 03:"))
        val out = EventRefreshLogic.apply(items, mapOf("1" to "[PPV] EVENTS 01: Title Fight", "2" to "Not a channel"))
        assertEquals(listOf("[PPV] EVENTS 01: Title Fight", "Movie", "[PPV] EVENTS 03:"), out.map { it.title })
        assertEquals(items.map { it.id }, out.map { it.id })                         // ids (favourites, history) unchanged
        assertSame(items, EventRefreshLogic.apply(items, emptyMap()))               // nothing to do: same list
        assertSame(items, EventRefreshLogic.apply(items, mapOf("3" to "[PPV] EVENTS 03:")))
    }

    @Test fun renamedSlotIsNoLongerAPlaceholder() {
        assertTrue(Chan("a", "[PPV] EVENTS 01:", "EVENTS").ppvPlaceholder)
        assertTrue(!Chan("a", "[PPV] EVENTS 01: Title Fight Night", "EVENTS").ppvPlaceholder)
    }
}

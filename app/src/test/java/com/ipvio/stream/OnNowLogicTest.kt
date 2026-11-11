package com.ipvio.stream

import com.ipvio.stream.OnNowLogic.Chan
import com.ipvio.stream.OnNowLogic.Prog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OnAir Phase 3: "on now" window, progress, classification, sport groups, duplicate merging. Invented data only. */
class OnNowLogicTest {
    private val now = 1_800_000_000L
    private val m = 60L

    private fun prog(title: String, cats: List<String> = emptyList(), ep: String? = null, startMin: Long = -30, lenMin: Long = 60, tvg: String = "x.us") =
        Prog(tvg, title, now + startMin * m, now + (startMin + lenMin) * m, cats, ep)

    private fun chan(name: String, group: String, q: String? = null, id: String = name) = Chan(id, name, group, quality = q)

    private fun cls(title: String, group: String, name: String = "Channel One", cats: List<String> = emptyList(), ep: String? = null, lenMin: Long = 60) =
        OnNowLogic.classify(prog(title, cats, ep, lenMin = lenMin), chan(name, group))

    // ------------------------------------------------------------------ window and progress

    @Test fun onNowWindowEdges() {
        assertTrue(OnNowLogic.isOnNow(now, now + 60, now))          // starts right now
        assertFalse(OnNowLogic.isOnNow(now - 60, now, now))         // ended right now
        assertFalse(OnNowLogic.isOnNow(now + 1, now + 60, now))     // not started yet
        assertTrue(OnNowLogic.isOnNow(now - 60, now + 1, now))
    }

    @Test fun progressMath() {
        assertEquals(0.5f, OnNowLogic.progress(now - 30 * m, now + 30 * m, now), 0.0001f)
        assertEquals(0f, OnNowLogic.progress(now + 10, now + 100, now), 0f)          // clamped
        assertEquals(1f, OnNowLogic.progress(now - 100, now - 10, now), 0f)          // clamped
        assertEquals(0f, OnNowLogic.progress(now, now, now), 0f)                     // broken programme
        assertEquals(45, OnNowLogic.elapsedMin(now - 45 * m - 59, now))
        assertEquals(70, OnNowLogic.remainingMin(now + 69 * m + 1, now))             // rounded up
        assertEquals(1, OnNowLogic.remainingMin(now + 59, now))
        assertEquals(0, OnNowLogic.remainingMin(now - 5, now))
        val item = OnNowItem(OnNowClass.MOVIE, null, prog("Quiet Harbor", startMin = -45, lenMin = 115), listOf(chan("Cinema One", "MOVIES")))
        assertEquals(45, item.elapsedMin(now)); assertEquals(70, item.remainingMin(now))
    }

    // ------------------------------------------------------------------ text

    @Test fun normIsAccentCaseAndPunctuationInsensitive() {
        assertEquals("FUTBOL AMERICA VS CHIVAS", OnNowLogic.norm("Fútbol: América vs. Chivas!"))
        assertEquals("CAMPEONES DE ESPANA", OnNowLogic.norm("  Campeones de España  "))
        assertEquals("", OnNowLogic.norm("¡¿ — ?!"))
    }

    @Test fun placeholdersAreExcludedEverywhere() {
        for (t in listOf("", "   ", "TBA", "To Be Announced", "No Information", "No information available", "OFF AIR", "Off-Air",
            "Sin información", "Programación por confirmar", "N/A")) {
            assertTrue(t, OnNowLogic.isPlaceholder(t))
            assertNull(t, OnNowLogic.classify(prog(t), chan("Sports Net 1", "US| SPORTS")))
        }
        assertFalse(OnNowLogic.isPlaceholder("Announced Tonight"))
    }

    // ------------------------------------------------------------------ 1. Event

    @Test fun eventByChannelGroupOrName() {
        assertEquals(OnNowClass.EVENT, cls("Fight Night: Main Card", "PPV EVENTS"))
        assertEquals(OnNowClass.EVENT, cls("Event Starts 9PM ET", "US| EVENTS"))
        assertEquals(OnNowClass.EVENT, cls("Championship Rematch", "US| PAY-PER-VIEW"))
        assertEquals(OnNowClass.EVENT, cls("Live Card", "COMBAT", name = "UFC PPV 1"))
        assertEquals(OnNowClass.EVENT, cls("Dual Meet", "US| FLOSPORTS", name = "FLO SPORTS 04"))
        assertEquals(OnNowClass.EVENT, cls("Gran Velada", "LATINO| EVENTOS"))
        // "Eventually" is not "EVENT"
        assertEquals(OnNowClass.SHOW, cls("Eventually Home", "US| ENTERTAINMENT", name = "Eventually TV"))
    }

    @Test fun mainEventInTheChannelNameIsNotAnEvent() {
        // Reported: "Sports Desk" on "UHD: Sky Sports Main Event 4K" (Xtream category UHD/4K) was the only live Event
        assertTrue(OnNowLogic.classify(prog("Sports Desk"), chan("UHD: Sky Sports Main Event 4K", "UHD/4K")) != OnNowClass.EVENT)
        assertEquals(OnNowClass.SPORTS, cls("Sports Desk", "UK| SPORTS", name = "Sky Sports Main Event HD"))
        assertTrue(cls("Big Night Live", "US| ENTERTAINMENT", name = "Event TV") != OnNowClass.EVENT)
        // A channel name only counts with an unmistakable PPV pattern
        assertEquals(OnNowClass.EVENT, cls("Title Fight", "US| SPORTS", name = "PPV 01"))
        assertEquals(OnNowClass.EVENT, cls("Title Fight", "US| SPORTS", name = "PPV: Ring Kings"))
        assertEquals(OnNowClass.EVENT, cls("Title Fight", "US| SPORTS", name = "PPV07"))
        assertEquals(OnNowClass.EVENT, cls("Title Fight", "US| SPORTS", name = "Boxing Pay-Per-View 2"))
        // FloSports by its category only, not by "Flo" in a name
        assertEquals(OnNowClass.SPORTS, cls("Dual Meet", "US| SPORTS", name = "FLO SPORTS 04"))
    }

    // ------------------------------------------------------------------ 2. Sports

    @Test fun sportsByEpgCategory() {
        assertEquals(OnNowClass.SPORTS, cls("Morning Skate Live", "US| ENTERTAINMENT", cats = listOf("Sports")))
        assertEquals(OnNowClass.SPORTS, cls("Clásico Nacional", "LATINO", cats = listOf("Deportes")))
        assertEquals(OnNowClass.SPORTS, cls("Highland Derby", "UK", cats = listOf("Football")))
    }

    @Test fun sportsByXtreamGroupNeedsASportsTitle() {
        assertEquals(OnNowClass.SPORTS, cls("Pregame Show", "SPORTS NETWORKS"))
        assertEquals(OnNowClass.SPORTS, cls("Resumen de la Jornada", "FUTBOL"))
        // a sports category alone is not enough: no sports signal in the title → Show
        assertEquals(OnNowClass.SHOW, cls("Lo Mejor de la Semana", "ES| DEPORTES"))
    }

    // ------------------------------------------------------------------ owner's tuning (debug samples)

    @Test fun sportsChannelWithoutSportsTitleIsAShow() {
        // reported: "Kirstie and Phil's Love It or List It" on PREMIER SPORTS 1 should be a Show
        assertEquals(OnNowClass.SHOW, cls("Kirstie and Phil's Love It or List It", "UK| SPORTS", name = "PREMIER SPORTS 1"))
        assertEquals(OnNowClass.SHOW, cls("Kirstie and Phil's Love It or List It", "SPORTS", name = "PREMIER SPORTS 1"))
        assertEquals(OnNowClass.SHOW, cls("Kitchen Rescue", "US| SPORTS", name = "Sports Net 2"))
        // with a sports signal in the title it stays Sports
        for (t in listOf("Sports Desk", "Live: Darts World Championship", "Grand Prix Highlights", "Cup Final", "Match of the Day",
            "Northside v Eastport", "Harbor City @ Lakeview", "Golf Central", "Super Rugby: Final", "Monster Energy Supercross"))
            assertEquals(t, OnNowClass.SPORTS, cls(t, "UK| SPORTS", name = "PREMIER SPORTS 1"))
        // still never Sports: paid programming on a sports channel
        assertEquals(OnNowClass.OTHER, cls("Paid Programming", "UK| SPORTS", name = "PREMIER SPORTS 1"))
    }

    private fun sportOn(title: String, name: String, group: String = "UK| SPORTS") = OnNowLogic.sportOf(prog(title), chan(name, group))

    @Test fun soccerCompetitions() {
        assertEquals(Sport.SOCCER, sportOn("EFL Highlights", "Sky Sports Football"))
        assertEquals(Sport.SOCCER, sportOn("FA Cup: Northside v Eastport", "BBC One"))
        assertEquals(Sport.SOCCER, sportOn("Carabao Cup Final: City v United", "Sky Sports Main Event"))
        assertEquals(Sport.SOCCER, sportOn("Championship: Leeds v Burnley", "Sky Sports Football"))
        assertEquals(Sport.SOCCER, sportOn("Live Championship Football", "Sky Sports Main Event"))
        assertEquals(Sport.SOCCER, sportOn("SPL: Glenview v Rockhill", "Premier Sports 1"))
        assertEquals(Sport.SOCCER, sportOn("Bundesliga: Nordstadt v Südhafen", "Sky Sports Mix"))
        // "Championship" without soccer context is not soccer
        assertEquals(Sport.OTHER, sportOn("Darts Championship", "Sports Net", "US| SPORTS"))
        assertEquals(Sport.OTHER, OnNowLogic.sportOf(prog("Cornhole Championship"), chan("Sports Net", "SPORTS NETWORKS")))
    }

    @Test fun footballIsSoccerOnUkChannelsAndNflOnUsChannels() {
        assertEquals(Sport.SOCCER, sportOn("Live Football: City v United", "Sky Sports Football"))
        assertEquals(Sport.SOCCER, sportOn("Football Focus", "BBC One"))
        assertEquals(Sport.SOCCER, sportOn("Live Football", "TNT Sports 1"))
        assertEquals(Sport.SOCCER, sportOn("Football Highlights", "beIN SPORTS 2", "AR| SPORTS"))
        assertEquals(Sport.SOCCER, sportOn("International Football", "Premier Sports 2"))
        assertEquals(Sport.SOCCER, sportOn("Football Tonight", "ITV1", "UK| ENTERTAINMENT"))
        assertEquals(Sport.NFL, sportOn("Football: Wolves at Rams", "ESPN", "US| SPORTS"))
        assertEquals(Sport.NFL, sportOn("Sunday Night Football", "Sky Sports Main Event"))   // named NFL show wins
        assertEquals(Sport.COLLEGE_FOOTBALL, sportOn("College Football: State vs Tech", "Sky Sports Main Event"))
    }

    @Test fun beinDefaultsToSoccer() {
        assertEquals(Sport.SOCCER, sportOn("Weekend Review Live", "beIN SPORTS 1", "US| SPORTS"))
        assertEquals(Sport.TENNIS, sportOn("ATP Masters: Final", "beIN SPORTS 1", "US| SPORTS"))   // another sport matches
        assertEquals(Sport.OTHER, sportOn("Weekend Review Live", "Sports Net", "US| SPORTS"))
    }

    @Test fun motorsportsNewKeywordsAndNoBareRacing() {
        for (t in listOf("WorldSBK Superbike Race 2", "Monster Energy Supercross", "Pro Motocross: Round 4", "MotoAmerica Superbike Live"))
            assertEquals(t, Sport.MOTORSPORTS, sportOn(t, "Sports Net", "US| SPORTS"))
        // "Racing" / "Raceday" alone is not motorsports
        assertEquals(Sport.OTHER, sportOn("Racing", "Sports Net", "US| SPORTS"))
        assertEquals(Sport.OTHER, sportOn("Raceday", "Sports Net", "US| SPORTS"))
    }

    @Test fun horseRacingGroup() {
        assertEquals(Sport.HORSE_RACING, sportOn("Raceday Live", "Sky Sports Racing"))
        assertEquals(Sport.HORSE_RACING, sportOn("Racing", "TVG", "US| SPORTS"))
        assertEquals(Sport.HORSE_RACING, sportOn("Derby Day", "FanDuel TV", "US| SPORTS"))
        assertEquals(Sport.HORSE_RACING, sportOn("Stakes Saturday", "Racing UK"))
        assertEquals(Sport.HORSE_RACING, sportOn("Kentucky Derby", "NBC", "US| NETWORK"))      // the race itself, any channel
        // racing words count only on racing channels; a football derby stays soccer
        assertEquals(Sport.SOCCER, sportOn("Derby: Northside v Eastport", "Sky Sports Football"))
        // classified as Sports on a racing channel, also without a sports category
        assertEquals(OnNowClass.SPORTS, cls("Racing", "US| ENTERTAINMENT", name = "TVG"))
        assertEquals(OnNowClass.SPORTS, cls("Raceday Live", "UK| SPORTS", name = "Sky Sports Racing"))
        assertEquals("Horse Racing", Sport.HORSE_RACING.label)
    }

    @Test fun sportsByGameTitleWithSportKeyword() {
        // on a general channel: needs the "A vs B" shape AND a sport keyword
        assertEquals(OnNowClass.SPORTS, cls("NBA: Harbor City vs. Lakeview", "NETWORK TV"))
        assertEquals(OnNowClass.SPORTS, cls("Ridgeback @ Stormers", "NETWORK TV", cats = listOf("Hockey")))
        assertEquals(OnNowClass.SPORTS, cls("MLB Baseball: Pines at Harbors", "NETWORK TV"))
        assertEquals(OnNowClass.SPORTS, cls("Liga MX: Tigres Azules vs Halcones", "LATINO"))
        // game shape without a sport keyword is not enough
        assertEquals(OnNowClass.SHOW, cls("Sisters vs Brothers", "NETWORK TV", cats = listOf("Reality")))
        assertEquals(OnNowClass.SHOW, cls("Dinner at Eight", "NETWORK TV", ep = "S02E04"))
    }

    // ------------------------------------------------------------------ 3. Movie

    @Test fun movieByEpgCategory() {
        assertEquals(OnNowClass.MOVIE, cls("Quiet Harbor", "NETWORK TV", cats = listOf("Movie", "Drama")))
        assertEquals(OnNowClass.MOVIE, cls("La Última Noche", "LATINO", cats = listOf("Película")))
        assertEquals(OnNowClass.MOVIE, cls("Paper Lanterns", "UK", cats = listOf("Film")))
    }

    @Test fun movieByMovieChannelWithoutEpisodeAndLongEnough() {
        assertEquals(OnNowClass.MOVIE, cls("The Long Valley", "PREMIUM MOVIE CHANNELS", name = "Silverline Cinema", lenMin = 115))
        assertEquals(OnNowClass.MOVIE, cls("Night Train", "US| HBO", lenMin = 95))
        // an episode on a movie channel is a show
        assertEquals(OnNowClass.SHOW, cls("Gilded Streets", "PREMIUM MOVIE CHANNELS", ep = "S01E03", lenMin = 58))
        // short programme on a movie channel, no episode: duration alone decides against a film → show
        assertEquals(OnNowClass.SHOW, cls("Behind the Scenes", "PREMIUM MOVIE CHANNELS", lenMin = 30))
        // long, no episode, but a series category → show
        assertEquals(OnNowClass.SHOW, cls("Kingdom Saga", "US| STARZ", cats = listOf("Series"), lenMin = 75))
        // duration alone on a non-movie channel is not a film
        assertEquals(OnNowClass.SHOW, cls("Big Talk Special", "NETWORK TV", lenMin = 120))
    }

    // ------------------------------------------------------------------ 4. Show

    @Test fun showByCategoryEpisodeOrEntertainmentChannel() {
        assertEquals(OnNowClass.SHOW, cls("The Workshop", "UK", cats = listOf("Sitcom")))
        assertEquals(OnNowClass.SHOW, cls("Amor en la Ciudad", "LATINO", cats = listOf("Telenovela")))
        assertEquals(OnNowClass.SHOW, cls("Deep Ocean Life", "DOCUMENTARIES"))
        assertEquals(OnNowClass.SHOW, cls("Harbor Patrol", "RANDOM GROUP", ep = "S04E11"))
        assertEquals(OnNowClass.SHOW, cls("Late Hour", "NETWORK TV"))
        assertEquals(OnNowClass.SHOW, cls("Garden Rescue", "CANADA"))
    }

    // ------------------------------------------------------------------ 5. Other

    @Test fun otherNeverInMoviesOrShows() {
        assertEquals(OnNowClass.OTHER, cls("Evening News", "NETWORK TV"))
        assertEquals(OnNowClass.OTHER, cls("Noticias Univision 24/7", "LATINO"))
        assertEquals(OnNowClass.OTHER, cls("Morning Report", "NETWORK TV", cats = listOf("News")))
        assertEquals(OnNowClass.OTHER, cls("Weather Now", "NETWORK TV"))
        assertEquals(OnNowClass.OTHER, cls("Paid Programming", "NETWORK TV", ep = "S01E01"))
        assertEquals(OnNowClass.OTHER, cls("Kitchen Gadget Hour", "NETWORK TV", cats = listOf("Shopping")))
        assertEquals(OnNowClass.OTHER, cls("Sunday Service", "RELIGION"))
        assertEquals(OnNowClass.OTHER, cls("Hit Videos Nonstop", "MUSIC"))
        // a movie-like length on a news channel is still Other
        assertEquals(OnNowClass.OTHER, cls("Special Report", "US| NEWS", lenMin = 120))
        // a film category on a religion channel: Other wins over Movie
        assertEquals(OnNowClass.OTHER, cls("The Pilgrim", "RELIGION", cats = listOf("Movie")))
        // "The Newsroom" is not news
        assertEquals(OnNowClass.SHOW, cls("The Newsroom", "US| HBO", ep = "S01E02"))
        // unknown channel, nothing to go on
        assertEquals(OnNowClass.OTHER, cls("Something", "MISC"))
    }

    @Test fun infomercialOnSportsChannelIsOther() {
        assertEquals(OnNowClass.OTHER, cls("Paid Programming", "SPORTS NETWORKS"))
        assertEquals(OnNowClass.OTHER, cls("Miracle Mop", "SPORTS NETWORKS", cats = listOf("Infomercial")))
    }

    // ------------------------------------------------------------------ sport groups

    private fun sport(title: String, cats: List<String> = emptyList(), name: String = "Sports Net", group: String = "SPORTS NETWORKS") =
        OnNowLogic.sportOf(prog(title, cats), chan(name, group))

    @Test fun sportGroupsFromTitle() {
        assertEquals(Sport.NFL, sport("Sunday Night Football: Wolves at Rams"))
        assertEquals(Sport.NFL, sport("NFL RedZone"))
        assertEquals(Sport.COLLEGE_FOOTBALL, sport("College Football: State vs Tech"))
        assertEquals(Sport.COLLEGE_FOOTBALL, sport("SEC Football: Tigers vs Gators"))
        assertEquals(Sport.NBA, sport("NBA Basketball: Harbor City at Lakeview"))
        assertEquals(Sport.COLLEGE_BASKETBALL, sport("March Madness: First Round"))
        assertEquals(Sport.MLB, sport("MLB: Pines vs Harbors"))
        assertEquals(Sport.NHL, sport("NHL Hockey: Ridgebacks @ Stormers"))
        assertEquals(Sport.SOCCER, sport("Premier League: Northside vs Eastport"))
        assertEquals(Sport.SOCCER, sport("Liga MX: Tigres Azules vs Halcones"))
        assertEquals(Sport.SOCCER, sport("Fútbol: América vs Chivas"))
        assertEquals(Sport.COMBAT, sport("UFC Fight Night Prelims"))
        assertEquals(Sport.COMBAT, sport("Boxeo de Campeones"))
        assertEquals(Sport.GOLF, sport("PGA Tour: Final Round"))
        assertEquals(Sport.TENNIS, sport("ATP Masters: Quarterfinal"))
        assertEquals(Sport.MOTORSPORTS, sport("NASCAR Cup Series"))
        assertEquals(Sport.MOTORSPORTS, sport("F1: Grand Prix Qualifying"))
    }

    @Test fun sportGroupContextRules() {
        // conference names need football context
        assertEquals(Sport.OTHER, sport("SEC Now", name = "Conference Net"))
        // "Fútbol americano" is American football, not soccer
        assertEquals(Sport.NFL, sport("Fútbol Americano: Halcones vs Toros"))
        assertEquals(Sport.COLLEGE_FOOTBALL, sport("Fútbol Americano Universitario: Pumas vs Borregos"))
        // "Open" only with tennis context; LIV only with golf context
        assertEquals(Sport.TENNIS, sport("Coastal Open", cats = listOf("Tennis")))
        assertEquals(Sport.OTHER, sport("Coastal Open Water Swim"))
        assertEquals(Sport.GOLF, sport("LIV Golf: Round 2"))
        assertEquals(Sport.OTHER, sport("Super Final LIV Highlights"))
        // word boundaries: WNBA is not NBA (stays Other Sports until tuned)
        assertEquals(Sport.OTHER, sport("WNBA: Storm vs Sky"))
    }

    @Test fun sportGroupFallsBackToCategoriesThenChannelThenGroup() {
        assertEquals(Sport.SOCCER, sport("Northside vs Eastport", cats = listOf("Soccer")))
        assertEquals(Sport.NFL, sport("Primetime Kickoff", name = "NFL Network"))
        assertEquals(Sport.SOCCER, sport("Resumen de la Jornada", name = "Canal 7", group = "FUTBOL"))
        assertEquals(Sport.OTHER, sport("Cornhole Championship"))
    }

    // ------------------------------------------------------------------ merging and build

    @Test fun duplicatesMergeIntoOneCardBestQualityFirst() {
        val p1 = prog("NBA: Harbor City vs. Lakeview", tvg = "sportsnet.us")
        val p2 = prog("NBA - Harbor City vs Lakeview", tvg = "sportsnet2.us")   // other provider id, same programme
        val chans = mapOf(
            "sportsnet.us" to listOf(chan("US: Sports Net HD", "SPORTS NETWORKS", "HD"), chan("US: Sports Net 4K", "SPORTS NETWORKS", "4K")),
            "sportsnet2.us" to listOf(chan("Sports Net FHD", "SPORTS NETWORKS", "FHD"))
        )
        val items = OnNowLogic.build(listOf(p1, p2), chans, now)
        assertEquals(1, items.size)
        val it = items.single()
        assertEquals(OnNowClass.SPORTS, it.cls)
        assertEquals(Sport.NBA, it.sport)
        assertEquals(listOf("4K", "FHD", "HD"), it.sources.map { s -> s.quality })
    }

    @Test fun differentStartTimesStaySeparate() {
        val chans = mapOf("a.us" to listOf(chan("A", "NETWORK TV")), "b.us" to listOf(chan("B", "NETWORK TV")))
        val items = OnNowLogic.build(listOf(prog("Late Hour", startMin = -10, tvg = "a.us"), prog("Late Hour", startMin = -40, tvg = "b.us")), chans, now)
        assertEquals(2, items.size)
        assertEquals(now - 10 * m, items.first().prog.start)   // most recently started first
    }

    @Test fun buildDropsPlaceholdersOffNowAndUnknownChannels() {
        val chans = mapOf("a.us" to listOf(chan("A", "NETWORK TV")))
        val items = OnNowLogic.build(
            listOf(
                prog("To Be Announced", tvg = "a.us"),
                prog("Later Show", startMin = 10, tvg = "a.us"),
                prog("Gone Show", startMin = -120, lenMin = 60, tvg = "a.us"),
                prog("Nobody Watches", tvg = "zz.us"),
                prog("Late Hour", tvg = "A.US")
            ), chans, now
        )
        assertEquals(listOf("Late Hour"), items.map { it.title })
    }

    @Test fun strongestClassWinsWhenSourcesDisagree() {
        val chans = mapOf("ufc.us" to listOf(chan("Fight Channel", "SPORTS NETWORKS"), chan("UFC PPV 2", "PPV EVENTS")))
        val items = OnNowLogic.build(listOf(prog("Fight Night: Main Card", tvg = "ufc.us")), chans, now)
        assertEquals(OnNowClass.EVENT, items.single().cls)
        assertEquals(2, items.single().sources.size)
    }

    @Test fun counts() {
        val chans = mapOf(
            "s.us" to listOf(chan("Sports Net", "SPORTS NETWORKS")),
            "n.us" to listOf(chan("News 1", "US| NEWS")),
            "m.us" to listOf(chan("Cinema", "PREMIUM MOVIE CHANNELS"))
        )
        val items = OnNowLogic.build(
            listOf(prog("NHL: Ridgebacks vs Stormers", tvg = "s.us"), prog("Evening News", tvg = "n.us"), prog("The Long Valley", lenMin = 110, tvg = "m.us")),
            chans, now
        )
        val byClass = OnNowLogic.countByClass(items)
        assertEquals(1, byClass[OnNowClass.SPORTS]); assertEquals(1, byClass[OnNowClass.OTHER]); assertEquals(1, byClass[OnNowClass.MOVIE])
        assertEquals(0, byClass[OnNowClass.EVENT])
        assertEquals(mapOf(Sport.NHL to 1), OnNowLogic.countBySport(items))
    }

    // ------------------------------------------------------------------ events

    private fun ev(name: String, group: String = "PPV EVENTS", tvg: String? = null, id: String = name) = Chan(id, name, group, tvgId = tvg)

    @Test fun eventChannelDetection() {
        assertTrue(OnNowLogic.isEventChannel(ev("PPV 3", group = "US| SPORTS")))              // clear PPV name
        assertTrue(OnNowLogic.isEventChannel(ev("Ring Night", group = "US| PAY PER VIEW")))   // PPV category
        assertTrue(OnNowLogic.isEventChannel(ev("Fight Card 2", group = "LATINO| EVENTOS")))
        assertTrue(OnNowLogic.isEventChannel(ev("Track Meet 12", group = "US| FLOSPORTS")))
        assertFalse(OnNowLogic.isEventChannel(ev("Flo Sports 12", group = "SPORTS")))
        assertFalse(OnNowLogic.isEventChannel(ev("Sports Net 1", group = "SPORTS NETWORKS")))
        assertFalse(OnNowLogic.isEventChannel(ev("UHD: Sky Sports Main Event 4K", group = "UHD/4K")))
        assertFalse(OnNowLogic.isEventChannel(ev("Eventos Deportivos TV", group = "LATINO| DEPORTES")))
        assertFalse(OnNowLogic.isEventChannel(ev("Hoppv Kids", group = "US| KIDS")))           // "PPV" only as a whole word
    }

    @Test fun ppvCategoryAndNameRules() {
        for (g in listOf("US| PPV", "PPV EVENTS", "UFC PPV", "US| PAY-PER-VIEW", "LIVE EVENTS", "DAZN| EVENTS", "MX| EVENTOS", "FLO SPORTS"))
            assertTrue(g, OnNowLogic.isPpvCategory(g))
        for (g in listOf("UHD/4K", "US| SPORTS", "UK| ENTERTAINMENT", "PREVENTION TV", "US| EVENTUALLY"))
            assertFalse(g, OnNowLogic.isPpvCategory(g))
        for (n in listOf("PPV 01", "PPV:", "PPV: UFC 310", "US: PPV 5", "PPV07", "UFC PPV", "Pay Per View 3", "DAZN PPV 2"))
            assertTrue(n, OnNowLogic.isPpvName(n))
        for (n in listOf("Main Event", "Sky Sports Main Event", "Event 1", "Events HD", "Flo Sports 4", "Eventos 3"))
            assertFalse(n, OnNowLogic.isPpvName(n))
    }

    @Test fun ppvPlaceholderSlots() {
        for (n in listOf("PPV 23", "PPV 01", "US: PPV 05 HD", "UK | PPV 7", "[US] PPV 12", "PPV07", "PPV 12 Backup", "Pay Per View 3",
            "PPV 23: No Event", "PPV 4 - TBA", "Event 15", "EVENTOS 2 FHD"))
            assertTrue(n, OnNowLogic.isPpvPlaceholderName(n))
        for (n in listOf("PPV 01: UFC 310 Pantoja vs Asakura", "PPV 3 | Canelo vs Berlanga", "UFC PPV", "Ring Night", "DAZN PPV 2",
            "US: PPV 5 - Title Fight Night"))
            assertFalse(n, OnNowLogic.isPpvPlaceholderName(n))
        assertTrue(Chan("a", "PPV 23", "US| PPV").ppvPlaceholder)
        assertFalse(Chan("b", "PPV 01: Title Fight", "US| PPV").ppvPlaceholder)
        assertFalse(Chan("c", "Event 15", "US| ENTERTAINMENT").ppvPlaceholder)              // not a PPV channel at all
    }

    @Test fun ppvChannelsWithoutGuideStayReachableWithTheirName() {
        val chans = listOf(
            ev("PPV 10: Ring Kings"), ev("PPV 2 | Title Fight Night"), ev("PPV 1", tvg = "ppv1.us"),
            ev("PPV 3: Cage Masters", tvg = "ppv3.us"),         // has rows, but only a placeholder
            ev("UFC PPV 4", tvg = "ufc4.us"),                   // event coming up tonight
            ev("PPV 11"), ev("PPV 12 HD"), ev("PPV 13"),        // empty slots: hidden, counted
            chan("Sports Net", "SPORTS NETWORKS")               // not an event channel
        )
        val byTvg = OnNowLogic.run { chans.filter { it.tvgId != null }.groupBy { it.tvgId!! } }
        val onNow = OnNowLogic.build(listOf(prog("Prelims Live", tvg = "ppv1.us"), prog("No Event Scheduled", tvg = "ppv3.us")), byTvg, now)
        val next = listOf(
            prog("Main Card: Champion vs Challenger", startMin = 90, lenMin = 180, tvg = "ufc4.us"),
            prog("Too Late", startMin = 25 * 60, tvg = "ufc4.us")                                  // beyond 24 h
        )
        val e = OnNowLogic.events(onNow, next, chans, now)
        assertEquals(listOf("Prelims Live"), e.live.map { it.title })
        assertEquals(listOf("Main Card: Champion vs Challenger"), e.upcoming.map { it.title })
        // everything else that is a PPV channel with an event name, natural order, channel name as title
        assertEquals(listOf("PPV 2 | Title Fight Night", "PPV 3: Cage Masters", "PPV 10: Ring Kings"), e.channels.map { OnNowLogic.eventTitle(it) })
        assertEquals(3, e.hiddenPlaceholders)
        // the same through the per-playlist index
        val idx = OnNowLogic.EventIndex(chans)
        val e2 = OnNowLogic.events(onNow, next, idx, now)
        assertEquals(e.channels, e2.channels); assertEquals(3, e2.hiddenPlaceholders)
    }

    @Test fun placeholderSlotWithAnEventOnNowIsListedLive() {
        val chans = listOf(ev("PPV 23", tvg = "ppv23.us"), ev("PPV 24"))
        val byTvg = mapOf("ppv23.us" to listOf(chans[0]))
        val e = OnNowLogic.events(OnNowLogic.build(listOf(prog("Main Card: Champion vs Challenger", tvg = "ppv23.us")), byTvg, now), emptyList(), chans, now)
        assertEquals(listOf("Main Card: Champion vs Challenger"), e.live.map { it.title })
        assertEquals(0, e.channels.size)
        assertEquals(1, e.hiddenPlaceholders)                 // only PPV 24 (nothing listed)
    }

    @Test fun eventIndexCountsCategoriesOnce() {
        val chans = listOf(
            ev("PPV 2", group = "US| PPV"), ev("PPV 10", group = "US| PPV"), ev("PPV 1", group = "US| PPV", tvg = "PPV1.US"),
            ev("Fight Card", group = "LATINO| EVENTOS"),
            ev("PPV 5: Title Night", group = "US| SPORTS"),       // by name only
            chan("UHD: Sky Sports Main Event 4K", "UHD/4K"), chan("News 1", "US| NEWS")
        )
        val idx = OnNowLogic.EventIndex(chans)
        assertEquals(listOf("Fight Card", "PPV 1", "PPV 2", "PPV 5: Title Night", "PPV 10"), idx.channels.map { it.name })
        assertEquals(listOf("US| PPV" to 3, "LATINO| EVENTOS" to 1), idx.categories)
        assertEquals(1, idx.byNameOnly)
        assertEquals(3, idx.placeholders)
        assertEquals(setOf("ppv1.us"), idx.tvgIds)
    }

    @Test fun upcomingIsSoonestFirstWithinTheWindow() {
        val chans = mapOf("a.us" to listOf(chan("A", "NETWORK TV")))
        val up = OnNowLogic.upcoming(
            listOf(prog("Later", startMin = 120, tvg = "a.us"), prog("Soon", startMin = 15, tvg = "a.us"), prog("Running", startMin = -5, tvg = "a.us")),
            chans, now, hours = 24
        )
        assertEquals(listOf("Soon", "Later"), up.map { it.title })
        assertEquals(emptyList<String>(), OnNowLogic.upcoming(listOf(prog("Later", startMin = 120, tvg = "a.us")), chans, now, hours = 1).map { it.title })
    }

    @Test fun naturalSort() {
        assertEquals(listOf("PPV 1", "PPV 2", "PPV 10", "PPV 10 HD", "ppv 11"), listOf("PPV 10", "ppv 11", "PPV 2", "PPV 10 HD", "PPV 1").sortedWith(OnNowLogic.NATURAL))
        assertEquals(listOf("PPV 1", "PPV 01", "PPV 2", "PPV 0010", "PPV 99999999999999999999"),
            listOf("PPV 99999999999999999999", "PPV 0010", "PPV 2", "PPV 01", "PPV 1").sortedWith(OnNowLogic.NATURAL))
    }

    @Test fun sharedProgrammeTextsGiveTheSameResult() {
        val p = prog("Fútbol: América vs. Chivas", cats = listOf("Sports"))
        val c1 = chan("TUDN HD", "LATINO| DEPORTES"); val c2 = chan("TUDN 4K", "LATINO| DEPORTES", q = "4K")
        val pt = OnNowLogic.ProgText(p)
        assertEquals(OnNowLogic.classify(OnNowLogic.Facts(p, c1)), OnNowLogic.classify(OnNowLogic.Facts(pt, c1)))
        assertEquals(Sport.SOCCER, OnNowLogic.sportOf(OnNowLogic.Facts(pt, c2)))
        assertEquals(OnNowLogic.mergeKey(p), pt.mergeKey)
    }

    @Test fun keywordTablesAreData() {
        // tuning happens in the tables; make sure the important ones are not empty and normalise cleanly
        for (l in listOf(OnNowLogic.PPV_CATEGORY_WORDS, OnNowLogic.PPV_NAME_WORDS, OnNowLogic.PPV_SLOT_WORDS, OnNowLogic.MOVIE_GROUP_PARTS, OnNowLogic.SHOW_CATEGORY_PARTS, OnNowLogic.PLACEHOLDER_TITLES)) {
            assertTrue(l.isNotEmpty())
            for (k in l) assertTrue(k, OnNowLogic.norm(k).isNotEmpty())
        }
        assertEquals(Sport.entries.size - 1, OnNowLogic.SPORT_RULES.map { it.sport }.toSet().size)   // every group except OTHER has a rule
    }
}

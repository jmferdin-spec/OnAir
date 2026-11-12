package com.ipvio.stream

import java.text.Normalizer

/** OnAir: what kind of programme is on (first matching rule wins, see [OnNowLogic.classify]). */
enum class OnNowClass { EVENT, SPORTS, MOVIE, SHOW, OTHER }

/** OnAir: sport groups on the Sports screen (order = the order of the rules in [OnNowLogic.SPORT_RULES] is separate). */
enum class Sport(val label: String) {
    NFL("NFL"), COLLEGE_FOOTBALL("College Football"), NBA("NBA"), COLLEGE_BASKETBALL("College Basketball"),
    MLB("MLB"), NHL("NHL"), SOCCER("Soccer"), COMBAT("Combat Sports"), GOLF("Golf"), TENNIS("Tennis"),
    MOTORSPORTS("Motorsports"), HORSE_RACING("Horse Racing"), OTHER("Other Sports")
}

/**
 * One card on a "now" screen: a programme with all channels that carry it ([sources], best quality first).
 * Identical programmes (same normalised title + same start) on several channels are merged into one item.
 */
data class OnNowItem(val cls: OnNowClass, val sport: Sport?, val prog: OnNowLogic.Prog, val sources: List<OnNowLogic.Chan>) {
    val title: String get() = prog.title
    fun progress(now: Long): Float = OnNowLogic.progress(prog.start, prog.stop, now)
    fun elapsedMin(now: Long): Int = OnNowLogic.elapsedMin(prog.start, now)
    fun remainingMin(now: Long): Int = OnNowLogic.remainingMin(prog.stop, now)
}

/**
 * OnAir Phase 3: the shared "on now" logic behind Movies, Shows, Sports and Events (pure Kotlin, tested in OnNowLogicTest).
 *
 * Classification of an on-now programme, first match wins (CLAUDE.md, Phase 3):
 * 1. placeholder title (empty, "To Be Announced", "Off Air" …) → excluded everywhere
 * 2. **Event** – channel name or Xtream category matches PPV/event words
 * 3. paid programming / infomercial / shopping → **Other** (also on sports channels)
 * 4. **Sports** – EPG category says sport, the Xtream category is a sports group, or the title is a game ("A vs B") with a
 *    sport keyword
 * 5. news, weather, religious, music … → **Other** (never in Movies or Shows)
 * 6. **Movie** – EPG category says movie/film, or a movie-channel group with no episode number and ≥ 70 minutes
 * 7. **Show** – series-like EPG category, an episode number, or an entertainment / movie channel
 * 8. **Other**
 *
 * All keyword tables below are plain data – tune them there (Phase 5), not in UI code. Matching is accent-, case- and
 * punctuation-insensitive ([norm]); "word" lists match whole words, "part" lists match anywhere.
 */
object OnNowLogic {

    // ================================================================== data

    /** A programme as stored in the guide. [categories] = XMLTV `<category>` values, [episode] = normalised marker or null. */
    data class Prog(
        val tvgId: String, val title: String, val start: Long, val stop: Long,
        val categories: List<String> = emptyList(), val episode: String? = null,
        val subTitle: String? = null, val desc: String? = null
    )

    /**
     * A live channel: [group] = Xtream category name, [catId] = Xtream category_id, [quality] = "4K"/"FHD"/"HD"/null.
     * The normalised texts and the PPV flags are computed once when the channel is created – the "now" screens build their
     * channels once per playlist (see OnNow), not on every refresh.
     */
    data class Chan(
        val id: String, val name: String, val group: String, val catId: String? = null,
        val quality: String? = null, val logo: String? = null, val tvgId: String? = null
    ) {
        internal val nName: String = pad(norm(name))
        internal val nGroup: String = pad(norm(group))
        /** The Xtream category is a PPV / event category ("US| PPV", "PAY PER VIEW", "LIVE EVENTS" …). */
        val ppvCategory: Boolean = isPpvCategoryText(nGroup)
        /** The channel name is clearly a PPV slot ("PPV 01", "PPV: …", "UFC PPV") – never just "Event" / "Main Event". */
        val ppvName: Boolean = isPpvNameText(nName)
        /** PPV / event channel: Event rule 1 of [classify], and listed on the Events screen. */
        val isEvent: Boolean get() = ppvCategory || ppvName
        /** A PPV slot without an event name ("PPV 23", "US: PPV 05 HD"): hidden from the "PPV channels" list, only counted. */
        val ppvPlaceholder: Boolean = (ppvCategory || ppvName) && isPpvPlaceholderName(name)
    }

    // ================================================================== keyword tables (tune here)

    /**
     * Xtream category (whole words) → PPV / event channel. The main signal: providers file PPV and event slots in their own
     * categories. "EVENTS" is fine here (a category named "US| EVENTS"), but never in a channel name – see [PPV_NAME_WORDS].
     */
    val PPV_CATEGORY_WORDS = listOf(
        "PPV", "PAY PER VIEW", "PAYPERVIEW", "EVENT", "EVENTS", "LIVE EVENTS", "SPORTS EVENTS", "EVENTO", "EVENTOS",
        "UFC PPV", "BOXING PPV", "WWE PPV", "DAZN PPV", "FLO", "FLOSPORTS", "FLO SPORTS"
    )

    /**
     * Channel name (whole words) → PPV slot, only unmistakable PPV patterns: "PPV 01", "PPV: …", "UFC PPV", "Pay Per View".
     * Deliberately NOT "Event" / "Main Event" ("Sky Sports Main Event" is a normal sports channel). "PPV01" is matched by
     * [PPV_NUMBERED].
     */
    val PPV_NAME_WORDS = listOf("PPV", "PAY PER VIEW", "PAYPERVIEW")
    /** "PPV01", "PPV12" written together (on the padded, normalised name). */
    val PPV_NUMBERED = Regex("""\sPPV\d{1,4}\s""")

    /**
     * Words of a PPV slot name that are not an event name. A PPV channel whose name has nothing else (after a "US:" / "UK |"
     * prefix and numbers) is a placeholder slot, e.g. "PPV 23", "US: PPV 05 HD", "PPV 12 Backup".
     */
    val PPV_SLOT_WORDS = listOf(
        "PPV", "PAY", "PER", "VIEW", "PAYPERVIEW", "EVENT", "EVENTS", "EVENTO", "EVENTOS", "CHANNEL", "CANAL", "CH", "LIVE",
        "BACKUP", "ALT", "FEED", "STREAM", "HD", "FHD", "UHD", "4K", "SD", "HEVC", "H265", "RAW", "VIP", "PLUS"
    )

    /** Title or EPG category → Other, checked before Sports (a sports channel's infomercial is not a game). */
    val JUNK_WORDS = listOf(
        "PAID PROGRAMMING", "PAID PROGRAM", "INFOMERCIAL", "SHOPPING", "TELESHOPPING", "TELEVENTA", "PROGRAMA PAGADO"
    )

    /** EPG category contains … → Sports. */
    val SPORT_CATEGORY_PARTS = listOf("SPORT", "DEPORT")
    /** EPG category is a sport name → Sports (XMLTV often says just "Football" or "Basketball"). */
    val SPORT_CATEGORY_WORDS = listOf(
        "FOOTBALL", "SOCCER", "BASKETBALL", "BASEBALL", "HOCKEY", "TENNIS", "GOLF", "BOXING", "MMA", "WRESTLING",
        "MOTORSPORTS", "RACING", "FUTBOL", "BEISBOL", "BALONCESTO", "BOXEO", "LUCHA LIBRE"
    )
    /** Xtream category contains … → Sports. */
    val SPORT_GROUP_PARTS = listOf("SPORT", "DEPORT", "FUTBOL")

    /** Title shaped like a game: "A vs B", "A vs. B", "A v B", "A @ B", "A at B" (needs a sport keyword as well). */
    val GAME_TITLE = Regex("""\S.*?\s(?:vs\.?|v\.?|@|at)\s+\S""", RegexOption.IGNORE_CASE)

    /** EPG category / title / Xtream category → Other (news, weather, religion, music …). */
    val OTHER_CATEGORY_WORDS = listOf(
        "NEWS", "WEATHER", "SHOPPING", "PAID PROGRAMMING", "INFOMERCIAL", "RELIGIOUS", "RELIGION", "MUSIC", "CONSUMER",
        "PUBLIC AFFAIRS", "NOTICIAS", "NOTICIERO", "CLIMA", "RELIGIOSO", "MUSICA"
    )
    val OTHER_TITLE_WORDS = listOf(
        "NEWS", "NOTICIAS", "NOTICIERO", "TELEDIARIO", "WEATHER", "EL TIEMPO", "MUSIC VIDEOS", "VIDEOS MUSICALES", "MISA"
    )
    val OTHER_GROUP_WORDS = listOf(
        "NEWS", "NOTICIAS", "RELIGION", "RELIGIOUS", "RELIGIOSO", "RELIGIOSOS", "FAITH", "MUSIC", "MUSICA", "SHOPPING", "WEATHER"
    )

    /** EPG category contains … → Movie. */
    val MOVIE_CATEGORY_PARTS = listOf("MOVIE", "FILM", "PELICULA", "CINE")
    /** Xtream category contains … → movie channel. */
    val MOVIE_GROUP_PARTS = listOf("MOVIE", "CINEMA", "HBO", "SHOWTIME", "STARZ", "CINEMAX", "MGM", "TCM", "PELICULA", "CINE")
    /** On a movie channel without episode number, this long counts as a film (supporting signal, never alone). */
    const val MOVIE_MIN_MINUTES = 70

    /** EPG category contains … → Show. */
    val SHOW_CATEGORY_PARTS = listOf(
        "SERIES", "SERIE", "EPISODE", "DRAMA", "COMEDY", "COMEDIA", "REALITY", "DOCUMENTAR", "DOCUMENTAL", "ANIMATION",
        "ANIMACION", "ANIMATED", "CARTOON", "ANIME", "SITCOM", "SOAP", "TELENOVELA", "TALK", "GAME SHOW", "CHILDREN", "KIDS",
        "INFANTIL", "FAMILY", "CRIME", "MYSTERY", "THRILLER", "SCI FI", "FANTASY", "ROMANCE", "ADVENTURE", "HORROR",
        "ENTERTAINMENT", "ENTRETENIMIENTO", "LIFESTYLE", "COOKING", "TRAVEL", "HISTORY", "NATURE", "SCIENCE", "VARIETY"
    )
    /** Xtream category contains … → entertainment channel (programmes not matched above count as shows). */
    val ENTERTAINMENT_GROUP_PARTS = listOf(
        "ENTERTAINMENT", "ENTRETENIMIENTO", "NETWORK", "GENERAL", "DOCUMENTAR", "DOCUMENTAL", "KIDS", "INFANTIL", "FAMILY",
        "FAMILIA", "LIFESTYLE", "REALITY", "COMEDY", "DRAMA", "VARIETY", "VARIEDADES", "SERIES"
    )
    /** Xtream category is (also) one of these whole words → general/entertainment channel. */
    val ENTERTAINMENT_GROUP_WORDS = listOf("US", "USA", "UK", "CANADA", "LATINO", "LATIN", "MEXICO", "ESPANA", "SPAIN", "LOCALS", "LOCAL")

    /** Titles that are no programme at all – excluded from every list. Compared after [norm]. */
    val PLACEHOLDER_TITLES = listOf(
        "TBA", "TBD", "TO BE ANNOUNCED", "TO BE DETERMINED", "NO INFORMATION", "NO INFORMATION AVAILABLE", "NO INFO",
        "NO PROGRAMME INFORMATION", "NO PROGRAM INFORMATION", "NO PROGRAM INFO", "NO DATA", "NO EPG", "NO EPG AVAILABLE",
        "OFF AIR", "SIGN OFF", "CLOSEDOWN", "N A", "NA", "UNKNOWN", "SIN INFORMACION", "POR CONFIRMAR",
        "PROGRAMACION POR CONFIRMAR", "PROGRAMA POR CONFIRMAR", "INFORMACION NO DISPONIBLE", "FUERA DEL AIRE",
        // PPV channels between events
        "NO EVENT", "NO EVENTS", "NO EVENT SCHEDULED", "NO EVENTS SCHEDULED", "NO EVENT TODAY", "NO LIVE EVENT", "SIN EVENTO", "SIN EVENTOS"
    )

    /**
     * One sport rule: any keyword (whole words), and – if given – one of the [context] words somewhere in the texts, and
     * [onlyIf] true for the programme on its channel. [resolve] picks the sport from the channel instead of [sport]
     * (e.g. "Football" = soccer on UK / international channels, NFL on US channels).
     */
    class SportRule(
        val sport: Sport, keywords: List<String>, context: List<String> = emptyList(),
        internal val onlyIf: ((Facts) -> Boolean)? = null,
        internal val resolve: ((Facts) -> Sport)? = null
    ) {
        internal val words = keywords.map { pad(norm(it)) }
        internal val ctx = context.map { pad(norm(it)) }
        internal fun matches(text: String, f: Facts): Boolean =
            words.any { text.contains(it) } && (ctx.isEmpty() || ctx.any { f.all.contains(it) }) && (onlyIf == null || onlyIf.invoke(f))
        internal fun sportFor(f: Facts): Sport = resolve?.invoke(f) ?: sport
    }

    /**
     * Channels where "Football" means soccer: UK / international broadcasters (channel name, whole words) or a UK /
     * international Xtream category. Everything else (US channels) reads "Football" as American football.
     */
    val INTL_FOOTBALL_CHANNEL_WORDS = listOf(
        "SKY SPORTS", "SKY SPORT", "TNT SPORTS", "BT SPORT", "BEIN", "BEIN SPORTS", "BEIN SPORT", "PREMIER SPORTS", "BBC", "ITV",
        "EUROSPORT", "VIAPLAY", "SPORTDIGITAL", "SUPERSPORT", "LALIGA TV", "PREMIER LEAGUE TV", "MUTV", "LFCTV", "CHELSEA TV",
        "RTE", "TG4", "S4C", "CHANNEL 4", "CHANNEL 5", "OPTUS SPORT", "MOVISTAR", "CANAL"
    )
    val INTL_GROUP_WORDS = listOf(
        "UK", "GB", "IE", "IRELAND", "EU", "EUROPE", "INT", "INTL", "INTERNATIONAL", "AFRICA", "ARABIC", "AR", "ES", "ESPANA",
        "SPAIN", "DE", "FR", "IT", "NL", "PT", "TR", "SCOTLAND", "WALES"
    )
    /** beIN channels: a sports programme that matches no other group is soccer. */
    val SOCCER_DEFAULT_CHANNEL_WORDS = listOf("BEIN", "BEIN SPORTS", "BEIN SPORT")
    /** Soccer context for "Championship" (the English second tier) when the channel is not clearly a soccer channel. */
    val SOCCER_CONTEXT_WORDS = listOf("SOCCER", "EFL", "FUTBOL", "SKY SPORTS FOOTBALL", "SKY BET", "FA CUP")

    /** Horse racing channels (channel name, whole words). */
    val HORSE_RACING_CHANNEL_WORDS = listOf(
        "SKY SPORTS RACING", "TVG", "TVG2", "TVG 2", "FANDUEL TV", "FANDUEL RACING", "RACING UK", "RACING TV", "AT THE RACES", "ATR"
    )
    /** On a horse racing channel these title words are racing (and a sports signal). */
    val HORSE_RACING_TITLE_WORDS = listOf(
        "RACEDAY", "RACE DAY", "RACING", "RACE", "RACES", "DERBY", "STAKES", "HANDICAP", "HURDLE", "CHASE", "POST TIME", "TROT", "HARNESS"
    )
    /** Horse racing anywhere, whatever the channel. */
    val HORSE_RACING_WORDS = listOf(
        "HORSE RACING", "KENTUCKY DERBY", "PREAKNESS", "BELMONT STAKES", "BREEDERS CUP", "ROYAL ASCOT", "CHELTENHAM FESTIVAL",
        "GRAND NATIONAL", "EPSOM DERBY", "PRIX DE L ARC", "MELBOURNE CUP"
    )

    /**
     * A sports Xtream category alone does not make a programme Sports – the title must show it too: a sport / league
     * keyword ([SPORT_RULES], [SPORT_CATEGORY_WORDS]), a game ("A vs B", "A @ B"), or one of these words. Without one,
     * a programme on a sports channel is a Show ("Kirstie and Phil's Love It or List It" on Premier Sports 1).
     */
    val SPORT_TITLE_WORDS = listOf(
        "LIVE", "EN VIVO", "AO VIVO", "EN DIRECTO", "DIRECTO", "HIGHLIGHTS", "MATCH", "MATCHDAY", "MATCH OF THE DAY", "GAME", "GAMES",
        "GAMEDAY", "GAME DAY", "CUP", "CHAMPIONSHIP", "CHAMPIONSHIPS", "GRAND PRIX", "GP", "TOURNAMENT", "FINAL", "FINALS",
        "SEMIFINAL", "SEMI FINAL", "QUARTERFINAL", "QUARTER FINAL", "PLAYOFF", "PLAYOFFS", "LEAGUE", "BOWL", "KICKOFF", "KICK OFF",
        "PREGAME", "POSTGAME", "PRE MATCH", "POST MATCH", "QUALIFYING", "PRACTICE", "FIGHT", "FIGHT NIGHT", "BOUT", "MEET",
        "SPORT", "SPORTS", "SPORTSCENTER", "DEPORTES", "PARTIDO", "JORNADA", "RESUMEN", "GOLES", "CLASICO", "TORNEO",
        "CAMPEONATO", "REPLAY", "RACE", "DERBY", "OPEN", "MASTERS"
    )

    /**
     * Sport groups, checked in this order on the title, then the EPG categories, then the channel name, then the Xtream
     * category; first match wins, nothing → [Sport.OTHER]. More specific rules come first (college before pro,
     * "Fútbol americano" before soccer's "Fútbol").
     */
    val SPORT_RULES = listOf(
        SportRule(Sport.COLLEGE_FOOTBALL, listOf("NCAAF", "COLLEGE FOOTBALL", "CFB", "NCAA FOOTBALL", "FUTBOL AMERICANO UNIVERSITARIO")),
        SportRule(Sport.COLLEGE_FOOTBALL, listOf("SEC", "BIG TEN", "BIG 12", "ACC", "PAC 12"), context = listOf("FOOTBALL", "FUTBOL AMERICANO")),
        SportRule(Sport.NFL, listOf(
            "NFL", "FOOTBALL NIGHT", "THURSDAY NIGHT FOOTBALL", "SUNDAY NIGHT FOOTBALL", "MONDAY NIGHT FOOTBALL",
            "NFL REDZONE", "REDZONE", "FUTBOL AMERICANO", "SUPER BOWL", "AFC CHAMPIONSHIP", "NFC CHAMPIONSHIP"
        )),
        // Horse racing before soccer ("Derby") and motorsports ("Racing"); racing words only on racing channels
        SportRule(Sport.HORSE_RACING, HORSE_RACING_WORDS),
        SportRule(Sport.HORSE_RACING, HORSE_RACING_TITLE_WORDS, onlyIf = { isHorseRacingChannel(it.chan) }),
        // "Football" alone: soccer on UK / international channels, American football (NFL) on US channels
        SportRule(Sport.NFL, listOf("FOOTBALL"), resolve = { if (isIntlFootballChannel(it.chan)) Sport.SOCCER else Sport.NFL }),
        SportRule(Sport.COLLEGE_BASKETBALL, listOf("NCAAB", "COLLEGE BASKETBALL", "NCAAM", "NCAAW", "MARCH MADNESS")),
        SportRule(Sport.NBA, listOf("NBA", "WNBA")),   // no WNBA group: WNBA games are basketball under NBA
        SportRule(Sport.MLB, listOf("MLB", "BASEBALL", "BEISBOL")),
        SportRule(Sport.NHL, listOf("NHL", "HOCKEY")),
        SportRule(Sport.SOCCER, listOf(
            "SOCCER", "FUTBOL", "LIGA MX", "MLS", "PREMIER LEAGUE", "EPL", "LA LIGA", "LALIGA", "SERIE A", "BUNDESLIGA",
            "2 BUNDESLIGA", "LIGUE 1", "CHAMPIONS LEAGUE", "EUROPA LEAGUE", "CONFERENCE LEAGUE", "UEFA", "COPA",
            "EFL", "FA CUP", "CARABAO", "CARABAO CUP", "EFL CUP", "LEAGUE CUP", "SKY BET CHAMPIONSHIP", "SPL", "SPFL",
            "SCOTTISH PREMIERSHIP", "EREDIVISIE", "PRIMEIRA LIGA", "SUPER LIG", "SAUDI PRO LEAGUE", "WSL", "MATCH OF THE DAY"
        )),
        // "Championship" = the English second tier, but only with soccer context or a UK / international football channel
        SportRule(Sport.SOCCER, listOf("CHAMPIONSHIP"), onlyIf = { f ->
            anyWord(f.all, soccerCtxW) || (f.all.contains(" FOOTBALL ") && isIntlFootballChannel(f.chan))
        }),
        SportRule(Sport.COMBAT, listOf("UFC", "MMA", "BOXING", "BOXEO", "BELLATOR", "PFL", "WWE", "AEW", "WRESTLING", "LUCHA LIBRE")),
        SportRule(Sport.GOLF, listOf("GOLF", "PGA", "LPGA")),
        SportRule(Sport.GOLF, listOf("LIV"), context = listOf("GOLF")),   // "LIV" alone is also a Roman numeral
        SportRule(Sport.TENNIS, listOf("TENNIS", "TENIS", "ATP", "WTA")),
        SportRule(Sport.TENNIS, listOf("OPEN"), context = listOf("TENNIS", "TENIS", "ATP", "WTA")),
        // "Racing" / "Raceday" alone is not motorsports (horse racing uses them too)
        SportRule(Sport.MOTORSPORTS, listOf(
            "NASCAR", "F1", "FORMULA 1", "FORMULA ONE", "FORMULA UNO", "FORMULA E", "INDYCAR", "INDY 500", "MOTOGP", "MOTO GP",
            "SUPERBIKE", "SUPERBIKES", "WORLDSBK", "WSBK", "BSB", "SUPERCROSS", "MOTOCROSS", "MXGP", "MOTOAMERICA", "NHRA",
            "IMSA", "WRC", "LE MANS", "DAYTONA 500", "SUPERCARS", "MOTORSPORT", "MOTORSPORTS"
        ))
    )

    // ================================================================== team names (data – tune here)

    /**
     * Pro teams per league as "City|Nickname|alias,alias". A team only counts in a game title ("A vs B", "A at B",
     * "A @ B"), and both sides must name a team or a team city, at least one side by its team name – so a show that just
     * mentions a team, or a college game like "Texas at Houston", is not taken for a pro game ([teamSport]).
     * WNBA teams count as NBA (basketball; there is no WNBA group).
     */
    val TEAMS: Map<Sport, List<String>> = mapOf(
        Sport.NBA to listOf(
            "Atlanta|Hawks", "Boston|Celtics", "Brooklyn|Nets", "Charlotte|Hornets", "Chicago|Bulls", "Cleveland|Cavaliers|Cavs",
            "Dallas|Mavericks|Mavs", "Denver|Nuggets", "Detroit|Pistons", "Golden State|Warriors", "Houston|Rockets",
            "Indiana|Pacers", "Los Angeles|Clippers", "Los Angeles|Lakers", "Memphis|Grizzlies", "Miami|Heat", "Milwaukee|Bucks",
            "Minnesota|Timberwolves|Wolves", "New Orleans|Pelicans", "New York|Knicks", "Oklahoma City|Thunder", "Orlando|Magic",
            "Philadelphia|76ers|Sixers", "Phoenix|Suns", "Portland|Trail Blazers|Blazers", "Sacramento|Kings",
            "San Antonio|Spurs", "Toronto|Raptors", "Utah|Jazz", "Washington|Wizards",
            // WNBA
            "Atlanta|Dream", "Chicago|Sky", "Connecticut|Sun", "Dallas|Wings", "Golden State|Valkyries", "Indiana|Fever",
            "Las Vegas|Aces", "Los Angeles|Sparks", "Minnesota|Lynx", "New York|Liberty", "Phoenix|Mercury", "Seattle|Storm",
            "Washington|Mystics", "Toronto|Tempo", "Portland|Fire"
        ),
        Sport.NFL to listOf(
            "Arizona|Cardinals", "Atlanta|Falcons", "Baltimore|Ravens", "Buffalo|Bills", "Carolina|Panthers", "Chicago|Bears",
            "Cincinnati|Bengals", "Cleveland|Browns", "Dallas|Cowboys", "Denver|Broncos", "Detroit|Lions", "Green Bay|Packers",
            "Houston|Texans", "Indianapolis|Colts", "Jacksonville|Jaguars|Jags", "Kansas City|Chiefs", "Las Vegas|Raiders",
            "Los Angeles|Chargers", "Los Angeles|Rams", "Miami|Dolphins", "Minnesota|Vikings", "New England|Patriots|Pats",
            "New Orleans|Saints", "New York|Giants", "New York|Jets", "Philadelphia|Eagles", "Pittsburgh|Steelers",
            "San Francisco|49ers|Niners", "Seattle|Seahawks", "Tampa Bay|Buccaneers|Bucs", "Tennessee|Titans",
            "Washington|Commanders"
        ),
        Sport.MLB to listOf(
            "Arizona|Diamondbacks|D-backs,Dbacks", "Atlanta|Braves", "Baltimore|Orioles", "Boston|Red Sox", "Chicago|Cubs",
            "Chicago|White Sox", "Cincinnati|Reds", "Cleveland|Guardians", "Colorado|Rockies", "Detroit|Tigers",
            "Houston|Astros", "Kansas City|Royals", "Los Angeles|Angels", "Los Angeles|Dodgers", "Miami|Marlins",
            "Milwaukee|Brewers", "Minnesota|Twins", "New York|Mets", "New York|Yankees", "|Athletics", "Philadelphia|Phillies",
            "Pittsburgh|Pirates", "San Diego|Padres", "San Francisco|Giants", "Seattle|Mariners", "St Louis|Cardinals",
            "Tampa Bay|Rays", "Texas|Rangers", "Toronto|Blue Jays", "Washington|Nationals|Nats"
        ),
        Sport.NHL to listOf(
            "Anaheim|Ducks", "Boston|Bruins", "Buffalo|Sabres", "Calgary|Flames", "Carolina|Hurricanes|Canes", "Chicago|Blackhawks",
            "Colorado|Avalanche|Avs", "Columbus|Blue Jackets", "Dallas|Stars", "Detroit|Red Wings", "Edmonton|Oilers",
            "Florida|Panthers", "Los Angeles|Kings", "Minnesota|Wild", "Montreal|Canadiens|Habs", "Nashville|Predators|Preds",
            "New Jersey|Devils", "New York|Islanders", "New York|Rangers", "Ottawa|Senators|Sens", "Philadelphia|Flyers",
            "Pittsburgh|Penguins", "San Jose|Sharks", "Seattle|Kraken", "St Louis|Blues", "Tampa Bay|Lightning",
            "Toronto|Maple Leafs|Leafs", "Utah|Mammoth", "Vancouver|Canucks", "Vegas|Golden Knights", "Washington|Capitals|Caps",
            "Winnipeg|Jets"
        )
    )

    /** Other spellings of team cities in titles. */
    val CITY_ALIASES: Map<String, List<String>> = mapOf(
        "LOS ANGELES" to listOf("LA", "L A"), "NEW YORK" to listOf("NY"), "ST LOUIS" to listOf("SAINT LOUIS"),
        "OKLAHOMA CITY" to listOf("OKC"), "GOLDEN STATE" to listOf("GS")
    )

    /** "A vs B", "A vs. B", "A v B", "A @ B", "A at B" – the two sides of a game title. */
    private val GAME_SPLIT = Regex("""\s+(?:vs\.?|v\.?|@|at)\s+""", RegexOption.IGNORE_CASE)

    /** Team phrases → leagues: strong = "City Nickname", nickname, alias; weak = city alone. Built once. */
    private class TeamIndex {
        val strong = HashMap<String, MutableSet<Sport>>()
        val weak = HashMap<String, MutableSet<Sport>>()
        init {
            for ((league, list) in TEAMS) for (t in list) {
                val f = t.split('|')
                val city = norm(f.getOrElse(0) { "" }); val nick = norm(f.getOrElse(1) { "" })
                val aliases = f.getOrElse(2) { "" }.split(',').map { norm(it) }.filter { it.isNotEmpty() }
                val cities = listOf(city) + (CITY_ALIASES[city] ?: emptyList())
                fun add(m: HashMap<String, MutableSet<Sport>>, k: String) { if (k.isNotEmpty()) m.getOrPut(pad(k)) { HashSet() }.add(league) }
                add(strong, nick)
                for (a in aliases) add(strong, a)
                for (c in cities) if (c.isNotEmpty()) { add(strong, "$c $nick"); add(weak, c) }
            }
        }
    }
    private val teamIndex by lazy { TeamIndex() }

    /** Leagues one side of a game title names, and whether by a team name (strong) or only a city (weak). */
    private fun side(text: String): Pair<Set<Sport>, Boolean> {
        val n = pad(norm(text))
        val strong = HashSet<Sport>()
        for ((k, v) in teamIndex.strong) if (n.contains(k)) strong.addAll(v)
        if (strong.isNotEmpty()) return strong to true
        val weak = HashSet<Sport>()
        for ((k, v) in teamIndex.weak) if (n.contains(k)) weak.addAll(v)
        return weak to false
    }

    /**
     * League of a pro game from its team names: "Golden State Warriors at Houston Rockets", "Warriors at Rockets",
     * "Kings at Rangers" (NHL – the only league both share). Both sides must name a team or a team city, at least one by
     * its team name; the leagues of both sides must leave exactly one. Otherwise null.
     */
    fun teamSport(title: String): Sport? {
        val parts = title.split(GAME_SPLIT, limit = 2)
        if (parts.size < 2) return null
        val (a, aStrong) = side(parts[0])
        val (b, bStrong) = side(parts[1])
        if (a.isEmpty() || b.isEmpty() || (!aStrong && !bStrong)) return null
        return a.intersect(b).singleOrNull()
    }

    // ================================================================== college schools (data – tune here)

    /**
     * FBS football schools and major Division I basketball schools as "School|Nickname|alias,alias". A school counts only
     * in a game title, with a school, short name or nickname on both sides ([gameSport]). For schools ending in "State",
     * "X St" is added automatically ("Ohio St.").
     */
    val SCHOOLS: List<String> = listOf(
        // ACC
        "Boston College|Eagles|BC", "California|Golden Bears|Cal", "Clemson|Tigers", "Duke|Blue Devils", "Florida State|Seminoles|FSU",
        "Georgia Tech|Yellow Jackets", "Louisville|Cardinals", "Miami|Hurricanes|The U", "NC State|Wolfpack|North Carolina State",
        "North Carolina|Tar Heels|UNC", "Pittsburgh|Panthers|Pitt", "SMU|Mustangs|Southern Methodist", "Stanford|Cardinal",
        "Syracuse|Orange", "Virginia|Cavaliers|UVA", "Virginia Tech|Hokies", "Wake Forest|Demon Deacons",
        // Big Ten
        "Illinois|Fighting Illini|Illini", "Indiana|Hoosiers", "Iowa|Hawkeyes", "Maryland|Terrapins|Terps", "Michigan|Wolverines",
        "Michigan State|Spartans", "Minnesota|Golden Gophers|Gophers", "Nebraska|Cornhuskers|Huskers", "Northwestern|Wildcats",
        "Ohio State|Buckeyes", "Oregon|Ducks", "Penn State|Nittany Lions", "Purdue|Boilermakers", "Rutgers|Scarlet Knights",
        "UCLA|Bruins", "USC|Trojans|Southern California", "Washington|Huskies|UW", "Wisconsin|Badgers",
        // Big 12
        "Arizona|Wildcats", "Arizona State|Sun Devils|ASU", "Baylor|Bears", "BYU|Cougars|Brigham Young", "Cincinnati|Bearcats",
        "Colorado|Buffaloes|Buffs", "Houston|Cougars", "Iowa State|Cyclones", "Kansas|Jayhawks|KU", "Kansas State|Wildcats|K-State",
        "Oklahoma State|Cowboys", "TCU|Horned Frogs|Texas Christian", "Texas Tech|Red Raiders", "UCF|Knights|Central Florida",
        "Utah|Utes", "West Virginia|Mountaineers|WVU",
        // SEC
        "Alabama|Crimson Tide|Bama", "Arkansas|Razorbacks|Hogs", "Auburn|Tigers", "Florida|Gators", "Georgia|Bulldogs|UGA",
        "Kentucky|Wildcats|UK", "LSU|Tigers|Louisiana State", "Mississippi State|Bulldogs|Miss State,Miss St", "Missouri|Tigers|Mizzou",
        "Oklahoma|Sooners|OU", "Ole Miss|Rebels|Mississippi", "South Carolina|Gamecocks", "Tennessee|Volunteers|Vols",
        "Texas|Longhorns|UT", "Texas A&M|Aggies|TAMU", "Vanderbilt|Commodores|Vandy",
        // Pac-12
        "Oregon State|Beavers", "Washington State|Cougars|Wazzu",
        // American
        "Army|Black Knights", "Charlotte|49ers", "East Carolina|Pirates|ECU", "Florida Atlantic|Owls|FAU", "Memphis|Tigers",
        "Navy|Midshipmen", "North Texas|Mean Green", "Rice|Owls", "South Florida|Bulls|USF", "Temple|Owls", "Tulane|Green Wave",
        "Tulsa|Golden Hurricane", "UAB|Blazers", "UTSA|Roadrunners",
        // Mountain West
        "Air Force|Falcons", "Boise State|Broncos", "Colorado State|Rams", "Fresno State|Bulldogs", "Hawaii|Rainbow Warriors",
        "Nevada|Wolf Pack", "New Mexico|Lobos", "San Diego State|Aztecs|SDSU", "San Jose State|Spartans", "UNLV|Rebels",
        "Utah State|Aggies", "Wyoming|Cowboys",
        // Sun Belt
        "Appalachian State|Mountaineers|App State", "Arkansas State|Red Wolves", "Coastal Carolina|Chanticleers",
        "Georgia Southern|Eagles", "Georgia State|Panthers", "James Madison|Dukes|JMU", "Louisiana|Ragin Cajuns|Louisiana Lafayette",
        "Louisiana Monroe|Warhawks|ULM", "Marshall|Thundering Herd", "Old Dominion|Monarchs|ODU", "South Alabama|Jaguars",
        "Southern Miss|Golden Eagles|Southern Mississippi", "Texas State|Bobcats", "Troy|Trojans",
        // MAC
        "Akron|Zips", "Ball State|Cardinals", "Bowling Green|Falcons", "Buffalo|Bulls", "Central Michigan|Chippewas",
        "Eastern Michigan|Eagles", "Kent State|Golden Flashes", "Miami OH|RedHawks|Miami Ohio", "Northern Illinois|Huskies|NIU",
        "Ohio|Bobcats", "Toledo|Rockets", "Western Michigan|Broncos",
        // Conference USA
        "Delaware|Blue Hens", "FIU|Panthers|Florida International", "Jacksonville State|Gamecocks", "Kennesaw State|Owls",
        "Liberty|Flames", "Louisiana Tech|Bulldogs", "Middle Tennessee|Blue Raiders|MTSU", "Missouri State|Bears",
        "New Mexico State|Aggies", "Sam Houston|Bearkats", "UTEP|Miners", "Western Kentucky|Hilltoppers|WKU",
        // Independents
        "Notre Dame|Fighting Irish", "UConn|Huskies|Connecticut",
        // Major Division I basketball (not FBS)
        "Villanova|Wildcats|Nova", "Georgetown|Hoyas", "St Johns|Red Storm|Saint Johns", "Seton Hall|Pirates", "Providence|Friars",
        "Marquette|Golden Eagles", "Creighton|Bluejays", "Xavier|Musketeers", "Butler|Bulldogs", "DePaul|Blue Demons",
        "Gonzaga|Bulldogs|Zags", "Saint Marys|Gaels|St Marys", "Dayton|Flyers", "VCU|Rams", "Saint Louis|Billikens|SLU",
        "Loyola Chicago|Ramblers", "Wichita State|Shockers", "Drake|Bulldogs", "Saint Josephs|Hawks|St Josephs",
        "St Bonaventure|Bonnies", "George Mason|Patriots", "Davidson|Wildcats", "Richmond|Spiders", "Grand Canyon|Antelopes|GCU",
        "San Francisco|Dons", "Santa Clara|Broncos", "Princeton|Tigers", "Yale|Bulldogs", "Belmont|Bruins", "Murray State|Racers",
        "Bradley|Braves", "McNeese|Cowboys", "UC Irvine|Anteaters", "Vermont|Catamounts", "Furman|Paladins", "Charleston|Cougars"
    )

    /** Channels that only (or mostly) show college sport – a college signal when a matchup could also be pro. */
    val COLLEGE_CHANNEL_WORDS = listOf(
        "ESPNU", "SEC NETWORK", "SECN", "BIG TEN NETWORK", "BTN", "ACC NETWORK", "ACCN", "PAC 12", "PAC12", "PAC 12 NETWORK",
        "CBS SPORTS NETWORK", "CBSSN", "LONGHORN NETWORK"
    )
    /** Words in title or description that say college. */
    val COLLEGE_WORDS = listOf("COLLEGE", "NCAA", "NCAAF", "NCAAB", "NCAAM", "NCAAW")
    /** Words in title or description that say which college sport. Men's / women's → basketball (football isn't). */
    val FOOTBALL_WORDS = listOf("FOOTBALL", "FUTBOL AMERICANO")
    val BASKETBALL_WORDS = listOf("BASKETBALL", "BALONCESTO", "BASQUETBOL", "MEN S", "WOMEN S", "MENS", "WOMENS")
    /** Slot length decides when no sport word is there: ≥ 3 h football, ≤ 2.5 h basketball, else unknown. */
    const val FOOTBALL_MIN_MINUTES = 180
    const val BASKETBALL_MAX_MINUTES = 150

    /** School matching ignores apostrophes: "Saint Mary's" = "Saint Marys", "St. John's" = "St Johns". */
    private fun schoolNorm(s: String?): String = norm(s?.replace("'", "")?.replace("’", ""))

    private val schoolPhrases by lazy {
        val out = HashSet<String>()
        for (t in SCHOOLS) {
            val f = t.split('|')
            val school = schoolNorm(f[0]); val nick = schoolNorm(f.getOrElse(1) { "" })
            val aliases = f.getOrElse(2) { "" }.split(',').map { schoolNorm(it) }.filter { it.isNotEmpty() }
            out.add(pad(school)); if (nick.isNotEmpty()) { out.add(pad(nick)); out.add(pad("$school $nick")) }
            if (school.endsWith(" STATE")) out.add(pad(school.removeSuffix(" STATE") + " ST"))
            for (a in aliases) out.add(pad(a))
        }
        out
    }
    private val collegeChanW by lazy { words(COLLEGE_CHANNEL_WORDS) }
    private val collegeW by lazy { words(COLLEGE_WORDS) }
    private val footballW by lazy { words(FOOTBALL_WORDS) }
    private val basketballW by lazy { words(BASKETBALL_WORDS) }

    /** Does this side of a game title name a school (name, short name or nickname)? */
    private fun schoolSide(text: String): Boolean {
        val n = pad(schoolNorm(text))
        return schoolPhrases.any { n.contains(it) }
    }

    /**
     * What a game title's two sides say: [pro] = the one pro league both name (≥ 1 by team name), [proPossible] = both
     * sides could be pro (team names or team cities, a league in common), [college] = both sides name a school.
     */
    class GameSides(val pro: Sport?, val proPossible: Boolean, val college: Boolean)

    fun gameSides(title: String): GameSides? {
        val parts = title.split(GAME_SPLIT, limit = 2)
        if (parts.size < 2) return null
        val (a, aStrong) = side(parts[0])
        val (b, bStrong) = side(parts[1])
        val both = a.isNotEmpty() && b.isNotEmpty()
        val common = if (both) a.intersect(b) else emptySet()
        val pro = if (both && (aStrong || bStrong)) common.singleOrNull() else null
        return GameSides(pro, both && common.isNotEmpty(), schoolSide(parts[0]) && schoolSide(parts[1]))
    }

    fun isCollegeChannel(c: Chan): Boolean = anyWord(c.nName, collegeChanW)

    /** Football or basketball for a college game: sport words in title / description, then slot length, else OTHER. */
    fun collegeSport(p: Prog): Sport {
        val text = pad(norm(p.title) + " " + norm(p.desc) + " " + p.categories.joinToString(" ") { norm(it) })
        val fb = anyWord(text, footballW); val bb = anyWord(text, basketballW)
        if (fb && !bb) return Sport.COLLEGE_FOOTBALL
        if (bb && !fb) return Sport.COLLEGE_BASKETBALL
        if (fb && bb) return Sport.OTHER
        val minutes = ((p.stop - p.start) / 60).toInt()
        return when {
            minutes >= FOOTBALL_MIN_MINUTES -> Sport.COLLEGE_FOOTBALL
            minutes in 1..BASKETBALL_MAX_MINUTES -> Sport.COLLEGE_BASKETBALL
            else -> Sport.OTHER
        }
    }

    /**
     * Sport group of a game title from its teams / schools (null = the title is no recognisable game):
     * 1. both sides pro team names (one league) → that pro league – pro names keep priority;
     * 2. both sides schools: if the sides could also be pro (e.g. "Arizona at Washington"), a pro league word in the
     *    description decides for pro, a college signal (college channel, "college" / "NCAA") for college, else OTHER;
     *    a college game then gets football / basketball from [collegeSport] (OTHER when unclear – never a guess).
     */
    fun gameSport(f: Facts): Sport? {
        val g = f.pt.game ?: return null
        g.pro?.let { return it }
        if (!g.college) return null
        if (g.proPossible) {
            val desc = f.pt.descN
            proLeagueWord(desc)?.let { return it }
            val collegeSignal = isCollegeChannel(f.chan) || anyWord(f.pt.title, collegeW) || anyWord(desc, collegeW)
            if (!collegeSignal) return Sport.OTHER
        }
        return collegeSport(f.prog)
    }

    private val proLeagueWords by lazy { mapOf(Sport.NFL to pad("NFL"), Sport.NBA to pad("NBA"), Sport.MLB to pad("MLB"), Sport.NHL to pad("NHL")) }
    private fun proLeagueWord(text: String): Sport? = proLeagueWords.entries.firstOrNull { text.contains(it.value) }?.key

    // ================================================================== text helpers

    /**
     * Text for matching: accents removed, upper case, every run of non-letters/digits → one space, trimmed.
     * "Fútbol: América vs. Chivas!" → "FUTBOL AMERICA VS CHIVAS". Letters of other scripts are kept.
     */
    fun norm(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val d = Normalizer.normalize(s, Normalizer.Form.NFD)
        val sb = StringBuilder(d.length)
        var space = true
        for (ch in d) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (Character.isLetterOrDigit(ch)) { sb.append(ch.uppercaseChar()); space = false }
            else if (!space) { sb.append(' '); space = true }
        }
        return sb.toString().trimEnd()
    }

    private fun pad(n: String) = " $n "
    private fun words(raw: List<String>) = raw.map { pad(norm(it)) }
    private fun parts(raw: List<String>) = raw.map { norm(it) }

    private val ppvCatW by lazy { words(PPV_CATEGORY_WORDS) }
    private val ppvNameW by lazy { words(PPV_NAME_WORDS) }
    private val slotWords by lazy { PPV_SLOT_WORDS.map { norm(it) }.toHashSet() }
    private val COUNTRY_PREFIX = Regex("""^\s*(?:\[[A-Za-z]{2,3}\]|[A-Za-z]{2,3}\s*[:|]|[A-Za-z]{2,3}\s+-)\s*""")
    private val NUMBERISH = Regex("""\d+(?:ST|ND|RD|TH)?|PPV\d+""")
    private val intlChanW by lazy { words(INTL_FOOTBALL_CHANNEL_WORDS) }
    private val intlGroupW by lazy { words(INTL_GROUP_WORDS) }
    private val soccerDefaultW by lazy { words(SOCCER_DEFAULT_CHANNEL_WORDS) }
    private val soccerCtxW by lazy { words(SOCCER_CONTEXT_WORDS) }
    private val horseChanW by lazy { words(HORSE_RACING_CHANNEL_WORDS) }
    private val horseTitleW by lazy { words(HORSE_RACING_TITLE_WORDS) }
    private val sportTitleW by lazy { words(SPORT_TITLE_WORDS) }
    private val junkW = words(JUNK_WORDS)
    private val sportCatP = parts(SPORT_CATEGORY_PARTS)
    private val sportCatW = words(SPORT_CATEGORY_WORDS)
    private val sportGroupP = parts(SPORT_GROUP_PARTS)
    private val otherCatW = words(OTHER_CATEGORY_WORDS)
    private val otherTitleW = words(OTHER_TITLE_WORDS)
    private val otherGroupW = words(OTHER_GROUP_WORDS)
    private val movieCatP = parts(MOVIE_CATEGORY_PARTS)
    private val movieGroupP = parts(MOVIE_GROUP_PARTS)
    private val showCatP = parts(SHOW_CATEGORY_PARTS)
    private val entGroupP = parts(ENTERTAINMENT_GROUP_PARTS)
    private val entGroupW = words(ENTERTAINMENT_GROUP_WORDS)
    private val placeholders by lazy { PLACEHOLDER_TITLES.map { norm(it) }.toHashSet() }

    private fun anyWord(text: String, list: List<String>) = list.any { text.contains(it) }
    private fun anyPart(text: String, list: List<String>) = list.any { text.contains(it) }

    /** Empty or placeholder title ("To Be Announced", "No information", "Off Air" …). */
    fun isPlaceholder(title: String?): Boolean {
        if (title.isNullOrBlank()) return true
        val n = norm(title)
        return n.isEmpty() || n in placeholders
    }

    /** [nGroup] = padded [norm] of an Xtream category. */
    internal fun isPpvCategoryText(nGroup: String): Boolean = anyWord(nGroup, ppvCatW)

    /** [nName] = padded [norm] of a channel name. */
    internal fun isPpvNameText(nName: String): Boolean = anyWord(nName, ppvNameW) || PPV_NUMBERED.containsMatchIn(nName)

    fun isPpvCategory(group: String): Boolean = isPpvCategoryText(pad(norm(group)))
    fun isPpvName(name: String): Boolean = isPpvNameText(pad(norm(name)))

    /**
     * The name of a PPV channel without an event in it: what is left after the country prefix, numbers and [PPV_SLOT_WORDS]
     * is empty, or only a placeholder text ("PPV 23: No Event").
     */
    fun isPpvPlaceholderName(name: String): Boolean {
        val tokens = norm(name.replace(COUNTRY_PREFIX, "")).split(' ').filter { it.isNotEmpty() && !NUMBERISH.matches(it) }
        if (tokens.none { it !in slotWords }) return true
        // "PPV 23: No Event" – what follows the PPV words is a placeholder text (its "EVENT" is a slot word, so check it whole)
        val rest = tokens.filter { it !in ppvWordsOnly }.joinToString(" ")
        return rest in placeholders
    }

    private val ppvWordsOnly by lazy { setOf("PPV", "PAY", "PER", "VIEW", "PAYPERVIEW") }

    // ================================================================== on-now window and progress

    /** On now: start ≤ now < stop. */
    fun isOnNow(start: Long, stop: Long, now: Long): Boolean = start <= now && now < stop

    /** (now − start) / (stop − start), clamped to 0..1 (0 for a broken programme). */
    fun progress(start: Long, stop: Long, now: Long): Float =
        if (stop <= start) 0f else ((now - start).toDouble() / (stop - start)).coerceIn(0.0, 1.0).toFloat()

    /** Whole minutes since the start (never negative). */
    fun elapsedMin(start: Long, now: Long): Int = ((now - start).coerceAtLeast(0) / 60).toInt()

    /** Minutes left, rounded up (59 s left = 1 min; never negative). */
    fun remainingMin(stop: Long, now: Long): Int = (((stop - now).coerceAtLeast(0) + 59) / 60).toInt()

    // ================================================================== classification

    /** Normalised texts of one programme, computed once and shared by all channels that carry it. */
    class ProgText(val prog: Prog) {
        val nTitle: String = norm(prog.title)
        val title = pad(nTitle)
        val cats = pad(prog.categories.joinToString(" ") { norm(it) })
        val gameTitle: Boolean = GAME_TITLE.containsMatchIn(prog.title)
        /** League named by the teams of a game title ([teamSport]), null if none – computed only when asked. */
        val teamSport: Sport? by lazy(LazyThreadSafetyMode.NONE) { game?.pro }
        /** Teams / schools of a game title ([gameSides]), null if the title is no game – computed only when asked. */
        val game: GameSides? by lazy(LazyThreadSafetyMode.NONE) { if (gameTitle) gameSides(prog.title) else null }
        /** Normalised description (padded) – for college / league words. */
        val descN: String by lazy(LazyThreadSafetyMode.NONE) { pad(norm(prog.desc)) }
        val placeholder: Boolean = nTitle.isEmpty() || nTitle in placeholders
        val mergeKey: String = nTitle.ifEmpty { prog.title.trim().lowercase() } + "\u0001" + prog.start
    }

    /** Pre-normalised texts of one programme on one channel (padded with spaces for whole-word matching). */
    class Facts(val pt: ProgText, val chan: Chan) {
        constructor(prog: Prog, chan: Chan) : this(ProgText(prog), chan)
        val prog: Prog get() = pt.prog
        val title: String get() = pt.title
        val cats: String get() = pt.cats
        val name: String get() = chan.nName
        val group: String get() = chan.nGroup
        val all: String by lazy(LazyThreadSafetyMode.NONE) { pt.title + pt.cats + chan.nName + chan.nGroup }
        val minutes = ((pt.prog.stop - pt.prog.start) / 60).toInt()
    }

    /** UK / international channel, where "Football" is soccer ([INTL_FOOTBALL_CHANNEL_WORDS], [INTL_GROUP_WORDS]). */
    fun isIntlFootballChannel(c: Chan): Boolean = anyWord(c.nName, intlChanW) || anyWord(c.nGroup, intlGroupW)

    fun isHorseRacingChannel(c: Chan): Boolean = anyWord(c.nName, horseChanW)

    private fun hasSportKeyword(f: Facts): Boolean =
        anyWord(f.all, sportCatW) || SPORT_RULES.any { r -> r.words.any { f.all.contains(it) } && (r.ctx.isEmpty() || r.ctx.any { f.all.contains(it) }) }

    /** The title itself says sport: a sport / league keyword, a game, or a word like Live, Highlights, Cup, Grand Prix. */
    fun hasSportTitleSignal(f: Facts): Boolean =
        f.pt.gameTitle || anyWord(f.title, sportTitleW) || anyWord(f.title, sportCatW) ||
            SPORT_RULES.any { it.matches(f.title, f) } ||
            (isHorseRacingChannel(f.chan) && anyWord(f.title, horseTitleW))

    private fun sportsGroup(f: Facts): Boolean = anyPart(f.group, sportGroupP)

    /**
     * Sports: the EPG category says so; or a sports Xtream category (or a racing channel) AND a sports signal in the title;
     * or a game title with a sport keyword anywhere. A sports category alone is not enough (see [SPORT_TITLE_WORDS]).
     */
    private fun isSports(f: Facts): Boolean =
        anyPart(f.cats, sportCatP) || anyWord(f.cats, sportCatW) ||
            ((sportsGroup(f) || isHorseRacingChannel(f.chan)) && hasSportTitleSignal(f)) ||
            (f.pt.gameTitle && (hasSportKeyword(f) || gameSport(f) != null))

    /** Class of [prog] on [chan]; null = placeholder title (shown nowhere). */
    fun classify(prog: Prog, chan: Chan): OnNowClass? = if (isPlaceholder(prog.title)) null else classify(Facts(prog, chan))

    fun classify(f: Facts): OnNowClass {
        val p = f.prog
        if (f.chan.isEvent) return OnNowClass.EVENT
        if (anyWord(f.title, junkW) || anyWord(f.cats, junkW)) return OnNowClass.OTHER
        if (isSports(f)) return OnNowClass.SPORTS
        if (anyWord(f.cats, otherCatW) || anyWord(f.title, otherTitleW) || anyWord(f.group, otherGroupW)) return OnNowClass.OTHER
        if (anyPart(f.cats, movieCatP)) return OnNowClass.MOVIE
        val movieGroup = anyPart(f.group, movieGroupP)
        val showCat = anyPart(f.cats, showCatP)
        if (movieGroup && p.episode == null && !showCat && f.minutes >= MOVIE_MIN_MINUTES) return OnNowClass.MOVIE
        // a sports channel's programme without any sports signal (a property or cooking show) is a show
        if (showCat || p.episode != null || movieGroup || anyPart(f.group, entGroupP) || anyWord(f.group, entGroupW) || sportsGroup(f)) return OnNowClass.SHOW
        return OnNowClass.OTHER
    }

    /** Sport group of a Sports item: title, then EPG categories, then channel name, then Xtream category. */
    fun sportOf(f: Facts): Sport {
        for (r in SPORT_RULES) if (r.matches(f.title, f)) return r.sportFor(f)
        gameSport(f)?.let { return it }
        for (text in listOf(f.cats, f.name, f.group)) for (r in SPORT_RULES) if (r.matches(text, f)) return r.sportFor(f)
        // beIN: sport that matches nothing else is soccer
        if (anyWord(f.chan.nName, soccerDefaultW)) return Sport.SOCCER
        return Sport.OTHER
    }

    fun sportOf(prog: Prog, chan: Chan): Sport = sportOf(Facts(prog, chan))

    // ================================================================== merging

    /** 4K > FHD > HD > anything else. */
    fun qualityRank(q: String?): Int = when (q?.uppercase()) { "4K", "UHD" -> 4; "FHD" -> 3; "HD" -> 2; else -> 1 }

    /** Sources best first: highest quality, then name. */
    val SOURCE_ORDER: Comparator<Chan> = compareByDescending<Chan> { qualityRank(it.quality) }.thenBy { it.name.lowercase() }

    /** Same programme on several channels: same normalised title and same start. */
    fun mergeKey(p: Prog): String = (norm(p.title).ifEmpty { p.title.trim().lowercase() }) + "\u0001" + p.start

    /**
     * Everything on now as cards: placeholders dropped, each programme classified on each of its channels, identical
     * programmes merged (the strongest class wins: Event > Sports > Movie > Show > Other), sources best quality first.
     * [chansByTvg] = live channels by lower-case tvg-id. Sorted by start, most recently started first.
     * [include] picks the programmes (default: on now); [upcoming] uses it for "coming up".
     */
    fun build(
        progs: List<Prog>, chansByTvg: Map<String, List<Chan>>, now: Long,
        include: (Prog) -> Boolean = { isOnNow(it.start, it.stop, now) }
    ): List<OnNowItem> {
        class Acc(var cls: OnNowClass, var best: Facts, val sources: LinkedHashMap<String, Chan>)
        val acc = LinkedHashMap<String, Acc>()
        for (p in progs) {
            if (!include(p)) continue
            val chans = chansByTvg[p.tvgId] ?: chansByTvg[p.tvgId.lowercase()] ?: continue
            val pt = ProgText(p)
            if (pt.placeholder) continue
            val key = pt.mergeKey
            for (ch in chans) {
                val f = Facts(pt, ch)
                val c = classify(f)
                val a = acc[key]
                if (a == null) acc[key] = Acc(c, f, linkedMapOf(ch.id to ch))
                else {
                    a.sources[ch.id] = ch
                    if (c.ordinal < a.cls.ordinal) { a.cls = c; a.best = f }
                }
            }
        }
        return acc.values.map { a ->
            OnNowItem(a.cls, if (a.cls == OnNowClass.SPORTS) sportOf(a.best) else null, a.best.prog, a.sources.values.sortedWith(SOURCE_ORDER))
        }.sortedWith(compareByDescending<OnNowItem> { it.prog.start }.thenBy { it.title.lowercase() })
    }

    /** Programmes starting in [now, now + [hours]) as cards, soonest first (Events "Coming up", Sports "Starting soon"). */
    fun upcoming(progs: List<Prog>, chansByTvg: Map<String, List<Chan>>, now: Long, hours: Int = 24): List<OnNowItem> =
        build(progs, chansByTvg, now) { it.start >= now && it.start < now + hours * 3600L }
            .sortedWith(compareBy<OnNowItem> { it.prog.start }.thenBy { it.title.lowercase() })

    // ================================================================== events

    /** PPV / event channel: a PPV/event Xtream category, or an unmistakable PPV name (rule 1 of [classify]). */
    fun isEventChannel(chan: Chan): Boolean = chan.isEvent

    private val RUNS = Regex("""\d+|\D+""")

    /** "PPV 2" before "PPV 10": compares runs of digits as numbers. For many items sort by a precomputed key ([EventIndex]). */
    val NATURAL: Comparator<String> = Comparator { a, b -> NatKey(a).compareTo(NatKey(b)) }

    /**
     * The Events screen: live now, coming up (next 24 h), and PPV channels with nothing listed that have an event name.
     * [hiddenPlaceholders] = PPV slots with nothing listed and no event name ("PPV 23"): not shown, only counted.
     */
    class Events(val live: List<OnNowItem>, val upcoming: List<OnNowItem>, val channels: List<Chan>, val hiddenPlaceholders: Int = 0)

    /** Natural-order sort key, computed once per channel: text runs, and digit runs compared as numbers. */
    private class NatKey(s: String) : Comparable<NatKey> {
        private val runs: List<String> = RUNS.findAll(s.lowercase()).map { it.value }.toList()
        override fun compareTo(other: NatKey): Int {
            val a = runs; val b = other.runs
            for (i in 0 until minOf(a.size, b.size)) {
                val x = a[i]; val y = b[i]
                val c = if (x[0].isDigit() && y[0].isDigit()) {
                    val xs = x.trimStart('0'); val ys = y.trimStart('0')
                    when { xs.length != ys.length -> xs.length - ys.length; xs != ys -> xs.compareTo(ys); else -> x.length - y.length }
                } else x.compareTo(y)
                if (c != 0) return c
            }
            return a.size - b.size
        }
    }

    /**
     * The PPV / event channels of one playlist, prepared once per playlist load (not on every refresh): natural order by
     * name, by tvg-id for "coming up", and the numbers for the debug screen.
     */
    class EventIndex(allChans: List<Chan>) {
        /** All PPV / event channels, natural order by name ("PPV 2" before "PPV 10"). */
        val channels: List<Chan>
        /** Event channels by lower-case tvg-id. */
        val byTvg: Map<String, List<Chan>>
        val tvgIds: Set<String> get() = byTvg.keys
        /** PPV categories: Xtream category name → event channels in it, most channels first. */
        val categories: List<Pair<String, Int>>
        /** Event channels found only by a PPV name, in a category that is not a PPV category. */
        val byNameOnly: Int
        /** Event channels that are placeholder slots ("PPV 23"). */
        val placeholders: Int

        init {
            channels = allChans.filter { it.isEvent }.map { it to NatKey(it.name) }.sortedBy { it.second }.map { it.first }
            val m = HashMap<String, MutableList<Chan>>()
            for (c in channels) { val k = c.tvgId?.trim()?.lowercase()?.ifEmpty { null } ?: continue; m.getOrPut(k) { ArrayList(2) }.add(c) }
            byTvg = m
            categories = channels.filter { it.ppvCategory }.groupingBy { it.group }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key to it.value }
            byNameOnly = channels.count { !it.ppvCategory }
            placeholders = channels.count { it.ppvPlaceholder }
        }
    }

    /**
     * Events from all live channels: event programmes on now and starting within 24 h, plus every PPV/event channel that
     * has neither – no guide rows at all, or only placeholders ("No Event Scheduled"). Those are listed under
     * "PPV channels" with the channel name as their title ([eventTitle]) so they stay reachable.
     */
    fun events(onNow: List<OnNowItem>, upcomingProgs: List<Prog>, allChans: List<Chan>, now: Long): Events =
        events(onNow, upcomingProgs, EventIndex(allChans), now)

    /** Same, with the event channels prepared once per playlist ([EventIndex]). */
    fun events(onNow: List<OnNowItem>, upcomingProgs: List<Prog>, idx: EventIndex, now: Long): Events {
        val live = onNow.filter { it.cls == OnNowClass.EVENT }
        val next = upcoming(upcomingProgs, idx.byTvg, now).filter { it.cls == OnNowClass.EVENT }
        val listed = HashSet<String>()
        for (i in live) for (s in i.sources) listed.add(s.id)
        for (i in next) for (s in i.sources) listed.add(s.id)
        val named = ArrayList<Chan>()
        var hidden = 0
        for (c in idx.channels) {
            if (c.id in listed) continue
            if (c.ppvPlaceholder) hidden++ else named.add(c)
        }
        return Events(live, next, named, hidden)
    }

    /** Title of a PPV channel without guide data: its name. */
    fun eventTitle(chan: Chan): String = chan.name.trim()

    fun countByClass(items: List<OnNowItem>): Map<OnNowClass, Int> =
        OnNowClass.entries.associateWith { c -> items.count { it.cls == c } }

    /** Sport groups that have games now, with counts, in [Sport] order. */
    fun countBySport(items: List<OnNowItem>): Map<Sport, Int> =
        Sport.entries.associateWith { s -> items.count { it.cls == OnNowClass.SPORTS && it.sport == s } }.filterValues { it > 0 }
}

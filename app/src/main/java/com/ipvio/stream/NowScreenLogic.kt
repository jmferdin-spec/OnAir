package com.ipvio.stream

/**
 * OnAir Phase 4: pure rules for the Movies, Shows, Sports and Events screens (tested in NowScreenLogicTest).
 * The screens themselves are in NowScreens.kt; the data comes from [OnNow] snapshots.
 * Texts are German source strings like everywhere in the app (translated when shown, see DictD.kt).
 */
object NowScreenLogic {

    /** Movies / Shows sort order (toggle on the screen). */
    enum class Sort { RECENT, CHANNEL }

    /** Which class a tile shows. */
    fun classOf(t: OnAirTile): OnNowClass? = when (t) {
        OnAirTile.MOVIES -> OnNowClass.MOVIE
        OnAirTile.SHOWS -> OnNowClass.SHOW
        OnAirTile.SPORTS -> OnNowClass.SPORTS
        OnAirTile.EVENTS -> OnNowClass.EVENT
        OnAirTile.LIVE -> null
    }

    /** Still on at [now] (finished programmes drop off between two snapshots). */
    fun stillOn(i: OnNowItem, now: Long): Boolean = now < i.prog.stop

    /**
     * Cards of one class, on now at [now]. [Sort.RECENT] = most recently started first (= least time remaining last),
     * [Sort.CHANNEL] = by the best source's channel name.
     */
    fun cards(items: List<OnNowItem>, cls: OnNowClass, now: Long, sort: Sort = Sort.RECENT): List<OnNowItem> {
        val l = items.filter { it.cls == cls && stillOn(it, now) }
        return when (sort) {
            Sort.RECENT -> l.sortedWith(compareByDescending<OnNowItem> { it.prog.start }.thenBy { it.title.lowercase() })
            Sort.CHANNEL -> l.sortedWith(compareBy<OnNowItem, String>(OnNowLogic.NATURAL) { channelName(it) }.thenBy { it.title.lowercase() })
        }
    }

    fun channelName(i: OnNowItem): String = i.sources.firstOrNull()?.name ?: ""

    /** Stable key of a card (focus restore, lazy-list keys): class + merge key. */
    fun key(i: OnNowItem): String = i.cls.name + "|" + OnNowLogic.mergeKey(i.prog)

    /** "45 min", "1h 10m", "2h". */
    fun dur(min: Int): String {
        val m = min.coerceAtLeast(0)
        if (m < 60) return "$m min"
        val h = m / 60; val r = m % 60
        return if (r == 0) "${h}h" else "${h}h ${r}m"
    }

    /** "45 min in · 1h 10m left" (German source text, see DictD.kt). */
    fun timeLine(i: OnNowItem, now: Long): String = "${dur(i.elapsedMin(now))} gelaufen · noch ${dur(i.remainingMin(now))}"

    /** "starts in 25 min" for something that has not started yet. */
    fun startsIn(start: Long, now: Long): String = "beginnt in ${dur((((start - now).coerceAtLeast(0)) + 59).toInt() / 60)}"

    // ------------------------------------------------------------------ Sports

    /** One entry of the Sports screen's left column. */
    data class SportGroup(val sport: Sport, val live: Int, val soon: Int)

    /**
     * Sport groups that have games on now or starting within the hour, in [Sport] order, with counts.
     * [soon] = upcoming items (any class; only Sports ones count).
     */
    fun sportGroups(items: List<OnNowItem>, soon: List<OnNowItem>, now: Long): List<SportGroup> {
        val live = items.filter { it.cls == OnNowClass.SPORTS && stillOn(it, now) }.groupingBy { it.sport ?: Sport.OTHER }.eachCount()
        val next = startingSoon(soon, now).groupingBy { it.sport ?: Sport.OTHER }.eachCount()
        return Sport.entries.mapNotNull { s ->
            val a = live[s] ?: 0; val b = next[s] ?: 0
            if (a == 0 && b == 0) null else SportGroup(s, a, b)
        }
    }

    /** Sports items starting within [minutes] from [now] (and not started yet), soonest first. */
    fun startingSoon(upcoming: List<OnNowItem>, now: Long, minutes: Int = 60): List<OnNowItem> =
        upcoming.filter { it.cls == OnNowClass.SPORTS && it.prog.start > now && it.prog.start < now + minutes * 60L }
            .sortedWith(compareBy<OnNowItem> { it.prog.start }.thenBy { it.title.lowercase() })

    /** The group to show: the remembered one if it still has games, else the first. */
    fun pickSport(groups: List<SportGroup>, remembered: Sport?): Sport? =
        groups.firstOrNull { it.sport == remembered }?.sport ?: groups.firstOrNull()?.sport

    /** Left-column label: "NFL 3", "Soccer 7 · 2 bald" (German source; "bald" = soon). */
    fun groupLabel(g: SportGroup): String = when {
        g.soon == 0 -> "${g.sport.label} ${g.live}"
        g.live == 0 -> "${g.sport.label} · ${g.soon} bald"
        else -> "${g.sport.label} ${g.live} · ${g.soon} bald"
    }

    // ------------------------------------------------------------------ home tiles

    /** Live count for a tile: on-now cards of its class (Events: live events). null for Live TV (channel count instead). */
    fun tileCount(t: OnAirTile, items: List<OnNowItem>, now: Long): Int? {
        val c = classOf(t) ?: return null
        return items.count { it.cls == c && stillOn(it, now) }
    }

    // ------------------------------------------------------------------ Events

    /** Event card title: the programme title, or the channel name when the guide has nothing ([OnNowLogic.eventTitle]). */
    fun eventTitle(i: OnNowItem): String = i.title.ifBlank { channelName(i) }
}

package com.ipvio.stream

/**
 * OnAir Phase 2: the five-tile home screen and the screens it opens (pure logic, tested in OnAirHomeLogicTest).
 * Labels are German source strings like everywhere in the app (translated when shown, see Dict*.kt).
 */
enum class OnAirTile(val label: String) {
    LIVE("Live TV"), MOVIES("Filme"), SHOWS("Shows"), SPORTS("Sport"), EVENTS("Events")
}

/** Where the app is, below the player and IPVIO's detail/browse screens. */
enum class OnAirScreen {
    /** The five tiles – start destination after login / profile choice. */
    HOME,
    /** Standalone TV guide (opened by the Live TV tile, the sidebar's EPG entry or Live TV → TV-Guide). */
    GUIDE,
    /** "Coming in Phase 4" placeholder for Movies, Shows, Sports and Events. */
    SOON,
    /** IPVIO's own home with sidebar (Movies/Series browsing, search, settings) – reached via "On Demand". */
    VOD
}

object OnAirHomeLogic {
    val TILES: List<OnAirTile> = OnAirTile.entries
    /** Focus when the home screen opens for the first time. */
    val INITIAL = OnAirTile.LIVE

    /** Tiles that open a real screen in this phase; the others show the placeholder. */
    fun isReady(t: OnAirTile): Boolean = t == OnAirTile.LIVE

    /** Small line under the label: live channel count for Live TV (cheap), a hint for sections that come later. */
    fun subtitle(t: OnAirTile, liveChannels: Int): String? = when {
        t == OnAirTile.LIVE -> if (liveChannels > 0) "$liveChannels Sender" else null
        else -> "Kommt in Phase 4"
    }

    /**
     * Tiles per row: all five if they fit at [minTileDp] each (with [gapDp] between), otherwise 3 (+ 2 in a second row).
     */
    fun columns(availableDp: Float, minTileDp: Float = 150f, gapDp: Float = 20f): Int =
        if (availableDp >= 5 * minTileDp + 4 * gapDp) 5 else 3

    /** The tiles as rows for [columns] (5 → one row; 3 → 3 + 2). */
    fun rows(columns: Int): List<List<OnAirTile>> = TILES.chunked(columns.coerceIn(1, TILES.size))

    /**
     * Channel the guide opens at: the most recently watched channel that is in [channelIds], else the first one,
     * "" when the list is empty. [history] = watched item ids, newest first.
     */
    fun guideStart(history: List<String>, channelIds: List<String>): String {
        if (channelIds.isEmpty()) return ""
        val set = channelIds.toHashSet()
        return history.firstOrNull { it in set } ?: channelIds.first()
    }

    /** Back from the guide or the placeholder: to the screen it was opened from (IPVIO's Live TV keeps its place). */
    fun backFrom(screen: OnAirScreen, openedFrom: OnAirScreen): OnAirScreen = when (screen) {
        OnAirScreen.GUIDE, OnAirScreen.SOON -> if (openedFrom == OnAirScreen.VOD) OnAirScreen.VOD else OnAirScreen.HOME
        OnAirScreen.VOD -> OnAirScreen.HOME
        OnAirScreen.HOME -> OnAirScreen.HOME
    }
}

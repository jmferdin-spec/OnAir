package com.ipvio.stream

/**
 * OnAir v2 (Phase 7): Multiview rules – pure, tested in MultiviewLogicTest. The screens come in Phase 8/9.
 *
 * - Model for N screens; v2 ships with [MAX_SCREENS] = 2, the layouts are defined up to [DESIGN_MAX] = 4 (2×2) so a 3rd /
 *   4th screen can be added later without changing the rules.
 * - Layouts: [Layout.SPLIT] (side by side 50/50; 3–4 screens: 2×2) and [Layout.ENLARGED] (one large screen ≈ 75 % width at
 *   the top, the others small in a row along the bottom).
 * - The selected screen has the highlight and the audio. SPLIT: Left/Right move the selection; ENLARGED: Up/Down between
 *   the large screen and the small row, Left/Right within the small row.
 * - OK on a screen: Change channel · Pause/Resume · Enlarge/Reduce · Full screen · Remove. Back asks "Exit multiview?";
 *   Yes keeps the selected screen full-screen.
 * - Decoding budget (onn 4K Plus: AVC ≈ one 4K30 or two 1080p60, HEVC ≈ one 4K60 or two 4K30): at most one screen in 4K,
 *   the others use a ≤ 1080p source of the same channel when there is one ("may stutter" note when not).
 */
object MultiviewLogic {
    /** Screens v2 opens at most. */
    const val MAX_SCREENS = 2
    /** Screens the layouts are designed for (2×2). */
    const val DESIGN_MAX = 4

    enum class Layout { SPLIT, ENLARGED }

    /** One screen: which live channel (item id), paused or not. */
    data class Screen(val channelId: String, val paused: Boolean = false)

    /** Multiview at one moment. [selected] has highlight + audio; [large] = the enlarged screen in [Layout.ENLARGED]. */
    data class State(val screens: List<Screen>, val layout: Layout = Layout.SPLIT, val selected: Int = 0, val large: Int = 0) {
        val count: Int get() = screens.size
    }

    /** Multiview starts from the channel playing full-screen; [added] opens next to it and gets the selection. */
    fun start(current: String, added: String): State = State(listOf(Screen(current), Screen(added)), Layout.SPLIT, selected = 1)

    /** Another screen (when there is room, [max] screens); null when full. */
    fun add(s: State, channelId: String, max: Int = MAX_SCREENS): State? =
        if (s.count >= max) null else s.copy(screens = s.screens + Screen(channelId), selected = s.count)

    // ------------------------------------------------------------------ navigation

    enum class Dir { LEFT, RIGHT, UP, DOWN }

    /** Indices of the small screens in [Layout.ENLARGED], left to right. */
    fun smallOnes(s: State): List<Int> = s.screens.indices.filter { it != s.large }

    /** Moves the selection (and with it the audio). Keys that lead nowhere keep it. */
    fun move(s: State, d: Dir): State {
        val n = s.count
        if (n <= 1) return s
        val to = when (s.layout) {
            Layout.SPLIT -> {
                val cols = if (n <= 2) n else 2
                val r = s.selected / cols; val c = s.selected % cols
                when (d) {
                    Dir.LEFT -> if (c > 0) s.selected - 1 else s.selected
                    Dir.RIGHT -> if (c < cols - 1 && s.selected + 1 < n) s.selected + 1 else s.selected
                    Dir.UP -> if (r > 0) s.selected - cols else s.selected
                    Dir.DOWN -> if (s.selected + cols < n) s.selected + cols else s.selected
                }
            }
            Layout.ENLARGED -> {
                val small = smallOnes(s)
                when {
                    s.selected == s.large && d == Dir.DOWN -> small.first()
                    s.selected != s.large && d == Dir.UP -> s.large
                    s.selected != s.large && (d == Dir.LEFT || d == Dir.RIGHT) -> {
                        val i = small.indexOf(s.selected) + if (d == Dir.LEFT) -1 else 1
                        small.getOrNull(i) ?: s.selected
                    }
                    else -> s.selected
                }
            }
        }
        return s.copy(selected = to)
    }

    // ------------------------------------------------------------------ the OK menu

    enum class Action { CHANGE_CHANNEL, PAUSE, RESUME, ENLARGE, REDUCE, FULL_SCREEN, REMOVE }

    /** Menu of screen [i]: "Reduce screen" on the enlarged one, "Resume" when paused. */
    fun menu(s: State, i: Int): List<Action> {
        val sc = s.screens[i]
        return listOf(
            Action.CHANGE_CHANNEL,
            if (sc.paused) Action.RESUME else Action.PAUSE,
            if (s.layout == Layout.ENLARGED && s.large == i) Action.REDUCE else Action.ENLARGE,
            Action.FULL_SCREEN,
            Action.REMOVE
        )
    }

    /** What an action leads to: a new multiview state, or leaving multiview with one channel full-screen. */
    sealed class Result {
        data class Stay(val state: State) : Result()
        /** Leave multiview: [channelId] continues full-screen, every other stream stops. */
        data class FullScreen(val channelId: String) : Result()
        /** Open the channel picker for screen [index] (Change channel). */
        data class Pick(val index: Int) : Result()
    }

    fun act(s: State, i: Int, a: Action): Result = when (a) {
        Action.CHANGE_CHANNEL -> Result.Pick(i)
        Action.PAUSE -> Result.Stay(s.copy(screens = s.screens.mapIndexed { k, sc -> if (k == i) sc.copy(paused = true) else sc }))
        // resuming a live screen jumps back to live (the player seeks to the live edge, see [resumeAtLive])
        Action.RESUME -> Result.Stay(s.copy(screens = s.screens.mapIndexed { k, sc -> if (k == i) sc.copy(paused = false) else sc }))
        Action.ENLARGE -> Result.Stay(s.copy(layout = Layout.ENLARGED, large = i, selected = i))
        Action.REDUCE -> Result.Stay(s.copy(layout = Layout.SPLIT, selected = i))
        Action.FULL_SCREEN -> Result.FullScreen(s.screens[i].channelId)
        Action.REMOVE -> remove(s, i)
    }

    /** After Pause, Resume goes back to the live edge (not where it stopped). */
    const val resumeAtLive = true

    /** Removes screen [i]: one left → it goes full-screen; more left → multiview goes on (selection and layout fixed up). */
    fun remove(s: State, i: Int): Result {
        val rest = s.screens.filterIndexed { k, _ -> k != i }
        if (rest.size <= 1) return Result.FullScreen(rest.firstOrNull()?.channelId ?: s.screens[i].channelId)
        fun shift(k: Int) = if (k > i) k - 1 else k
        val layout = if (s.layout == Layout.ENLARGED && s.large == i) Layout.SPLIT else s.layout
        val sel = if (s.selected == i) shift(minOf(i, rest.size)).coerceIn(0, rest.size - 1) else shift(s.selected)
        return Result.Stay(State(rest, layout, sel, if (layout == Layout.ENLARGED) shift(s.large) else 0))
    }

    /** Change channel on screen [i] (picked in the channel picker); the screen keeps its place and selection. */
    fun change(s: State, i: Int, channelId: String): State =
        s.copy(screens = s.screens.mapIndexed { k, sc -> if (k == i) Screen(channelId) else sc })

    /** Back → "Exit multiview?" → Yes: the selected screen continues full-screen. */
    fun exitTo(s: State): String = s.screens[s.selected].channelId

    // ------------------------------------------------------------------ geometry

    /** A rectangle in a W×H area (dp or px – the caller decides). */
    data class Box(val x: Float, val y: Float, val w: Float, val h: Float)

    /** Gap between screens (fraction of the width). */
    const val GAP = 0.012f

    /** Where each screen goes in a [width]×[height] area, keeping 16:9 and centring. */
    fun boxes(s: State, width: Float, height: Float): List<Box> {
        val n = s.count
        if (n == 0) return emptyList()
        val g = width * GAP
        fun fit(x: Float, y: Float, w: Float, h: Float): Box {
            val bw = minOf(w, h * 16f / 9f); val bh = bw * 9f / 16f
            return Box(x + (w - bw) / 2, y + (h - bh) / 2, bw, bh)
        }
        if (n == 1) return listOf(fit(0f, 0f, width, height))
        return when (s.layout) {
            Layout.SPLIT -> {
                val cols = if (n <= 2) n else 2
                val rows = (n + cols - 1) / cols
                val cw = (width - g * (cols - 1)) / cols
                val ch = (height - g * (rows - 1)) / rows
                (0 until n).map { k -> fit((k % cols) * (cw + g), (k / cols) * (ch + g), cw, ch) }
            }
            Layout.ENLARGED -> {
                // large ≈ 75 % of the width at the top, the small ones in a row underneath
                val lw = width * 0.75f
                val lh = minOf(lw * 9f / 16f, height * 0.75f)
                val large = fit((width - lw) / 2, 0f, lw, lh)
                val small = smallOnes(s)
                val rowY = large.y + large.h + g
                val rowH = height - rowY
                val sw = minOf((width - g * (DESIGN_MAX - 2)) / (DESIGN_MAX - 1), rowH * 16f / 9f)
                val total = sw * small.size + g * (small.size - 1)
                val out = arrayOfNulls<Box>(n)
                out[s.large] = large
                small.forEachIndexed { k, idx -> out[idx] = fit((width - total) / 2 + k * (sw + g), rowY, sw, rowH) }
                out.map { it!! }
            }
        }
    }

    // ------------------------------------------------------------------ decoding budget and sources

    /** A video stream as the decoder sees it. [mime] e.g. "video/avc", "video/hevc". */
    data class Stream(val mime: String, val width: Int, val height: Int, val fps: Float)

    /** 16×16 blocks per second a stream needs (how Android describes decoder throughput). */
    fun blocksPerSecond(st: Stream): Long = ((st.width + 15) / 16).toLong() * ((st.height + 15) / 16) * st.fps.toDouble().let { Math.ceil(it).toLong() }

    /**
     * Do these streams fit the decoders together? [budget] = blocks per second per mime (measured or from the device's
     * codec list); streams of the same codec share it. Unknown codecs are assumed to fit (the player reports problems).
     */
    fun fits(budget: Map<String, Long>, streams: List<Stream>): Boolean =
        streams.groupBy { it.mime }.all { (mime, l) -> budget[mime]?.let { b -> l.sumOf { blocksPerSecond(it) } <= b } ?: true }

    /** Budget from a decoder's best rate at 1080p (e.g. measured 134 fps for AVC) – 8,160 blocks per 1080p frame. */
    fun budgetFrom1080p(fps: Double): Long = (120L * 68L * fps).toLong()

    /** A source of the same channel (HD / FHD / 4K variant). [quality] "4K" / "FHD" / "HD" / null. */
    data class Source(val channelId: String, val quality: String?)

    fun is4k(q: String?): Boolean = q?.uppercase() in setOf("4K", "UHD")

    /** Chosen source for a new screen and whether to warn that it may stutter. */
    data class Choice(val source: Source, val mayStutter: Boolean)

    /**
     * Source for a screen that is added while [others] play: if another screen already shows 4K, take the best ≤ 1080p
     * variant of the channel (FHD before HD before unlabelled); when the channel only exists in 4K, use it anyway and warn.
     * [variants] = the channel and its siblings (same programme / tvg-id), any order; [wanted] = what the owner picked.
     */
    fun chooseSource(wanted: Source, variants: List<Source>, others: List<String?>): Choice {
        val other4k = others.any { is4k(it) }
        if (!other4k || !is4k(wanted.quality)) return Choice(wanted, false)
        val lower = variants.filter { !is4k(it.quality) }.sortedByDescending { OnNowLogic.qualityRank(it.quality) }
        return lower.firstOrNull()?.let { Choice(it, false) } ?: Choice(wanted, true)
    }

    // ------------------------------------------------------------------ stream limit message

    /** Shown when a further stream does not start while other screens play (owner's wording; German source in DictD). */
    const val STREAM_LIMIT_MESSAGE = "Dein Konto erlaubt {#0} Streams; einer wird vielleicht auf einem anderen Gerät genutzt"

    /** HTTP answers that mean "too many connections" at Xtream providers (plus refused / closed connections, [code] null). */
    private val LIMIT_CODES = setOf(401, 403, 405, 406, 429, 458, 509, 503, 512, 513)

    /**
     * Message for a stream that failed to start: the stream-limit text when other screens are playing and the failure
     * looks like a refused connection, else null (the normal player error is shown). [maxConnections] null = unknown → 2.
     */
    fun startFailure(httpCode: Int?, connectionRefused: Boolean, othersPlaying: Int, maxConnections: Int?): String? {
        if (othersPlaying <= 0) return null
        if (!(connectionRefused || (httpCode != null && httpCode in LIMIT_CODES))) return null
        return STREAM_LIMIT_MESSAGE.replace("{#0}", (maxConnections ?: 2).toString())
    }

    /** Multiview can open when the account allows at least 2 streams (unknown = allowed; the start failure explains it). */
    fun available(maxConnections: Int?): Boolean = maxConnections == null || maxConnections >= 2
}

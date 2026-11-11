package com.ipvio.stream

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * OnAir v2 (Phase 7): what the device's video decoders can do (MediaCodecList), for the debug screen and for
 * [MultiviewLogic.fits]. Hardware, non-secure decoders only.
 */
object DecoderCaps {
    /**
     * One decoder: [supported1080] / [supported2160] = highest frame rate the codec list allows at that size (from its
     * blocks-per-second limit), [measured1080] / [measured2160] = the vendor's measured rate (achievable), null = unknown.
     */
    data class Dec(
        val name: String, val mime: String, val maxInstances: Int, val maxW: Int, val maxH: Int,
        val supported1080: Double?, val supported2160: Double?, val measured1080: Double?, val measured2160: Double?
    )

    private fun hw(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated
        else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    @Volatile private var cache: List<Dec>? = null

    /** Hardware video decoders (non-secure). Read once per process (the list does not change). */
    fun read(): List<Dec> = cache ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && hw(it) && !it.name.endsWith(".secure") }
            .flatMap { info ->
                info.supportedTypes.filter { it.startsWith("video/") }.mapNotNull { mime ->
                    val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
                    val vc = caps.videoCapabilities ?: return@mapNotNull null
                    fun sup(w: Int, h: Int) = runCatching { if (vc.isSizeSupported(w, h)) vc.getSupportedFrameRatesFor(w, h).upper else null }.getOrNull()
                    fun ach(w: Int, h: Int) = runCatching { vc.getAchievableFrameRatesFor(w, h)?.upper }.getOrNull()
                    Dec(
                        info.name, mime, caps.maxSupportedInstances, vc.supportedWidths.upper, vc.supportedHeights.upper,
                        sup(1920, 1080), sup(3840, 2160), ach(1920, 1080), ach(3840, 2160)
                    )
                }
            }
    }.getOrDefault(emptyList()).also { cache = it }

    /**
     * Decoding budget per codec (blocks per second) for [MultiviewLogic.fits]: the vendor's measured 1080p rate when
     * known (realistic), else what the codec list allows.
     */
    fun budget(decs: List<Dec> = read()): Map<String, Long> =
        decs.groupBy { it.mime }.mapValues { (_, l) ->
            val fps = l.mapNotNull { it.measured1080 ?: it.supported1080 }.maxOrNull() ?: 0.0
            MultiviewLogic.budgetFrom1080p(fps)
        }.filterValues { it > 0 }

    fun short(mime: String): String = when (mime) {
        "video/avc" -> "H.264"; "video/hevc" -> "HEVC"; "video/x-vnd.on2.vp9" -> "VP9"; "video/av01" -> "AV1"
        "video/mpeg2" -> "MPEG-2"; "video/mp4v-es" -> "MPEG-4"; else -> mime.removePrefix("video/")
    }
}

/** Result of one stream of the two-stream test. */
class StreamResult(
    val channel: String,
    var decoder: String? = null,
    var mime: String? = null, var width: Int = 0, var height: Int = 0, var fps: Float = 0f,
    var firstFrameMs: Long = -1, var rendered: Int = 0, var dropped: Int = 0,
    var error: String? = null
) {
    val formatText: String get() = if (width <= 0) "–" else "${DecoderCaps.short(mime ?: "")} ${width}×$height" + (if (fps > 0) " @ ${"%.0f".format(fps)} fps" else "")
}

/**
 * OnAir v2 (Phase 7): two-stream test on the debug screen – only when the owner presses Start. Plays two live channels at
 * once (picked, or two 1080p channels with guide data by default) for at most [MAX_MS], then stops and releases both
 * players and shows per stream: decoder, format, frames rendered / dropped, time to first picture, errors. Stream A has
 * the normal buffer and audio; stream B the small buffer and no audio – as Multiview will do. Uses 2 provider connections.
 */
object TwoStreamTest {
    const val MAX_MS = 60_000L

    /** Two sensible defaults: live channels labelled FHD (1080p) that have guide data, from different guide channels. */
    fun defaults(live: List<Item>): List<Item> {
        val fhd = live.filter { it.kind == Kind.LIVE && it.quality == "FHD" && Xmltv.has(it.tvgId) }
        val first = fhd.firstOrNull() ?: live.firstOrNull { it.kind == Kind.LIVE } ?: return emptyList()
        val second = fhd.firstOrNull { it.id != first.id && it.tvgId?.lowercase() != first.tvgId?.lowercase() }
            ?: live.firstOrNull { it.kind == Kind.LIVE && it.id != first.id }
        return listOfNotNull(first, second)
    }

    /** Channels to pick from: recently watched live channels first, then 1080p channels with guide data. */
    fun candidates(live: List<Item>, max: Int = 150): List<Pair<String, Item>> {
        val byId = live.associateBy { it.id }
        val recent = History.ids.toList().mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE }.take(30)
        val fhd = live.filter { it.kind == Kind.LIVE && it.quality == "FHD" && Xmltv.has(it.tvgId) && it !in recent }.take(max - recent.size)
        return recent.map { "recently watched" to it } + fhd.map { "1080p with guide" to it }
    }

    @OptIn(UnstableApi::class)
    fun player(ctx: Context, small: Boolean, audio: Boolean): ExoPlayer {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
        val load = if (!small) Mem.loadControl(ctx) else {
            val p = Mem.bufferPlan(0)
            DefaultLoadControl.Builder().setBufferDurationsMs(p[0], p[1], p[2], p[3]).setTargetBufferBytes(p[4]).setPrioritizeTimeOverSizeThresholds(false).build()
        }
        val pl = ExoPlayer.Builder(ctx, SoftAudio.factory(ctx))
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(DefaultDataSource.Factory(ctx, http)))
            .build()
        if (!audio) pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        return pl
    }

    /** Collects decoder, format, dropped frames and errors of one player into [r]. */
    @OptIn(UnstableApi::class)
    fun attach(pl: ExoPlayer, r: StreamResult, startedAt: Long, othersPlaying: () -> Int, maxConnections: Int?, onChange: () -> Unit) {
        pl.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                r.decoder = decoderName; onChange()
            }
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                r.mime = format.sampleMimeType; r.width = format.width; r.height = format.height
                r.fps = if (format.frameRate > 0) format.frameRate else r.fps
                onChange()
            }
            override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                if (r.firstFrameMs < 0) r.firstFrameMs = System.currentTimeMillis() - startedAt
                onChange()
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                r.dropped += droppedFrames; onChange()
            }
            override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                val http = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                val refused = generateSequence<Throwable>(error) { it.cause }.any { it is java.net.ConnectException || it is java.net.SocketException }
                r.error = MultiviewLogic.startFailure(http, refused, othersPlaying(), maxConnections)?.let { Lang.tr(it) }
                    ?: ("${error.errorCodeName}" + (http?.let { " (HTTP $it)" } ?: ""))
                LoginDebug.error("two-stream test", "stream ${r.channel}", error)
                onChange()
            }
        })
    }
}

/** The two-stream test block on the debug screen (pick → Start → up to 60 s → results). */
@OptIn(UnstableApi::class)
@Composable
fun TwoStreamTestPanel(live: List<Item>, maxConnections: Int?) {
    val ctx = LocalContext.current
    val defaults = remember(live) { TwoStreamTest.defaults(live) }
    var a by remember(live) { mutableStateOf(defaults.getOrNull(0)) }
    var b by remember(live) { mutableStateOf(defaults.getOrNull(1)) }
    var picking by remember { mutableIntStateOf(-1) }        // -1 none, 0 = A, 1 = B
    var running by remember { mutableStateOf(false) }
    var startedAt by remember { mutableStateOf(0L) }
    var elapsed by remember { mutableStateOf(0L) }
    var results by remember { mutableStateOf<List<StreamResult>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }            // redraw while running
    var players by remember { mutableStateOf<List<ExoPlayer>>(emptyList()) }

    fun stop() {
        for (p in players) {
            runCatching { p.videoDecoderCounters?.let { c -> c.ensureUpdated(); results.getOrNull(players.indexOf(p))?.rendered = c.renderedOutputBufferCount } }
            runCatching { p.stop(); p.release() }
        }
        players = emptyList()
        running = false
        tick++
    }

    // never leave streams running when the screen goes away
    DisposableEffect(Unit) { onDispose { players.forEach { runCatching { it.release() } } } }
    KeepScreenOn("twostream", running)
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (running) {
            delay(500)
            elapsed = System.currentTimeMillis() - startedAt
            players.forEachIndexed { i, p ->
                runCatching { p.videoDecoderCounters?.let { c -> c.ensureUpdated(); results.getOrNull(i)?.rendered = c.renderedOutputBufferCount } }
            }
            tick++
            if (elapsed >= TwoStreamTest.MAX_MS) stop()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            "Plays two live channels at once for up to 60 s, then stops both. Uses both connections of the account" +
                (maxConnections?.let { " (max $it)" } ?: "") + " – don't watch elsewhere meanwhile.",
            color = Muted, fontSize = 14.sp, raw = true
        )
        if (!running) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("A: " + (a?.title ?: "–"), raw = true) { picking = 0 }
                PillButton("B: " + (b?.title ?: "–"), raw = true) { picking = 1 }
                PillButton("Start test (60 s)", raw = true, light = true) {
                    val sa = a; val sb = b
                    if (sa == null || sb == null || running) return@PillButton
                    picking = -1
                    val now = System.currentTimeMillis()
                    val ra = StreamResult(sa.title); val rb = StreamResult(sb.title)
                    val pa = TwoStreamTest.player(ctx, small = false, audio = true)
                    val pb = TwoStreamTest.player(ctx, small = true, audio = false)
                    TwoStreamTest.attach(pa, ra, now, { if (pb.isPlaying) 1 else 0 }, maxConnections) { tick++ }
                    TwoStreamTest.attach(pb, rb, now, { if (pa.isPlaying) 1 else 0 }, maxConnections) { tick++ }
                    pa.setMediaItem(MediaItem.fromUri(sa.url)); pb.setMediaItem(MediaItem.fromUri(sb.url))
                    pa.playWhenReady = true; pb.playWhenReady = true
                    pa.prepare(); pb.prepare()
                    results = listOf(ra, rb); players = listOf(pa, pb)
                    startedAt = now; elapsed = 0; running = true
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Stop (${(TwoStreamTest.MAX_MS - elapsed).coerceAtLeast(0) / 1000} s left)", raw = true, light = true) { stop() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                for (p in players) {
                    Box(Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(DS.RadiusS)).background(Color.Black)) {
                        AndroidView(factory = { c -> PlayerView(c).apply { useController = false; player = p } }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        // results (live while running, final after stop)
        val redraw = tick   // results are plain objects: this read makes the block redraw while the test runs
        if (results.isNotEmpty() && redraw >= 0) {
            val budget = remember(results.size) { DecoderCaps.budget() }
            results.forEachIndexed { i, r ->
                val secs = (if (running) elapsed else maxOf(elapsed, 1)) / 1000.0
                val fpsOut = if (secs > 0) r.rendered / secs else 0.0
                Text(
                    "${if (i == 0) "A" else "B"}: ${r.channel}   ·   ${r.formatText}   ·   ${r.decoder ?: "decoder –"}",
                    color = Color.White, fontSize = 15.sp, raw = true, maxLines = 1
                )
                Text(
                    "   rendered ${r.rendered} (${"%.1f".format(fpsOut)} fps)   ·   dropped ${r.dropped}   ·   first picture " +
                        (if (r.firstFrameMs >= 0) "${r.firstFrameMs} ms" else "–") + (r.error?.let { "   ·   ERROR: $it" } ?: ""),
                    color = if (r.error != null || r.dropped > 0) Accent2 else Muted, fontSize = 14.sp, raw = true, maxLines = 2
                )
            }
            val streams = results.filter { it.width > 0 && it.mime != null }.map { MultiviewLogic.Stream(it.mime!!, it.width, it.height, if (it.fps > 0) it.fps else 50f) }
            if (streams.size == 2) Text(
                "Decoder budget: " + (if (MultiviewLogic.fits(budget, streams)) "both fit" else "over budget – expect stutter") +
                    (if (!running) "   ·   test ran ${elapsed / 1000} s" else ""),
                color = Muted, fontSize = 14.sp, raw = true
            )
        }
    }

    // channel picker for A / B
    if (picking >= 0 && !running) {
        val list = remember(live) { TwoStreamTest.candidates(live) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(DS.RadiusM)).background(Surf).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(DS.RadiusM)).padding(12.dp)) {
            Text("Pick stream ${if (picking == 0) "A" else "B"} (Back = cancel)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, raw = true)
            androidx.activity.compose.BackHandler { picking = -1 }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                items(list, key = { it.second.id }) { (label, it) ->
                    PillButton("${it.title}  ·  ${it.group}  ·  $label", raw = true) {
                        if (picking == 0) a = it else b = it
                        picking = -1
                    }
                }
            }
        }
    }
}

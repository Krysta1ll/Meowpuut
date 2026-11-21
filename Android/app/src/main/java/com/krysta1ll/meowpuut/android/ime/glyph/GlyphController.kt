package com.krysta1ll.meowpuut.android.ime.glyph

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.max
import kotlin.math.min

/** One finished picture, handed from the render thread to the views. Immutable once published. */
internal class GlyphFrame(
    val bitmap: Bitmap,
    val index: Int,
    val front: GlyphFrontFrame,
    val layout: GlyphLayout,
    val dark: Boolean,
    val opaque: Boolean,
    /** The sun or moon in the picture, for the views to cut their type out of; null when there is none or the picture is the safe one. */
    val disc: DiscInfo? = null,
    /** What is nearest, for the view in front of the cats to draw round them; null without depth. */
    val near: GlyphNear? = null,
    /** The world's clock when this was drawn, and the name of the place. */
    val tick: Int = 0,
    val scene: String = "",
)

/**
 * Runs the 字景 world for a keyboard: owns a render thread, the [GlyphEngine] that lives on it, and the two views that show what it
 * draws (the land under the keys, the cats over them). The main thread only posts small messages and reads the last finished
 * [frame]; it never waits for the picture, so typing is never held up by it. The thread exists only while the skin is on screen.
 *
 * The clock: at most twelve frames a second, none while nothing changes, and a full stop about twenty seconds after the last key
 * (the engine's [GlyphEngine.busy] says when). A key, a commit or a new layout starts it again.
 */
internal class GlyphController(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    /** Device pixels to a point in the picture: the screen's, but no finer than three, so a tablet's picture stays small. */
    private val renderScale = min(density, 3f)
    private val main = Handler(Looper.getMainLooper())

    val scene = GlyphSceneView(context, this)
    val front = GlyphFrontView(context, this)

    /** The last finished picture. */
    @Volatile var frame: GlyphFrame? = null
        private set

    private class Settings(val dark: Boolean, val level: Int, val opaque: Boolean, val crab: Boolean, val cats: Boolean, val arrival: Boolean)

    @Volatile private var settings = Settings(false, 1, false, false, true, true)
    @Volatile private var layout = GlyphLayout.NONE
    @Volatile private var hasCandidates = false
    @Volatile private var systemStill = false
    /** Where the journey stood when the world started (from the cats' count of characters). */
    private var startDistance = 0L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // ---- the render thread's own ---------------------------------------------------------------------------
    private var engine: GlyphEngine? = null
    private var sink: GlyphCanvasSink? = null
    private var nearSink: GlyphCanvasSink? = null
    /** The corner's bank and what passes: drawn live between the land and the cats, at the display's rate while anything moves. */
    val nearView = GlyphNearView(context, this)
    /** The ground with depth, drawn live under the picture (see [GlyphGround]). */
    val groundView = GlyphGroundView(context, this)
    private val ground = GlyphGround(renderScale)
    /** The land drawn once and moved (see [GlyphLayerStrips]); off, the picture is drawn whole twelve times a second as it was. */
    private val layerStrips = GlyphLayerStrips(renderScale)
    private var layersOn = LAYER_STRIPS
    private var lastSyncMs = 0L
    @Volatile private var groundExact = DoubleArray(0)
    private var groundWant = IntArray(0)
    private var groundAdvancing = false
    /** The motion of what is near (main thread only): see [GlyphMotion]. */
    val motion = GlyphMotion()
    @Volatile private var worldTarget = 0
    @Volatile private var worldEpoch = 0
    @Volatile var foreHold: Double? = null
    private var dbgLast = -1L
    private var driving = false
    private var lastVsync = 0L
    private val choreographer = android.view.Choreographer.getInstance()
    private val ring = arrayOfNulls<Bitmap>(3)
    @Volatile private var showing = -1
    private var published = -1
    private var scheduled = false
    private var lastTickAt = 0L
    private val invertPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f)))
    }

    /** A torn band replaces what is under it, transparent pixels and all. */
    private val copyPaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC) }

    private val tick = Runnable { tickOnce() }
    private val perf = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var perfN = 0
    private var perfSum = 0.0
    private var perfMax = 0.0

    // ---- the main thread's ---------------------------------------------------------------------------------

    val running: Boolean get() = thread != null

    /** The keyboard is on screen: the thread starts and the world comes back from the journey's distance. */
    fun start(distance: Long) {
        if (thread != null) return
        startDistance = distance
        refreshSystemState()
        val t = HandlerThread("glyph-render", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        h.post {
            val e = GlyphEngine(pools(), clockHm = ::clockHm)
            e.scape.liveNear = true
            e.scape.groundLive = true
            layersOn = if (perf) context.getSharedPreferences("meowpuut", Context.MODE_PRIVATE).getBoolean("glyphLayers", LAYER_STRIPS) else LAYER_STRIPS
            e.theme.deep = !perf || context.getSharedPreferences("meowpuut", Context.MODE_PRIVATE).getBoolean("glyphDeep", true)   // 縱深: the picture in the finest pixels, with mist, a near corner, what passes
            engine = e
            sink = GlyphCanvasSink(renderScale, gather = true)
            e.restore(startDistance)
            forced?.let { e.force(it) }
            held?.let { e.hold(it) }
            heldFx?.let { e.holdFx(it) }
            applyAll(e)
            arriveIfDue(e)
            requestTick()
        }
    }

    /**
     * The cats' book was read after the world started: the journey stands where it stands from the book's count, plus what this world has
     * walked since it started on its own.
     */
    fun rebase(distance: Long) {
        val earlier = startDistance
        startDistance = distance
        handler?.post { engine?.let { e -> e.restore(distance + max(0L, e.distance - earlier)); requestTick() } }
    }

    /** The cats' records were cleared, and the journey with them: the world goes back to its first place. */
    fun rewind() {
        startDistance = 0L
        handler?.post { engine?.restore(0L, reset = true); requestTick() }
    }

    /** The keyboard went away (or the skin did). Nothing typed is remembered and the thread ends. */
    fun stop() {
        val t = thread ?: return
        choreographer.removeFrameCallback(vsync); driving = false; motion.reset(); ground.clear(); groundExact = DoubleArray(0)
        hiddenAt = SystemClock.uptimeMillis()
        val h = handler
        thread = null
        handler = null
        h?.removeCallbacksAndMessages(null)
        h?.post {
            engine?.forget()
            engine = null
            sink?.release(); sink = null
            for (i in ring.indices) ring[i] = null
            scheduled = false
            t.quitSafely()
        }
        frame = null
    }

    /** The keyboard is hidden, but the view stays: the clock stops, what was typed is dropped, the distance is saved. */
    fun pause() {
        val h = handler ?: return
        hiddenAt = SystemClock.uptimeMillis()
        h.removeCallbacks(tick)
        h.post {
            val e = engine ?: return@post
            e.forget()
            scheduled = false
        }
    }

    fun resume() {
        refreshSystemState()
        // What the last time left on the screen is not shown again: the picture begins from the paper.
        if (arrivalDue()) { frame = null; scene.invalidate(); front.invalidate() }
        handler?.post { engine?.let { applyAll(it); arriveIfDue(it); requestTick() } }
    }

    /**
     * The keyboard was called up: the picture is typed into being, unless it comes back within two seconds of going away, or the
     * settings say no (a still picture, the lowest effects level, the safe drawing and a floating card all show it whole at once).
     */
    private fun arriveIfDue(e: GlyphEngine) {
        if (arrivalDue()) e.arrive()
        hiddenAt = 0L
    }

    private fun arrivalDue(): Boolean {
        val s = settings
        if (!s.arrival || s.opaque || s.level == 0 || systemStill) return false
        val away = hiddenAt
        return away == 0L || SystemClock.uptimeMillis() - away >= ARRIVAL_GRACE_MS
    }

    /** When the keyboard was last put away (uptime), 0 when it has not been. */
    @Volatile private var hiddenAt = 0L



    /** Reduced motion and power saving ask for a still picture. */
    fun refreshSystemState() {
        val saver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        systemStill = !ValueAnimator.areAnimatorsEnabled() || saver
    }

    fun configure(dark: Boolean, level: Int, opaque: Boolean, crab: Boolean, cats: Boolean, arrival: Boolean = true) {
        val next = Settings(dark, level, opaque, crab, cats, arrival)
        val prev = settings
        if (prev.dark == dark && prev.level == level && prev.opaque == opaque && prev.crab == crab && prev.cats == cats && prev.arrival == arrival) return
        settings = next
        handler?.post { engine?.let { applyAll(it); requestTick() } }
    }

    fun setLayout(next: GlyphLayout) {
        if (next == layout) return
        layout = next
        handler?.post { engine?.let { applyAll(it); requestTick() } }
    }

    /** The bar shows its input-mode label between [left] and [right] (points), or none when null: the land thins round it so it is read. */
    fun setBarLabel(left: Double?, right: Double = 0.0) {
        handler?.post { engine?.let { if (left == null) it.clearBarLabel() else it.setBarLabel(left, right) }; requestTick() }
    }

    /** The candidates on the bar and the edges of their type, in points: the birds settle on them. */
    /** By day, what is near in the picture for the keys' type to be cut out of (see [GlyphCanvasSink.NearCut]); [dx], [dy] is where the view stands in the keyboard. */
    fun nearCut(c: GlyphColors, dx: Double, dy: Double): GlyphCanvasSink.NearCut? {
        val f = frame ?: return null
        val n = f.near ?: return null
        if (n.dark) return null
        val w = f.bitmap.width
        val h = f.bitmap.height
        var m = nearMaskBitmap
        if (m == null || m.width != w || m.height != h) { m = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8); nearMaskBitmap = m }
        m.eraseColor(0)
        val g = nearMaskSink ?: GlyphCanvasSink(renderScale, gather = false).also { nearMaskSink = it }
        g.begin(Canvas(m), f.layout.lift)
        drawNearLive(g, c, n, front = false)
        return GlyphCanvasSink.NearCut(m, renderScale, f.layout.lift, c.paper, dx, dy)
    }
    private var nearMaskBitmap: Bitmap? = null
    private var nearMaskSink: GlyphCanvasSink? = null

    /** The state of what is near right now: the live motion's clock, and the held place of a shot. */
    private fun liveState(n: GlyphNear) = n.state(motion.clock, motion.keyAt)

    /** Where what passes stands right now, its left edge in points. */
    private fun liveForeAt(n: GlyphNear): Double =
        foreHold?.let { it * n.width } ?: (GlyphDepthFore.passesAt(if (motion.fore.isNaN()) worldTarget.toDouble() else motion.fore, motion.coast) + GlyphDepthFore.sway(liveState(n)))

    /** The corner and, unless [front], the corner is drawn too; what passes is drawn when it is in the picture. Nothing above the keyboard's top. */
    internal fun drawNearLive(g: GlyphSink, c: GlyphColors, n: GlyphNear, front: Boolean) {
        val clip = GlyphTopClip(g, 0.0)
        val st = liveState(n)
        if (!front) GlyphDepthFore.drawFrame(clip, c, st)
        if (n.passes || foreHold != null) GlyphDepthFore.drawFore(clip, c, st, liveForeAt(n), 0.0)
    }

    /** What passes, drawn alone: for the view in front of the cats, clipped by the caller to where they stand. */
    internal fun drawForeLive(g: GlyphSink, c: GlyphColors, n: GlyphNear) {
        if (n.passes || foreHold != null) GlyphDepthFore.drawFore(GlyphTopClip(g, 0.0), c, liveState(n), liveForeAt(n), 0.0)
    }

    // ---- the display's own rate: what is near keeps moving between the twelve pictures a second ------------------

    /** Something wants moving: keys, a new picture. Starts stepping the motion with the display (main thread). */
    fun kick() {
        if (driving || frame?.near == null) return
        driving = true
        lastVsync = 0L
        choreographer.postFrameCallback(vsync)
    }

    private val vsync: android.view.Choreographer.FrameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) = step(frameTimeNanos)
    }

    private fun step(now: Long) {
        driving = false
        val f = frame
        val n = f?.near
        if (n == null || thread == null) return
        val dt = if (lastVsync == 0L) 1.0 / 60 else ((now - lastVsync) / 1e9).coerceIn(0.001, 0.05)
        lastVsync = now
        val still = n.still || n.level == 0
        val busy = motion.advance(dt, worldTarget.toDouble(), worldEpoch, still, NO_TAU, foreHold == null && n.passes) { GlyphMotion.visible(it, n.width) }
        updateGround(n, busy)
        if (perf && (motion.clock.toLong() / 200) != dbgLast) { dbgLast = motion.clock.toLong() / 200; android.util.Log.i("MeowpuutGlyphPerf", "near: target $worldTarget fore ${"%.1f".format(motion.fore)} coast ${"%.1f".format(motion.coast)} at ${"%.0f".format(liveForeAt(n))} passes ${n.passes} busy $busy still $still level ${n.level} scene ${n.scene}") }
        if (busy || motion.fore.isNaN()) {
            nearView.invalidate()
            if (n.passes && GlyphMotion.visible(liveForeAt(n), n.width)) { front.invalidate(); for (v in overlays) v.invalidate() }
        }
        if (busy) { driving = true; choreographer.postFrameCallback(vsync) }
    }

    /** How far each row of the ground has gone, in whole cells, as the motion has it now (or, before it has stepped, as the world's position has it). */
    private fun wantFor(geo: GlyphScape.GroundGeometry, e: GlyphEngine): IntArray {
        val ex = groundExact
        val out = IntArray(geo.rows)
        if (ex.size == geo.rows) for (r in 0 until geo.rows) out[r] = floor(ex[r]).toInt()
        else for (r in 0 until geo.rows) out[r] = floor(e.world.pos * geo.speed(r) * 8 / geo.cell).toInt()
        return out
    }

    private var groundSig = 0L
    private var groundEpoch = -1
    private var groundAt = 0L

    /** Everything the ground's cells are made of besides the turn of the type: when it changes the ground is drawn afresh. */
    private fun groundSignature(e: GlyphEngine, l: GlyphLayout): Long {
        var h = e.scape.sceneName.hashCode().toLong()
        h = h * 31 + e.theme.level; h = h * 31 + (if (e.theme.dark) 1 else 0); h = h * 31 + (if (e.theme.still) 1 else 0)
        h = h * 31 + l.hashCode(); h = h * 31 + (if (e.hasCandidates) 1 else 0); h = h * 31 + (e.barLabel?.contentHashCode() ?: 0)
        h = h * 31 + Math.round(e.scape.formed * 20); h = h * 31 + Math.round(e.world.fog * 20)
        h = h * 31 + (if (e.world.ripples.isNotEmpty()) e.world.tick.toLong() else 0L)          // a ring is running out through the land
        h = h * 31 + (if (e.world.tick < e.world.glitch) e.world.tick.toLong() else 0L)
        return h
    }

    private var spN = 0; private var spSum = 0.0; private var spMax = 0.0
    private fun stripPerf(ms: Double) {
        spN++; spSum += ms; if (ms > spMax) spMax = ms
        if (spN == 24) { android.util.Log.i("MeowpuutGlyphPerf", "layer strips sync: ${"%.1f".format(spSum / 24)} ms avg, ${"%.1f".format(spMax)} ms max over 24"); spN = 0; spSum = 0.0; spMax = 0.0 }
    }

    private val gpN = IntArray(2); private val gpSum = DoubleArray(2); private val gpMax = DoubleArray(2)
    private fun groundPerf(what: String, ms: Double) {
        val i = if (what == "rebuild") 0 else 1
        gpN[i]++; gpSum[i] += ms; if (ms > gpMax[i]) gpMax[i] = ms
        if (gpN[i] == 24) { android.util.Log.i("MeowpuutGlyphPerf", "ground $what: ${"%.1f".format(gpSum[i] / 24)} ms avg, ${"%.1f".format(gpMax[i])} ms max over 24"); gpN[i] = 0; gpSum[i] = 0.0; gpMax[i] = 0.0 }
    }

    /** Main thread, each vsync: where the ground's rows are now, and a request to the render thread to shift those that have gone a cell. */
    private fun updateGround(n: GlyphNear, busy: Boolean) {
        val rows = ground.rows ?: return
        val geo = rows.geo
        if (groundExact.size != geo.rows) groundExact = DoubleArray(geo.rows)
        val near = if (motion.near.isNaN()) worldTarget.toDouble() else motion.near
        val far = if (motion.far.isNaN()) worldTarget.toDouble() else motion.far
        ground.exact(geo, near, far, groundExact)
        var behind = false
        for (r in 0 until geo.rows) if (floor(groundExact[r]).toInt() - rows.baked[r] >= GlyphGround.ADVANCE_AT) { behind = true; break }
        if (behind && !groundAdvancing) {
            groundAdvancing = true
            handler?.post {
                val e = engine
                if (e != null && e.scape.liveGroundActive()) {
                    val geoNow = e.scape.groundGeometry()
                    if (geoNow != null) {
                        val t0 = System.nanoTime()
                        val did = ground.advance(e.scape, GlyphColors.of(e.theme.dark), wantFor(geoNow, e))
                        if (perf && did) groundPerf("advance", (System.nanoTime() - t0) / 1e6)
                    }
                }
                groundAdvancing = false
                groundView.postInvalidateOnAnimation()
            }
        }
        groundView.invalidate()
    }

    /** The ground's strips, each moved by what its row has gone beyond the cells it was drawn at (main thread, in the ground view's draw). */
    fun drawGround(canvas: Canvas, paint: Paint) {
        synchronized(ground.lock) {
            val rows = ground.rows ?: return
            val ex = groundExact
            for (r in 0 until rows.geo.rows) {
                val b = rows.bitmaps[r] ?: continue
                val frac = if (ex.size == rows.geo.rows) ex[r] - rows.baked[r] else 0.0
                val dx = -(frac.coerceIn(0.0, GlyphGround.MARGIN - 1.0) * rows.cellPx).roundToInt()
                canvas.drawBitmap(b, dx.toFloat(), rows.topPx[r].toFloat(), paint)
            }
        }
    }

    /** The picture's sun or moon for a view to cut its type out of (see [GlyphCanvasSink.Eclipse]); [dx], [dy] is where the view stands in the keyboard. */
    fun eclipse(c: GlyphColors, dx: Double, dy: Double): GlyphCanvasSink.Eclipse? {
        val f = frame ?: return null
        val d = f.disc ?: return null
        if (d.earth) return GlyphCanvasSink.Eclipse(f.bitmap, renderScale, f.layout.lift, d, GlyphEarth.PALETTE, 0xFFFFFFFF.toInt(), 0xFF0B1E3F.toInt(), dx, dy)   // white type, edged dark blue
        val colour = if (d.hue != 0) d.hue else if (d.sun && !f.dark) c.red else c.ink
        return GlyphCanvasSink.Eclipse(f.bitmap, renderScale, f.layout.lift, d, intArrayOf(colour), c.paper, null, dx, dy)
    }

    /** The views that lay type over the picture, redrawn when the picture's sun or moon forms or the place changes; and the keys, for the signs of the street. */
    @Volatile var overlays: List<android.view.View> = emptyList()
    @Volatile var signs: android.view.View? = null
    private var lastScene = ""

    fun setPerches(sig: Int, edges: DoubleArray, textTop: Double) {
        if (sig == perchSig) return
        perchSig = sig
        handler?.post { engine?.candidates(sig, edges, textTop); requestTick() }
    }
    @Volatile private var perchSig = 0

    /** Debug: hold one moment of an effect ("storm:6", "burst:5", "run"), or null to let them happen. */
    fun holdFx(what: String?) {
        heldFx = what
        handler?.post { engine?.holdFx(what); requestTick() }
    }
    @Volatile private var heldFx: String? = null

    fun setCandidates(has: Boolean) {
        if (hasCandidates == has) return
        hasCandidates = has
        handler?.post { engine?.setCandidates(has); requestTick() }
    }

    /** A key went down. Called on the touch path: it allocates one message and does nothing else. */
    fun key(kind: GlyphKey, x: Float, y: Float, composing: Boolean, letter: String) {
        motion.keyed()
        handler?.post { engine?.let { it.key(kind, x.toDouble(), y.toDouble(), composing, letter); worldTarget = it.world.target; worldEpoch = it.world.epoch }; requestTick() }
        kick()
    }

    /** Text was committed to the editor. [secret]: it may be read for the words that do something (never in a field that is not to be learned from). */
    fun committed(text: String, graphemes: Int, japanese: Boolean, secret: Boolean) {
        handler?.post {
            val e = engine ?: return@post
            e.committed(text, graphemes, japanese, secret)
            requestTick()
        }
    }

    /** Debug: hold one moment of the arrival (0..1), or null. */
    fun holdForm(form: Double?) {
        held = form
        handler?.post { engine?.hold(form); requestTick() }
    }
    @Volatile private var held: Double? = null

    /** Debug: hold what passes in front at this part of the width, or null. */
    fun holdFore(at: Double?) {
        fore = at
        foreHold = at
        handler?.post { engine?.world?.dirty = true; requestTick() }
        nearView.postInvalidateOnAnimation()
    }
    @Volatile private var fore: Double? = null

    /** Debug: stay in one place. */
    fun force(sceneIndex: Int?) {
        forced = sceneIndex
        handler?.post { engine?.force(sceneIndex); requestTick() }
    }
    @Volatile private var forced: Int? = null

    // ---- the render thread ------------------------------------------------------------------------------------

    private fun clockHm(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 100 + c.get(Calendar.MINUTE)
    }

    private fun applyAll(e: GlyphEngine) {
        val s = settings
        e.configure(s.dark, s.level, systemStill, s.opaque, s.crab)
        e.showCats = s.cats
        e.setLayout(layout)
        e.setCandidates(hasCandidates)
    }

    private fun requestTick() {
        val h = handler ?: return
        if (scheduled) return
        scheduled = true
        val wait = (lastTickAt + FRAME_MS - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        h.postDelayed(tick, wait)
    }

    private fun tickOnce() {
        scheduled = false
        val e = engine ?: return
        val h = handler ?: return
        val started = SystemClock.uptimeMillis()
        lastTickAt = started
        val stepped = e.step()
        worldTarget = e.world.target; worldEpoch = e.world.epoch
        if (stepped || layerStrips.moving) render(e)
        if (e.busy() || layerStrips.moving) {
            scheduled = true
            h.postDelayed(tick, (FRAME_MS - (SystemClock.uptimeMillis() - started)).coerceAtLeast(1L))
        }
    }

    private fun render(e: GlyphEngine) {
        val l = e.layout
        val w = ceil(l.width * renderScale).toInt().coerceAtLeast(1)
        val h = ceil((l.height + l.lift + l.tail) * renderScale).toInt().coerceAtLeast(1)
        // Three pictures: one is on show, one is the last finished, one is free to draw into.
        var i = 0
        while (i == published || i == showing) i++
        var bitmap = ring[i]
        if (bitmap == null || bitmap.width != w || bitmap.height != h) {
            // The old one is left to the collector: a view may still be drawing it.
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(true) }
            ring[i] = bitmap
        }
        bitmap.eraseColor(0)                       // the strip above the keyboard is transparent until the picture says otherwise
        val canvas = Canvas(bitmap)
        val out = sink ?: return
        out.begin(canvas, l.lift)
        if (layersOn && e.theme.deep && !e.theme.opaque) {                    // the land's strips are brought to where the layers are now, before the picture is made
            val nowMs = SystemClock.uptimeMillis()
            val dt = if (lastSyncMs == 0L) 0.084 else ((nowMs - lastSyncMs) / 1000.0).coerceIn(0.005, 0.25)
            lastSyncMs = nowMs
            val t0 = System.nanoTime()
            layerStrips.sync(e.scape, GlyphColors.of(e.theme.dark), l.lift, groundSignature(e, l), e.world.epoch, e.world.target.toDouble(), dt, nowMs)
            if (perf) stripPerf((System.nanoTime() - t0) / 1e6)
        } else { layerStrips.release(e.scape); lastSyncMs = 0L }
        if (perf && e.scape.profile == null) e.scape.profile = LongArray(9)
        val drawStart = System.nanoTime()
        val tear = e.drawScene(out)
        out.layerEnd()
        if (perf) {                                                  // a debug build says what a picture costs (measure, do not guess)
            val ms = (System.nanoTime() - drawStart) / 1e6
            perfN++; perfSum += ms; if (ms > perfMax) perfMax = ms
            if (perfN == 24) {
                val p = e.scape.profile!!
                android.util.Log.i("MeowpuutGlyphPerf", "scene ${e.scape.sceneName}: ${"%.1f".format(perfSum / perfN)} ms avg, ${"%.1f".format(perfMax)} ms max over $perfN pictures (${w}x$h px); " +
                    "ms each: sky ${"%.1f".format(p[0] / 1e6 / perfN)} back ${"%.1f".format(p[1] / 1e6 / perfN)} mist ${"%.1f".format(p[2] / 1e6 / perfN)} ground ${"%.1f".format(p[3] / 1e6 / perfN)} " +
                    "front ${"%.1f".format(p[4] / 1e6 / perfN)} seam ${"%.1f".format(p[5] / 1e6 / perfN)} air ${"%.1f".format(p[6] / 1e6 / perfN)} near ${"%.1f".format(p[7] / 1e6 / perfN)} disc ${"%.1f".format(p[8] / 1e6 / perfN)}")
                p.fill(0); perfN = 0; perfSum = 0.0; perfMax = 0.0
            }
        }
        if (tear != null) applyTear(bitmap, tear, l.lift)
        if (e.scape.liveGroundActive()) {                                    // the ground is drawn live, under this picture
            val geo = e.scape.groundGeometry()
            if (geo != null) {
                // The ground is drawn afresh when what it is made of changes; between those it is only moved, and its type turns a little late
                // (a whole new drawing at most every [GROUND_TURN_MS] while the type is turning), which is calmer than a new drawing at every key.
                val sig = groundSignature(e, l)
                val nowMs = SystemClock.uptimeMillis()
                val turned = e.world.epoch != groundEpoch
                if (sig != groundSig || ground.rows == null || (turned && nowMs - groundAt >= GROUND_TURN_MS)) {
                    val t0 = System.nanoTime()
                    ground.rebuild(e.scape, GlyphColors.of(e.theme.dark), l.lift, wantFor(geo, e))
                    if (perf) groundPerf("rebuild", (System.nanoTime() - t0) / 1e6)
                    groundSig = sig; groundEpoch = e.world.epoch; groundAt = nowMs
                    groundView.postInvalidateOnAnimation()
                }
            }
        } else ground.clear()
        val near = e.near()
        val finished = GlyphFrame(bitmap, i, e.front(), l, e.theme.dark, e.theme.opaque, e.disc(), near, e.world.tick, e.scape.sceneName)
        published = i
        frame = finished
        scene.postInvalidateOnAnimation()
        nearView.postInvalidateOnAnimation()
        front.postInvalidateOnAnimation()
        main.post { kick() }
        val name = e.scape.sceneName
        if (e.disc() != null && (e.scape.formed < 1.0 || name != lastScene)) for (v in overlays) v.postInvalidateOnAnimation()
        lastScene = name
    }

    /** The glitch: bands of the picture slide sideways, and one band turns to its negative. */
    private fun applyTear(bitmap: Bitmap, t: Tear, lift: Double) {
        val canvas = Canvas(bitmap)
        val rs = renderScale
        val shift = (lift * rs).toInt()
        for ((y, h, dx) in t.bands) {
            val y0 = ((y * rs).toInt() + shift).coerceIn(0, bitmap.height - 1)
            val hh = (h * rs).toInt().coerceIn(1, bitmap.height - y0)
            val strip = Bitmap.createBitmap(bitmap, 0, y0, bitmap.width, hh)
            canvas.drawBitmap(strip, dx * rs, y0.toFloat(), copyPaint)
            strip.recycle()
        }
        val y0 = ((t.negativeY * rs).toInt() + shift).coerceIn(0, bitmap.height - 1)
        val hh = (t.negativeH * rs).toInt().coerceIn(1, bitmap.height - y0)
        val strip = Bitmap.createBitmap(bitmap, 0, y0, bitmap.width, hh)
        canvas.drawBitmap(strip, 0f, y0.toFloat(), invertPaint)
        strip.recycle()
    }

    // ---- what the views read -------------------------------------------------------------------------------------

    /** The scene view is drawing [f]: the render thread keeps off its bitmap until it is done. */
    fun show(f: GlyphFrame?) { showing = f?.index ?: -1 }

    /** What the keyboard that is about to be shown can do before it is shown: faces, type pools, the first run of the code. */
    fun warm() {
        if (warmed) return
        warmed = true
        Thread({
            try {
                GlyphPixelFonts.preload(context.applicationContext)
                val e = GlyphEngine(pools())
                e.configure(false, 2, false, false, false)
                e.setLayout(GlyphLayout.NONE)
                e.force(1)
                e.prime(2)
                e.drawScene(GlyphNullSink)
                e.front()
            } catch (_: Exception) {
            }
        }, "glyph-warm").start()
    }
    private var warmed = false

    private fun pools(): GlyphPools = sharedPools ?: synchronized(Companion) {
        sharedPools ?: GlyphPools(Paint()::hasGlyph).also { sharedPools = it }
    }

    companion object {
        /** The place whose keys hang as signs. */
        const val STREET = "街"
        @Volatile private var sharedPools: GlyphPools? = null

        /** Twelve frames a second at most. */
        const val FRAME_MS = 84L
        /** Whether the land is drawn as strips (a debug build can ask either way with the setting "glyphLayers"). */
        const val LAYER_STRIPS = false
        /** The least time between two whole drawings of the ground while only the turn of the type changes. */
        const val GROUND_TURN_MS = 700L
        private val NO_TAU = DoubleArray(0)
        /** A keyboard called up again within this long of going away does not arrive again. */
        const val ARRIVAL_GRACE_MS = 2000L
    }
}

/** The land, the sky and the air, behind the keys. */
@SuppressLint("ViewConstructor")
internal class GlyphSceneView(context: Context, private val controller: GlyphController) : View(context) {
    private val paint = Paint().apply { isFilterBitmap = false }
    private val src = Rect()
    private val dst = Rect()
    var paper = 0xFFECE8DC.toInt()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val f = controller.frame
        if (f == null) {
            canvas.drawColor(paper)
            return
        }
        controller.show(f)
        try {
            src.set(0, 0, f.bitmap.width, f.bitmap.height)
            dst.set(0, 0, width, height)
            canvas.drawBitmap(f.bitmap, src, dst, paint)
        } finally {
            controller.show(null)
        }
    }
}

/**
 * The corner's bank and what passes in front of the land: between the land and the cats, under the keys' own type. It is drawn live,
 * with the display, while the near motion has anything to do (see [GlyphController.kick]); it takes no touch.
 */
internal class GlyphNearView(context: Context, private val controller: GlyphController) : View(context) {
    private val scale = context.resources.displayMetrics.density.coerceAtMost(3f)
    private val sink = GlyphCanvasSink(scale, gather = false)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val f = controller.frame ?: return
        val n = f.near ?: return
        sink.begin(canvas, f.layout.lift)
        controller.drawNearLive(sink, GlyphColors.of(f.dark), n, front = false)
    }
}

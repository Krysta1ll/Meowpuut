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
    /** The ring a key press runs out through the picture, by the screen, between the land and what is near. */
    val ringView = GlyphRingView(context, this)
    /** The ground with depth, drawn live under the picture (see [GlyphGround]). */
    val groundView = GlyphGroundView(context, this)
    private val ground = GlyphGround(renderScale)
    /** The land drawn once and moved (see [GlyphLayerStrips]); off, the picture is drawn whole twelve times a second as it was. */
    private val layerStrips = GlyphLayerStrips(renderScale)
    private var layersOn = LAYER_STRIPS
    private var lastWorldAt = 0L
    @Volatile private var groundExact = DoubleArray(0)
    private var groundWant = IntArray(0)
    private var groundAdvancing = false
    @Volatile private var worldTarget = 0
    @Volatile private var worldEpoch = 0
    @Volatile var foreHold: Double? = null
    private var dbgLast = -1L
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
            e.scape.groundLive = LIVE_GROUND
            layersOn = if (perf) context.getSharedPreferences("meowpuut", Context.MODE_PRIVATE).getBoolean("glyphLayers", LAYER_STRIPS) else LAYER_STRIPS
            e.theme.deep = !perf || context.getSharedPreferences("meowpuut", Context.MODE_PRIVATE).getBoolean("glyphDeep", true)   // 縱深: the picture in the finest pixels, with mist, a near corner, what passes
            engine = e
            sink = GlyphCanvasSink(renderScale, gather = true)
            e.restore(startDistance)
            val carried = carriedTarget                           // the picture goes on from where the last showing left it (the records only count what was committed)
            if (carried > e.world.target) { e.world.target = carried; e.world.pos = max(e.world.pos, carried.toDouble()) }
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
        carriedTarget = 0
        handler?.post { engine?.restore(0L, reset = true); requestTick() }
    }

    /** The keyboard went away (or the skin did). Nothing typed is remembered and the thread ends. */
    fun stop() {
        val t = thread ?: return
        main.removeCallbacks(comeDown); lifted = false
        carriedTarget = max(carriedTarget, max(worldTarget, liveTarget))
        ground.clear(); groundExact = DoubleArray(0)
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

    /**
     * The system is short of memory: the land's strips, the ground's rows and the rings' frames are given back, and the picture is drawn whole
     * (as it is with the strips off: the same picture, only dearer to make) until the next showing.
     */
    fun trim() {
        ringView.release()
        val h = handler
        if (h == null) { ground.clear(); return }
        h.post {
            val e = engine ?: return@post
            layersOn = false
            e.scape.groundLive = false
            layerStrips.release(e.scape)
            ground.clear(); groundExact = DoubleArray(0)
            for (i in ring.indices) ring[i] = null
            e.world.dirty = true
            requestTick()
        }
        groundView.postInvalidateOnAnimation()
    }

    /** The keyboard is hidden, but the view stays: the clock stops, what was typed is dropped, the distance is saved. */
    fun pause() {
        val h = handler ?: return
        hiddenAt = SystemClock.uptimeMillis()
        main.removeCallbacks(comeDown); landStep()
        ringView.clear()
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
    private fun liveState(n: GlyphNear) = n.state()

    /** Where what passes stands right now, its left edge in points. */
    private fun liveForeAt(n: GlyphNear): Double = foreHold?.let { it * n.width } ?: n.foreAt

    /** The corner and, unless [front], the corner is drawn too; what passes is drawn when it is in the picture. Nothing above the keyboard's top. */
    internal fun drawNearLive(g: GlyphSink, c: GlyphColors, n: GlyphNear, front: Boolean) {
        val clip = GlyphTopClip(g, 0.0)
        val st = liveState(n)
        if (!front) GlyphDepthFore.drawFrame(if (n.fog < 1.0) GlyphTopClip(GlyphFadeSink(g, n.fog), 0.0) else clip, c, st)
        if (n.passes || foreHold != null) GlyphDepthFore.drawFore(clip, c, st, liveForeAt(n), 0.0)
    }

    /** What passes, drawn alone: for the view in front of the cats, clipped by the caller to where they stand. */
    internal fun drawForeLive(g: GlyphSink, c: GlyphColors, n: GlyphNear) {
        if (n.passes || foreHold != null) GlyphDepthFore.drawFore(GlyphTopClip(g, 0.0), c, liveState(n), liveForeAt(n), 0.0)
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
        if (!e.theme.deep) h = h * 31 + (if (e.world.ripples.isNotEmpty()) e.world.tick.toLong() else 0L)   // a ring is running out through the land
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
        val steps = kind != GlyphKey.DELETE && kind != GlyphKey.OTHER
        if (perf && steps) { probeSig = lastSig; probeAt = SystemClock.elapsedRealtimeNanos() }
        if (steps) beginStep()
        ringView.start(x, y)
        handler?.post { engine?.let { it.key(kind, x.toDouble(), y.toDouble(), composing, letter); worldTarget = it.world.target; worldEpoch = it.world.epoch }; requestTick() }
    }

    // ---- the feel of a key: a step begins with it, on this thread, in this frame -------------------------------------------

    private var liveTarget = 0
    private var lifted = false
    private var stepAtMs = 0L
    private val comeDown = Runnable { landStep() }

    /** Whether a key is a step of the cats now: with depth, a picture that moves, and the effects not at the lowest. */
    private fun stepping(): Boolean { val n = frame?.near ?: return false; return !n.still && n.level >= 1 }

    /**
     * A key that types is a step, and the step begins now (main thread, no waiting for the twelve-a-second clock or for a picture): the cats change
     * foot and lift a pixel in the very next frame. The foot comes down after 0.6 of the time since the key before (60 to 160 ms), and the ground
     * under it goes a cell then. A key that comes before that finishes the step in hand at once and begins its own: steps never queue.
     */
    private fun beginStep() {
        if (!stepping()) return
        val now = SystemClock.uptimeMillis()
        if (lifted) { main.removeCallbacks(comeDown); landStep() }
        else if (now - stepAtMs > 400) liveTarget = worldTarget                   // (nothing is in hand: the engine's count is the truth)
        val gap = now - stepAtMs
        stepAtMs = now
        liveTarget += STEP
        lifted = true
        if (perf) android.util.Log.i("MeowpuutGlyphPerf", "step up target=$liveTarget foot=${walkOf(CatId.MIMI)?.first}")
        front.invalidate()                                                       // the leg lifts in this frame
        main.postDelayed(comeDown, gap.times(0.6).toLong().coerceIn(60L, 160L))
    }

    /** The foot comes down: the body is back in place, and the picture goes on a step (the render thread does that, at once). */
    private fun landStep() {
        if (!lifted) return
        lifted = false
        front.invalidate()
        handler?.post { engine?.let { e -> if (e.land()) render(e); worldTarget = e.world.target
            if (perf) android.util.Log.i("MeowpuutGlyphPerf", "step down target=${e.world.target} ${e.scape.debugCells()}") } }
    }

    /** What the cats are doing as the keys have made them, for the front view to draw over the last picture: the pose, and a pixel of lift while the foot is in the air. Null when the keys have been idle. */
    fun walkOf(cat: CatId): Pair<String, Int>? {
        if (!stepping() || stepAtMs == 0L || SystemClock.uptimeMillis() - stepAtMs >= 1200L) return null
        val step = Math.round(liveTarget / GlyphWorld.STEP_UNITS) + (if (cat == CatId.MIMI) 0L else 1L)
        return (if (step and 1L != 0L) "walkA" else "walkB") to (if (lifted) GlyphCats.POINTS else 0)
    }

    // ---- debug builds: how long a key takes to show in the cats (the feel of it) ----------------------------------------
    @Volatile private var probeAt = 0L
    @Volatile private var probeSig = 0L
    @Volatile private var lastSig = 0L

    /** The front view has drawn the cats ([sig]: what they were drawn in). The first draw that differs from the one before the key is the key's answer. */
    fun probeDrawn(sig: Long) {
        lastSig = sig
        val at = probeAt
        if (at != 0L && sig != probeSig) {
            probeAt = 0L
            android.util.Log.i("MeowpuutGlyphPerf", "cats answer the key in ${"%.1f".format((SystemClock.elapsedRealtimeNanos() - at) / 1e6)} ms")
        }
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
        handler?.post { engine?.let { it.foreHold = at; it.world.dirty = true }; requestTick() }
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
        // The world takes twelve steps a second, and the picture is made in a step only when something has changed in it (a layer a cell on).
        var stepped = false
        if (started - lastWorldAt >= FRAME_MS - 10) {
            lastWorldAt = started
            stepped = e.step()
            worldTarget = e.world.target; worldEpoch = e.world.epoch
        }
        if (stepped) render(e)
        if (e.busy()) {
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
            val t0 = System.nanoTime()
            layerStrips.sync(e.scape, GlyphColors.of(e.theme.dark), l.lift, groundSignature(e, l), e.world.epoch, e.world.run, nowMs)
            if (perf) stripPerf((System.nanoTime() - t0) / 1e6)
        } else { layerStrips.release(e.scape) }
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
        val n = near
        if (n != null && n.passes && GlyphMotion.visible(n.foreAt, n.width)) for (v in overlays) v.postInvalidateOnAnimation()
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
        /** Where the picture had got to when the last showing ended, for the next one to go on from (this process only; the journey's own count is the records'). */
        @Volatile private var carriedTarget = 0

        /** Twelve frames a second at most. */
        const val FRAME_MS = 84L
        /** Units of the journey to a step (see [GlyphWorld.STEP_UNITS]). */
        private const val STEP = 3
        /** Whether the ground is drawn live under the picture, in strips that go by with the display (smooth, and so not what 字景 wants: off; kept for a shot). */
        const val LIVE_GROUND = false
        /** Whether the land is drawn as strips (a debug build can ask either way with the setting "glyphLayers"). */
        const val LAYER_STRIPS = true
        /** The least time between two whole drawings of the ground while only the turn of the type changes. */
        const val GROUND_TURN_MS = 700L
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

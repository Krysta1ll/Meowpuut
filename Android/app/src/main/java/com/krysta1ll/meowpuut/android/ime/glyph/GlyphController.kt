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
    /** By day, the near art alone (its alpha), for the keys' type to be cut out of it; null at night or without depth. */
    val nearMask: Bitmap? = null,
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
    private val ring = arrayOfNulls<Bitmap>(3)
    private val nearRing = arrayOfNulls<Bitmap>(3)
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
            e.theme.deep = true                                // 縱深: the picture in the finest pixels, with mist, a near corner, what passes
            engine = e
            sink = GlyphCanvasSink(renderScale, gather = true)
            e.restore(startDistance)
            forced?.let { e.force(it) }
            fore?.let { e.scape.foreFix = it }
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
        handler?.post { engine?.restore(0L); requestTick() }
    }

    /** The keyboard went away (or the skin did). Nothing typed is remembered and the thread ends. */
    fun stop() {
        val t = thread ?: return
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
        val m = f.nearMask ?: return null
        return GlyphCanvasSink.NearCut(m, renderScale, f.layout.lift, c.paper, dx, dy)
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
        handler?.post { engine?.key(kind, x.toDouble(), y.toDouble(), composing, letter); requestTick() }
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
        handler?.post { engine?.scape?.foreFix = at; engine?.world?.dirty = true; requestTick() }
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
        if (e.step()) render(e)
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
        val tear = e.drawScene(out)
        out.layerEnd()
        if (tear != null) applyTear(bitmap, tear, l.lift)
        val near = e.near()
        var mask: Bitmap? = null
        if (near != null && !e.theme.dark) {                              // by day the keys' type is cut out of what is near: its alpha, drawn once more on its own
            var nb = nearRing[i]
            if (nb == null || nb.width != w || nb.height != h) { nb = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8); nearRing[i] = nb }
            nb.eraseColor(0)
            val maskSink = nearSink ?: GlyphCanvasSink(renderScale, gather = false).also { nearSink = it }
            maskSink.begin(Canvas(nb), l.lift)
            e.scape.drawNear(maskSink, GlyphColors.of(false), front = false)
            mask = nb
        }
        val finished = GlyphFrame(bitmap, i, e.front(), l, e.theme.dark, e.theme.opaque, e.disc(), near, mask, e.world.tick, e.scape.sceneName)
        published = i
        frame = finished
        scene.postInvalidateOnAnimation()
        front.postInvalidateOnAnimation()
        val name = e.scape.sceneName
        if (e.disc() != null && (e.scape.formed < 1.0 || name != lastScene)) for (v in overlays) v.postInvalidateOnAnimation()
        if (name != lastScene) front.post { front.translationZ = if (name == STREET) 2f * density else 0f }      // in the street the cats walk in front of the signs
        lastScene = name
        if (e.theme.dark && name == STREET) signs?.postInvalidateOnAnimation()
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

package com.krysta1ll.meowpuut.android.app.home

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCanvasSink
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphEngine
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q
import kotlin.math.max
import kotlin.math.min

/** What a live picture shows. Either one [scene] (a place on the road) or the place the journey has brought the cats to at [distance]. */
internal class LiveSpec(
    val scene: Int? = null,
    val distance: Long = 0L,
    /** The two cats walk in it. */
    val cats: Boolean = false,
    /** A place not yet reached is shown behind a veil of paper. */
    val veiled: Boolean = false,
    /** The picture's shape: height as a share of width (tiles) or a fixed height in dp (the home's own picture). */
    val aspect: Double = 0.0,
    val heightDp: Int = 0,
    /** A small picture: what stands in it (the gate, the lamp) is drawn at its small size. */
    val small: Boolean = false,
)

/**
 * A picture of 字景 that moves: a place drawn by the same engine as the keyboard's, shown on a page. It moves only while it can be seen
 * (the page is in front and the view is on the screen), at twelve frames a second at most, and is still the moment it leaves. With the
 * system's animations off, power saving, or the effects setting at its lowest, it is drawn once and stays so; with more contrast it is
 * the plain sheet. Drawing happens on the [GlyphStage]'s thread; this view only shows the last finished picture.
 */
@SuppressLint("ViewConstructor")
internal class GlyphLiveView(
    context: Context, val spec: LiveSpec, private val dark: Boolean, private val plain: Boolean, private val level: Int,
) : View(context), ViewTreeObserver.OnScrollChangedListener {
    private val density = context.resources.displayMetrics.density
    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val src = Rect()
    private val dst = Rect()
    @Volatile internal var shown: Bitmap? = null
    private var attached = false
    private var wanted = false
    private val visible = Rect()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (spec.heightDp > 0) (spec.heightDp * density).toInt() else (w * spec.aspect).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) GlyphStage.of(context).configure(this, w / density.toDouble(), h / density.toDouble(), dark, plain, level)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        refreshVisibility()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = shown ?: run { canvas.drawColor(GlyphColors.of(dark).paper); return }
        src.set(0, 0, bitmap.width, bitmap.height)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
    }

    // ---- when it may move ------------------------------------------------------------------------------------

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        viewTreeObserver.addOnScrollChangedListener(this)
        GlyphStage.of(context).attach(this)
        refreshVisibility()
    }

    override fun onDetachedFromWindow() {
        attached = false
        if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(this)
        GlyphStage.of(context).detach(this)
        super.onDetachedFromWindow()
    }

    override fun onScrollChanged() = refreshVisibility()
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        refreshVisibility()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refreshVisibility()
    }

    private fun refreshVisibility() {
        val now = attached && isShown && windowVisibility == VISIBLE && getGlobalVisibleRect(visible) && visible.height() > 0 && visible.width() > 0
        if (now == wanted) return
        wanted = now
        GlyphStage.of(context).run(this, now)
    }

    /** A tap on the picture: the cats go for a short walk. */
    fun nudge() = GlyphStage.of(context).nudge(this)

    fun publish(bitmap: Bitmap) {
        shown = bitmap
        postInvalidateOnAnimation()
    }
}

/**
 * The one thread that draws every live picture on the pages: it steps each picture that is on the screen, at most twelve times a second, and
 * is idle (no timer at all) when none is. Each picture has its own [GlyphEngine]; a picture that leaves the screen keeps its last frame and
 * gives its working bitmaps back.
 */
internal class GlyphStage private constructor(private val context: Context) {
    private val thread = HandlerThread("meowpuut-home-glyph", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
    private val handler = Handler(thread.looper)
    private val pools = Brush.pools(context)
    private val entries = HashMap<GlyphLiveView, Entry>()
    private var ticking = false
    private var lastTick = 0L

    private class Settings(val width: Double, val height: Double, val dark: Boolean, val plain: Boolean, val level: Int)

    private inner class Entry(val view: GlyphLiveView) {
        var settings: Settings? = null
        var engine: GlyphEngine? = null
        var sink: GlyphCanvasSink? = null
        var ring = arrayOfNulls<Bitmap>(3)
        var next = 0
        var running = false
        var scale = 1f
        var drawn = false
        var still = false
        var hiddenAt = 0L
        var lastStep = 0L

        fun rebuild() {
            val s = settings ?: return
            val spec = view.spec
            val e = GlyphEngine(pools)
            val calm = s.plain || s.level == 0 || systemStill()
            e.configure(s.dark, s.level.coerceIn(0, 3), still = calm, opaque = s.plain, crab = false)
            e.showCats = spec.cats
            e.setLayout(
                GlyphLayout(
                    s.width, s.height, DoubleArray(0), 30.0, 0.0, 0.0, 0.0,
                    // The ridge the cats walk on: low in the picture, with the ground below it (the sea, the street).
                    horizon = s.height * 0.78, wide = true,
                ),
            )
            val place = spec.scene ?: com.krysta1ll.meowpuut.android.app.stats.JourneyStats.places(spec.distance).here
            e.force(place)
            engine = e
            for (i in ring.indices) ring[i] = null
            sink = null
            drawn = false
            still = calm
            e.prime(2)
            if (spec.small) for (l in e.scape.layers) l.big = 1
            if (!calm && !spec.small) e.arrive()
        }

        fun frame(): Bitmap? {
            val s = settings ?: return null
            val e = engine ?: return null
            val brushScale = min(context.resources.displayMetrics.density, 3f)
            scale = brushScale
            val w = max(1, Math.ceil(s.width * brushScale).toInt())
            val h = max(1, Math.ceil(s.height * brushScale).toInt())
            var bitmap = ring[next]
            if (bitmap == null || bitmap.width != w || bitmap.height != h) {
                bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                ring[next] = bitmap
            }
            val canvas = Canvas(bitmap)
            val g = sink ?: GlyphCanvasSink(brushScale, gather = true).also { sink = it }
            g.begin(canvas, 0.0)
            e.drawScene(g)
            drawCats(e, g, canvas, s)
            if (view.spec.veiled) {
                paint.color = GlyphColors.of(s.dark).paper
                paint.alpha = 219
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            next = (next + 1) % ring.size
            return bitmap
        }

        private val paint = Paint()
        private val painter = com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyPainter(com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelFonts(context))
        private val rasters = HashMap<String, com.krysta1ll.meowpuut.android.ime.glyph.Raster>()

        private fun drawCats(e: GlyphEngine, g: GlyphCanvasSink, canvas: Canvas, s: Settings) {
            if (!view.spec.cats) return
            val colors = GlyphColors.of(s.dark)
            val u = GlyphCats.POINTS.toDouble()
            for (d in e.front().cats) {
                val key = "${d.cat}/${d.pose}/${d.face}/${s.dark}/${d.night}"
                if (rasters.size > 60) rasters.clear()
                val r = rasters.getOrPut(key) { GlyphCats.raster(d.cat, d.pose, d.face, s.dark, d.night, colors) }
                val top = d.feetY - GlyphCats.H * u + d.hopY
                g.raster(r, d.x - GlyphCats.MARGIN * u, top - GlyphCats.MARGIN * u, u)
                if (d.zzz) for (i in 0 until 3) {
                    painter.label(g, colors, "ㄗ", d.x + 36.0 + i * 11 + ((d.zPhase + i) % 2), d.feetY - 24.0 - i * 12, 1.0, 1.0, centre = true, halo = true)
                }
            }
        }
    }

    fun configure(view: GlyphLiveView, width: Double, height: Double, dark: Boolean, plain: Boolean, level: Int) {
        val s = Settings(width, height, dark, plain, level)
        handler.post {
            val e = entries.getOrPut(view) { Entry(view) }
            val old = e.settings
            e.settings = s
            if (old == null || old.width != width || old.height != height || old.dark != dark || old.plain != plain || old.level != level) {
                e.rebuild()
                if (e.running || !e.drawn) drawOnce(e)
                schedule()
            }
        }
    }

    fun attach(view: GlyphLiveView) { handler.post { entries.getOrPut(view) { Entry(view) } } }

    fun detach(view: GlyphLiveView) {
        handler.post {
            entries.remove(view)?.let { it.engine = null; it.sink?.release(); for (i in it.ring.indices) it.ring[i] = null }
        }
    }

    /** The picture is (not) on the screen: it moves while it is. */
    fun run(view: GlyphLiveView, on: Boolean) {
        handler.post {
            val e = entries[view] ?: return@post
            e.running = on
            if (on) {
                e.lastStep = 0L
                if (!e.drawn) drawOnce(e)
                // A picture that comes back after a while is typed into being again, as the keyboard's is; one that only blinked out of view is not.
                if (!e.still && !e.view.spec.small && e.hiddenAt != 0L && SystemClock.uptimeMillis() - e.hiddenAt > 2_000L) e.engine?.arrive()
                e.hiddenAt = 0L
                schedule()
            } else e.hiddenAt = SystemClock.uptimeMillis()
        }
    }

    /** The cats go for a walk. */
    fun nudge(view: GlyphLiveView) {
        handler.post {
            val e = entries[view] ?: return@post
            val w = e.engine?.world ?: return@post
            w.moved(36, null)
            w.feel(mapOf(CatId.MIMI to "happy", CatId.MOMO to "happy"), 12, true)
            if (e.still) {                                  // a still picture does not walk, but it can look pleased once
                e.engine?.step(); drawOnce(e)
            }
            schedule()
        }
    }

    private fun drawOnce(e: Entry) {
        val eng = e.engine ?: return
        eng.step()
        val bitmap = e.frame() ?: return
        e.drawn = true
        e.view.publish(bitmap)
    }

    private fun systemStill(): Boolean =
        !ValueAnimator.areAnimatorsEnabled() || context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    private fun schedule() {
        if (ticking) return
        if (entries.values.none { it.running && it.engine != null && !it.still }) return
        ticking = true
        handler.postDelayed(tick, max(0L, FRAME_MS - (SystemClock.uptimeMillis() - lastTick)))
    }

    private val tick = Runnable {
        ticking = false
        lastTick = SystemClock.uptimeMillis()
        var again = false
        for (e in entries.values) {
            if (!e.running || e.still) continue
            val eng = e.engine ?: continue
            if (!eng.busy()) continue
            again = true
            // The world keeps its own time: when a picture is slow to draw (the first frames, a busy phone) it takes the steps it missed, so
            // the arrival and the walking last as long as they should.
            val now = SystemClock.uptimeMillis()
            val missed = if (e.lastStep == 0L) 1 else ((now - e.lastStep) / FRAME_MS).toInt().coerceIn(1, 4)
            e.lastStep = now
            var changed = false
            repeat(missed) { if (eng.step()) changed = true }
            if (changed) {
                val bitmap = e.frame() ?: continue
                e.drawn = true
                e.view.publish(bitmap)
            }
        }
        if (again) schedule()
    }

    companion object {
        /** Twelve frames a second at most. */
        const val FRAME_MS = 84L
        @Volatile private var instance: GlyphStage? = null
        fun of(context: Context): GlyphStage = instance ?: synchronized(GlyphStage::class.java) {
            instance ?: GlyphStage(context.applicationContext).also { instance = it }
        }
    }
}

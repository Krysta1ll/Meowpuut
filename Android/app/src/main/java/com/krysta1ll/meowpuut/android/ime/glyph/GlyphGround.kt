package com.krysta1ll.meowpuut.android.ime.glyph

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The ground of 字景 with depth, drawn live: each row of its cells is a strip a little wider than the screen, drawn once and then only moved
 * (by device pixels, with the display), and when the row has gone a cell or more it is shifted over in memory and only the cells that come
 * into view are drawn. Nothing here is the 12-pictures-a-second picture: that one leaves the ground's place bare (see [GlyphScape.groundLive]).
 *
 * The render thread owns the drawing ([rebuild], [advance]); the main thread only composes what it is handed ([GlyphGroundView]). A row has two
 * bitmaps, one on show and one to draw into; they are swapped under [lock], which the view holds while it draws.
 */
internal class GlyphGround(private val scale: Float) {
    val lock = Any()
    private val sink = GlyphCanvasSink(scale, gather = true)
    private val paint = Paint().apply { isFilterBitmap = false }

    /** What the view needs of a row: its picture, where it stands, how far it was drawn, and how fast the row goes. */
    class Rows(
        val geo: GlyphScape.GroundGeometry,
        /** The strips on show, one per row; a strip's top in device pixels (in the view), and the cell's width in device pixels. */
        val bitmaps: Array<Bitmap?>, val topPx: IntArray, val baked: IntArray, val cellPx: Float,
        val speed: DoubleArray,
    )

    @Volatile var rows: Rows? = null
        private set
    private var spare = arrayOf<Bitmap?>()
    private var lift = 0.0
    private var geoKey = ""

    /** Where each row of this ground stands for a world run of [near] at the feet and [far] at the horizon: its exact distance in cells. */
    fun exact(geo: GlyphScape.GroundGeometry, near: Double, far: Double, out: DoubleArray) {
        for (r in 0 until geo.rows) out[r] = GlyphMotion.groundRun(near, far, geo.g0(r)) * geo.speed(r) * 8 / geo.cell
    }

    fun clear() { synchronized(lock) { rows = null }; geoKey = "" }

    /** Draws every row afresh at the offsets in [want] (whole cells). */
    fun rebuild(scape: GlyphScape, c: GlyphColors, layoutLift: Double, want: IntArray) {
        val geo = scape.groundGeometry() ?: return clear()
        lift = layoutLift
        val key = "${geo.cell}|${geo.rows}|${geo.top}|${scape.width}"
        val old = rows
        val reuse = old != null && geoKey == key
        geoKey = key
        val cellPx = geo.cell * scale
        val wPx = ceil((geo.cols + MARGIN) * geo.cell * scale)
        val pad = PAD
        val shown = if (reuse) old!!.bitmaps.copyOf() else arrayOfNulls<Bitmap>(geo.rows)
        val topPx = IntArray(geo.rows) { edge(geo.y(it) - pad + lift) }
        val baked = IntArray(geo.rows)
        if (!reuse) spare = arrayOfNulls(geo.rows)
        val next = arrayOfNulls<Bitmap>(geo.rows)
        for (r in 0 until geo.rows) {
            val h = ceil((geo.cell + 2 * pad) * scale)
            var b = spare[r]
            if (b == null || b.width != wPx || b.height != h) b = Bitmap.createBitmap(wPx, h, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(true) }
            b.eraseColor(0)
            val off = want.getOrElse(r) { 0 }
            drawInto(scape, c, geo, r, b, topPx[r], 0, geo.cols + MARGIN, off)
            next[r] = b; baked[r] = off
            spare[r] = shown[r]                                          // the one that was on show is free to draw into next time
        }
        val speed = DoubleArray(geo.rows) { geo.speed(it) }
        synchronized(lock) { rows = Rows(geo, next, topPx, baked, cellPx.toFloat(), speed) }
    }

    /** Moves the rows whose wanted offset ([want], whole cells) is not the one they were drawn at: shifts them over and draws what comes into view. */
    fun advance(scape: GlyphScape, c: GlyphColors, want: IntArray): Boolean {
        val cur = rows ?: return false
        val geo = cur.geo
        var any = false
        val next = cur.bitmaps.copyOf()
        val baked = cur.baked.copyOf()
        for (r in 0 until geo.rows) {
            val w = want.getOrElse(r) { baked[r] }
            val dc = w - baked[r]
            if (dc == 0) continue
            any = true
            val shown = cur.bitmaps[r] ?: continue
            var b = spare[r]
            if (b == null || b.width != shown.width || b.height != shown.height) b = Bitmap.createBitmap(shown.width, shown.height, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(true) }
            if (dc in 1 until MARGIN) {
                b.eraseColor(0)
                val shift = edge(dc * geo.cell.toDouble() - 0.0).let { floor(dc * geo.cell * scale + 0.5).toInt() }
                Canvas(b).drawBitmap(shown, -shift.toFloat(), 0f, paint)
                drawInto(scape, c, geo, r, b, cur.topPx[r], geo.cols + MARGIN - dc, geo.cols + MARGIN, w)
            } else {
                b.eraseColor(0)
                drawInto(scape, c, geo, r, b, cur.topPx[r], 0, geo.cols + MARGIN, w)
            }
            next[r] = b; baked[r] = w; spare[r] = shown
        }
        if (any) synchronized(lock) { rows = Rows(geo, next, cur.topPx, baked, cur.cellPx, cur.speed) }
        return any
    }

    private fun drawInto(scape: GlyphScape, c: GlyphColors, geo: GlyphScape.GroundGeometry, row: Int, b: Bitmap, topPx: Int, colFrom: Int, colTo: Int, off: Int) {
        val canvas = Canvas(b)
        sink.begin(canvas, lift - topPx / scale.toDouble())
        scape.drawGroundRow(sink, c, geo, row, colFrom, colTo, off)
        sink.layerEnd()
    }

    private fun edge(v: Double): Int = floor(v * scale + 0.5 + 1e-6).toInt()
    private fun ceil(v: Double): Int = kotlin.math.ceil(v).toInt()
    private fun ceil(v: Float): Int = kotlin.math.ceil(v.toDouble()).toInt()

    companion object {
        /** Cells drawn beyond the screen on the right, so a row can go a few cells between two drawings; points of room round a row for the type that overhangs. */
        const val MARGIN = 10
        /** A row is moved when it has gone this many cells beyond what was drawn: the margin left in front of the screen is then still plenty. */
        const val ADVANCE_AT = 3
        const val PAD = 3.0
    }
}

/** The ground, live: the strips of [GlyphGround], each moved to where the row has got to now. Under the picture, which leaves its place bare. */
internal class GlyphGroundView(context: Context, private val controller: GlyphController) : View(context) {
    private val paint = Paint().apply { isFilterBitmap = false }
    var paper = 0xFFECE8DC.toInt()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(paper)
        controller.drawGround(canvas, paint)
    }
}

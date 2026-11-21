package com.krysta1ll.meowpuut.android.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Where pictures are drawn: two low-priority threads shared by every page; the result is handed back on the main thread. */
internal object PictureWorker {
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }.apply { isDaemon = true; name = "meowpuut-pictures" } }
    private val main = Handler(Looper.getMainLooper())

    fun <T> run(work: () -> T, done: (T) -> Unit) {
        pool.execute {
            val result = try { work() } catch (_: Throwable) { return@execute }
            main.post { done(result) }
        }
    }
}

/** A still picture of the land's type, drawn once at the width it is shown at (off the main thread) and again if that changes. */
@SuppressLint("ViewConstructor")
internal class PictureView(
    context: Context,
    private val brush: Brush,
    private val heightOf: (widthDp: Double) -> Double,
    description: String,
    private val body: (Pen, Double) -> Unit,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val src = Rect()
    private val dst = Rect()
    private var bitmap: Bitmap? = null
    private var drawnWidth = 0.0
    private var requested = 0.0

    init {
        contentDescription = description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (heightOf(w / density.toDouble()) * density).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wDp = w / density.toDouble()
        if (wDp <= 0 || wDp == requested) return
        requested = wDp
        val hDp = heightOf(wDp)
        PictureWorker.run({ brush.picture(wDp, hDp) { body(it, wDp) } }) { made ->
            if (requested == wDp) { bitmap = made; drawnWidth = wDp; invalidate() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: run { canvas.drawColor(brush.colors.paper); return }
        src.set(0, 0, b.width, b.height)
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, src, dst, paint)
    }
}

/**
 * A long picture (日子的山, the road of festivals), drawn in tiles as it is scrolled to (a tile is 64 cells wide, so no cell is split) and
 * kept while near. It sits in a horizontal scroll view. [paint] draws the part of the picture between two x (dp).
 */
@SuppressLint("ViewConstructor")
internal class TiledView(
    context: Context,
    private val brush: Brush,
    private val widthDp: Double,
    private val heightDp: Double,
    description: String,
    private val paint: (Pen, Double, Double) -> Unit,
) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val tiles = object : LruCache<Int, Bitmap>(9) {}
    private val asked = HashSet<Int>()
    private val src = Rect()
    private val dst = Rect()
    private val clip = Rect()

    init {
        contentDescription = description
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ceil(widthDp * density).toInt(), (heightDp * density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        canvas.getClipBounds(clip)
        canvas.drawColor(brush.colors.paper)
        val tilePx = TILE * density
        val first = max(0, (clip.left / tilePx).toInt())
        val last = min(count() - 1, (clip.right / tilePx).toInt())
        for (t in first..last) {
            val b = tiles.get(t)
            if (b == null) { request(t); continue }
            src.set(0, 0, b.width, b.height)
            val left = (t * tilePx).toInt()
            dst.set(left, 0, left + (b.width * density / brush.scale).toInt(), height)
            canvas.drawBitmap(b, src, dst, bitmapPaint)
        }
        // The tiles on either side are made ahead of the finger.
        if (first > 0) request(first - 1)
        if (last < count() - 1) request(last + 1)
    }

    private fun count() = ceil(widthDp / TILE).toInt()

    private fun request(t: Int) {
        if (tiles.get(t) != null || !asked.add(t)) return
        val from = t * TILE
        val to = min(widthDp, from + TILE)
        PictureWorker.run({ brush.picture(to - from, heightDp) { paint(it, from, to) } }) { b ->
            asked.remove(t)
            tiles.put(t, b)
            invalidate()
        }
    }

    companion object {
        /** 64 cells of 6 dp. */
        const val TILE = 384.0
    }
}

/**
 * A photograph first as type (the same glyphs as the land: its darks are mosaic, its middle tones are type, its lights are paper), the way
 * the prototype sets it; a tap brings the photograph back. [source] is whatever the page has of the photograph (it may arrive late).
 */
internal object Mosaic {
    private val BAYER = floatArrayOf(0f, 8f, 2f, 10f, 12f, 4f, 14f, 6f, 3f, 11f, 1f, 9f, 15f, 7f, 13f, 5f).map { (it + 0.5f) / 16f }

    /** [photo] set in type at [widthDp] by [heightDp] (a cover crop, as the photograph is shown). Off the main thread. */
    fun of(brush: Brush, photo: Bitmap, widthDp: Double, heightDp: Double, cell: Int = 6): Bitmap = brush.picture(widthDp, heightDp) { pen ->
        val cols = ceil(widthDp / cell).toInt()
        val rows = ceil(heightDp / cell).toInt()
        val ow = cols * 2
        val oh = rows * 2
        val small = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val s = max(ow.toDouble() / photo.width, oh.toDouble() / photo.height)
        val c = Canvas(small)
        val p = Paint().apply { isFilterBitmap = true }
        val dw = (photo.width * s).toFloat()
        val dh = (photo.height * s).toFloat()
        c.drawBitmap(photo, null, android.graphics.RectF((ow - dw) / 2, (oh - dh) / 2, (ow + dw) / 2, (oh + dh) / 2), p)
        val px = IntArray(ow * oh)
        small.getPixels(px, 0, ow, 0, 0, ow, oh)
        small.recycle()
        val n = ow * oh
        val lum = IntArray(n)
        val hist = IntArray(256)
        for (i in 0 until n) {
            val v = px[i]
            val l = (((v shr 16) and 0xFF) * 0.3 + ((v shr 8) and 0xFF) * 0.59 + (v and 0xFF) * 0.11).toInt().coerceIn(0, 255)
            lum[i] = l; hist[l]++
        }
        for (i in 1 until 256) hist[i] += hist[i - 1]            // spread the tones over the whole range: any photograph has its darks and its lights
        val v = pen.tones()
        for (r in 0 until rows) for (col in 0 until cols) {
            for (k in 0 until 4) {
                val bx = col * 2 + (k and 1)
                val by = r * 2 + (k shr 1)
                val l = hist[lum[by * ow + bx]].toDouble() / n
                val d = if (pen.dark) l else 1 - l
                v[k] = if (d > 0.56 + 0.4 * BAYER[(by and 3) * 4 + (bx and 3)]) 1.0 else if (d < 0.2) -1.0 else (d - 0.2) * 0.62
            }
            pen.cell(col * cell.toDouble(), r * cell.toDouble(), cell, v, "bopo", col, r, 71)
        }
    }

}

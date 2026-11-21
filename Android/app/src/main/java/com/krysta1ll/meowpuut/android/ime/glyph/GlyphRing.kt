package com.krysta1ll.meowpuut.android.ime.glyph

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The ring a key press runs out through the picture, one at a time (a new key takes it to itself and begins again): four frames, 50 ms each (as the prototype's, which had the ring grow by fifteen units a
 * world step), drawn over the picture where the key was pressed and by the screen, so that it stays where the finger was while the land goes
 * by. The frames are made once (a ring, edge dithered thinner with its growth) and drawn as they are: a key press costs one small bitmap blit.
 */
internal class GlyphRingView(context: Context, private val controller: GlyphController) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply { isFilterBitmap = false }
    private val rings = Array(MAX) { Ring() }
    private var made: Array<Bitmap>? = null
    private var madeDark: Boolean? = null

    private class Ring { var x = 0f; var y = 0f; var at = 0L; var live = false }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    /** A key went down at ([x], [y]) in the keyboard's own dp (the picture is [lift] above its top). Main thread. */
    fun start(x: Float, y: Float) {
        val f = controller.frame ?: return
        val n = f.near ?: return
        if (n.level < 2 || n.still) return
        var slot = rings.firstOrNull { !it.live } ?: rings.minByOrNull { it.at }!!
        slot.x = x; slot.y = y + f.layout.lift.toFloat(); slot.at = SystemClock.uptimeMillis(); slot.live = true
        postInvalidateOnAnimation()
    }

    /** Off: stop what is running (the level fell, or the picture is still). */
    fun clear() {
        for (r in rings) r.live = false
        invalidate()
    }

    /** Drop the frames (memory is short): made again when a ring next runs. */
    fun release() {
        made = null; madeDark = null
    }

    override fun onDraw(canvas: Canvas) {
        val f = controller.frame
        val n = f?.near
        if (n == null || n.level < 2 || n.still) { for (r in rings) r.live = false; return }
        val now = SystemClock.uptimeMillis()
        var any = false
        for (r in rings) {
            if (!r.live) continue
            val frame = ((now - r.at) / FRAME_MS).toInt()
            if (frame >= FRAMES) { r.live = false; continue }
            any = true
            val set = frames(n.dark)
            val b = set[frame]
            canvas.drawBitmap(b, r.x * density - b.width / 2f, r.y * density - b.height / 2f, paint)
        }
        if (any) postInvalidateOnAnimation()
    }

    private fun frames(dark: Boolean): Array<Bitmap> {
        val have = made
        if (have != null && madeDark == dark) return have
        val c = GlyphColors.of(dark)
        val out = Array(FRAMES) { make(it, c.ink, c.paper) }
        made = out; madeDark = dark
        return out
    }

    private fun make(n: Int, color: Int, paper: Int): Bitmap {
        val px = max(1, (PIXEL_DP * density).roundToInt())                       // one dither pixel
        val r = RADII[n]
        val halfW = r + HALF_BAND + EDGE + PIXEL_DP
        val halfH = r / Y_SQUASH + HALF_BAND + EDGE + PIXEL_DP
        val w = max(1, Math.ceil(2 * halfW * density / px).toInt())
        val h = max(1, Math.ceil(2 * halfH * density / px).toInt())
        val small = IntArray(w * h)
        val cover = COVER[n]
        for (j in 0 until h) for (i in 0 until w) {
            val dx = (i + 0.5) * px / density - halfW
            val dy = ((j + 0.5) * px / density - halfH) * Y_SQUASH
            val off = abs(hypot(dx, dy) - r)
            val t = BAYER[(j and 3) * 4 + (i and 3)] / 16.0
            if (off < HALF_BAND) {
                // the band: solid in the middle, broken at its sides
                val keep = if (off < HALF_BAND / 2) cover else cover * 0.6
                if (t < keep) small[j * w + i] = color
            } else if (off < HALF_BAND + EDGE && t < cover * 0.35) small[j * w + i] = paper    // and a broken edge of paper outside it
        }
        val bmp = Bitmap.createBitmap(w * px, h * px, Bitmap.Config.ARGB_8888)
        val row = IntArray(w * px)
        for (j in 0 until h) {
            for (i in 0 until w) { val v = small[j * w + i]; for (k in 0 until px) row[i * px + k] = v }
            for (k in 0 until px) bmp.setPixels(row, 0, w * px, 0, j * px + k, w * px, 1)
        }
        return bmp
    }

    companion object {
        const val FRAMES = 4
        const val FRAME_MS = 50L
        private const val MAX = 1
        private const val PIXEL_DP = 2.0
        private const val HALF_BAND = 2.0
        private const val EDGE = 2.0
        private const val Y_SQUASH = 1.4
        private val RADII = doubleArrayOf(12.0, 22.0, 32.0, 42.0)
        private val COVER = doubleArrayOf(1.0, 0.72, 0.46, 0.24)
        private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
    }
}

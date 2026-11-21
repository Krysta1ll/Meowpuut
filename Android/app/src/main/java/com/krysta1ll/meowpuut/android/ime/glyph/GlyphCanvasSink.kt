package com.krysta1ll.meowpuut.android.ime.glyph

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Type cut to one bit with the system's own fonts: the text is drawn small in an off-screen bitmap and every pixel that is more than a
 * third ink becomes ink, as the prototype does. It draws what the pixel faces lack (see [GlyphPixelFonts]) and is what everything was
 * drawn in before they came. Called from the main thread only; the answers are cached by [GlyphKeyPainter].
 */
internal class GlyphSystemFont : GlyphPixelFont {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faces = HashMap<Int, Typeface>()

    private fun face(weight: Int, mono: Boolean): Typeface = faces.getOrPut(weight * 2 + if (mono) 1 else 0) {
        when {
            mono -> Typeface.create(Typeface.MONOSPACE, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            Build.VERSION.SDK_INT >= 28 -> Typeface.create(Typeface.DEFAULT, weight, false)
            weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.DEFAULT
        }
    }

    override fun mask(text: String, size: Int, weight: Int, mono: Boolean): GlyphKeyArt.Mask {
        paint.typeface = face(weight, mono)
        paint.textSize = size.toFloat()
        val w = max(1, ceil(paint.measureText(text)).toInt() + 2)
        val h = size + 4
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        try {
            val canvas = Canvas(bitmap)
            paint.color = 0xFF000000.toInt()
            canvas.drawText(text, 1f, h / 2f - (paint.ascent() + paint.descent()) / 2f + 0.5f, paint)
            val pixels = ByteArray(w * h)
            bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(pixels))
            val on = BooleanArray(w * h) { (pixels[it].toInt() and 0xFF) > 100 }
            return GlyphKeyArt.Mask(w, h, on)
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * The skin's pixel type on the platform: the two bitmap faces shipped with the app (assets/fonts, subsets of Cubic 11 and Fusion Pixel,
 * both OFL 1.1) drawn at 12 px without smoothing or hinting, so every outline lies on the pixel grid, and the system font for what they
 * lack. Which face draws which character is [GlyphFontChain]'s; this draws the runs into a bitmap and cuts it to one bit. Main thread only.
 */
internal class GlyphPixelFonts(context: Context) : GlyphPixelFont {
    private class Loaded(val faces: List<Typeface>, val chain: GlyphFontChain)

    private val loaded: Loaded? = load(context.applicationContext)
    private val system = GlyphSystemFont()
    private val pixel = Paint().apply {
        isAntiAlias = false
        isSubpixelText = false
        isLinearText = false
        textSize = GlyphPixelType.EM.toFloat()
        hinting = Paint.HINTING_OFF
    }
    private val fallback = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = GlyphPixelType.EM.toFloat(); typeface = Typeface.DEFAULT }

    override fun mask(text: String, size: Int, weight: Int, mono: Boolean): GlyphKeyArt.Mask {
        val l = loaded ?: return system.mask(text, GlyphPixelType.EM, weight, mono)
        val runs = l.chain.plan(text)
        if (runs.isEmpty()) return GlyphKeyArt.Mask(1, GlyphPixelType.ROWS, BooleanArray(GlyphPixelType.ROWS))
        // Widths first: a pixel face's advances are whole pixels at 12 px.
        val widths = IntArray(runs.size)
        for ((i, run) in runs.withIndex()) {
            if (run.face == GlyphPixelType.COLOUR) { widths[i] = GlyphPixelType.COLOUR_W; continue }
            val paint = if (run.face >= 0) pixel.also { it.typeface = l.faces[run.face] } else fallback
            widths[i] = ceil(paint.measureText(run.text)).toInt()
        }
        val w = max(1, widths.sum() + 2)
        val h = GlyphPixelType.ROWS
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        try {
            val canvas = Canvas(bitmap)
            var x = 1
            var colour: IntArray? = null
            for ((i, run) in runs.withIndex()) {
                if (run.face == GlyphPixelType.COLOUR) {
                    // An emoji comes down to the grid of the type: set at the em's size, then every pixel kept or dropped (no smoothing),
                    // in its own colour; the picture is made of the same pixels as the world.
                    val pic = emoji(run.text)
                    val into = colour ?: IntArray(w * h).also { colour = it }
                    for (r in 0 until h) for (k in 0 until GlyphPixelType.COLOUR_W) {
                        val c = pic[r * GlyphPixelType.COLOUR_W + k]
                        if (c ushr 24 >= 110 && x + k < w) into[r * w + x + k] = c or 0xFF000000.toInt()
                    }
                } else if (run.face >= 0) {
                    pixel.typeface = l.faces[run.face]
                    pixel.color = 0xFF000000.toInt()
                    canvas.drawText(run.text, x.toFloat(), GlyphPixelType.baselineRow(run.face).toFloat(), pixel)
                } else {
                    fallback.color = 0xFF000000.toInt()
                    canvas.drawText(run.text, x.toFloat(), h / 2f - (fallback.ascent() + fallback.descent()) / 2f + 0.5f, fallback)
                }
                x += widths[i]
            }
            val pixels = ByteArray(w * h)
            bitmap.copyPixelsToBuffer(java.nio.ByteBuffer.wrap(pixels))
            val c = colour
            return GlyphKeyArt.Mask(w, h, BooleanArray(w * h) { (pixels[it].toInt() and 0xFF) > 100 || c != null && c[it] != 0 }, c)
        } finally {
            bitmap.recycle()
        }
    }

    private val emojiPaint = Paint().apply { isAntiAlias = false; isFilterBitmap = false; textSize = EMOJI_EM }

    /** An emoji at the size of the type, as [GlyphPixelType.COLOUR_W] by [GlyphPixelType.ROWS] pixels of colour (0 where there is none). */
    private fun emoji(text: String): IntArray {
        val w = GlyphPixelType.COLOUR_W
        val h = GlyphPixelType.ROWS
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val width = emojiPaint.measureText(text)
            canvas.drawText(text, (w - width) / 2f, h / 2f - (emojiPaint.ascent() + emojiPaint.descent()) / 2f, emojiPaint)
            val out = IntArray(w * h)
            bitmap.getPixels(out, 0, w, 0, 0, w, h)
            return out
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        /** An emoji is set at this size and cut to the grid: a little under the em, so it sits in the thirteen pixels it is given. */
        private const val EMOJI_EM = 11f
        private const val FACES = "fonts/pixel_a.ttf" // face A; B is pixel_b.otf

        @Volatile private var shared: Any? = null

        /** Reads the faces now (off the main thread, ahead of the first key), so the first legend does not wait for them. */
        fun preload(context: Context) { load(context.applicationContext) }

        private fun load(context: Context): Loaded? {
            (shared as? Loaded)?.let { return it }
            if (shared == FAILED) return null
            return try {
                val assets = context.assets
                val a = Typeface.createFromAsset(assets, FACES)
                val b = Typeface.createFromAsset(assets, "fonts/pixel_b.otf")
                fun coverage(name: String) = GlyphCoverage.parse(assets.open(name).use { it.readBytes().decodeToString() })
                Loaded(listOf(a, b), GlyphFontChain(listOf(coverage("fonts/pixel_a.cov"), coverage("fonts/pixel_b.cov")))).also { shared = it }
            } catch (failure: Exception) {
                android.util.Log.w("MeowpuutGlyph", "pixel fonts unavailable, using the system font", failure)
                shared = FAILED
                null
            }
        }

        private val FAILED = Any()
    }
}

/** A sink that draws nothing: the keyboard's first run of the picture's code, ahead of being shown. */
internal object GlyphNullSink : GlyphSink {
    override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) = Unit
    override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) = Unit
}

/**
 * The picture's [GlyphSink] on a [Canvas]. Coordinates are in points and [scale] is device pixels to a point; masks and cats
 * are drawn as whole device pixels so the grid of pixels stays square. With [gather] glyphs of one size and colour are collected
 * and drawn as one run at [layerEnd]: a scene has thousands of them, and one run is far cheaper than one call each.
 */
internal class GlyphCanvasSink(private val scale: Float, private val gather: Boolean) : GlyphSink {
    private var canvas: Canvas? = null
    /** Where y = 0 of the picture lies on the canvas, in points: the strip above the keyboard is above it. */
    private var oy = 0.0
    private val clear = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) }
    private val fill = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val dst = Rect()
    private val monoFace: Typeface = Typeface.MONOSPACE
    private val widths = HashMap<Long, Float>()

    private class Run(val size: Int, val mono: Boolean, val color: Int, val weight: Int) {
        var chars = CharArray(256)
        var pos = FloatArray(512)
        var n = 0
        fun add(ch: Char, x: Float, y: Float) {
            if (n == chars.size) { chars = chars.copyOf(n * 2); pos = pos.copyOf(n * 4) }
            chars[n] = ch; pos[2 * n] = x; pos[2 * n + 1] = y; n++
        }
    }
    private val runs = HashMap<Long, Run>()

    fun begin(canvas: Canvas, originY: Double = 0.0) {
        this.canvas = canvas
        oy = originY
    }

    override fun blit(picture: Any, dx: Int, y: Double) {
        val b = picture as? Bitmap ?: return
        canvas?.drawBitmap(b, dx.toFloat(), edge(y + oy), bitmapPaint)
    }

    override fun erase(x: Double, y: Double, w: Double, h: Double) {
        canvas?.drawRect(edge(x), edge(y + oy), edge(x + w), edge(y + h + oy), clear)
    }

    override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) {
        val c = canvas ?: return
        fill.color = color
        // Edges are whole device pixels, rounded the same way for both neighbours: no seam where two blocks meet.
        c.drawRect(edge(x), edge(y + oy), edge(x + w), edge(y + h + oy), fill)
    }

    private fun edge(v: Double): Float = Math.floor(v * scale + 0.5 + 1e-6).toFloat()

    private val faces = HashMap<Int, Typeface>()

    /** The face for a weight (400 regular, 500, 700 heavy): the system's own weights from Android 9, else regular and bold. */
    private fun faceFor(mono: Boolean, weight: Int): Typeface = faces.getOrPut(weight * 2 + if (mono) 1 else 0) {
        when {
            weight <= 400 -> if (mono) monoFace else Typeface.DEFAULT
            mono -> Typeface.create(Typeface.MONOSPACE, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            Build.VERSION.SDK_INT >= 28 -> Typeface.create(Typeface.DEFAULT, weight, false)
            weight >= 600 -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            else -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    }

    private fun measure(ch: Char, size: Int, mono: Boolean, weight: Int): Float = widths.getOrPut((ch.code.toLong() shl 24) or (size.toLong() shl 4) or ((weight / 100).toLong() shl 1) or if (mono) 1L else 0L) {
        textPaint.typeface = faceFor(mono, weight)
        textPaint.textSize = size * scale
        textPaint.measureText(ch.toString())
    }

    override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) = textW(ch, cx, cy, size, mono, color, 400)

    override fun textW(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int, weight: Int) {
        if (ch.isEmpty()) return
        val c = ch[0]
        val px = (cx * scale).toFloat() - measure(c, size, mono, weight) / 2f
        textPaint.typeface = faceFor(mono, weight)
        textPaint.textSize = size * scale
        val py = ((cy + oy) * scale).toFloat() - (textPaint.ascent() + textPaint.descent()) / 2f
        if (!gather) {
            textPaint.color = color
            canvas?.drawText(ch, px, py, textPaint)
            return
        }
        val key = (size.toLong() shl 44) or ((weight / 100).toLong() shl 36) or (if (mono) 1L shl 32 else 0L) or (color.toLong() and 0xFFFFFFFFL)
        runs.getOrPut(key) { Run(size, mono, color, weight) }.add(c, px, py)
    }

    @Suppress("DEPRECATION")
    override fun layerEnd() {
        val c = canvas ?: return
        for (run in runs.values) {
            if (run.n == 0) continue
            textPaint.typeface = faceFor(run.mono, run.weight)
            textPaint.textSize = run.size * scale
            textPaint.color = run.color
            c.drawPosText(run.chars, 0, run.n, run.pos.copyOf(run.n * 2), textPaint)
            run.n = 0
        }
    }

    // ---- pixels -----------------------------------------------------------------------------------------

    private fun pixelBitmap(argb: IntArray, w: Int, h: Int): Bitmap = Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888)

    /** One device pixel to a pixel of the picture: whole numbers, so a mask never has fat and thin pixels. */
    private fun pixelSize(unit: Double): Int = max(1, (unit * scale).roundToInt())

    /**
     * The picture under the type, and its sun or moon: type that lies over the disc is cut out of it, in the colour of the paper and with no
     * halo, as if the letters were taken out of the sun. Set by the view that draws the type; the offsets are where it stands in the keyboard.
     */
    class Eclipse(
        val bitmap: Bitmap, val perPoint: Float, val lift: Double, val disc: DiscInfo,
        /** The colours the disc is drawn in (one for the sun or moon, several for the Earth). */
        val colours: IntArray,
        /** What type over the disc is cut out in, and the edge it gets there instead of a halo (the sun and moon have none). */
        val cut: Int, val edge: Int?, val dx: Double, val dy: Double,
    )
    var eclipse: Eclipse? = null

    /**
     * By day, what is nearest in the picture (the corner's bank, what passes in front) lies over the keys' own type: where a letter falls
     * on it, the letter is cut out of it in the paper's colour, with no halo. [mask] is the near art alone (its alpha); the offsets are where the view stands.
     */
    class NearCut(val mask: Bitmap, val perPoint: Float, val lift: Double, val paper: Int, val dx: Double, val dy: Double)
    var nearCut: NearCut? = null

    /** Draws [mask] with the part of it that lies on the near art cut out in paper; false when no pixel of it does (the ordinary way then). */
    private fun cutNear(n: NearCut, mask: GlyphKeyArt.Mask, x: Double, y: Double, unit: Double, ink: Int, halo: Int?, round: Boolean): Boolean {
        val reach = if (halo != null) GlyphKeyArt.reach(round) else 0
        val left = x + n.dx - reach * unit
        val top = y + n.dy - reach * unit
        val base = if (halo != null) GlyphKeyArt.grown(mask, round) else mask
        val out = IntArray(base.w * base.h)
        var any = false
        for (r in 0 until base.h) for (k in 0 until base.w) {
            val onBase = base.on[r * base.w + k]
            val mr = r - reach
            val mk = k - reach
            val isInk = mr in 0 until mask.h && mk in 0 until mask.w && mask.on[mr * mask.w + mk]
            if (!onBase && !isInk) continue
            val bx = ((left + (k + 0.5) * unit) * n.perPoint).toInt()
            val by = ((top + (r + 0.5) * unit + n.lift) * n.perPoint).toInt()
            val over = bx in 0 until n.mask.width && by in 0 until n.mask.height && (n.mask.getPixel(bx, by) ushr 24) != 0
            if (over) any = true
            out[r * base.w + k] = when {
                isInk -> if (over) n.paper else mask.paint(mr * mask.w + mk, ink)
                over -> 0
                else -> halo ?: 0
            }
        }
        if (!any) return false
        val c = canvas ?: return true
        val bitmap = pixelBitmap(out, base.w, base.h)
        val u = pixelSize(unit)
        val l = (x * scale).roundToInt() - reach * u
        val t = ((y + oy) * scale).roundToInt() - reach * u
        dst.set(l, t, l + bitmap.width * u, t + bitmap.height * u)
        c.drawBitmap(bitmap, null, dst, bitmapPaint)
        bitmap.recycle()
        return true
    }

    /** Whether a pixel of [mask] stands within one pixel of the type (the edge on the Earth is that thin). */
    private fun near(mask: GlyphKeyArt.Mask, r: Int, k: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) if (mask[k + dx, r + dy]) return true
        return false
    }

    /** Draws [mask] cut out of the disc where it lies over it; false when no pixel of it does (the ordinary, cached way then). */
    private fun eclipsed(e: Eclipse, mask: GlyphKeyArt.Mask, x: Double, y: Double, unit: Double, ink: Int, halo: Int?, round: Boolean): Boolean {
        val reach = if (halo != null) GlyphKeyArt.reach(round) else 0
        val d = e.disc
        val left = x + e.dx - reach * unit
        val top = y + e.dy - reach * unit
        val right = left + (mask.w + 2 * reach) * unit
        val bottom = top + (mask.h + 2 * reach) * unit
        if (right < d.x - d.r || left > d.x + d.r || bottom < d.y - d.r || top > d.y + d.r) return false
        val base = if (halo != null) GlyphKeyArt.grown(mask, round) else mask
        val out = IntArray(base.w * base.h)
        var any = false
        for (r in 0 until base.h) for (k in 0 until base.w) {
            val onBase = base.on[r * base.w + k]
            val mr = r - reach
            val mk = k - reach
            val isInk = mr in 0 until mask.h && mk in 0 until mask.w && mask.on[mr * mask.w + mk]
            if (!onBase && !isInk) continue
            val px = left + (k + 0.5) * unit
            val py = top + (r + 0.5) * unit
            val inside = (px - d.x) * (px - d.x) + (py - d.y) * (py - d.y) <= d.r * d.r && e.bitmap.run {
                val bx = (px * e.perPoint).toInt()
                val by = ((py + e.lift) * e.perPoint).toInt()
                bx in 0 until width && by in 0 until height && getPixel(bx, by).let { px0 -> e.colours.any { it == px0 } }
            }
            if (inside) any = true
            out[r * base.w + k] = when {
                isInk -> if (inside) e.cut else mask.paint(mr * mask.w + mk, ink)
                inside -> if (e.edge != null && near(mask, mr, mk)) e.edge else 0   // no halo on the sun or moon; on the Earth one pixel of dark edge round the type
                else -> halo ?: 0
            }
        }
        if (!any) return false
        val c = canvas ?: return true
        val bitmap = pixelBitmap(out, base.w, base.h)
        val u = pixelSize(unit)
        val l = (x * scale).roundToInt() - reach * u
        val t = ((y + oy) * scale).roundToInt() - reach * u
        dst.set(l, t, l + bitmap.width * u, t + bitmap.height * u)
        c.drawBitmap(bitmap, null, dst, bitmapPaint)
        bitmap.recycle()
        return true
    }

    override fun bits(mask: GlyphKeyArt.Mask, x: Double, y: Double, unit: Double, ink: Int, halo: Int?, round: Boolean) {
        val c = canvas ?: return
        nearCut?.let { if (cutNear(it, mask, x, y, unit, ink, halo, round)) return }
        eclipse?.let { if (eclipsed(it, mask, x, y, unit, ink, halo, round)) return }
        val u = pixelSize(unit)
        val reach = if (halo != null) GlyphKeyArt.reach(round) else 0
        val cache = mask.extras
        val key = Triple(ink, halo, round)
        val bitmap = (cache[key] as? Bitmap)?.takeIf { !it.isRecycled } ?: run {
            val base = if (halo != null) GlyphKeyArt.grown(mask, round) else mask
            val out = IntArray(base.w * base.h)
            if (halo != null) for (i in out.indices) if (base.on[i]) out[i] = halo
            for (r in 0 until mask.h) for (k in 0 until mask.w) if (mask.on[r * mask.w + k]) out[(r + reach) * base.w + k + reach] = mask.paint(r * mask.w + k, ink)
            pixelBitmap(out, base.w, base.h).also { if (cache.size > 24) cache.clear(); cache[key] = it }
        }
        val left = (x * scale).roundToInt() - reach * u
        val top = ((y + oy) * scale).roundToInt() - reach * u
        dst.set(left, top, left + bitmap.width * u, top + bitmap.height * u)
        c.drawBitmap(bitmap, null, dst, bitmapPaint)
    }

    override fun raster(r: Raster, x: Double, y: Double, scale: Double) {
        val c = canvas ?: return
        val u = pixelSize(scale)
        val bitmap = rasters.getOrPut(r) { pixelBitmap(r.pixels, r.w, r.h) }
        val left = (x * this.scale).roundToInt()
        val top = ((y + oy) * this.scale).roundToInt()
        dst.set(left, top, left + r.w * u, top + r.h * u)
        c.drawBitmap(bitmap, null, dst, bitmapPaint)
    }

    private val rasters = object : LinkedHashMap<Raster, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Raster, Bitmap>?) = size > 48
    }

    fun release() {
        runs.clear()
        rasters.clear()
    }
}

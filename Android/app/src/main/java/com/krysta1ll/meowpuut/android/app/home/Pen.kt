package com.krysta1ll.meowpuut.android.app.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCanvasSink
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyPainter
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.mod
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n1
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.round
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelFonts
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The paint of the app pages in 字景 (the home page and 行旅): the second inks of the places, the landmarks the road passes, and the pen
 * the pictures are drawn with. Everything here is drawn the way the keyboard's own land is (a cell of four blocks is either mosaic or one
 * glyph), through the same sink, so a page and the keyboard are set in the same type. The one thing taken from the glyph engine is its
 * drawing layer: [GlyphCanvasSink], [GlyphPools], [GlyphKeyPainter], [GlyphPixelFonts], [GlyphColors], [GlyphCats] and [GlyphScenes.STAMPS].
 */
internal object Hues {
    /** The prototype's second inks (light, dark). A place uses one of them on one kind of thing, and little of it. */
    private val TABLE: Map<String, Pair<Int, Int>> = mapOf(
        "green" to (0xFF4E8B57.toInt() to 0xFF7CC08A.toInt()), "blue" to (0xFF2F6DA8.toInt() to 0xFF6FA9E6.toInt()),
        "pink" to (0xFFDE7C95.toInt() to 0xFFF29DB3.toInt()), "gold" to (0xFFD29A22.toInt() to 0xFFF3C64E.toInt()),
        "orange" to (0xFFDD7A2B.toInt() to 0xFFF39A4A.toInt()), "ice" to (0xFF6FA6CF.toInt() to 0xFFA6D1F0.toInt()),
        "cyan" to (0xFF1F97A3.toInt() to 0xFF55CAD6.toInt()), "magenta" to (0xFFC6427C.toInt() to 0xFFEE74AC.toInt()),
        "ochre" to (0xFFB98236.toInt() to 0xFFD9A65A.toInt()),
    )

    fun of(name: String, dark: Boolean): Int = TABLE.getValue(name).let { if (dark) it.second else it.first }
}

/**
 * What the road passes, and what a festival leaves by it, cut in the same pixels (X ink, R vermilion, o a light, paper by day and lit by night,
 * and G Y B P O C M the places' second inks). Stamps are of any size.
 */
internal object Stamps {
    fun rows(name: String): List<String> = GlyphScenes.STAMPS.getValue(name)

    /** The rows of a festival's stamp, by its name: the shipped picture, or the gate where the festival has none. */
    fun ofFestival(name: String?, source: com.krysta1ll.meowpuut.android.app.stats.FestivalStamps? = null): List<String> =
        name?.let { source?.stamp(it) } ?: rows("torii")

    /** The colour of one mark of a stamp; null for nothing. */
    fun colour(mark: Char, colors: GlyphColors, dark: Boolean): Int? = when (mark) {
        '.' -> null
        'R' -> colors.red
        'o' -> if (dark) colors.ink else colors.paper
        'G' -> Hues.of("green", dark)
        'Y' -> Hues.of("gold", dark)
        'B' -> Hues.of("blue", dark)
        'P' -> Hues.of("pink", dark)
        'O' -> Hues.of("orange", dark)
        'C' -> Hues.of("cyan", dark)
        'M' -> Hues.of("magenta", dark)
        else -> colors.ink
    }

    /** Draws a stamp, top-left on [x], [y], [u] dp to a pixel, through [rect] (a pen's, or a sink's). [halo] clears a paper edge round it. */
    fun draw(rows: List<String>, x: Double, y: Double, u: Double, halo: Boolean, colors: GlyphColors, dark: Boolean, rect: (Double, Double, Double, Double, Int) -> Unit) {
        if (halo) for ((r, row) in rows.withIndex()) for ((k, ch) in row.withIndex()) if (ch != '.') rect(x + (k - 1) * u, y + (r - 1) * u, 3 * u, 3 * u, colors.paper)
        for ((r, row) in rows.withIndex()) for ((k, ch) in row.withIndex()) colour(ch, colors, dark)?.let { rect(x + k * u, y + r * u, u, u, it) }
    }
}

/**
 * One cell of type, as the engine paints it (see GlyphScape.paintCell) but for a picture that is drawn once: a cell holds four blocks of
 * tone, -1 where there is nothing; the heavy blocks are mosaic and the rest is one glyph picked by the tone, heavier where it is darker.
 * [turn] is the world's epoch for a picture that is set in a moment: it decides which glyph a cell shows.
 */
internal class GlyphCells(private val pools: GlyphPools, private val level: Int = 2) {
    private val v = DoubleArray(4)

    fun tones(a: Double, b: Double, c: Double, d: Double): DoubleArray { v[0] = a; v[1] = b; v[2] = c; v[3] = d; return v }

    /** [tones] is the four blocks (row-major); [ink] overrides the ink for the whole cell (a second ink or vermilion). */
    fun cell(
        g: GlyphCanvasSink, c: GlyphColors, x: Double, y: Double, cell: Int, tones: DoubleArray, part: String, wx: Int, row: Int, seed: Int,
        ink: Int? = null, turn: Int = 0,
    ) {
        val sub = cell / 2.0
        var mean = 0.0
        var top = 0.0
        var any = false
        for (k in 0 until 4) if (tones[k] >= 0) { any = true; mean += tones[k]; if (tones[k] > top) top = tones[k] }
        if (!any) return
        // What is behind is covered, block by block.
        for (k in 0 until 4) if (tones[k] >= 0) g.rect(x + (k and 1) * sub, y + (k shr 1) * sub, sub, sub, c.paper)
        mean /= 4
        if (mean < 0.03) return
        val colour = ink ?: c.ink
        val pool = pools.script(part, wx, row, seed)
        val size = round(cell * GlyphPools.faceScale(pool))
        val mono = GlyphPools.isMono(pool)
        if (top >= 0.9) {
            for (k in 0 until 4) if (tones[k] >= 0.9) g.rect(x + (k and 1) * sub, y + (k shr 1) * sub, sub, sub, colour)
            if (mean > 0.6) return                                    // a cell that mostly is solid is only solid
        }
        val t = min(1.0, mean / 0.6)
        if (h2(wx, row * 13 + seed + 5) > doubleArrayOf(0.12, 0.22, 0.3, 0.44)[level] + 0.95 * t) return
        val line = pools.poem(pool)
        val said = line?.get(mod(wx + row * 17 + turn * 5, line.size))
        val rank = if (said != null) pools.rank(pool, said) else null
        val ch = if (said != null && rank != null && abs(rank - t) < 0.4) said else pools.glyph(pool, t, h2(wx + turn * 7919, row * 7 + seed + 3))
        val loose = if (pool == "pixel") 0.0 else 1.0                 // a pixel stays on its grid; only the grace notes are nudged
        g.text(ch, x + sub + loose * (h2(wx, row + seed + 91) - 0.5) * cell * 0.34, y + sub + 0.5 + loose * (h2(wx, row + seed + 57) - 0.5) * cell * 0.3, size, mono, colour)
    }
}

/** How a line of pixel type is set on a picture. [scale] is dp to a pixel of the type's 12 pixel grid; [halo] is a paper edge for type over the land. */
internal class Say(val scale: Double = 1.0, val ink: Int? = null, val halo: Boolean = false, val centre: Boolean = false)

/**
 * What a picture is drawn with: the sink, the colours, the type. One pen is used by one thread (the sink and the pixel faces keep state);
 * a picture asks the [Brush] for its own. Coordinates are in dp from the picture's top left; a picture drawn in tiles sets [ox] to where its
 * tile begins, and everything is drawn as if the whole picture were there.
 */
internal class Pen(
    val sink: GlyphCanvasSink, val colors: GlyphColors, val dark: Boolean, private val cells: GlyphCells, private val painter: GlyphKeyPainter,
    /** The system asks for more contrast: type over a picture is set on a solid plate of paper, so it is read against nothing. */
    val plain: Boolean = false,
    private val devScale: Float = 1f,
) {
    /** Where the tile being drawn begins (dp): the picture's x of the tile's left edge. */
    var ox = 0.0
    /** A shift of everything drawn (dp), for a picture made of side-by-side parts that are each drawn from their own left edge. */
    var tx = 0.0
    val paper get() = colors.paper
    val ink get() = colors.ink
    val red get() = colors.red
    fun hue(name: String) = Hues.of(name, dark)

    fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) = sink.rect(x + tx - ox, y, w, h, color)

    /** A pixel of type in dp, made a whole number of device pixels, so the grid of the type stays square (as the sink draws it). */
    fun snap(scale: Double): Double = max(1, Math.round(scale * devScale).toInt()) / devScale.toDouble()

    /** A line of pixel type whose middle is on [mid]. */
    fun say(text: String, x: Double, mid: Double, o: Say = Say()) {
        if (text.isEmpty()) return
        val ink = o.ink ?: colors.ink
        val u = snap(o.scale)
        if (plain) {                                                   // a plate under the words, so nothing of the picture is behind them
            val w = measure(text, o.scale)
            val x0 = if (o.centre) x - w / 2 else x
            rect(x0 - 3, mid - 7 * u - 2, w + 6, 14 * u + 4, colors.paper)
            painter.run(sink, text, x + tx - ox, mid, u, ink, null, o.centre)
            return
        }
        painter.run(sink, text, x + tx - ox, mid, u, ink, if (o.halo) colors.paper else null, o.centre)
    }

    /** The width of a line of pixel type in dp. */
    fun measure(text: String, scale: Double = 1.0): Double = painter.mask(text, 12, 400, false).w * snap(scale)

    /** A landmark or a festival's stamp, top-left on [x], [y], [u] dp to a pixel. [halo] clears a paper edge round it. */
    fun stamp(rows: List<String>, x: Double, y: Double, u: Double, halo: Boolean) = Stamps.draw(rows, x, y, u, halo, colors, dark) { rx, ry, w, h, c -> rect(rx, ry, w, h, c) }

    fun stamp(name: String, x: Double, y: Double, u: Double, halo: Boolean) = stamp(Stamps.rows(name), x, y, u, halo)

    /** A cat, [u] dp to a pixel of the cat, its top-left on [x], [y]. */
    fun cat(cat: CatId, pose: String, face: String, x: Double, y: Double, u: Double, night: Boolean = false) {
        val r = GlyphCats.raster(cat, pose, face, dark, night, colors)
        sink.raster(r, x + tx - ox - GlyphCats.MARGIN * u, y - GlyphCats.MARGIN * u, u)
    }

    /**
     * A range of hills from a list of tops (one per 3 dp, so a cell of 6 holds two), set in type. [tops] gives the y of the ridge; [cap] how deep
     * the pale cap goes at a point (snow); [tone] how dark the body is. Only the columns that fall in the tile being drawn are painted.
     */
    fun range(
        width: Double, base: Double, tops: FloatArray, part: String, seed: Int, tone: Double, cap: FloatArray? = null, ink: Int? = null,
        redAt: ((Int, Double) -> Boolean)? = null, tileWidth: Double = width,
    ) {
        val cell = 6
        val sub = 3.0
        val v = cells.tones(0.0, 0.0, 0.0, 0.0)
        val from = max(0, Math.floor(ox / cell).toInt())
        val to = min(Math.ceil(width / cell).toInt(), Math.ceil((ox + tileWidth) / cell).toInt())
        for (r in 0..Math.ceil(base / cell).toInt()) for (col in from until to) {
            var any = false
            var red = false
            for (k in 0 until 4) {
                val i = col * 2 + (k and 1)
                val py = (r * 2 + (k shr 1)) * sub + sub / 2
                val t = q(tops[min(tops.size - 1, i)].toDouble(), sub)
                if (py < t || py > base) { v[k] = -1.0; continue }
                val depth = py - t
                any = true
                v[k] = if (depth < sub) 1.0 else if (cap != null && depth < cap[min(cap.size - 1, i)]) 0.02
                else max(0.1, tone * (1 - depth / 120)) * (0.55 + 0.45 * GlyphNoise.n2(i * 0.33, py * 0.12, seed))
                if (redAt != null && redAt(i * 3, depth)) red = true
            }
            if (any) cells.cell(sink, colors, col * cell.toDouble() + tx - ox, r * cell.toDouble(), cell, v, part, col, r, seed, if (red) colors.red else ink)
        }
    }

    /** One cell of the picture, for those that lay their own (the photographs, the skies). */
    fun cell(x: Double, y: Double, size: Int, tones: DoubleArray, part: String, wx: Int, row: Int, seed: Int, ink: Int? = null) =
        cells.cell(sink, colors, x + tx - ox, y, size, tones, part, wx, row, seed, ink)

    fun tones() = cells.tones(0.0, 0.0, 0.0, 0.0)
    fun noise(x: Double, s: Int) = n1(x, s)
}

/**
 * Makes the pens of one page. [dark] and [plain] are the page's own; a [Pen] is made for each picture and each thread (the faces of the
 * pixel type are read once and shared, the masks are cut once per pen).
 */
internal class Brush(private val context: Context, val dark: Boolean, val plain: Boolean) {
    val colors: GlyphColors = GlyphColors.of(dark)
    val density: Float = context.resources.displayMetrics.density
    /** Device pixels to a dp in a picture: the screen's, but no finer than 3 (a tablet's picture stays small). */
    val scale: Float = min(density, 3f)

    /** A bitmap of [widthDp] by [heightDp], painted by [body] on a sheet of paper. A tile of a wider picture has its [Pen.ox] set by [body]. */
    fun picture(widthDp: Double, heightDp: Double, body: (Pen) -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(max(1, Math.ceil(widthDp * scale).toInt()), max(1, Math.ceil(heightDp * scale).toInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val sink = GlyphCanvasSink(scale, gather = true)
        sink.begin(canvas, 0.0)
        val pen = pen(sink)
        sink.rect(0.0, 0.0, widthDp, heightDp, colors.paper)
        body(pen)
        sink.layerEnd()
        sink.release()
        return bitmap
    }

    /** A pen on [sink]; the caller begins the sink on its canvas first. */
    fun pen(sink: GlyphCanvasSink): Pen = Pen(sink, colors, dark, GlyphCells(pools(context)), GlyphKeyPainter(GlyphPixelFonts(context)), plain, scale)

    companion object {
        @Volatile private var shared: GlyphPools? = null

        /** The pools of type the land is set in, made once (the platform says which glyphs its fonts can draw). */
        fun pools(context: Context): GlyphPools = shared ?: synchronized(Brush) {
            shared ?: GlyphPools(Paint()::hasGlyph).also { shared = it }
        }
    }
}

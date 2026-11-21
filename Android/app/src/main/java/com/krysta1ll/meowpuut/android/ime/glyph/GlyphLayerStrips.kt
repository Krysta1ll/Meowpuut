package com.krysta1ll.meowpuut.android.ime.glyph

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The land of 字景 drawn once and moved: each layer (and the sky) is a strip a few cells wider than the screen, drawn into its own bitmap;
 * where the layer has gone since is only a shift of that picture, and when it has gone whole cells the bitmap is shifted over and only the
 * cells that come into view are drawn. A whole new drawing is made when what the layer is made of changes, and at most every [TURN_MS] when only
 * the turn of the type has (so the type turns a little late, and in one go, instead of at every key).
 *
 * Each layer has its own weight (see [GlyphMotion]): the near land follows the typing in about a tenth of a second, the far distance in
 * most of one. The render thread owns all of this; [GlyphScape] asks for a layer's picture through [GlyphScape.layerBlit].
 */
internal class GlyphLayerStrips(private val scale: Float) {
    private class Strip(val layer: GlyphScape.Layer) {
        var bmp: Bitmap? = null
        var spare: Bitmap? = null
        var baked = Int.MIN_VALUE
        var band = 0.0 to 0.0
        var topPx = 0
        var cols = 0
    }

    private val strips = IdentityHashMap<GlyphScape.Layer, Strip>()
    private val sink = GlyphCanvasSink(scale, gather = true)
    private val paint = Paint().apply { isFilterBitmap = false }
    private var order: List<GlyphScape.Layer> = emptyList()
    private var sig = Long.MIN_VALUE
    private var turnAt = 0L
    private var turned = -1

    /** Drops what it holds (the keyboard went away, or depth went off). */
    fun release(scape: GlyphScape?) {
        strips.clear(); order = emptyList(); sig = Long.MIN_VALUE
        scape?.layerBlit = null
        scape?.unpin()
    }

    /**
     * Brings every strip to where its layer is now, ready for the picture to be made. [signature] is everything the layers are made of besides
     * the turn of the type; [dt] seconds have passed since the last time; [target] and [epoch] are the world's mileage and turn of the type.
     */
    fun sync(scape: GlyphScape, c: GlyphColors, lift: Double, signature: Long, epoch: Int, run: Double, nowMs: Long) {
        val layers = scape.strippableLayers()
        if (layers.isEmpty()) { release(scape); return }
        if (order.size != layers.size || order.indices.any { order[it] !== layers[it] }) { strips.clear(); order = layers }
        // Each layer stands a whole number of cells on, as the whole picture would put it: the strip is drawn at that and shifted by the cells it has gone.
        val fresh = signature != sig || epoch != turned
        for ((i, l) in layers.withIndex()) {
            val want = floor(run * l.spec.speed * 8 / l.cell).toInt()
            val s = strips.getOrPut(l) { Strip(l) }
            val band = scape.stripBand(l)
            val cols = ceil(scape.width / l.cell).toInt()
            val widthPx = ceil((cols + GlyphScape.STRIP_MARGIN_CELLS) * l.cell * scale.toDouble()).toInt()
            val heightPx = ceil((band.second - band.first) * scale.toDouble()).toInt() + 2
            val b = s.bmp
            val full = fresh || b == null || b.width != widthPx || b.height != heightPx || s.band != band || s.baked == Int.MIN_VALUE || want < s.baked || want - s.baked >= GlyphScape.STRIP_MARGIN_CELLS || (want != s.baked && scape.hasLamps(l))
            val topPx = Math.floor((band.first + lift) * scale + 0.5 + 1e-6).toInt()
            if (full) {
                val nb = (s.spare?.takeIf { it.width == widthPx && it.height == heightPx }) ?: Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(true) }
                nb.eraseColor(0)
                draw(scape, c, l, nb, topPx, lift, want, 0, cols + GlyphScape.STRIP_MARGIN_CELLS, band)
                s.spare = s.bmp; s.bmp = nb
                s.cols = cols; s.band = band; s.topPx = topPx; s.baked = want
            } else if (want > s.baked) {
                val dc = want - s.baked
                val nb = (s.spare?.takeIf { it.width == widthPx && it.height == heightPx }) ?: Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { it.setHasAlpha(true) }
                nb.eraseColor(0)
                val shift = floor(dc * l.cell * scale + 0.5).toInt()
                Canvas(nb).drawBitmap(b!!, -shift.toFloat(), 0f, paint)
                draw(scape, c, l, nb, topPx, lift, want, cols + GlyphScape.STRIP_MARGIN_CELLS - dc, cols + GlyphScape.STRIP_MARGIN_CELLS, band)
                s.spare = s.bmp; s.bmp = nb
                s.baked = want
            }
        }
        if (fresh) { sig = signature; turned = epoch; turnAt = nowMs }
        scape.layerBlit = { l, g -> blit(l, g) }
    }

    private fun draw(scape: GlyphScape, c: GlyphColors, l: GlyphScape.Layer, b: Bitmap, topPx: Int, lift: Double, off: Int, colFrom: Int, colTo: Int, band: Pair<Double, Double>) {
        sink.begin(Canvas(b), lift - topPx / scale.toDouble())
        scape.renderStrip(l, sink, c, off, colFrom, colTo, band)
        sink.layerEnd()
    }

    private fun blit(l: GlyphScape.Layer, g: GlyphSink): Boolean {
        val s = strips[l] ?: return false
        val b = s.bmp ?: return false
        g.blit(b, 0, s.band.first)
        return true
    }

}

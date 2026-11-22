package com.krysta1ll.meowpuut.android.ime.glyph

import kotlin.math.floor
import kotlin.math.max

/** A cat as it stands in the keyboard: its raster, where the raster's top left corner is (points), and the size of one of its pixels. */
internal class PlacedCat(val raster: Raster, val left: Double, val top: Double, val unit: Double) {
    /** The cat's own pixel at a point of the keyboard, or 0 where the cat is not ([paper] is the clear edge round it, not the cat). */
    fun at(x: Double, y: Double, paper: Int): Int {
        val k = floor((x - left) / unit).toInt()
        val r = floor((y - top) / unit).toInt()
        if (k !in 0 until raster.w || r !in 0 until raster.h) return 0
        val pixel = raster.pixels[r * raster.w + k]
        return if (pixel ushr 24 == 0 || pixel == paper) 0 else pixel
    }
}

/**
 * The keys' type lies over the two cats, and with its halo it used to take a clear patch out of whichever cat stood behind it (the e
 * and the r hid most of 莫莫's face). Where a letter falls on a cat its halo now thins to a hairline about a point wide: the letter
 * is still read, in its own ink with a fine edge of paper, and the cat is whole round it. Off the cat the letter and its halo are as
 * everywhere else.
 */
internal object GlyphCatCut {
    /**
     * The pixels to draw (0 is nothing): [w] by [h] cells, [fine] of them to a pixel of the mask each way, and the mask's own pixels
     * begin [reach] of its pixels in from every side.
     */
    class Cut(val w: Int, val h: Int, val fine: Int, val reach: Int, val pixels: IntArray)

    /**
     * [mask] at ([x], [y]) in the keyboard's points with pixels of [unit], in [ink] with a [halo] grown [round] or square, with the
     * halo thinned where it lies on one of [cats]. Null when there is no halo or no part of it touches a cat: the type is drawn the
     * ordinary way then.
     */
    fun compose(cats: List<PlacedCat>, paper: Int, mask: GlyphKeyArt.Mask, x: Double, y: Double, unit: Double, ink: Int, halo: Int?, round: Boolean): Cut? {
        if (cats.isEmpty() || halo == null) return null
        val reach = GlyphKeyArt.reach(round)
        val left = x - reach * unit
        val top = y - reach * unit
        val bw = mask.w + 2 * reach
        val bh = mask.h + 2 * reach
        // Most keys are nowhere near a cat: the boxes decide that before any pixel is read.
        if (cats.none { left < it.left + it.raster.w * it.unit && left + bw * unit > it.left && top < it.top + it.raster.h * it.unit && top + bh * unit > it.top }) return null
        val base = GlyphKeyArt.grown(mask, round)
        // Cells of about a point: the hairline is one cell wide whatever the size of the mask's pixels.
        val fine = max(1, Math.round(unit).toInt())
        val cell = unit / fine
        val w = bw * fine
        val h = bh * fine
        fun inked(k: Int, r: Int): Boolean = k >= 0 && r >= 0 && mask[k / fine - reach, r / fine - reach]
        val out = IntArray(w * h)
        var any = false
        for (r in 0 until h) for (k in 0 until w) {
            val isInk = inked(k, r)
            if (!isInk && !base.on[(r / fine) * bw + k / fine]) continue
            if (isInk) { out[r * w + k] = mask.paint((r / fine - reach) * mask.w + k / fine - reach, ink); continue }
            var hair = false
            for (b in -1..1) for (a in -1..1) if (inked(k + a, r + b)) hair = true
            if (hair) { out[r * w + k] = halo; continue }
            val px = left + (k + 0.5) * cell
            val py = top + (r + 0.5) * cell
            val onCat = cats.any { it.at(px, py, paper) != 0 }
            if (onCat) any = true else out[r * w + k] = halo
        }
        return if (any) Cut(w, h, fine, reach, out) else null
    }
}

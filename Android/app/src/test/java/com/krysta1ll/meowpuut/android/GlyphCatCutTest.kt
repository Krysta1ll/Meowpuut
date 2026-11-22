package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.CatDraw
import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCatCut
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.glyph.PlacedCat
import com.krysta1ll.meowpuut.android.ime.glyph.Raster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The halo of the keys' type thins to a hairline over the cats (the e and the r used to hide 莫莫 behind theirs). */
class GlyphCatCutTest {
    private val c = GlyphColors.LIGHT
    private val dark = 0xFF2A2420.toInt()
    private val cream = 0xFFE8D8B8.toInt()

    /** A cat of 6 by 4 pixels of 2 points at (100, 50): a column of paper edge, two dark columns, three cream ones; its last row is empty. */
    private fun cat(): PlacedCat {
        val w = 6
        val h = 4
        val pixels = IntArray(w * h)
        for (r in 0 until 3) for (k in 0 until w) pixels[r * w + k] = when (k) { 0 -> c.paper; 1, 2 -> dark; else -> cream }
        return PlacedCat(Raster(w, h, pixels), 100.0, 50.0, 2.0)
    }

    /** A solid block of [w] by [h] pixels. */
    private fun block(w: Int, h: Int) = GlyphKeyArt.Mask(w, h, BooleanArray(w * h) { true })

    @Test
    fun aCatIsItsOwnPixelsNotItsEdgeOrTheAirRoundIt() {
        val cat = cat()
        assertEquals(dark, cat.at(102.5, 50.5, c.paper))
        assertEquals(cream, cat.at(111.9, 55.9, c.paper))
        assertEquals("the clear edge round a cat is not the cat", 0, cat.at(100.5, 50.5, c.paper))
        assertEquals("nor is an empty pixel of its raster", 0, cat.at(104.0, 57.0, c.paper))
        for ((x, y) in listOf(99.9 to 52.0, 112.0 to 52.0, 104.0 to 49.9, 104.0 to 58.0)) assertEquals("($x, $y) is outside", 0, cat.at(x, y, c.paper))
    }

    @Test
    fun typeAwayFromEveryCatIsDrawnTheOrdinaryWay() {
        val mask = block(2, 2)
        assertNull(GlyphCatCut.compose(emptyList(), c.paper, mask, 100.0, 50.0, 2.0, c.ink, c.paper, false))
        assertNull("far to the left", GlyphCatCut.compose(listOf(cat()), c.paper, mask, 20.0, 50.0, 2.0, c.ink, c.paper, false))
        assertNull("far below", GlyphCatCut.compose(listOf(cat()), c.paper, mask, 104.0, 90.0, 2.0, c.ink, c.paper, false))
        // Inside the raster's box but only on its edge column and its empty row: still the ordinary way.
        assertNull(GlyphCatCut.compose(listOf(cat()), c.paper, block(1, 1), 98.0, 58.0, 2.0, c.ink, c.paper, false))
        // Type without a halo takes nothing out of a cat: nothing to thin.
        assertNull(GlyphCatCut.compose(listOf(cat()), c.paper, block(4, 1), 98.0, 52.0, 2.0, c.ink, null, false))
    }

    @Test
    fun onACatTheHaloThinsToAHairlineAndTheLetterKeepsItsInk() {
        // A 4 by 1 letter of 2-point pixels from x = 98 to 106 on the cat's second row; its halo reaches from 96 to 108 and 50 to 56.
        val cut = GlyphCatCut.compose(listOf(cat()), c.paper, block(4, 1), 98.0, 52.0, 2.0, c.ink, c.paper, false)
        assertNotNull(cut); cut!!
        assertEquals("cells of a point: two to a pixel of the mask", 2, cut.fine)
        assertEquals(1, cut.reach); assertEquals(12, cut.w); assertEquals(6, cut.h)
        fun row(r: Int) = (0 until 12).map { cut.pixels[r * 12 + it] }
        val p = c.paper
        val i = c.ink
        // The halo's outer rows: kept left of the cat (and on its clear edge), gone where the cat is (x from 102).
        assertEquals(listOf(p, p, p, p, p, p, 0, 0, 0, 0, 0, 0), row(0))
        assertEquals(row(0), row(5))
        // The row next to the letter is the hairline, on the cat and off it; past it the halo is gone on the cat.
        assertEquals(listOf(p, p, p, p, p, p, p, p, p, p, p, 0), row(1))
        assertEquals(row(1), row(4))
        // The letter's rows: its ink everywhere, a cell of hairline at each end.
        assertEquals(listOf(p, p, i, i, i, i, i, i, i, i, p, 0), row(2))
        assertEquals(row(2), row(3))
    }

    @Test
    fun aRoundHaloThinsTheSameWayAndTheSecondCatCounts() {
        val far = PlacedCat(cat().raster, 300.0, 50.0, 2.0)
        val cut = GlyphCatCut.compose(listOf(far, cat()), c.paper, block(1, 1), 104.0, 52.0, 2.0, c.ink, c.paper, true)!!
        assertEquals(GlyphKeyArt.HALO_REACH, cut.reach)
        assertEquals(14, cut.w)
        fun at(r: Int, k: Int) = cut.pixels[r * cut.w + k]
        assertEquals("the letter", c.ink, at(6, 6))
        assertEquals("the hairline beside it, on the dark coat", c.paper, at(6, 5))
        assertEquals("past the hairline the halo is gone on the coat", 0, at(6, 4))
        assertEquals("and on the cream", 0, at(6, 9))
        assertEquals("left of the cat it stays", c.paper, at(6, 1))
    }

    @Test
    fun aMaskOfOnePointPixelsHasAHairlineOfOnePixel() {
        val cut = GlyphCatCut.compose(listOf(cat()), c.paper, block(2, 2), 104.0, 52.0, 1.0, c.ink, c.paper, true)!!
        assertEquals(1, cut.fine)
        assertEquals(2 + 2 * GlyphKeyArt.HALO_REACH, cut.w)
        fun at(r: Int, k: Int) = cut.pixels[r * cut.w + k]
        assertEquals(c.ink, at(3, 3))
        assertEquals("one pixel of hairline", c.paper, at(3, 2))
        assertEquals("then the cat", 0, at(3, 1))
    }

    @Test
    fun aCatStandsWhereItsFeetAreLessTheLiftOfAStep() {
        val raster = GlyphCats.raster(CatId.MOMO, "sit", "neutral", false, false, c)
        val d = CatDraw(CatId.MOMO, "sit", "neutral", 104, 80, 0, night = false, platform = false, afterImageDx = null, zzz = false, zPhase = 0)
        val placed = GlyphCats.place(d, raster, 0, 2.0)
        assertEquals(104.0 - GlyphCats.MARGIN * 2.0, placed.left, 0.0)
        assertEquals("the raster's last row but the margin is at the feet", 80.0, placed.top + (raster.h - GlyphCats.MARGIN) * 2.0, 0.0)
        assertEquals("a lifted foot takes the whole cat up", placed.top - 2.0, GlyphCats.place(d, raster, -2, 2.0).top, 0.0)
    }

    @Test
    fun theEAndTheRNoLongerHideMomo() {
        // 莫莫 on the ridge under the first row, as on a 393-point keyboard: the e and the r stand over her.
        val raster = GlyphCats.raster(CatId.MOMO, "sit", "neutral", false, false, c)
        val d = CatDraw(CatId.MOMO, "sit", "neutral", 104, 64, 0, night = false, platform = false, afterImageDx = null, zzz = false, zPhase = 0)
        val cats = listOf(GlyphCats.place(d, raster, 0, 2.0))
        var before = 0                                                          // square points of her under a halo, as it was
        var after = 0                                                           // and under the hairlines now
        for ((label, cx) in listOf("e" to 98.0, "r" to 137.0)) {
            val mask = GlyphKeyArt.mask(GlyphKeyArt.bits(label)!!)
            val x = Math.round(cx - mask.w * 3.0 / 2).toDouble()
            val y = Math.round(36.0 - mask.h * 3.0 / 2).toDouble()
            val grown = GlyphKeyArt.grown(mask, false)
            val cut = GlyphCatCut.compose(cats, c.paper, mask, x, y, 3.0, c.ink, c.paper, false)
            for (r in 0 until grown.h * 3) for (k in 0 until grown.w * 3) {
                val onHer = cats[0].at(x - 3.0 + k + 0.5, y - 3.0 + r + 0.5, c.paper) != 0
                val inked = mask[k / 3 - 1, r / 3 - 1]
                if (onHer && !inked && grown.on[(r / 3) * grown.w + k / 3]) before++
                if (cut != null) {
                    assertEquals(3, cut.fine)
                    val drawn = cut.pixels[r * cut.w + k]
                    if (inked) assertEquals("the letter keeps its ink", c.ink, drawn)
                    else if (onHer && drawn != 0) after++
                }
            }
        }
        assertTrue("the halos used to cover part of her ($before square points)", before > 100)
        assertTrue("the hairlines cover well under half of that ($after of $before)", after * 2 < before)
    }

    /**
     * A development tool, not a test: with GLYPH_CATCUT_DIR set it draws 莫莫 and 米米莫 under the q w e r t y row, light and dark, the
     * old way (left) and cut (right), so the two can be compared before anything runs on a phone.
     */
    @Test
    fun drawsBeforeAndAfter() {
        val dir = System.getenv("GLYPH_CATCUT_DIR")
        assumeTrue(dir != null)
        val scale = 4
        for (night in listOf(false, true)) {
            val col = GlyphColors.of(night)
            val image = BufferedImage(520 * scale, 96 * scale, BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.color = Color(col.paper, true); g.fillRect(0, 0, image.width, image.height)
            fun dot(x: Double, y: Double, unit: Double, argb: Int) {
                if (argb ushr 24 == 0) return
                g.color = Color(argb, true)
                g.fillRect(Math.round(x * scale).toInt(), Math.round(y * scale).toInt(), Math.round(unit * scale).toInt(), Math.round(unit * scale).toInt())
            }
            for ((side, cutting) in listOf(0.0 to false, 260.0 to true)) {
                // A little land behind, so that a halo shows as what it is.
                for (i in 0 until 400) dot(side + 6 + (i * 37 % 240), 8.0 + (i * 53 % 78), 1.0, GlyphColors.withAlpha(col.ink, 0.35))
                val cats = listOf(CatId.MOMO to (if (cutting) 94 else 104), CatId.MIMI to 172).map { (id, x) ->
                    val raster = GlyphCats.raster(id, "sit", "neutral", night, false, col)
                    GlyphCats.place(CatDraw(id, "sit", "neutral", x, 74, 0, night = false, platform = false, afterImageDx = null, zzz = false, zPhase = 0), raster, 0, 2.0)
                }
                for (cat in cats) for (r in 0 until cat.raster.h) for (k in 0 until cat.raster.w)
                    dot(side + cat.left + k * cat.unit, cat.top + r * cat.unit, cat.unit, cat.raster.pixels[r * cat.raster.w + k])
                for ((i, label) in "qwerty".map { it.toString() }.withIndex()) {
                    val mask = GlyphKeyArt.mask(GlyphKeyArt.bits(label)!!)
                    val x = Math.round(19.6 + i * 39.3 - mask.w * 3.0 / 2).toDouble()
                    val y = Math.round(44.0 - mask.h * 3.0 / 2).toDouble()
                    val cut = if (cutting) GlyphCatCut.compose(cats, col.paper, mask, x, y, 3.0, col.ink, col.paper, false) else null
                    if (cut != null) {
                        val cell = 3.0 / cut.fine
                        for (r in 0 until cut.h) for (k in 0 until cut.w) dot(side + x - cut.reach * 3.0 + k * cell, y - cut.reach * 3.0 + r * cell, cell, cut.pixels[r * cut.w + k])
                    } else {
                        val grown = GlyphKeyArt.grown(mask, false)
                        for (r in 0 until grown.h) for (k in 0 until grown.w) if (grown.on[r * grown.w + k]) dot(side + x + (k - 1) * 3.0, y + (r - 1) * 3.0, 3.0, col.paper)
                        for (r in 0 until mask.h) for (k in 0 until mask.w) if (mask[k, r]) dot(side + x + k * 3.0, y + r * 3.0, 3.0, col.ink)
                    }
                }
            }
            g.dispose()
            ImageIO.write(image, "png", File(File(dir!!).apply { mkdirs() }, if (night) "cat-cut-dark.png" else "cat-cut-light.png"))
        }
    }
}

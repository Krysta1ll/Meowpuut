package com.krysta1ll.meowpuut.android.ime.glyph

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q

/**
 * What stands in front of the keys: the two cats, the words that rise off the bar, the guest crab, the fish, the name of the place.
 * It draws a [GlyphFrontFrame] the render thread has already worked out (where each cat is, in what pose and face) and nothing
 * else, and it never takes a touch.
 */
@SuppressLint("ViewConstructor")
internal class GlyphFrontView(context: Context, private val controller: GlyphController) : View(context) {
    private val scale = context.resources.displayMetrics.density.coerceAtMost(3f)
    private val sink = GlyphCanvasSink(scale, gather = false)
    private val painter = GlyphKeyPainter(GlyphPixelFonts(context))
    private val rasters = HashMap<String, Raster>()
    private val fishMask = GlyphKeyArt.mask(GlyphCats.FISH)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    private fun cat(d: CatDraw, colors: GlyphColors, dark: Boolean): Raster {
        val key = "${d.cat}/${d.pose}/${d.face}/$dark/${d.night}/${d.suit}"
        if (rasters.size > 120) rasters.clear()
        return rasters.getOrPut(key) { GlyphCats.raster(d.cat, d.pose, d.face, dark, d.night, colors, d.suit) }
    }

    override fun onDraw(canvas: Canvas) {
        val f = controller.frame ?: return
        val ff = f.front
        val c = GlyphColors.of(f.dark)
        val u = f.layout.catUnit
        val k = u / GlyphCats.POINTS                                   // the cats' own sizes (a platform, the sleeper's z's) go with their pixel
        sink.begin(canvas, f.layout.lift)
        if (ff.crabFrame >= 0) {
            val top = ff.crabFeetY - 8 * 2.0
            sink.raster(GlyphCats.crabRaster(ff.crabFrame, c.paper), ff.crabX - 2.0, top - 2.0, u)
        }
        ff.fish?.let { (fx, fy) -> sink.bits(fishMask, q(fx.toDouble(), 3.0), q(fy.toDouble(), 3.0), 3.0, c.ink, null, false) }
        for (d in ff.cats) {
            val r = cat(d, c, f.dark)
            val top = d.feetY - (r.h - 2 * GlyphCats.MARGIN) * u + d.hopY       // each cat has its own height (Mimi is taller, and a suit adds a row)
            if (d.platform) {                                          // something to stand on
                sink.rect(d.x + 2.0 * k, d.feetY - 1.0, 36.0 * k, 4.0, c.paper)
                sink.rect(d.x + 4.0 * k, d.feetY.toDouble(), 32.0 * k, 2.0, c.ink)
            }
            d.afterImageDx?.let { dx ->                                // a glitch leaves an after-image
                val save = canvas.saveLayerAlpha(null, 102)
                sink.raster(r, d.x + dx - GlyphCats.MARGIN * u, top - GlyphCats.MARGIN * u, u)
                canvas.restoreToCount(save)
            }
            sink.raster(r, d.x - GlyphCats.MARGIN * u, top - GlyphCats.MARGIN * u, u)
            if (d.zzz) for (i in 0 until 3) {
                painter.label(sink, c, "ㄗ", d.x + 36.0 * k + i * 11 + ((d.zPhase + i) % 2), d.feetY - 24.0 * k - i * 12, 1.0, 1.0, centre = true, halo = true)
            }
        }
        for (d in ff.dust) sink.text(d.ch, q(d.x, 2.0), q(d.y, 2.0), d.size, false, GlyphColors.withAlpha(c.ink, d.alpha.toDouble()))
        for (m in ff.motes) {
            painter.label(sink, c, m.ch, q(m.x, 4.0), q(m.y, 4.0), 1.0, m.alpha.toDouble(), centre = true, halo = true)
        }
        if (ff.bannerAlpha > 0f && ff.bannerText.isNotEmpty()) {
            painter.label(sink, c, "— ${ff.bannerText} —", f.layout.width / 2, f.layout.barMid, 2.0, ff.bannerAlpha.toDouble(), centre = true, halo = true)
        }
    }
}

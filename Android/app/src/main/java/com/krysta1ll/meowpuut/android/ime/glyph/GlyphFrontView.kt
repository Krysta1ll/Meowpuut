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

    private fun cat(d: CatDraw, pose: String, colors: GlyphColors, dark: Boolean): Raster {
        val key = "${d.cat}/$pose/${d.face}/$dark/${d.night}/${d.suit}"
        if (rasters.size > 120) rasters.clear()
        return rasters.getOrPut(key) { GlyphCats.raster(d.cat, pose, d.face, dark, d.night, colors, d.suit) }
    }

    override fun onDraw(canvas: Canvas) = draw0(canvas)

    private fun draw0(canvas: Canvas) {
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
        var sig = 7L
        for (d in ff.cats) {
            val walk = if (f.near != null) controller.walkOf(d.cat) else null     // what the keys have just made of the cat, before any new picture
            val pose = walk?.first ?: d.pose
            val hopY = d.hopY - (walk?.second ?: 0)
            sig = sig * 31 + pose.hashCode() + hopY * 1009L
            val r = cat(d, pose, c, f.dark)
            val top = d.feetY - (r.h - 2 * GlyphCats.MARGIN) * u + hopY       // each cat has its own height (Mimi is taller, and a suit adds a row)
            if (f.near != null && !d.platform) {                       // with depth the cat stands on its own shadow: a few fine dots under its feet
                val bx = d.x
                val tone = if (f.dark) c.paper else c.ink                    // (at night the shadow is the dark on the faintly lit ground)
                for (b in 0 until 3) for (a in 0 until 40) {
                    val e = 1 - ((a - 20) / 20.0).let { it * it } - b * 0.22
                    if (GlyphScape.BAYER[(b and 3) * 4 + ((a + bx) and 3)] < e * 0.85) sink.rect((bx + a).toDouble(), (d.feetY + b).toDouble(), 1.0, 1.0, tone)
                }
                // and a wedge of two steps of a screen away from the light, longer when the light is low
                val len = Math.round(14 + 30 * f.near.lowLight).toInt()
                for (b in 0 until 2) {
                    var a = 0
                    while (a < len - b * 8) {
                        val x = if (f.near.sunSide > 0) bx + 6 - a else bx + 34 + a
                        if (GlyphScape.BAYER[(b and 3) * 4 + ((x shr 1) and 3)] < (if (a < len / 2.0) 0.5 else 0.25)) sink.rect(x.toDouble(), (d.feetY + b * 2).toDouble(), 2.0, 2.0, tone)
                        a += 2
                    }
                }
            }
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
            if (d.zzz && walk == null) for (i in 0 until 3) {
                painter.label(sink, c, "ㄗ", d.x + 36.0 * k + i * 11 + ((d.zPhase + i) % 2), d.feetY - 24.0 * k - i * 12, 1.0, 1.0, centre = true, halo = true)
            }
        }
        f.near?.let { n ->                                              // what passes in front of the land is in front of the cats too, where they stand
            for (d in ff.cats) {
                canvas.save()
                canvas.clipRect(((d.x - 4) * scale), ((d.feetY - 44 + f.layout.lift) * scale).toFloat(), ((d.x + 44) * scale), ((d.feetY + 4 + f.layout.lift) * scale).toFloat())
                controller.drawForeLive(sink, c, n)
                canvas.restore()
            }
        }
        for (d in ff.dust) sink.text(d.ch, q(d.x, 2.0), q(d.y, 2.0), d.size, false, GlyphColors.withAlpha(c.ink, d.alpha.toDouble()))
        controller.probeDrawn(sig)
        for (m in ff.motes) {
            painter.label(sink, c, m.ch, q(m.x, 4.0), q(m.y, 4.0), 1.0, m.alpha.toDouble(), centre = true, halo = true)
        }
        if (ff.bannerAlpha > 0f && ff.bannerText.isNotEmpty()) {
            painter.label(sink, c, "— ${ff.bannerText} —", f.layout.width / 2, f.layout.barMid, 2.0, ff.bannerAlpha.toDouble(), centre = true, halo = true)
        }
    }
}

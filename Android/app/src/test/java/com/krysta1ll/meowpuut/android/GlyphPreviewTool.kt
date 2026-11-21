package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScape
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSink
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphTheme
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphWorld
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A development tool, not a test: with GLYPH_PREVIEW_DIR set it draws the six places, light and dark, with the shared pure code and
 * the desktop's fonts, so they can be set beside the browser prototype before anything runs on a phone. Without the variable it does nothing.
 */
class GlyphPreviewTool {
    @Test
    fun drawsTheSixPlaces() {
        val dir = System.getenv("GLYPH_PREVIEW_DIR")
        assumeTrue(dir != null)
        val out = File(dir!!).apply { mkdirs() }
        val level = System.getenv("GLYPH_LEVEL")?.toIntOrNull() ?: 2
        GlyphScape.CULL_OFF = System.getenv("GLYPH_NOCULL") != null
        val scale = 2
        val serif = Font("PingFang TC", Font.PLAIN, 12)
        val mono = Font(Font.MONOSPACED, Font.PLAIN, 12)
        val pools = GlyphPools { s -> serif.canDisplay(s[0]) }
        for (dark in listOf(false, true)) for (scene in GlyphScenes.ALL.indices) {
            val theme = GlyphTheme(dark = dark, level = level).also { it.deep = System.getenv("GLYPH_DEEP") != null }
            val world = GlyphWorld(theme)
            val layout = GlyphLayout.NONE
            world.target = 40 + scene * 17; world.pos = world.target.toDouble(); world.epoch = 3; world.tick = 100; world.lastMove = 100
            val scape = GlyphScape(world, pools)
            System.getenv("GLYPH_FORE")?.toDoubleOrNull()?.let { scape.foreFix = it }
            scape.wear(scene, layout)
            scape.hush(hasCandidates = false, enabled = true)
            val colors = GlyphColors.of(dark)
            val image = BufferedImage((layout.width * scale).toInt(), (layout.height * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.scale(scale.toDouble(), scale.toDouble())
            val sink = object : GlyphSink {
                override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) {
                    g.color = Color(color, true); g.fill(java.awt.geom.Rectangle2D.Double(x, y, w, h))
                }
                override fun textW(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int, weight: Int) = draw(ch, cx, cy, size, mono, color, weight)
                override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) = draw(ch, cx, cy, size, mono, color, 400)
                private fun draw(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int, weight: Int) {
                    val style = if (weight >= 600) Font.BOLD else Font.PLAIN
                    val f = (if (mono) Font(Font.MONOSPACED, style, size) else Font("PingFang TC", style, size))
                    g.font = f; g.color = Color(color, true)
                    val m = g.fontMetrics
                    g.drawString(ch, (cx - m.stringWidth(ch) / 2.0).toFloat(), (cy + (m.ascent - m.descent) / 2.0).toFloat())
                }
            }
            val started = System.nanoTime()
            scape.draw(sink, colors)
            val ms = (System.nanoTime() - started) / 1e6
            // The two cats, on the ridge.
            for (cat in listOf(CatId.MOMO, CatId.MIMI)) {
                val r = GlyphCats.raster(cat, "sit", "neutral", dark, false, colors)
                val x = if (cat == CatId.MOMO) 104 else 172
                val feetY = (scape.ridgeAt(x + 20.0) + 2).toInt()
                for (py in 0 until r.h) for (px in 0 until r.w) {
                    val c = r.pixels[py * r.w + px]
                    if (c ushr 24 == 0) continue
                    g.color = Color(c, true)
                    g.fillRect(x + (px - GlyphCats.MARGIN) * 2, feetY - 15 * 2 + (py - GlyphCats.MARGIN) * 2, 2, 2)
                }
            }
            g.dispose()
            val name = "scene${scene}${if (dark) "-dark" else ""}.png"
            ImageIO.write(image, "png", File(out, name))
            println("$name: ${"%.1f".format(ms)} ms")
        }
    }
}

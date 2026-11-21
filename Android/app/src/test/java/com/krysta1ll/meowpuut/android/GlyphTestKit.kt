package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelFont
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSink

/** What a test draws into: every call, in order. */
internal class RecordingSink : GlyphSink {
    sealed interface Op
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double, val color: Int) : Op
    data class Text(val ch: String, val cx: Double, val cy: Double, val size: Int, val mono: Boolean, val color: Int) : Op
    data class Erase(val x: Double, val y: Double, val w: Double, val h: Double) : Op
    data class Bits(val mask: GlyphKeyArt.Mask, val x: Double, val y: Double, val unit: Double, val ink: Int, val halo: Int?, val round: Boolean) : Op

    val ops = ArrayList<Op>()
    var layerEnds = 0
    val rects get() = ops.filterIsInstance<Rect>()
    val texts get() = ops.filterIsInstance<Text>()
    val bits get() = ops.filterIsInstance<Bits>()
    val erases get() = ops.filterIsInstance<Erase>()

    override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) { ops += Rect(x, y, w, h, color) }
    override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) { ops += Text(ch, cx, cy, size, mono, color) }
    override fun layerEnd() { layerEnds++ }
    override fun erase(x: Double, y: Double, w: Double, h: Double) { ops += Erase(x, y, w, h) }
    override fun bits(mask: GlyphKeyArt.Mask, x: Double, y: Double, unit: Double, ink: Int, halo: Int?, round: Boolean) {
        ops += Bits(mask, x, y, unit, ink, halo, round)
    }
}

/** A font with no shapes: every text is a solid block six pixels wide to the character. */
internal object BlockFont : GlyphPixelFont {
    override fun mask(text: String, size: Int, weight: Int, mono: Boolean): GlyphKeyArt.Mask {
        val w = text.length * 6
        val h = size + 4
        return GlyphKeyArt.Mask(w, h, BooleanArray(w * h) { true })
    }
}

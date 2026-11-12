package com.krysta1ll.meowpuut.android.ime

import kotlin.math.max
import kotlin.math.min

// 米米莫 and 莫莫 on the candidate bar (docs/DESIGN.md §1): how a sprite is
// seated on the glass by its paw anchor. The same rules as
// Sources/CatSprites.swift, for the one arrangement this client shows.
// Nothing here touches android.*, so the JVM unit tests cover it.

enum class Cat(val key: String) { MIMIMO("mimimo"), MOMO("momo") }

/** The bar as the cats see it. Boxes are in the candidate region's coordinates, in dp. */
class CatStage(
    /** The candidate region; nothing of a cat leaves it. */
    val width: Float,
    val height: Float,
    val glass: Box,
    val shape: GlassShape,
    /** 1 on a 393 dp phone; gaps and tolerances scale with it. */
    val unit: Float,
    /** The height a cat may rise above the glass's box. It sets the cats' size, the same for the three shapes. */
    val room: Float,
    /** The band the candidates and the selected capsule take. No paw covers it. */
    val row: Box,
    /** The expand key's arrow. No paw covers it. */
    val arrow: Box,
)

/** One cat's place on a stage. */
class CatPlacement(
    val cat: Cat,
    val sprite: CatSprite,
    /** The sprite's whole canvas. */
    val frame: Box,
    /** Where the sprite's anchor sits: on the glass's edge, between the paws. */
    val anchorX: Float,
    val anchorY: Float,
) {
    val scale get() = frame.width / sprite.width

    /** The opaque part. */
    val visible = Box(
        frame.left + sprite.left * frame.width, frame.top + sprite.top * frame.height,
        (sprite.right - sprite.left) * frame.width, (sprite.bottom - sprite.top) * frame.height,
    )

    /** The paws that hang over the edge, in front of the glass. */
    val paws: List<Box> = sprite.paws.map { (x, halfWidth, depth) ->
        Box(frame.left + (x - halfWidth) * frame.width - 0.5f, anchorY, 2 * halfWidth * frame.width + 1f, depth * frame.height + 0.5f)
    }

    /** How far the flat cut reaches to either side of the anchor. */
    val reach get() = max(sprite.anchorX - sprite.cutLeft, sprite.cutRight - sprite.anchorX) * frame.width

    fun covers(stage: CatStage) = paws.any { it.intersects(stage.row) || it.intersects(stage.arrow) }

    /** Where a tap pets this cat: its picture, widened to 44 dp, from the top of the region down to the glass's edge. */
    fun touch(stage: CatStage): Box {
        val width = max(44f, visible.width)
        val x = min(max(0f, visible.centerX - width / 2), max(0f, stage.width - width))
        return Box(x, 0f, width, max(1f, anchorY))
    }
}

object CatSeats {
    /** The smallest a cat is drawn: under this it no longer reads as a face. */
    const val SMALLEST = 0.45f

    /**
     * The arrangement named `ends` in Sources/CatSprites.swift: 米米莫 looks
     * over the left end of the glass's top edge, 莫莫 over the right end.
     * Null when it does not fit the stage: a cat would be too small, would
     * find no level stretch of edge, would cover the candidates or would
     * leave the region.
     */
    fun ends(stage: CatStage, mimimo: CatSprite, momo: CatSprite): List<CatPlacement>? {
        val left = seat(stage, Cat.MIMIMO, mimimo, fromLeft = true) ?: return null
        val right = seat(stage, Cat.MOMO, momo, fromLeft = false) ?: return null
        for (box in listOf(left.visible, right.visible)) {
            if (box.left < -0.01f || box.top < -0.01f || box.right > stage.width + 0.01f || box.bottom > stage.height + 0.01f) return null
        }
        // The two keep apart.
        if (left.visible.right + 4 * stage.unit > right.visible.left) return null
        return listOf(left, right)
    }

    /**
     * One cat on the top edge. It starts a little inside the glass's round
     * end and slides inward until the edge under it is level and its paws
     * are clear of what is drawn on the glass.
     */
    private fun seat(stage: CatStage, cat: Cat, art: CatSprite, fromLeft: Boolean): CatPlacement? {
        val unit = stage.unit
        val glass = stage.glass
        val scale = min(1f, stage.room / ((art.anchorY - art.top) * art.height))
        if (scale < SMALLEST) return null
        val width = art.width * scale
        val height = art.height * scale
        val span = (art.cutRight - art.cutLeft) * width
        val radius = GlassOutline.endRadius(stage.shape, glass.height)
        var slide = 0f
        while (slide <= glass.width * 0.3f) {
            val start = if (fromLeft) radius - 4 * unit + slide else glass.width - radius + 4 * unit - slide - span
            val edge = rise(stage.shape, glass, start, start + span)
            // How much of a cat the edge may hide: at its sides, and under its face.
            val x = glass.left + start - art.cutLeft * width
            // A paw sinks into the glass by as much as the edge rises under it.
            val pawsLevel = art.paws.all { (px, halfWidth, _) ->
                val centre = x - glass.left + px * width
                val under = rise(stage.shape, glass, centre - halfWidth * width, centre + halfWidth * width)
                edge.low - (under.low - under.most) <= 1.5f * unit
            }
            if (edge.most <= 4 * unit && edge.middle <= 1.5f * unit && pawsLevel) {
                val anchorY = glass.top + edge.low
                val place = CatPlacement(cat, art, Box(x, anchorY - art.anchorY * height, width, height), x + art.anchorX * width, anchorY)
                if (!place.covers(stage)) return place
            }
            slide += 2 * unit
        }
        return null
    }

    class Rise(val low: Float, val most: Float, val middle: Float)

    /**
     * How far the glass's top edge rises above its lowest point between two
     * x positions (from the glass's left end): anywhere on the stretch, and
     * over its middle half. A cat is seated on the lowest point, so this
     * much of it is hidden by the glass.
     */
    fun rise(shape: GlassShape, glass: Box, from: Float, to: Float): Rise {
        val quarter = (to - from) / 4
        var low = -Float.MAX_VALUE
        var high = Float.MAX_VALUE
        var middle = Float.MAX_VALUE
        var x = from
        while (true) {
            val at = min(x, to)
            val y = GlassOutline.top(shape, at, glass.width, glass.height)
            low = max(low, y)
            high = min(high, y)
            if (at >= from + quarter && at <= to - quarter) middle = min(middle, y)
            if (x >= to) break
            x += 1
        }
        return Rise(low, low - high, max(0f, low - middle))
    }
}

package com.krysta1ll.meowpuut.android.ime

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// 米米莫 and 莫莫 on the candidate bar (docs/DESIGN.md §1): how a sprite is
// seated on the glass by its paw anchor. The same rules as
// Sources/CatSprites.swift, with disjoint touch targets for Android.
// Nothing here touches android.*, so the JVM unit tests cover it.

enum class Cat(val key: String) { MIMIMO("mimimo"), MOMO("momo") }

enum class CatPose(val key: String) {
    PEEK("peek"), REST("rest"), LOUNGE("lounge");

    fun sprite(cat: Cat) = CatSpriteData.all.getValue("${cat.key}_edge_$key")
}

enum class CatSide { LEFT, RIGHT }

enum class CatArrangement(
    val mimimo: CatPose = CatPose.PEEK,
    val momo: CatPose = CatPose.PEEK,
    val together: CatSide? = null,
) {
    ENDS,
    LEFT(together = CatSide.LEFT),
    RIGHT(together = CatSide.RIGHT),
    LOUNGE(CatPose.LOUNGE, CatPose.LOUNGE),
    REST(CatPose.REST, CatPose.REST),
    MIMIMO_LOUNGE(CatPose.LOUNGE),
    MOMO_LOUNGE(momo = CatPose.LOUNGE),
    COMPANY(momo = CatPose.LOUNGE, together = CatSide.RIGHT),
}

/** Keep the chosen arrangement until the next presentation of the keyboard. */
class CatSchedule {
    private var current = CatArrangement.ENDS
    private var shown = false
    private var advance = false

    fun newPresentation() {
        advance = shown
        shown = true
    }

    fun seats(stage: CatStage, sprite: (Cat, CatPose) -> CatSprite): List<CatPlacement>? {
        fun place(arrangement: CatArrangement) = CatSeats.place(
            stage, arrangement, sprite(Cat.MIMIMO, arrangement.mimimo), sprite(Cat.MOMO, arrangement.momo),
        )
        val previous = place(current)
        if (advance || previous == null) {
            val arrangements = CatArrangement.entries
            var changedPose: Pair<CatArrangement, List<CatPlacement>>? = null
            for (step in 1 until arrangements.size) {
                val layout = arrangements[(current.ordinal + step) % arrangements.size]
                val next = place(layout) ?: continue
                val moved = previous == null || next.any { cat ->
                    val before = previous.first { it.cat == cat.cat }
                    abs(before.touch(stage).centerX - cat.touch(stage).centerX) >= 8f * stage.unit
                }
                if (moved) return select(layout, next)
                if (changedPose == null && next.any { cat ->
                    previous?.first { it.cat == cat.cat }?.sprite?.name != cat.sprite.name
                }) changedPose = layout to next
            }
            changedPose?.let { return select(it.first, it.second) }
        }
        return previous?.let { select(current, it) }
    }

    private fun select(arrangement: CatArrangement, places: List<CatPlacement>): List<CatPlacement> {
        current = arrangement
        advance = false
        return places
    }
}

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
    /** The baseline band before cats take room on its leading or trailing edge. */
    val row: Box,
    /** The baseline expand glyph. */
    val arrow: Box,
    val arrowTouch: Box,
) {
    // The top of the selected pill has space above its label. A small paw
    // overhang there can share its horizontal position with a candidate.
    internal val candidateTextBand: Box
        get() {
            val inset = min(5f * unit, row.height * 0.15f)
            return Box(row.left, row.top + inset, row.width, row.height - inset)
        }

    // A few dp of overlap at the foot of a perched cat should not move a
    // 44 dp button. Let the arrow take that narrow shared edge of the hit area.
    private fun sharesUpperEdge(target: Box): Boolean =
        target.bottom - arrowTouch.top in 0f..min(8f, target.height * 0.25f)

    fun petTarget(place: CatPlacement): Box {
        val target = place.touch(this)
        return if (target.intersects(arrowTouch) && sharesUpperEdge(target)) {
            target.copy(height = arrowTouch.top - target.top)
        } else target
    }

    /** Seats stay put. Only the candidate row and its arrow move around occupied space. */
    fun fitting(places: List<CatPlacement>, ornament: FestivalPiece? = null): CatStage? {
        if (places.isEmpty()) return this
        val left = glass.left + 12f * unit
        val edge = glass.right - 6f * unit
        val right = if (ornament == null) edge else {
            val band = GlassOutline.contentBand(shape, glass.height)
            val ornamentWidth = (band.second - band.first) * 0.6f * ornament.width / ornament.height
            min(edge, min(width - 1f, glass.right + 4f) - 3f - ornamentWidth + arrowTouch.right - arrow.right)
        }
        val gap = 3f * unit
        fun crosses(box: Box, band: Box) = box.bottom > band.top && box.top < band.bottom
        // A paw can overhang the top of the button without hiding its
        // arrow. The interactive pet target must still clear the whole button.
        val paws = places.flatMap { it.paws }
        val touches = places.map { it.touch(this) }
        val pillLimit = right - arrowTouch.width
        val textBand = candidateTextBand
        val pillObstacles = paws.filter { it.left < pillLimit && crosses(it, textBand) } +
            touches.filter { it.left < pillLimit && crosses(it, row) }
        val arrowObstacles = paws.filter { crosses(it, arrow) } +
            touches.filter { crosses(it, arrowTouch) && !sharesUpperEdge(it) }
        val blocked = (pillObstacles + arrowObstacles).sortedBy { it.left }

        var next = left
        var best = left
        var widest = 0f
        for (box in blocked) {
            val start = box.left - gap
            val end = box.right + gap
            if (end <= next || start >= right) continue
            if (start - next > widest) {
                best = next
                widest = start - next
            }
            next = min(right, end)
        }
        if (right - next > widest) {
            best = next
            widest = right - next
        }
        val buttonLeft = best + widest - arrowTouch.width
        if (buttonLeft - best < min(row.width, max(90f * unit, row.width * 0.46f))) return null
        // Reclaim space above the leading end of the visual candidate band.
        val rowLeft = pillObstacles.filter { it.right <= best }.fold(left) { edge, box -> max(edge, box.right + gap) }
        val shift = buttonLeft - arrowTouch.left
        return CatStage(width, height, glass, shape, unit, room,
            Box(rowLeft, row.top, buttonLeft - rowLeft, row.height), arrow.offset(shift, 0f), arrowTouch.offset(shift, 0f))
    }
}

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

    fun covers(stage: CatStage) = paws.any { it.intersects(stage.candidateTextBand) || it.intersects(stage.arrow) }

    /** The translated paws can land anywhere above or at the first key row. */
    fun supportedBy(firstKeyTop: Float, vertical: Float): Boolean = vertical <= firstKeyTop - anchorY

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
    fun ends(stage: CatStage, mimimo: CatSprite, momo: CatSprite): List<CatPlacement>? =
        place(stage, CatArrangement.ENDS, mimimo, momo)

    fun place(stage: CatStage, arrangement: CatArrangement, mimimo: CatSprite, momo: CatSprite): List<CatPlacement>? {
        val places = when (arrangement.together) {
            CatSide.LEFT -> {
                val first = seat(stage, Cat.MIMIMO, mimimo, fromLeft = true) ?: return null
                val second = seat(stage, Cat.MOMO, momo, fromLeft = true, beside = first) ?: return null
                listOf(first, second)
            }
            CatSide.RIGHT -> {
                val first = seat(stage, Cat.MOMO, momo, fromLeft = false) ?: return null
                val second = seat(stage, Cat.MIMIMO, mimimo, fromLeft = false, beside = first) ?: return null
                listOf(first, second)
            }
            null -> {
                val left = seat(stage, Cat.MIMIMO, mimimo, fromLeft = true) ?: return null
                val right = seat(stage, Cat.MOMO, momo, fromLeft = false) ?: return null
                if (!apart(stage, left, right)) return null
                listOf(left, right)
            }
        }
        return places.takeIf { stage.fitting(it) != null }
    }

    private fun apart(stage: CatStage, left: CatPlacement, right: CatPlacement): Boolean =
        left.visible.right + 4 * stage.unit <= right.visible.left && left.touch(stage).right <= right.touch(stage).left

    /**
     * One cat on the top edge. It starts a little inside the glass's round
     * end and slides inward until the edge under it is level and its paws
     * are clear of what is drawn on the glass.
     */
    private fun seat(stage: CatStage, cat: Cat, art: CatSprite, fromLeft: Boolean, beside: CatPlacement? = null): CatPlacement? {
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
                val box = place.visible
                val inside = box.left >= -0.01f && box.top >= -0.01f && box.right <= stage.width + 0.01f && box.bottom <= stage.height + 0.01f
                if (inside &&
                    (beside == null || if (fromLeft) apart(stage, beside, place) else apart(stage, place, beside))) return place
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
            x += 0.5f
        }
        return Rise(low, low - high, max(0f, low - middle))
    }
}

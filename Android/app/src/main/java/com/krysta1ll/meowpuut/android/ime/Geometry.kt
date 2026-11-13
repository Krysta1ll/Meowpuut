package com.krysta1ll.meowpuut.android.ime

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// Pure layout math in dp (docs/DESIGN.md §5), the same numbers as the iOS
// keyboard's KeyboardGeometry.swift. Nothing here touches android.*, so the
// JVM unit tests cover it.

/** A rectangle in dp. */
data class Box(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right get() = left + width
    val bottom get() = top + height
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun intersects(other: Box) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
    fun offset(dx: Float, dy: Float) = Box(left + dx, top + dy, width, height)

    companion object {
        val ZERO = Box(0f, 0f, 0f, 0f)
    }
}

enum class Idiom {
    PHONE, PHONE_LANDSCAPE, TABLET;

    companion object {
        /** Landscape phones have far less vertical room; tablets are wide. */
        fun resolve(screenHeightDp: Float, smallestWidthDp: Float): Idiom = when {
            smallestWidthDp >= 600 -> TABLET
            screenHeightDp < 500 -> PHONE_LANDSCAPE
            else -> PHONE
        }
    }
}

/**
 * The candidate region above the keys: the glass bar, the candidate row and
 * the expand key. 米米莫 and 莫莫 sit on the glass's top edge ([stage]).
 * [rim] is where the shape's upper rim sits below the top of the glass
 * rectangle, as a fraction of the glass height (0 for the capsule, the wave's
 * amplitude, the cloud's base). The expand key lines up with the band the
 * candidates sit in.
 */
class CandidateRegionLayout(rawWidth: Float, val idiom: Idiom, val cats: Boolean = true, val rim: Float = 0f,
                            val edgeHuggingCats: Boolean = false) {
    val width: Float = if (rawWidth.isFinite() && rawWidth > 1f) rawWidth else 320f
    val scale: Float
    val height: Float
    val glass: Box
    val row: Box
    val chevron: Box
    val fontSize: Float
    val statusFontSize: Float
    val pillHeight: Float

    init {
        val s: Float
        val regionHeight: Float
        val glassHeight: Float
        val font: Float
        when (idiom) {
            Idiom.PHONE -> {
                s = min(1.15f, max(0.9f, width / 393f))
                regionHeight = 82 * s; glassHeight = 46 * s
                font = min(21f, max(19f, 20 * s))
            }
            Idiom.PHONE_LANDSCAPE -> {
                s = 1f
                regionHeight = 63f; glassHeight = 36f
                font = 18f
            }
            Idiom.TABLET -> {
                s = 1.15f
                regionHeight = 94f; glassHeight = 53f
                font = 21f
            }
        }
        scale = s
        fontSize = font.roundToInt().toFloat()
        pillHeight = (glassHeight * 0.70f).roundToInt().toFloat()
        statusFontSize = when (idiom) {
            Idiom.TABLET -> 15f
            Idiom.PHONE_LANDSCAPE -> 12f
            Idiom.PHONE -> (13 * s).roundToInt().toFloat()
        }
        val rimY = min(0.3f, max(0f, rim)) * glassHeight

        if (cats) {
            height = regionHeight.roundToInt().toFloat()
            val glassBottom = height - 2 * s
            val top = glassBottom - glassHeight
            val margin = 5 * s
            val left = if (edgeHuggingCats) 15 * s else margin
            glass = Box(left, top, max(1f, width - left - margin), glassHeight)
            val chevronWidth = 44f
            val chevronY = glass.centerY + rimY / 2
            val chevronHeight = min(min(44f, glassHeight), 2 * (height - chevronY))
            chevron = Box(glass.right - 3 * s - chevronWidth, chevronY - chevronHeight / 2, chevronWidth, chevronHeight)
            val rowLeft = glass.left + glassHeight / 2 + 5 * s
            row = Box(rowLeft, glass.top, max(1f, chevron.left - rowLeft), glassHeight)
        } else {
            height = (glassHeight + 12 * s).roundToInt().toFloat()
            glass = Box(4 * s, (height - glassHeight) / 2, max(1f, width - 8 * s), glassHeight)
            val chevronWidth = 44f
            val chevronHeight = min(44f, glassHeight)
            val chevronY = min(glass.centerY + rimY / 2, height - chevronHeight / 2)
            chevron = Box(glass.right - 6 * s - chevronWidth, chevronY - chevronHeight / 2, chevronWidth, chevronHeight)
            val rowLeft = glass.left + glassHeight * 0.3f
            row = Box(rowLeft, glass.top, max(1f, chevron.left - rowLeft), glassHeight)
        }
    }

    /** Longest a single candidate may be before it is shortened in the middle. */
    fun maximumCandidateWidth(row: Box): Float = max(60f, row.width * 0.70f)

    fun candidateTarget(row: Box): Box {
        val targetHeight = max(row.height, 48f).coerceAtMost(height)
        val top = (row.centerY - targetHeight / 2).coerceIn(0f, height - targetHeight)
        return Box(row.left, top, row.width, targetHeight)
    }

    fun expandTarget(button: Box): Box {
        val top = min(button.top, height - 44f).coerceAtLeast(0f)
        return Box(button.left, top, button.width, height - top)
    }

    /**
     * The bar as the cats see it (CatStage.kt). They take the height above
     * the glass less 1.5 dp: a keyboard cannot draw above its own top edge.
     */
    fun stage(shape: GlassShape): CatStage {
        val band = GlassOutline.contentBand(shape, glass.height)
        val middle = glass.top + (band.first + band.second) / 2
        // The arrow's stroke is 2 dp wide with round ends.
        val arrowWidth = ARROW_WIDTH + 2
        val arrowHeight = ARROW_WIDTH / 2 + 2
        return CatStage(
            width, height, glass, shape, unit = scale, room = glass.top - 1.5f,
            row = Box(row.left, middle - pillHeight / 2, row.width, pillHeight),
            arrow = Box(chevron.centerX - arrowWidth / 2, middle - arrowHeight / 2, arrowWidth, arrowHeight),
            arrowTouch = expandTarget(chevron),
        )
    }

    companion object {
        /** Width of the expand key's arrow; it is half as tall. */
        const val ARROW_WIDTH = 11f
    }
}

enum class KeyboardTextSize(val key: String, val scale: Float) {
    SMALL("small", 0.85f), STANDARD("standard", 1f), LARGE("large", 1.2f);

    fun fontSize(base: Float, systemScale: Float): Float = base * scale * systemScale.coerceIn(0.85f, 1.15f)

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

enum class KeyboardHeight(val key: String, val offset: Float) {
    COMPACT("compact", -4f), STANDARD("standard", 0f), TALL("tall", 5f);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

object PanelAdjustment {
    const val MIN_HEIGHT = -10
    const val MAX_HEIGHT = 14
    const val MAX_BOTTOM_GAP = 72

    // Reserve some of the editor on short displays. System navigation is
    // accounted for by the IME in addition to this optional spacing.
    fun availableHeight(screenHeight: Float, idiom: Idiom, navigationInset: Float): Float {
        val editor = if (idiom == Idiom.PHONE_LANDSCAPE) 80f else 144f
        return (screenHeight - editor - navigationInset).coerceAtLeast(0f)
    }

    fun bottomGap(requested: Int, available: Float, contentHeight: Float): Float =
        min(requested.coerceIn(0, MAX_BOTTOM_GAP).toFloat(), (available - contentHeight).coerceAtLeast(0f))
}

/** Key metrics in the proportions of Apple's keyboard (DESIGN.md §5). */
data class KeyMetrics(
    val sideMargin: Float,
    val keyGap: Float,
    val keyHeight: Float,
    val rowGap: Float,
    val topPadding: Float,
    val bottomPadding: Float,
    val cornerRadius: Float,
    val letterFont: Float,
    val utilityFont: Float,
) {
    fun areaHeight(rows: Int): Float = topPadding + rows * keyHeight + max(0, rows - 1) * rowGap + bottomPadding

    companion object {
        fun of(idiom: Idiom, width: Float, rows: Int): KeyMetrics {
            val dense = rows >= 5
            return when (idiom) {
                Idiom.PHONE -> {
                    val s = min(1.06f, max(0.94f, width / 393f))
                    KeyMetrics(
                        sideMargin = 3f, keyGap = 6f,
                        keyHeight = ((if (dense) 37f else 42f) * s).roundToInt().toFloat(),
                        rowGap = if (dense) 7.5f else 11f,
                        topPadding = if (dense) 4f else 5f, bottomPadding = 4f,
                        cornerRadius = if (dense) 10f else 12f, letterFont = if (dense) 21f else 23f, utilityFont = 15f,
                    )
                }
                Idiom.PHONE_LANDSCAPE -> KeyMetrics(
                    sideMargin = 3f, keyGap = 6f,
                    keyHeight = if (dense) 28f else 32f,
                    rowGap = if (dense) 5f else 6f,
                    topPadding = 5f, bottomPadding = 3f,
                    cornerRadius = 9f, letterFont = if (dense) 19f else 21f, utilityFont = 14f,
                )
                Idiom.TABLET -> KeyMetrics(
                    sideMargin = 6f, keyGap = 9f,
                    keyHeight = if (dense) 46f else 52f,
                    rowGap = if (dense) 8f else 10f,
                    topPadding = 8f, bottomPadding = 6f,
                    cornerRadius = if (dense) 13f else 15f, letterFont = if (dense) 23f else 25f, utilityFont = 17f,
                )
            }
        }

        /**
         * Metrics for a key set of [rows] rows in the height that [baseRows]
         * rows take. Zhuyin's five rows set the height for its symbol pages
         * too, so changing pages never makes the keyboard jump. What does not
         * divide evenly goes below the last row, where it still answers touches.
         */
        fun fitted(
            idiom: Idiom, width: Float, rows: Int, baseRows: Int,
            height: KeyboardHeight = KeyboardHeight.STANDARD, panelHeightDelta: Int? = null,
            availableHeight: Float = Float.POSITIVE_INFINITY,
        ): KeyMetrics {
            val base = of(idiom, width, baseRows)
            val custom = panelHeightDelta?.coerceIn(PanelAdjustment.MIN_HEIGHT, PanelAdjustment.MAX_HEIGHT)?.toFloat()
            val landscape = idiom == Idiom.PHONE_LANDSCAPE
            val offset = when {
                !landscape -> custom ?: height.offset
                custom != null -> custom / 2f
                else -> min(0f, height.offset / 2f)
            }
            val minimum = if (landscape) 26f else 32f
            val maxHeightPerRow = floor((availableHeight - base.areaHeight(baseRows) + baseRows * base.keyHeight) / baseRows)
            val sized = base.copy(keyHeight = min(maxHeightPerRow.coerceAtLeast(minimum), (base.keyHeight + offset).coerceAtLeast(minimum)))
            if (rows == baseRows) return sized
            val own = of(idiom, width, rows)
            val area = sized.areaHeight(baseRows)
            val gaps = max(0, rows - 1) * own.rowGap
            val keyHeight = floor(max(1f, (area - own.topPadding - own.bottomPadding - gaps) / rows))
            val used = own.topPadding + rows * keyHeight + gaps
            return own.copy(keyHeight = keyHeight, bottomPadding = max(own.bottomPadding, area - used))
        }
    }
}

enum class RowAlignment {
    /** Keys use the reference unit width and are centred (letters row 2). */
    CENTERED,
    /** Keys stretch to fill; a unit of 0 marks the flexible key (space). */
    JUSTIFIED,
    /** First and last keys sit at the edges, the rest are centred (shift, delete). */
    SPLIT_EDGES,
}

data class RowSpec(val units: List<Float>, val alignment: RowAlignment)

/** One key's place: what is drawn, and the larger area that answers touches. */
data class KeyPlacement(val row: Int, val column: Int, val frame: Box, val hitFrame: Box)

object KeyGrid {
    /**
     * Lays out rows inside an area of [width] (origin at the area's top).
     * [referenceColumns] sets the unit width: 10 for letters, 11 for Zhuyin.
     * Hit frames tile the whole area: horizontally to the middle of each gap
     * (and to the edges at the ends of a row), vertically to the middle of
     * the row gap, so no touch ever lands in a dead zone.
     */
    fun place(rows: List<RowSpec>, width: Float, referenceColumns: Int, metrics: KeyMetrics): List<List<KeyPlacement>> {
        val usable = max(1f, width - 2 * metrics.sideMargin)
        val unit = max(1f, (usable - (referenceColumns - 1) * metrics.keyGap) / referenceColumns)
        val totalHeight = metrics.areaHeight(rows.size)
        fun span(units: Float) = units * unit + max(0f, units - 1) * metrics.keyGap
        return rows.mapIndexed { r, spec ->
            val y = metrics.topPadding + r * (metrics.keyHeight + metrics.rowGap)
            val hitTop = if (r == 0) 0f else y - metrics.rowGap / 2
            val hitBottom = if (r == rows.size - 1) totalHeight else y + metrics.keyHeight + metrics.rowGap / 2
            val gaps = max(0, spec.units.size - 1) * metrics.keyGap
            val frames = ArrayList<Box>(spec.units.size)
            when (spec.alignment) {
                RowAlignment.CENTERED -> {
                    val widths = spec.units.map(::span)
                    var x = metrics.sideMargin + max(0f, (usable - widths.sum() - gaps) / 2)
                    for (w in widths) { frames += Box(x, y, w, metrics.keyHeight); x += w + metrics.keyGap }
                }
                RowAlignment.JUSTIFIED -> {
                    val fixed = spec.units.filter { it > 0 }.sumOf { span(it).toDouble() }.toFloat()
                    val flexibleCount = spec.units.count { it == 0f }
                    val widths = if (flexibleCount > 0) {
                        val flexible = max(unit, (usable - gaps - fixed) / flexibleCount)
                        spec.units.map { if (it == 0f) flexible else span(it) }
                    } else {
                        val factor = if (fixed > 0) max(0.5f, (usable - gaps) / fixed) else 1f
                        spec.units.map { span(it) * factor }
                    }
                    var x = metrics.sideMargin + max(0f, (usable - widths.sum() - gaps) / 2)
                    for (w in widths) { frames += Box(x, y, w, metrics.keyHeight); x += w + metrics.keyGap }
                }
                RowAlignment.SPLIT_EDGES -> {
                    val widths = spec.units.map(::span)
                    val first = widths.first()
                    val last = widths.last()
                    frames += Box(metrics.sideMargin, y, first, metrics.keyHeight)
                    val middle = widths.subList(1, max(1, widths.size - 1))
                    val middleWidth = middle.sum() + max(0, middle.size - 1) * metrics.keyGap
                    var x = metrics.sideMargin + (usable - middleWidth) / 2
                    for (w in middle) { frames += Box(x, y, w, metrics.keyHeight); x += w + metrics.keyGap }
                    if (widths.size > 1) frames += Box(metrics.sideMargin + usable - last, y, last, metrics.keyHeight)
                }
            }
            frames.mapIndexed { c, frame ->
                val left = if (c == 0) 0f else (frames[c - 1].right + frame.left) / 2
                val right = if (c == frames.size - 1) width else (frame.right + frames[c + 1].left) / 2
                KeyPlacement(r, c, frame, Box(left, hitTop, max(0f, right - left), hitBottom - hitTop))
            }
        }
    }

    /** The key under a point (row, column), or null outside the area. */
    fun keyAt(x: Float, y: Float, placements: List<List<KeyPlacement>>): Pair<Int, Int>? {
        for (row in placements) {
            val first = row.firstOrNull() ?: continue
            if (y < first.hitFrame.top || y >= first.hitFrame.bottom) continue
            for (key in row) if (x >= key.hitFrame.left && x < key.hitFrame.right) return key.row to key.column
            // Past either end of the row: the nearest end key.
            val end = if (x < first.hitFrame.left) first else row.last()
            return end.row to end.column
        }
        return null
    }
}

/** The balloon a phone shows above a pressed character key. */
object KeyBalloon {
    fun bubble(key: Box, keyboardWidth: Float, idiom: Idiom): Box {
        val flare = if (idiom == Idiom.PHONE_LANDSCAPE) 8f else 12f
        val height = if (idiom == Idiom.PHONE_LANDSCAPE) 40f else 54f
        val width = min(keyboardWidth - 4, key.width + 2 * flare)
        val x = min(max(2f, key.centerX - width / 2), keyboardWidth - 2 - width)
        return Box(x, key.top - height + 2, width, height)
    }

    /**
     * The long-press alternates strip. It starts over the key and grows toward
     * the middle of the keyboard; on the right half the options run right to
     * left, so the first one is always the one nearest the finger.
     */
    fun alternates(key: Box, count: Int, keyboardWidth: Float, keepOnScreen: Boolean = false): AlternatesStrip {
        val options = max(1, count)
        val cell = min(max(30f, key.width), max(1f, keyboardWidth - 4f - 2 * AlternatesStrip.PADDING) / options)
        val width = cell * options + 2 * AlternatesStrip.PADDING
        val leftHalf = key.centerX < keyboardWidth / 2
        val x = min(max(2f, if (leftHalf) key.left - 4 else key.right + 4 - width), keyboardWidth - 2 - width)
        val above = key.top - 54f
        val y = if (keepOnScreen && above < 2f) key.bottom + 4f else above
        return AlternatesStrip(Box(x, y, width, 50f), cell, options, mirrored = !leftHalf)
    }
}

data class AlternatesStrip(val box: Box, val cell: Float, val count: Int, val mirrored: Boolean) {
    /** The option under [x], or the nearest one. */
    fun indexAt(x: Float): Int {
        val slot = floor((x - box.left - PADDING) / cell).toInt().coerceIn(0, count - 1)
        return if (mirrored) count - 1 - slot else slot
    }

    /** Left edge of the cell that shows option [index]. */
    fun cellLeft(index: Int): Float = box.left + PADDING + (if (mirrored) count - 1 - index else index) * cell

    fun offset(dx: Float, dy: Float) = copy(box = box.offset(dx, dy))

    companion object {
        const val PADDING = 4f
    }
}

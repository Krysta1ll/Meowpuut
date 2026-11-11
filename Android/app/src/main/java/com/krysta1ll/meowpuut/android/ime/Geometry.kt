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
 * The candidate region above the keys: the glass bar, 米米莫 behind it on the
 * left, 莫莫 resting on it on the right, the candidate row and the expand key.
 * [rim] is where the shape's upper rim sits below the top of the glass
 * rectangle, as a fraction of the glass height (0 for the capsule, the wave's
 * amplitude, the cloud's base). The cats line up with that rim, and the
 * expand key with the band the candidates sit in.
 */
class CandidateRegionLayout(rawWidth: Float, val idiom: Idiom, val cats: Boolean = true, val rim: Float = 0f) {
    val width: Float = if (rawWidth.isFinite() && rawWidth > 1f) rawWidth else 320f
    val scale: Float
    val height: Float
    val glass: Box
    val mimimo: Box
    val momo: Box
    val tail: Box
    val row: Box
    val chevron: Box
    val fontSize: Float
    val statusFontSize: Float
    val pillHeight: Float

    init {
        val s: Float
        val regionHeight: Float
        val glassHeight: Float
        val mimimoHeight: Float
        val momoHeight: Float
        val tailHeight: Float
        val font: Float
        when (idiom) {
            Idiom.PHONE -> {
                s = min(1.15f, max(0.9f, width / 393f))
                regionHeight = 82 * s; glassHeight = 46 * s
                mimimoHeight = 62 * s; momoHeight = 54 * s; tailHeight = 28 * s
                font = min(21f, max(19f, 20 * s))
            }
            Idiom.PHONE_LANDSCAPE -> {
                s = 1f
                regionHeight = 63f; glassHeight = 36f
                mimimoHeight = 46f; momoHeight = 40f; tailHeight = 22f
                font = 18f
            }
            Idiom.TABLET -> {
                s = 1.15f
                regionHeight = 94f; glassHeight = 53f
                mimimoHeight = 71f; momoHeight = 62f; tailHeight = 32f
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
            val mimimoWidth = mimimoHeight * MIMIMO_ASPECT
            // His eyes peek just over the glass's upper rim.
            mimimo = Box(2 * s, top + rimY - mimimoHeight * MIMIMO_EYE_BOTTOM, mimimoWidth, mimimoHeight)
            val momoWidth = momoHeight * MOMO_ASPECT
            // Her chin rests on the glass, eyes just above its rim.
            momo = Box(width - 4 * s - momoWidth, top + rimY - 1 * s - momoHeight * MOMO_EYE_LINE, momoWidth, momoHeight)
            val glassLeft = mimimo.left + 0.24f * mimimoWidth
            glass = Box(glassLeft, top, max(1f, width - 6 * s - glassLeft), glassHeight)
            // The tail leaves his body at the lower left and runs along the
            // glass's bottom rim, in front of it.
            val tailWidth = tailHeight * TAIL_ASPECT
            tail = Box(mimimo.left + 3 * s, glassBottom + 1 * s - tailHeight, tailWidth, tailHeight)
            val chevronWidth = 34 * s
            val chevronY = glass.centerY + rimY / 2 + 2 * s
            val chevronHeight = min(min(44f, glassHeight), 2 * (height - chevronY))
            chevron = Box(momo.left - 6 * s - chevronWidth / 2, chevronY - chevronHeight / 2, chevronWidth, chevronHeight)
            val rowLeft = mimimo.right + 2 * s
            row = Box(rowLeft, glass.top, max(1f, chevron.left - rowLeft), glassHeight)
        } else {
            height = (glassHeight + 12 * s).roundToInt().toFloat()
            glass = Box(4 * s, (height - glassHeight) / 2, max(1f, width - 8 * s), glassHeight)
            mimimo = Box.ZERO; momo = Box.ZERO; tail = Box.ZERO
            val chevronWidth = 40 * s
            val chevronHeight = min(44f, glassHeight)
            val chevronY = min(glass.centerY + rimY / 2, height - chevronHeight / 2)
            chevron = Box(glass.right - 6 * s - chevronWidth, chevronY - chevronHeight / 2, chevronWidth, chevronHeight)
            val rowLeft = glass.left + glassHeight * 0.3f
            row = Box(rowLeft, glass.top, max(1f, chevron.left - rowLeft), glassHeight)
        }
    }

    /** Longest a single candidate may be before it is shortened in the middle. */
    val maximumCandidateWidth: Float get() = max(60f, row.width * 0.70f)

    companion object {
        // Sprite anchors from assets/brand/cats/cats.json.
        const val MIMIMO_ASPECT = 0.9135f
        const val MIMIMO_EYE_BOTTOM = 0.538f
        const val MOMO_ASPECT = 1.0543f
        const val MOMO_EYE_LINE = 0.50f
        const val TAIL_ASPECT = 4.02f
    }
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
                        topPadding = if (dense) 6f else 8f, bottomPadding = 4f,
                        cornerRadius = 8.5f, letterFont = if (dense) 21f else 23f, utilityFont = 15f,
                    )
                }
                Idiom.PHONE_LANDSCAPE -> KeyMetrics(
                    sideMargin = 3f, keyGap = 6f,
                    keyHeight = if (dense) 28f else 32f,
                    rowGap = if (dense) 5f else 6f,
                    topPadding = 5f, bottomPadding = 3f,
                    cornerRadius = 7f, letterFont = if (dense) 19f else 21f, utilityFont = 14f,
                )
                Idiom.TABLET -> KeyMetrics(
                    sideMargin = 6f, keyGap = 9f,
                    keyHeight = if (dense) 46f else 52f,
                    rowGap = if (dense) 8f else 10f,
                    topPadding = 8f, bottomPadding = 6f,
                    cornerRadius = 9f, letterFont = if (dense) 23f else 25f, utilityFont = 17f,
                )
            }
        }

        /**
         * Metrics for a key set of [rows] rows in the height that [baseRows]
         * rows take. Zhuyin's five rows set the height for its symbol pages
         * too, so changing pages never makes the keyboard jump. What does not
         * divide evenly goes below the last row, where it still answers touches.
         */
        fun fitted(idiom: Idiom, width: Float, rows: Int, baseRows: Int): KeyMetrics {
            val own = of(idiom, width, rows)
            if (rows == baseRows) return own
            val area = of(idiom, width, baseRows).areaHeight(baseRows)
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
    fun alternates(key: Box, count: Int, keyboardWidth: Float): AlternatesStrip {
        val options = max(1, count)
        val cell = max(30f, min(key.width, (keyboardWidth - 8) / options))
        val width = cell * options + 2 * AlternatesStrip.PADDING
        val leftHalf = key.centerX < keyboardWidth / 2
        val x = min(max(2f, if (leftHalf) key.left - 4 else key.right + 4 - width), keyboardWidth - 2 - width)
        return AlternatesStrip(Box(x, key.top - 50f - 4, width, 50f), cell, options, mirrored = !leftHalf)
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

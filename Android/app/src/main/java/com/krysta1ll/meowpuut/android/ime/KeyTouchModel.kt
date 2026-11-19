package com.krysta1ll.meowpuut.android.ime

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bitflags describing each key's role in the spatial touch resolver.
 *
 * Example:
 * ```kotlin
 * val flags = KeyTouchFlags.pack(isCharacter = true, isDefensiveUtility = false, enabled = true)
 * check((flags.toInt() and KeyTouchFlags.CHARACTER) != 0)
 * ```
 */
object KeyTouchFlags {
    const val CHARACTER: Int = 1 shl 0
    const val DEFENSIVE_UTILITY: Int = 1 shl 1
    const val ENABLED: Int = 1 shl 2
    const val SPACE: Int = 1 shl 3

    fun pack(isCharacter: Boolean, isDefensiveUtility: Boolean, enabled: Boolean, isSpace: Boolean = false): Byte {
        var bits = 0
        if (isCharacter) bits = bits or CHARACTER
        if (isDefensiveUtility) bits = bits or DEFENSIVE_UTILITY
        if (enabled) bits = bits or ENABLED
        if (isSpace) bits = bits or SPACE
        return bits.toByte()
    }

    fun from(key: Key): Byte {
        val charLike = key.isCharacter || key.action == KeyAction.Segment
        val isSpace = key.action == KeyAction.Space
        val defensive = !charLike && !isSpace
        return pack(isCharacter = charLike, isDefensiveUtility = defensive, enabled = key.enabled, isSpace = isSpace)
    }
}

/**
 * Cache-friendly Structure-of-Arrays (SoA) spatial touch model modeled on
 * AOSP `LatinIME` (`PointerTracker` + `ProximityKeyDetector`) and Apple
 * `UIKeyboardLayoutStar` defensive key-edge rules, with zero heap allocations
 * in the hot touch-event loop (`ACTION_DOWN` / `ACTION_MOVE` / `ACTION_UP`).
 *
 * Example:
 * ```kotlin
 * val metrics = KeyMetrics.of(Idiom.PHONE, 393f, 4)
 * val placed = KeyGrid.place(listOf(RowSpec(List(10) { 1f }, RowAlignment.CENTERED)), 393f, 10, metrics)
 * val grid = KeyTouchGridSoA()
 * grid.rebuild(placed) { _, _ -> KeyTouchFlags.pack(true, false, true) }
 * check(grid.columnOf(grid.resolveDown(placed[0][0].frame.right + 1f, placed[0][0].frame.centerY)) == 0)
 * ```
 */
class KeyTouchGridSoA {
    private var rowStart = IntArray(8)
    private var rowHitTop = FloatArray(8)
    private var rowHitBottom = FloatArray(8)

    private var keyRow = IntArray(64)
    private var keyCol = IntArray(64)
    private var frameLeft = FloatArray(64)
    private var frameRight = FloatArray(64)
    private var frameTop = FloatArray(64)
    private var frameBottom = FloatArray(64)
    private var hitLeft = FloatArray(64)
    private var hitRight = FloatArray(64)
    private var hitTop = FloatArray(64)
    private var hitBottom = FloatArray(64)
    private var flags = ByteArray(64)

    var count: Int = 0
        private set
    var rowCount: Int = 0
        private set

    fun interface FlagProvider {
        fun flagFor(row: Int, column: Int): Byte
    }

    fun rebuild(placements: List<List<KeyPlacement>>, keySet: KeySet) {
        val rows = placements.size
        var total = 0
        for (i in 0 until rows) total += placements[i].size
        ensureRowCapacity(rows + 1)
        ensureKeyCapacity(total)

        var cursor = 0
        rowStart[0] = 0
        for (r in 0 until rows) {
            val row = placements[r]
            val keyList = keySet.rows.getOrNull(r)
            val first = row.firstOrNull()
            if (first == null) {
                rowHitTop[r] = 0f
                rowHitBottom[r] = 0f
                rowStart[r + 1] = cursor
                continue
            }
            rowHitTop[r] = first.hitFrame.top
            rowHitBottom[r] = first.hitFrame.bottom
            for (k in 0 until row.size) {
                val item = row[k]
                val key = keyList?.getOrNull(item.column)
                writeEntry(cursor, item, if (key != null) KeyTouchFlags.from(key) else 0)
                cursor++
            }
            rowStart[r + 1] = cursor
        }
        rowCount = rows
        count = cursor
    }

    fun rebuild(placements: List<List<KeyPlacement>>, provider: FlagProvider) {
        val rows = placements.size
        var total = 0
        for (i in 0 until rows) total += placements[i].size
        ensureRowCapacity(rows + 1)
        ensureKeyCapacity(total)

        var cursor = 0
        rowStart[0] = 0
        for (r in 0 until rows) {
            val row = placements[r]
            val first = row.firstOrNull()
            if (first == null) {
                rowHitTop[r] = 0f
                rowHitBottom[r] = 0f
                rowStart[r + 1] = cursor
                continue
            }
            rowHitTop[r] = first.hitFrame.top
            rowHitBottom[r] = first.hitFrame.bottom
            for (k in 0 until row.size) {
                val item = row[k]
                writeEntry(cursor, item, provider.flagFor(r, item.column))
                cursor++
            }
            rowStart[r + 1] = cursor
        }
        rowCount = rows
        count = cursor
    }

    private fun writeEntry(cursor: Int, item: KeyPlacement, flag: Byte) {
        keyRow[cursor] = item.row
        keyCol[cursor] = item.column
        frameLeft[cursor] = item.frame.left
        frameRight[cursor] = item.frame.right
        frameTop[cursor] = item.frame.top
        frameBottom[cursor] = item.frame.bottom
        hitLeft[cursor] = item.hitFrame.left
        hitRight[cursor] = item.hitFrame.right
        hitTop[cursor] = item.hitFrame.top
        hitBottom[cursor] = item.hitFrame.bottom
        flags[cursor] = flag
    }

    fun rowOf(index: Int): Int = if (index in 0 until count) keyRow[index] else -1
    fun columnOf(index: Int): Int = if (index in 0 until count) keyCol[index] else -1

    fun indexOf(row: Int, column: Int): Int {
        if (row !in 0 until rowCount || column < 0) return -1
        val idx = rowStart[row] + column
        return if (idx < rowStart[row + 1]) idx else -1
    }

    /** Pure Voronoi hit lookup matching [KeyGrid.keyAt] bit-for-bit. */
    fun resolveHit(x: Float, y: Float): Int {
        val rows = rowCount
        if (rows <= 0) return -1
        val lastRow = rows - 1
        for (r in 0 until rows) {
            val start = rowStart[r]
            val end = rowStart[r + 1]
            if (start < end && y >= rowHitTop[r] && (y < rowHitBottom[r] || r == lastRow)) {
                for (i in start until end) {
                    if (x >= hitLeft[i] && x < hitRight[i]) return i
                }
                return if (x < hitLeft[start]) start else end - 1
            }
        }
        return -1
    }

    /**
     * Touch-down resolver with top-seam reach, disabled-key edge rescue,
     * anchored Spacebar top-gutter parallax guard, and character-prioritized
     * utility gutter disambiguation.
     */
    fun resolveDown(x: Float, y: Float, allowTopMargin: Float = 0f): Int {
        val effectiveY = if (y < 0f && y >= -allowTopMargin) 0f else y
        val base = resolveHit(x, effectiveY)
        if (base < 0) return -1
        val baseFlags = flags[base].toInt()
        val rBase = keyRow[base]

        if ((baseFlags and KeyTouchFlags.ENABLED) == 0) {
            val inset = DISABLED_INNER_INSET_DP
            val insideCore = x >= frameLeft[base] + inset && x <= frameRight[base] - inset &&
                effectiveY >= frameTop[base] + inset && effectiveY <= frameBottom[base] - inset
            if (insideCore) return base
            val rescue = nearestMatchingKey(
                rMin = max(0, rBase - 1),
                rMax = min(rowCount - 1, rBase + 1),
                requiredAll = KeyTouchFlags.ENABLED,
                anyMask = KeyTouchFlags.CHARACTER or KeyTouchFlags.SPACE,
                maxDist = CHARACTER_GUTTER_REACH_DP,
                maxVsBase = Float.POSITIVE_INFINITY,
                x = x,
                y = effectiveY,
            )
            return if (rescue >= 0) rescue else base
        }

        if ((baseFlags and KeyTouchFlags.SPACE) != 0) {
            // Thumb parallax (Holz & Baudisch CHI 2011; Henze et al. CHI 2012):
            // touches landing in the rowGap above Space's anchored cap+2dp rim yield to Row-2 keys.
            if (effectiveY < frameTop[base] - SPACE_TOP_PROTECTED_MARGIN_DP && rBase > 0) {
                val baseDist = boxDistance(base, x, effectiveY)
                val rescue = nearestMatchingKey(
                    rMin = rBase - 1,
                    rMax = rBase - 1,
                    requiredAll = KeyTouchFlags.CHARACTER or KeyTouchFlags.ENABLED,
                    anyMask = KeyTouchFlags.CHARACTER,
                    maxDist = CHARACTER_GUTTER_REACH_DP,
                    maxVsBase = baseDist + UTILITY_YIELD_BIAS_DP,
                    x = x,
                    y = effectiveY,
                )
                if (rescue >= 0) return rescue
            }
            return base
        }

        if ((baseFlags and KeyTouchFlags.DEFENSIVE_UTILITY) == 0) return base

        val m = UTILITY_PROTECTED_MARGIN_DP
        if (x >= frameLeft[base] - m && x <= frameRight[base] + m &&
            effectiveY >= frameTop[base] - m && effectiveY <= frameBottom[base] + m
        ) {
            return base
        }

        val baseDist = boxDistance(base, x, effectiveY)
        val bestIndex = nearestMatchingKey(
            rMin = max(0, rBase - 1),
            rMax = min(rowCount - 1, rBase + 1),
            requiredAll = KeyTouchFlags.CHARACTER or KeyTouchFlags.ENABLED,
            anyMask = KeyTouchFlags.CHARACTER,
            maxDist = CHARACTER_GUTTER_REACH_DP,
            maxVsBase = baseDist + UTILITY_YIELD_BIAS_DP,
            x = x,
            y = effectiveY,
        )
        return if (bestIndex >= 0) bestIndex else base
    }

    private fun nearestMatchingKey(
        rMin: Int, rMax: Int, requiredAll: Int, anyMask: Int,
        maxDist: Float, maxVsBase: Float, x: Float, y: Float,
    ): Int {
        var bestIndex = -1
        var bestDist = maxDist
        for (r in rMin..rMax) {
            for (i in rowStart[r] until rowStart[r + 1]) {
                val f = flags[i].toInt()
                if ((f and requiredAll) == requiredAll && (f and anyMask) != 0) {
                    val d = boxDistance(i, x, y)
                    if (d <= bestDist && d <= maxVsBase) {
                        if (bestIndex < 0 || d < bestDist) {
                            bestDist = d
                            bestIndex = i
                        }
                    }
                }
            }
        }
        return bestIndex
    }

    /** Returns true when rapid inter-keystroke timing and short dwell indicate a fast tap rather than a slide. */
    fun isFastTypingBurst(interDownMs: Long, dwellMs: Long): Boolean =
        interDownMs in 0L..FAST_BURST_INTER_DOWN_MS && dwellMs in 0L..FAST_BURST_MAX_DWELL_MS

    /** Two-condition character slide hysteresis (`PointerTracker` + velocity/dwell burst guard). */
    fun resolveCharacterSlide(
        current: Int, downX: Float, downY: Float, x: Float, y: Float,
        toleratesDrift: Boolean, fastTyping: Boolean = false,
    ): Int {
        if (current !in 0 until count || (flags[current].toInt() and KeyTouchFlags.CHARACTER) == 0) return -1
        val next = resolveHit(x, y)
        val needed = KeyTouchFlags.CHARACTER or KeyTouchFlags.ENABLED
        if (next < 0 || next == current || (flags[next].toInt() and needed) != needed) return -1
        if (toleratesDrift) {
            val h = if (fastTyping) FAST_SLIDE_BOUNDARY_HYSTERESIS_DP else SLIDE_BOUNDARY_HYSTERESIS_DP
            if (x >= hitLeft[current] - h && x < hitRight[current] + h &&
                y >= hitTop[current] - h && y < hitBottom[current] + h
            ) {
                return -1
            }
            val dx = x - downX
            val dy = y - downY
            val minSq = if (fastTyping) FAST_SLIDE_MIN_TRAVEL_SQ_DP else SLIDE_MIN_TRAVEL_SQ_DP
            if (dx * dx + dy * dy < minSq) return -1
        }
        return next
    }

    fun allowsModifierSlide(downX: Float, downY: Float, x: Float, y: Float, alreadySlid: Boolean): Boolean {
        if (alreadySlid) return true
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy >= MODIFIER_SLIDE_MIN_TRAVEL_SQ_DP
    }

    fun isWithinReleaseSlop(index: Int, x: Float, y: Float): Boolean {
        if (index !in 0 until count) return false
        val s = UTILITY_RELEASE_SLOP_DP
        return x >= hitLeft[index] - s && x <= hitRight[index] + s &&
            y >= hitTop[index] - s && y <= hitBottom[index] + s
    }

    fun isValidToneSwipe(index: Int, originX: Float, originY: Float, x: Float, y: Float): Boolean {
        if (index !in 0 until count) return false
        val rise = originY - y
        if (rise < TONE_SWIPE_MIN_RISE_DP || rise < TONE_SWIPE_SLOPE * abs(x - originX)) return false
        val top = frameTop[index]
        val height = frameBottom[index] - top
        return y <= top + height * TONE_TOP_ZONE_FRACTION || rise >= height * TONE_FULL_FLICK_FRACTION
    }

    /** Returns true when an upward drag on Delete arms clearing the composition or field. */
    fun isValidDeleteAllSwipe(downX: Float, downY: Float, x: Float, y: Float): Boolean {
        val rise = downY - y
        return rise >= DELETE_SWIPE_UP_MIN_RISE_DP && rise >= DELETE_SWIPE_UP_SLOPE * abs(x - downX)
    }

    /** Hysteresis check keeping Delete-All armed until the finger returns down below the cancel band. */
    fun shouldKeepDeleteAllSwipe(downX: Float, downY: Float, x: Float, y: Float): Boolean {
        val rise = downY - y
        return rise >= DELETE_SWIPE_UP_CANCEL_RISE_DP && rise >= DELETE_SWIPE_UP_KEEP_SLOPE * abs(x - downX)
    }

    private fun boxDistance(index: Int, x: Float, y: Float): Float {
        val dx = when {
            x < frameLeft[index] -> frameLeft[index] - x
            x > frameRight[index] -> x - frameRight[index]
            else -> 0f
        }
        val dy = when {
            y < frameTop[index] -> frameTop[index] - y
            y > frameBottom[index] -> y - frameBottom[index]
            else -> 0f
        }
        return sqrt(dx * dx + dy * dy)
    }

    private fun ensureRowCapacity(required: Int) {
        if (rowStart.size >= required) return
        val cap = max(required, rowStart.size * 2)
        rowStart = IntArray(cap)
        rowHitTop = FloatArray(cap)
        rowHitBottom = FloatArray(cap)
    }

    private fun ensureKeyCapacity(required: Int) {
        if (flags.size >= required) return
        val cap = max(required, flags.size * 2)
        keyRow = IntArray(cap); keyCol = IntArray(cap)
        frameLeft = FloatArray(cap); frameRight = FloatArray(cap); frameTop = FloatArray(cap); frameBottom = FloatArray(cap)
        hitLeft = FloatArray(cap); hitRight = FloatArray(cap); hitTop = FloatArray(cap); hitBottom = FloatArray(cap)
        flags = ByteArray(cap)
    }

    companion object {
        const val UTILITY_PROTECTED_MARGIN_DP = 3f
        const val SPACE_TOP_PROTECTED_MARGIN_DP = 2f
        const val DISABLED_INNER_INSET_DP = 2f
        const val CHARACTER_GUTTER_REACH_DP = 11f
        const val UTILITY_YIELD_BIAS_DP = 4.5f
        const val SLIDE_BOUNDARY_HYSTERESIS_DP = 2.5f
        const val FAST_SLIDE_BOUNDARY_HYSTERESIS_DP = 3.5f
        const val SLIDE_MIN_TRAVEL_SQ_DP = 16f
        const val FAST_SLIDE_MIN_TRAVEL_SQ_DP = 100f
        const val FAST_BURST_INTER_DOWN_MS = 160L
        const val FAST_BURST_MAX_DWELL_MS = 120L
        const val MODIFIER_SLIDE_MIN_TRAVEL_SQ_DP = 100f
        const val UTILITY_RELEASE_SLOP_DP = 12f
        const val TONE_SWIPE_MIN_RISE_DP = 20f
        const val TONE_SWIPE_SLOPE = 1.4f
        const val TONE_TOP_ZONE_FRACTION = 0.22f
        const val TONE_FULL_FLICK_FRACTION = 0.68f
        const val DELETE_SWIPE_UP_MIN_RISE_DP = 24f
        const val DELETE_SWIPE_UP_CANCEL_RISE_DP = 12f
        const val DELETE_SWIPE_UP_SLOPE = 1.6f
        const val DELETE_SWIPE_UP_KEEP_SLOPE = 1.1f
    }
}

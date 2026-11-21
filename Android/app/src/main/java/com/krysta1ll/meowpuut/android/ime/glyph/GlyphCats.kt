package com.krysta1ll.meowpuut.android.ime.glyph

/** A picture as pixels (ARGB), [w] by [h]. */
internal class Raster(val w: Int, val h: Int, val pixels: IntArray)

/**
 * The two cats, 米米莫 and 莫莫, in the pixels they have in this skin: Mimi is 22 x 16, Momo 20 x 15, six poses (the last two are
 * the one turned to watch the view and the one on her haunches) and sixteen faces. What is drawn round them is part of the picture: a
 * clear margin that keeps the cat off the glyphs and, where the cat is the colour of the ground, an outline. On the Moon they are in
 * suits.
 */
internal object GlyphCats {
    /** The clear margin, in cat pixels. A [Raster] of a cat is this much larger on every side. */
    const val MARGIN = 2
    /** One cat pixel is this many points on the keyboard. */
    const val POINTS = 2

    val POSES = listOf("walkA", "walkB", "sit", "sleep", "back", "loaf")
    val FACES = listOf("neutral", "blink", "happy", "smug", "wide", "left", "focus", "meow", "sparkle", "love", "dizzy", "angry", "wink", "up", "blep", "yawn")

    /** The grid of a cat, without a suit. */
    fun w(cat: CatId): Int = if (cat == CatId.MIMI) 22 else 20
    fun h(cat: CatId): Int = if (cat == CatId.MIMI) 16 else 15

    fun rows(cat: CatId, pose: String, face: String): Array<CharArray> {
        val art = if (cat == CatId.MIMI) GlyphCatArt.MIMI else GlyphCatArt.MOMO
        val grid = Array(h(cat)) { r -> art.getValue(pose)[r].toCharArray() }
        // A cat asleep has shut eyes already drawn, and one with its back turned has no face to show: every other pose takes the face over its own.
        if (pose != "sleep" && pose != "back") for ((r, col, s) in GlyphCatArt.FACES.getValue(cat.key)[face].orEmpty()) for (i in s.indices) grid[r][col + i] = s[i]
        return grid
    }

    /**
     * On the Moon: a helmet and a white suit. The grid gains a row above and a column to the right, so the helmet goes round the head;
     * the helmet's collar is the one vermilion thing, 'h' is its glass edge (the ink), 'S' the suit, 'b' the boots, 'g' a light on the glass.
     */
    fun suited(rows: Array<CharArray>, pose: String): Array<CharArray> {
        val h = rows.size
        val w = rows[0].size
        val out = Array(h + 1) { r -> CharArray(w + 1) { k -> if (r == 0 || k == w) '.' else rows[r - 1][k] } }
        val lying = pose == "sleep"
        val x0 = if (lying) w - 11 else w - 12
        val x1 = w
        val y0 = if (lying) 6 else 0
        val y1 = if (lying) 14 else 10
        for (cy in 1..h) for (cx in 0..w) {
            if (out[cy][cx] == '.' || (cx > x0 && cx < x1 && cy > y0 && cy < y1)) continue
            out[cy][cx] = if (cy == h) 'b' else 'S'
        }
        for (cy in y0..y1) for (cx in x0..x1) {
            val edge = cx == x0 || cx == x1 || cy == y0 || cy == y1
            val corner = (cx - x0 < 2 || x1 - cx < 2) && (cy - y0 < 2 || y1 - cy < 2)
            val inner = (cx == x0 + 1 || cx == x1 - 1) && (cy == y0 + 1 || cy == y1 - 1)
            if ((edge && !corner) || inner) out[cy][cx] = if (cy == y1) 'R' else 'h'
        }
        for (cy in intArrayOf(y0 + 2, y0 + 3)) if (out[cy][x0 + 2] == '.') out[cy][x0 + 2] = 'g'
        return out
    }

    /** The edge colour round a cat, or null for none: the cat that is the colour of the ground needs no outline, the other does. */
    fun edgeColor(cat: CatId, dark: Boolean, ink: Int, suit: Boolean = false): Int? {
        if (suit) return if (dark) 0xFF8A8578.toInt() else 0xFF2B2725.toInt()
        val light = cat == CatId.MOMO
        return if (light == dark) null else if (dark) ink else 0xFF5C3D2E.toInt()
    }

    private fun color(k: Char, colors: GlyphColors): Int = when (k) {
        'h' -> colors.ink
        'R' -> colors.red
        'S' -> 0xFFF4F1E8.toInt()
        'g' -> 0xFFFFFFFF.toInt()
        'b' -> 0xFF2B2725.toInt()
        else -> GlyphCatArt.PALETTE.getValue(k)
    }

    /**
     * The cat as pixels, in a raster [MARGIN] larger than its grid on every side (a suited cat's grid is a row taller and a column wider).
     * [eyesOnly] is the dark cat at night: it is the night itself, and only its eyes (and a glint) can be seen. Not in a suit.
     */
    fun raster(cat: CatId, pose: String, face: String, dark: Boolean, eyesOnly: Boolean, colors: GlyphColors, suit: Boolean = false): Raster {
        var rows = rows(cat, pose, face)
        if (suit) rows = suited(rows, pose)
        val gh = rows.size
        val gw = rows[0].size
        val w = gw + 2 * MARGIN
        val h = gh + 2 * MARGIN
        val out = IntArray(w * h)
        fun at(cx: Int, cy: Int) = cy in 0 until gh && cx in 0 until gw && rows[cy][cx] != '.'
        if (eyesOnly && !suit) {
            for (cy in 0 until gh) for (cx in 0 until gw) {
                val k = rows[cy][cx]
                if (k != '.') out[(cy + MARGIN) * w + cx + MARGIN] = if (k == 'Y' || k == 'W' || k == 'e') GlyphCatArt.PALETTE.getValue(k) else colors.paper
            }
            return Raster(w, h, out)
        }
        for (cy in -MARGIN until gh + MARGIN) for (cx in -MARGIN until gw + MARGIN) {
            if (at(cx, cy)) continue
            var near = false
            loop@ for (dy in -2..2) for (dx in -2..2) if (Math.abs(dx) + Math.abs(dy) <= 2 && at(cx + dx, cy + dy)) { near = true; break@loop }
            if (near) out[(cy + MARGIN) * w + cx + MARGIN] = colors.paper
        }
        val edge = edgeColor(cat, dark, colors.ink, suit)
        if (edge != null) for (cy in -1..gh) for (cx in -1..gw) {
            if (!at(cx, cy) && (at(cx + 1, cy) || at(cx - 1, cy) || at(cx, cy + 1) || at(cx, cy - 1))) out[(cy + MARGIN) * w + cx + MARGIN] = edge
        }
        for (cy in 0 until gh) for (cx in 0 until gw) {
            val k = rows[cy][cx]
            if (k != '.') out[(cy + MARGIN) * w + cx + MARGIN] = color(k, colors)
        }
        return Raster(w, h, out)
    }

    /** The little orange crab that may keep the cats company for a while (debug builds only: see GlyphEngine.crabAllowed). */
    val CRAB = listOf(
        listOf(".O.......O.", ".OO.....OO.", "..OOOOOOO..", ".OOOOOOOOO.", ".OOkOOOkOO.", ".OOOOOOOOO.", "..OOOOOOO..", ".O.O...O.O."),
        listOf(".O.......O.", ".OO.....OO.", "..OOOOOOO..", ".OOOOOOOOO.", ".OOkOOOkOO.", ".OOOOOOOOO.", "..OOOOOOO..", "..O.O.O.O.."),
    )
    const val CRAB_ORANGE = 0xFFD97757.toInt()
    const val CRAB_EYE = 0xFF2B2725.toInt()

    /** A fish that clears the bar when 寿司 is typed. */
    val FISH = listOf("..XXX..X", ".XXXXXXX", "..XXX..X")

    fun crabRaster(frame: Int, paper: Int): Raster {
        val rows = CRAB[frame and 1]
        val w = 11 + 2
        val h = rows.size + 2
        val out = IntArray(w * h)
        for (r in rows.indices) for (k in 0 until 11) if (rows[r][k] != '.') {
            for (dy in -1..1) for (dx in -1..1) {
                val x = k + 1 + dx
                val y = r + 1 + dy
                if (x in 0 until w && y in 0 until h) out[y * w + x] = paper
            }
        }
        for (r in rows.indices) for (k in 0 until 11) if (rows[r][k] != '.') out[(r + 1) * w + k + 1] = if (rows[r][k] == 'k') CRAB_EYE else CRAB_ORANGE
        return Raster(w, h, out)
    }
}

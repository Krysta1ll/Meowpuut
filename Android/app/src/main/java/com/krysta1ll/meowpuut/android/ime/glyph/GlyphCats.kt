package com.krysta1ll.meowpuut.android.ime.glyph

/** A picture as pixels (ARGB), [w] by [h]. */
internal class Raster(val w: Int, val h: Int, val pixels: IntArray)

/**
 * The two cats, 米米莫 and 莫莫, in the pixels they have in this skin: a 20 x 15 grid each, four poses and a handful of faces.
 * What is drawn round them is part of the picture: a clear margin that keeps the cat off the glyphs and, where the cat is the colour of
 * the ground, an outline.
 */
internal object GlyphCats {
    const val W = 20
    const val H = 15
    /** The clear margin, in cat pixels. A [Raster] of a cat is this much larger on every side. */
    const val MARGIN = 2
    /** One cat pixel is this many points on the keyboard. */
    const val POINTS = 2

    val POSES = listOf("walkA", "walkB", "sit", "sleep")
    val FACES = listOf("neutral", "blink", "happy", "smug", "wide", "left", "focus", "meow", "sparkle")

    fun rows(cat: CatId, pose: String, face: String): Array<CharArray> {
        val art = if (cat == CatId.MIMI) GlyphCatArt.MIMI else GlyphCatArt.MOMO
        val grid = Array(H) { r -> art.getValue(pose)[r].toCharArray() }
        // A cat asleep has shut eyes already drawn; every other pose takes the face over its own.
        if (pose != "sleep") for ((r, col, s) in GlyphCatArt.FACES.getValue(cat.key)[face].orEmpty()) for (i in s.indices) grid[r][col + i] = s[i]
        return grid
    }

    /** The edge colour round a cat, or null for none: the cat that is the colour of the ground needs no outline, the other does. */
    fun edgeColor(cat: CatId, dark: Boolean, ink: Int): Int? {
        val light = cat == CatId.MOMO
        return if (light == dark) null else if (dark) ink else 0xFF5C3D2E.toInt()
    }

    /**
     * The cat as pixels, in a raster [MARGIN] larger than the grid on every side. [eyesOnly] is the dark cat at night: it is the
     * night itself, and only its eyes (and a glint) can be seen.
     */
    fun raster(cat: CatId, pose: String, face: String, dark: Boolean, eyesOnly: Boolean, colors: GlyphColors): Raster {
        val rows = rows(cat, pose, face)
        val w = W + 2 * MARGIN
        val h = H + 2 * MARGIN
        val out = IntArray(w * h)
        fun at(cx: Int, cy: Int) = cy in 0 until H && cx in 0 until W && rows[cy][cx] != '.'
        if (eyesOnly) {
            for (cy in 0 until H) for (cx in 0 until W) {
                val k = rows[cy][cx]
                if (k != '.') out[(cy + MARGIN) * w + cx + MARGIN] = if (k == 'Y' || k == 'W' || k == 'e') GlyphCatArt.PALETTE.getValue(k) else colors.paper
            }
            return Raster(w, h, out)
        }
        for (cy in -MARGIN until H + MARGIN) for (cx in -MARGIN until W + MARGIN) {
            if (at(cx, cy)) continue
            var near = false
            loop@ for (dy in -2..2) for (dx in -2..2) if (Math.abs(dx) + Math.abs(dy) <= 2 && at(cx + dx, cy + dy)) { near = true; break@loop }
            if (near) out[(cy + MARGIN) * w + cx + MARGIN] = colors.paper
        }
        val edge = edgeColor(cat, dark, colors.ink)
        if (edge != null) for (cy in -1..H) for (cx in -1..W) {
            if (!at(cx, cy) && (at(cx + 1, cy) || at(cx - 1, cy) || at(cx, cy + 1) || at(cx, cy - 1))) out[(cy + MARGIN) * w + cx + MARGIN] = edge
        }
        for (cy in 0 until H) for (cx in 0 until W) {
            val k = rows[cy][cx]
            if (k != '.') out[(cy + MARGIN) * w + cx + MARGIN] = GlyphCatArt.PALETTE.getValue(k)
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

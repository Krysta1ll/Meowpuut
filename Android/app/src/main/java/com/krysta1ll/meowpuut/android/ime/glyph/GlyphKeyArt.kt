package com.krysta1ll.meowpuut.android.ime.glyph

/**
 * The type on the keys, cut by hand: letters and digits on a 5 x 7 grid, and the icons. Each row is a string; X is ink.
 * Anything else a key says (Zhuyin, Chinese, symbols) is set in a system font and cut to one bit by the platform (see GlyphPixelText).
 */
internal object GlyphKeyArt {
    private val LOWER: Map<String, List<String>> = mapOf(
        "a" to listOf(".....", ".....", ".XXX.", "....X", ".XXXX", "X...X", ".XXXX"), "b" to listOf("X....", "X....", "XXXX.", "X...X", "X...X", "X...X", "XXXX."),
        "c" to listOf(".....", ".....", ".XXXX", "X....", "X....", "X....", ".XXXX"), "d" to listOf("....X", "....X", ".XXXX", "X...X", "X...X", "X...X", ".XXXX"),
        "e" to listOf(".....", ".....", ".XXX.", "X...X", "XXXXX", "X....", ".XXXX"), "f" to listOf("..XX.", ".X...", "XXXX.", ".X...", ".X...", ".X...", ".X..."),
        "g" to listOf(".....", ".XXXX", "X...X", "X...X", ".XXXX", "....X", ".XXX."), "h" to listOf("X....", "X....", "XXXX.", "X...X", "X...X", "X...X", "X...X"),
        "i" to listOf("..X..", ".....", ".XX..", "..X..", "..X..", "..X..", ".XXX."), "j" to listOf("...X.", ".....", "..XX.", "...X.", "...X.", "X..X.", ".XX.."),
        "k" to listOf("X....", "X....", "X..X.", "X.X..", "XX...", "X.X..", "X..X."), "l" to listOf(".XX..", "..X..", "..X..", "..X..", "..X..", "..X..", ".XXX."),
        "m" to listOf(".....", ".....", "XX.X.", "X.X.X", "X.X.X", "X.X.X", "X.X.X"), "n" to listOf(".....", ".....", "XXXX.", "X...X", "X...X", "X...X", "X...X"),
        "o" to listOf(".....", ".....", ".XXX.", "X...X", "X...X", "X...X", ".XXX."), "p" to listOf(".....", "XXXX.", "X...X", "X...X", "XXXX.", "X....", "X...."),
        "q" to listOf(".....", ".XXXX", "X...X", "X...X", ".XXXX", "....X", "....X"), "r" to listOf(".....", ".....", "X.XX.", "XX..X", "X....", "X....", "X...."),
        "s" to listOf(".....", ".....", ".XXXX", "X....", ".XXX.", "....X", "XXXX."), "t" to listOf(".X...", ".X...", "XXXX.", ".X...", ".X...", ".X..X", "..XX."),
        "u" to listOf(".....", ".....", "X...X", "X...X", "X...X", "X..XX", ".XX.X"), "v" to listOf(".....", ".....", "X...X", "X...X", "X...X", ".X.X.", "..X.."),
        "w" to listOf(".....", ".....", "X...X", "X.X.X", "X.X.X", "X.X.X", ".X.X."), "x" to listOf(".....", ".....", "X...X", ".X.X.", "..X..", ".X.X.", "X...X"),
        "y" to listOf(".....", "X...X", "X...X", "X...X", ".XXXX", "....X", ".XXX."), "z" to listOf(".....", ".....", "XXXXX", "...X.", "..X..", ".X...", "XXXXX"),
    )

    /** Capitals, for Shift. The prototype only draws lower case; these follow the same grid. */
    private val UPPER: Map<String, List<String>> = mapOf(
        "A" to listOf(".XXX.", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"), "B" to listOf("XXXX.", "X...X", "X...X", "XXXX.", "X...X", "X...X", "XXXX."),
        "C" to listOf(".XXX.", "X...X", "X....", "X....", "X....", "X...X", ".XXX."), "D" to listOf("XXXX.", "X...X", "X...X", "X...X", "X...X", "X...X", "XXXX."),
        "E" to listOf("XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "XXXXX"), "F" to listOf("XXXXX", "X....", "X....", "XXXX.", "X....", "X....", "X...."),
        "G" to listOf(".XXX.", "X...X", "X....", "X.XXX", "X...X", "X...X", ".XXX."), "H" to listOf("X...X", "X...X", "X...X", "XXXXX", "X...X", "X...X", "X...X"),
        "I" to listOf(".XXX.", "..X..", "..X..", "..X..", "..X..", "..X..", ".XXX."), "J" to listOf("..XXX", "...X.", "...X.", "...X.", "...X.", "X..X.", ".XX.."),
        "K" to listOf("X...X", "X..X.", "X.X..", "XX...", "X.X..", "X..X.", "X...X"), "L" to listOf("X....", "X....", "X....", "X....", "X....", "X....", "XXXXX"),
        "M" to listOf("X...X", "XX.XX", "X.X.X", "X.X.X", "X...X", "X...X", "X...X"), "N" to listOf("X...X", "XX..X", "X.X.X", "X..XX", "X...X", "X...X", "X...X"),
        "O" to listOf(".XXX.", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."), "P" to listOf("XXXX.", "X...X", "X...X", "XXXX.", "X....", "X....", "X...."),
        "Q" to listOf(".XXX.", "X...X", "X...X", "X...X", "X.X.X", "X..X.", ".XX.X"), "R" to listOf("XXXX.", "X...X", "X...X", "XXXX.", "X.X..", "X..X.", "X...X"),
        "S" to listOf(".XXXX", "X....", "X....", ".XXX.", "....X", "....X", "XXXX."), "T" to listOf("XXXXX", "..X..", "..X..", "..X..", "..X..", "..X..", "..X.."),
        "U" to listOf("X...X", "X...X", "X...X", "X...X", "X...X", "X...X", ".XXX."), "V" to listOf("X...X", "X...X", "X...X", "X...X", "X...X", ".X.X.", "..X.."),
        "W" to listOf("X...X", "X...X", "X...X", "X.X.X", "X.X.X", "XX.XX", "X...X"), "X" to listOf("X...X", "X...X", ".X.X.", "..X..", ".X.X.", "X...X", "X...X"),
        "Y" to listOf("X...X", "X...X", ".X.X.", "..X..", "..X..", "..X..", "..X.."), "Z" to listOf("XXXXX", "....X", "...X.", "..X..", ".X...", "X....", "XXXXX"),
    )

    private val DIGITS: Map<String, List<String>> = mapOf(
        "0" to listOf(".XXX.", "X...X", "X..XX", "X.X.X", "XX..X", "X...X", ".XXX."), "1" to listOf("..X..", ".XX..", "..X..", "..X..", "..X..", "..X..", ".XXX."),
        "2" to listOf(".XXX.", "X...X", "....X", "...X.", "..X..", ".X...", "XXXXX"), "3" to listOf("XXXXX", "...X.", "..X..", "...X.", "....X", "X...X", ".XXX."),
        "4" to listOf("...X.", "..XX.", ".X.X.", "X..X.", "XXXXX", "...X.", "...X."), "5" to listOf("XXXXX", "X....", "XXXX.", "....X", "....X", "X...X", ".XXX."),
        "6" to listOf("..XX.", ".X...", "X....", "XXXX.", "X...X", "X...X", ".XXX."), "7" to listOf("XXXXX", "....X", "...X.", "..X..", ".X...", ".X...", ".X..."),
        "8" to listOf(".XXX.", "X...X", "X...X", ".XXX.", "X...X", "X...X", ".XXX."), "9" to listOf(".XXX.", "X...X", "X...X", ".XXXX", "....X", "...X.", ".XX.."),
    )

    /** The icons and the words the prototype cut by hand. The keys' own names (the shift arrow, delete) are the icons of the key set. */
    private val ICONS: Map<String, List<String>> = mapOf(
        "SHIFT" to listOf("...X...", "..X.X..", ".X...X.", "XX...XX", "..X.X..", "..X.X..", "..XXX.."),
        "SHIFT_ON" to listOf("...X...", "..XXX..", ".XXXXX.", "XXXXXXX", "..XXX..", "..XXX..", "..XXX.."),
        "CAPS_LOCK" to listOf("...X...", "..XXX..", ".XXXXX.", "XXXXXXX", "..XXX..", "..XXX..", ".......", "XXXXXXX"),
        "DELETE" to listOf("..XXXXXXX", ".XX.....X", "XX.X.X..X", "X...X...X", "XX.X.X..X", ".XX.....X", "..XXXXXXX"),
        "GLOBE" to listOf(".XXXXX.", "X..X..X", "X..X..X", "XXXXXXX", "X..X..X", "X..X..X", ".XXXXX."),
        "確認" to listOf("......X", "......X", "..X...X", ".XX...X", "XXXXXXX", ".XX....", "..X...."),
        "符" to listOf(".X.X.", "XXXXX", ".X.X.", "XXXXX", ".X.X."),
        "ABC" to listOf(".X..XX...XX", "X.X.X.X.X..", "XXX.XX..X..", "X.X.X.X.X..", "X.X.XX...XX"),
        // The four tones of Zhuyin, cut large enough to read as a key: a caron, a grave, an acute and the dot.
        "ˇ" to listOf("XX.....XX", ".XX...XX.", "..XX.XX..", "...XXX..."),
        "ˋ" to listOf("XX....", ".XX...", "..XX..", "...XX.", "....XX"),
        "ˊ" to listOf("....XX", "...XX.", "..XX..", ".XX...", "XX...."),
        "˙" to listOf(".XX.", "XXXX", "XXXX", ".XX."),
    )

    /** The hand-cut pixels for a key's label, or null when it must be set in type. */
    fun bits(label: String): List<String>? = LOWER[label] ?: UPPER[label] ?: DIGITS[label] ?: ICONS[label]
    fun hasBits(label: String) = bits(label) != null

    /** The paw that marks a Japanese word, in blocks (x, y, w, h) of 2 points. */
    val PAW: List<IntArray> = listOf(intArrayOf(1, 2, 3, 2), intArrayOf(0, 1, 1, 1), intArrayOf(2, 0, 1, 1), intArrayOf(4, 1, 1, 1))

    /** A picture of one bit per pixel: [on] is row-major, [w] by [h]. */
    /**
     * [colour], when there is any, holds the colour of the pixels that are not ink (an emoji brought down to the grid of the type: the same
     * pixels, in their own colours); the pixel is set in [on] too, so it has its halo like any other.
     */
    class Mask(val w: Int, val h: Int, val on: BooleanArray, val colour: IntArray? = null) {
        /** The colour pixel [i] is drawn in: its own, or [ink]. */
        fun paint(i: Int, ink: Int): Int = colour?.get(i)?.takeIf { it != 0 } ?: ink

        operator fun get(x: Int, y: Int) = x in 0 until w && y in 0 until h && on[y * w + x]

        /** What a platform has made of this mask (pictures in colours), so it makes each only once. Not part of the mask. */
        val extras = HashMap<Any, Any>()
        var grownSquare: Mask? = null
        var grownRound: Mask? = null
    }

    /** The hand-cut [rows] (X is ink) as a mask. */
    fun mask(rows: List<String>): Mask {
        val w = rows.maxOf { it.length }
        val on = BooleanArray(w * rows.size)
        for (r in rows.indices) for (k in rows[r].indices) if (rows[r][k] == 'X') on[r * w + k] = true
        return Mask(w, rows.size, on)
    }

    /**
     * [mask] grown to make a clear edge that reads on any ground. Hand-cut pixels grow by one pixel all round (a square); set type
     * grows by the prototype's round halo, about three pixels. The result is [reach] pixels larger on every side.
     */
    fun grown(mask: Mask, round: Boolean): Mask {
        val cached = if (round) mask.grownRound else mask.grownSquare
        if (cached != null) return cached
        val reach = if (round) HALO_REACH else 1
        val w = mask.w + 2 * reach
        val h = mask.h + 2 * reach
        val on = BooleanArray(w * h)
        for (y in 0 until mask.h) for (x in 0 until mask.w) if (mask.on[y * mask.w + x]) {
            if (round) { for ((a, b) in HALO) on[(y + reach + b) * w + x + reach + a] = true }
            else for (dy in -1..1) for (dx in -1..1) on[(y + reach + dy) * w + x + reach + dx] = true
            on[(y + reach) * w + x + reach] = true
        }
        return Mask(w, h, on).also { if (round) mask.grownRound = it else mask.grownSquare = it }
    }

    fun reach(round: Boolean) = if (round) HALO_REACH else 1

    const val HALO_REACH = 3
    private val HALO: List<Pair<Int, Int>> = buildList {
        for (a in -3..3) for (b in -3..3) if ((a != 0 || b != 0) && a * a + b * b <= 10) add(a to b)
    }
}

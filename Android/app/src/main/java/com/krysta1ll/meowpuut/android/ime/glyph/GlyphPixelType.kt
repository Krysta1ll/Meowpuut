package com.krysta1ll.meowpuut.android.ime.glyph

/**
 * The pixel type of the skin. Two bitmap faces on a 12 pixel grid are drawn one after the other, then the system font:
 *  - face A (Cubic 11) draws what it has, then
 *  - face B (Fusion Pixel 12px, the Traditional variant) draws what A lacks, and
 *  - the system font draws the rest, cut to one bit as it always was.
 * Both faces are 11 pixels tall in a 12 pixel em, and a pixel is 100 font units, so at 12 px every outline lies on the pixel grid and
 * the picture needs no smoothing; A's ideographs sit one pixel lower than B's in their design, so A is drawn one pixel higher and the two
 * share a baseline and a height. Everything here is pure; the platform draws the runs (GlyphPixelFonts).
 */
internal object GlyphPixelType {
    /** The em of both faces, in pixels of the one-bit grid. */
    const val EM = 12
    /** A mask of pixel type is this many pixels tall: room for the tallest mark above and the deepest descender below. */
    const val ROWS = 14
    /** The row of the baseline, counted from the top, for face B; the ideographs of both faces then fill the rows 2 to 12. */
    const val BASELINE = 12
    /** Face A is drawn this many pixels higher than its own baseline puts it, so it lines up with face B. */
    const val FACE_A_RISE = 1

    /** Face numbers: 0 is A, 1 is B, and [SYSTEM] is the system font. */
    const val SYSTEM = -1

    /** An emoji: not cut to one bit, drawn in its own colours by the platform (iOS does the same), in a square [COLOUR_W] pixels wide. */
    const val COLOUR = -2
    const val COLOUR_W = 13

    /** The code points that are pictures when no pixel face has them (the ones that are plain symbols without U+FE0F are covered or fall back to one bit). */
    fun isEmoji(cp: Int): Boolean = cp in 0x1F000..0x1FAFF || EMOJI_BELOW.any { cp in it }

    /** The pictures below U+1F000 that are emoji without a selector (the rest of those blocks are plain symbols: a note, a star, an arrow). */
    private val EMOJI_BELOW: List<IntRange> = listOf(
        0x231A..0x231B, 0x23E9..0x23EC, 0x23F0..0x23F0, 0x23F3..0x23F3, 0x25FD..0x25FE, 0x2614..0x2615, 0x2648..0x2653, 0x267F..0x267F,
        0x2693..0x2693, 0x26A1..0x26A1, 0x26AA..0x26AB, 0x26BD..0x26BE, 0x26C4..0x26C5, 0x26CE..0x26CE, 0x26D4..0x26D4, 0x26EA..0x26EA,
        0x26F2..0x26F3, 0x26F5..0x26F5, 0x26FA..0x26FA, 0x26FD..0x26FD, 0x2705..0x2705, 0x270A..0x270B, 0x2728..0x2728, 0x274C..0x274C,
        0x274E..0x274E, 0x2753..0x2755, 0x2757..0x2757, 0x2795..0x2797, 0x27B0..0x27B0, 0x27BF..0x27BF, 0x2B1B..0x2B1C, 0x2B50..0x2B50,
        0x2B55..0x2B55, 0x3297..0x3297, 0x3299..0x3299,
    )

    internal fun isModifier(cp: Int) = cp in 0x1F3FB..0x1F3FF
    internal fun isRegional(cp: Int) = cp in 0x1F1E6..0x1F1FF

    fun baselineRow(face: Int): Int = if (face == 0) BASELINE - FACE_A_RISE else BASELINE
}

/** The code points a face draws, as sorted inclusive ranges (the `.cov` files the build script writes: one `HEX` or `HEX-HEX` per line). */
internal class GlyphCoverage(private val lo: IntArray, private val hi: IntArray) {
    val size: Int get() = lo.indices.sumOf { hi[it] - lo[it] + 1 }

    fun has(codePoint: Int): Boolean {
        var a = 0
        var b = lo.size - 1
        while (a <= b) {
            val mid = (a + b) ushr 1
            when {
                codePoint < lo[mid] -> b = mid - 1
                codePoint > hi[mid] -> a = mid + 1
                else -> return true
            }
        }
        return false
    }

    companion object {
        val NONE = GlyphCoverage(IntArray(0), IntArray(0))

        fun parse(text: String): GlyphCoverage {
            val los = ArrayList<Int>()
            val his = ArrayList<Int>()
            for (line in text.lineSequence()) {
                val s = line.trim()
                if (s.isEmpty()) continue
                val dash = s.indexOf('-')
                val a = (if (dash < 0) s else s.substring(0, dash)).toIntOrNull(16) ?: continue
                val b = if (dash < 0) a else s.substring(dash + 1).toIntOrNull(16) ?: continue
                los += a; his += b
            }
            return GlyphCoverage(los.toIntArray(), his.toIntArray())
        }
    }
}

/** A stretch of text drawn in one face. */
internal class FaceRun(val face: Int, val text: String)

/** Which face draws each character: the first of the chain that has it, else the system font. */
internal class GlyphFontChain(private val faces: List<GlyphCoverage>) {
    fun faceOf(codePoint: Int): Int {
        for ((i, c) in faces.withIndex()) if (c.has(codePoint)) return i
        return GlyphPixelType.SYSTEM
    }

    /** The text cut into runs of one face each (a variation selector or a joiner stays with the character before it). */
    fun plan(text: String): List<FaceRun> {
        val runs = ArrayList<FaceRun>(2)
        var face = Int.MIN_VALUE
        val run = StringBuilder()
        var i = 0
        var last = GlyphPixelType.SYSTEM
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            val after = if (i + n < text.length) text.codePointAt(i + n) else -1
            if (cp != 0x200D && cp !in 0xFE00..0xFE0F && (after == 0xFE0F && cp > 0x7F || faceOf(cp) == GlyphPixelType.SYSTEM && GlyphPixelType.isEmoji(cp) || after == 0xFE0F && cp in 0x30..0x39)) {
                // A picture: it stands alone with its selectors, skin tone, joined parts or flag pair.
                if (run.isNotEmpty()) { runs += FaceRun(face, run.toString()); run.setLength(0) }
                val end = emojiEnd(text, i)
                runs += FaceRun(GlyphPixelType.COLOUR, text.substring(i, end))
                i = end
                face = Int.MIN_VALUE
                continue
            }
            val f = if (cp == 0x200D || cp in 0xFE00..0xFE0F) last else faceOf(cp)
            if (f != face && run.isNotEmpty()) { runs += FaceRun(face, run.toString()); run.setLength(0) }
            face = f
            last = f
            run.append(text, i, i + n)
            i += n
        }
        if (run.isNotEmpty()) runs += FaceRun(face, run.toString())
        return runs
    }

    /** Where the picture that begins at [start] ends: selectors, a skin tone, a keycap, parts joined by U+200D, or the second half of a flag. */
    private fun emojiEnd(text: String, start: Int): Int {
        var i = start
        val first = text.codePointAt(i)
        i += Character.charCount(first)
        if (GlyphPixelType.isRegional(first) && i < text.length && GlyphPixelType.isRegional(text.codePointAt(i))) i += Character.charCount(text.codePointAt(i))
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val n = Character.charCount(cp)
            if (cp in 0xFE00..0xFE0F || cp == 0x20E3 || GlyphPixelType.isModifier(cp)) { i += n; continue }
            if (cp == 0x200D && i + n < text.length) { i += n + Character.charCount(text.codePointAt(i + n)); continue }
            break
        }
        return i
    }

    /** The share of the characters of [text] that a bitmap face draws. */
    fun drawnShare(text: String): Double {
        var all = 0
        var drawn = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            all++
            if (faceOf(cp) != GlyphPixelType.SYSTEM) drawn++
        }
        return if (all == 0) 1.0 else drawn.toDouble() / all
    }
}

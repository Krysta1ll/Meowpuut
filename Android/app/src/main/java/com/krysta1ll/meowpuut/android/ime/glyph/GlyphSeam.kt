package com.krysta1ll.meowpuut.android.ime.glyph

/** How the paper breaks up: a threshold for each 2 point block (the prototype's GRAINS). */
internal enum class Grain { BAYER, ROWS, COLS, NOISE }

/** Which far layer sends its tallest summits (or towers) through the edge, from what height (a part of the layer's range) and how much taller. */
internal data class SpireSpec(val back: Int, val over: Double, val gain: Double)

/**
 * One place's way of meeting what is above it. [band] is how long the thinning is, in points; [inner] how far below the top of the keys it
 * ends; [wander] how much its lower edge billows; [leak] how many stars have got out above the edge; [rise] how much higher the disc stands.
 */
internal data class SeamSpec(
    val grain: Grain, val band: Double, val inner: Double, val wander: Double = 0.0, val leak: Double = 0.0,
    val spire: SpireSpec? = null, val rise: Double = 0.0,
)

internal object GlyphSeam {
    /**
     * The strip the picture paints above the keyboard's keys, over the app, in points. The decision is none: no height is added, the
     * keyboard is as tall as every other skin's. It stays a parameter ([GlyphLayout.lift]) for the way that was set aside: a strip of 20
     * points laid over the app, which needs the window and its insets to make room for it.
     */
    const val LIFT = 0.0

    /** The sky the keyboard already has above its candidate bar, which the seam thins into, in points. */
    const val INSET = 14.0

    /** Blocks of the seam are this many points square. */
    const val BLOCK = 2.0

    private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5).map { (it + 0.5) / 16 }.toDoubleArray()

    fun grain(g: Grain, x: Int, y: Int): Double = when (g) {
        Grain.BAYER -> BAYER[(y and 3) * 4 + (x and 3)]
        Grain.ROWS -> 0.42 * GlyphNoise.h2(3, y) + 0.58 * GlyphNoise.h2((x + y * 5) shr 2, y + 50)          // mist lies level, in short broken strokes
        Grain.COLS -> 0.08 + 0.67 * GlyphNoise.h2(x, 7) + 0.25 * BAYER[(y and 3) * 4 + (x and 3)]            // rain down the stems
        Grain.NOISE -> GlyphNoise.h2(x, y + 900)                                                              // snow, dust
    }

    val SEAMS: Map<String, SeamSpec> = mapOf(
        "山野" to SeamSpec(Grain.BAYER, band = 26.0, inner = 12.0, wander = 8.0),
        "海" to SeamSpec(Grain.ROWS, band = 22.0, inner = 8.0),
        "街" to SeamSpec(Grain.BAYER, band = 16.0, inner = 6.0, leak = 1.0, spire = SpireSpec(0, 0.8, 3.5)),
        "竹林" to SeamSpec(Grain.COLS, band = 26.0, inner = 12.0, leak = 0.4),
        "雪山" to SeamSpec(Grain.NOISE, band = 24.0, inner = 10.0, wander = 3.0, leak = 0.6, spire = SpireSpec(0, 0.66, 3.3)),
        "月" to SeamSpec(Grain.NOISE, band = 30.0, inner = 14.0, leak = 2.0, rise = 10.0),
        "富士" to SeamSpec(Grain.BAYER, band = 24.0, inner = 10.0, wander = 6.0),
        "香港" to SeamSpec(Grain.BAYER, band = 16.0, inner = 6.0, leak = 0.6, spire = SpireSpec(1, 0.8, 2.6)),
        "火山" to SeamSpec(Grain.NOISE, band = 26.0, inner = 12.0),
        "沙漠" to SeamSpec(Grain.ROWS, band = 20.0, inner = 8.0),
        "紅葉" to SeamSpec(Grain.COLS, band = 24.0, inner = 12.0),
        "月球" to SeamSpec(Grain.NOISE, band = 30.0, inner = 14.0, leak = 2.0),
    )

    /** The strip above the keys the picture paints, 0 when there is none (the safe drawing and a floating card have no seam at all). */
    fun lift(opaque: Boolean, floating: Boolean): Double = if (opaque || floating) 0.0 else LIFT

    /** The sky inside the keyboard the seam thins into. */
    fun inset(opaque: Boolean, floating: Boolean): Double = if (opaque || floating) 0.0 else INSET
}

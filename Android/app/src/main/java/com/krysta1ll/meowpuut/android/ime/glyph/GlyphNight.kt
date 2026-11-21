package com.krysta1ll.meowpuut.android.ime.glyph

/**
 * 字景 at night, with depth. By day the paper is the light: what is empty is bright and the type is the shade. At night the paper is black, so a
 * place drawn the same way is a few light marks in a void (snow, sand and sky are simply nothing). So the night has its own way (the prototype's
 * NIGHT()): the sky glows toward the horizon and round the moon and every place has stars; each plane of land has a body (a screen of the night's
 * air, close for what is far and thin for what is near); the ground lies in the moon's light; the type is moonlit, bluer and dimmer far off; and
 * each place has something of its own (fireflies, the aurora, the Milky Way, the crater's glow, the city's glow).
 */
internal object GlyphNight {
    /** The night's colours (the paper and the ink are unchanged): air, pale, moonlight, warm. */
    const val AIR = 0xFF2B3554.toInt()
    const val PALE = 0xFF4A5C8C.toInt()
    const val MOON = 0xFF8FA0C8.toInt()
    const val WARM = 0xFF3B3850.toInt()
    const val FIREFLY = 0xFFE4F58A.toInt()
    const val CRATER = 0xFF8A3A22.toInt()

    /** What a place has at night: its stars (null: as the place has them, or 0.8), a glow of its own colour (0: none, space has no air), and its lights. */
    class Place(val stars: Double? = null, val glow: Int? = null, val noGlow: Boolean = false, val fireflies: Int = 0, val aurora: Boolean = false, val crater: Boolean = false, val milky: Boolean = false)

    private val PLACES = mapOf(
        "山野" to Place(fireflies = 9),
        "海" to Place(),
        "街" to Place(glow = 0xFF3D2A4C.toInt(), stars = 0.3),
        "竹林" to Place(fireflies = 12, stars = 0.5),
        "雪山" to Place(aurora = true, stars = 1.0),
        "月" to Place(stars = 1.6),
        "富士" to Place(stars = 0.9),
        "香港" to Place(glow = 0xFF43294A.toInt(), stars = 0.2),
        "火山" to Place(crater = true, stars = 0.7),
        "沙漠" to Place(milky = true, stars = 1.1),
        "紅葉" to Place(fireflies = 7, stars = 0.6),
        "月球" to Place(noGlow = true, stars = 1.6, milky = true),
    )
    private val NONE = Place()

    fun of(scene: String): Place = PLACES[scene] ?: NONE

    /** How much of the ground is a screen of dots in the moon's light, and of which colour. */
    fun ground(kind: GroundKind): Pair<Double, Int> = when (kind) {
        GroundKind.SNOW -> 0.62 to PALE
        GroundKind.REGOLITH -> 0.5 to PALE
        GroundKind.SAND -> 0.44 to WARM
        GroundKind.SEA -> 0.28 to AIR
        GroundKind.PLAIN -> 0.26 to AIR
        GroundKind.STREET -> 0.14 to AIR
        else -> 0.2 to AIR
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        fun ch(s: Int) = Math.floor(((a shr s and 255) + ((b shr s and 255) - (a shr s and 255)) * t + 0.5)).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** The type's colour by its dimness (0 near .. 1 far): from the ink toward the moonlight, then toward the air. */
    fun ink(ink: Int, dim: Double): Int =
        if (dim <= 0.5) mix(ink, MOON, Math.floor(dim * 20 + 0.5) / 10) else mix(MOON, AIR, Math.floor((dim - 0.5) * 12 + 0.5) / 10)

    /** The same, for an accent (a place's own ink): only the far ones go toward the air. */
    fun accent(color: Int, dim: Double, firm: Boolean): Int = if (dim > 0.3 && !firm) mix(color, AIR, Math.floor(dim * 5 + 0.5) / 10) else color
}

package com.krysta1ll.meowpuut.android.ime.glyph

/** How a layer of the land is built. */
internal enum class LayerKind { LAND, SKYLINE, STEMS }

internal enum class LandShape { HILLS, PEAKS, ISLAND, FLAT }
internal enum class TreeKind { ROUND, PINE }

/**
 * One layer of the picture, far to near. cell is the size of its type in points; far layers are small and pale, near ones
 * large and dark. Heights (base, amp) are parts of the picture; the keyboard frames them (GlyphScape.wear).
 */
internal data class LayerSpec(
    val kind: LayerKind,
    val cell: Int,
    val pool: String,
    val seed: Int,
    val speed: Double,
    val tone: Double,
    val base: Double = 0.0,
    val amp: Double = 0.0,
    val freq: Double = 0.0,
    val edge: Double = 1.0,
    val shape: LandShape = LandShape.HILLS,
    val trees: TreeKind? = null,
    val sparse: Double = 0.0,
    val grass: Boolean = false,
    val haze: Double = 0.0,
    val marks: List<String>? = null,
    val cap: Double = 0.0,
    val bw: Int = 0,
    val stride: Int = 0,
    val w: Int = 0,
)

internal data class DiscSpec(val x: Double, val y: Double, val r: Double, val sun: Boolean = false, val crescent: Boolean = false)
internal data class FallSpec(val glyphs: String, val dx: Double, val dy: Double)
internal data class SkySpec(
    val floor: Double = 0.0,
    val clouds: Double = 0.0,
    val stars: Double = 0.0,
    val birds: Int = 0,
    val fall: FallSpec? = null,
    val disc: DiscSpec? = null,
)

internal enum class GroundKind { PLAIN, SEA, STREET, FLOOR, SNOW }
internal data class GroundSpec(val kind: GroundKind, val top: Double = 0.0, val mass: Double = 0.0, val pool: String = "wave")

/** WIDE: the picture as a picture of its own (the app's home and statistics): heights are parts of the picture, as the scenes give them. */
internal enum class Framing { ROWS, SKYLINE, WIDE }

internal data class SceneSpec(
    val name: String,
    /** How the keyboard frames this place; null is the skyline framing. */
    val kb: Framing? = null,
    val front: List<LayerSpec> = emptyList(),
    val back: List<LayerSpec> = emptyList(),
    /** The index, in front followed by back, of the layer the cats walk on. */
    val walk: Int = 0,
    val ground: GroundSpec? = null,
    val kbGround: GroundSpec? = null,
    val kbFirst: Boolean = false,
    val sky: SkySpec = SkySpec(),
    val rare: Boolean = false,
)

internal object GlyphScenes {
    /** Things the road passes now and then. X is solid; R is solid vermilion; o is a window or a lamp: paper by day, lit at night. */
    val STAMPS: Map<String, List<String>> = mapOf(
        "torii" to listOf("RRRRRRRRRRR", ".RRRRRRRRR.", "..R.....R..", ".RRRRRRRRR.", "..R.....R..", "..R.....R..", "..R.....R..", "..R.....R..", "..R.....R.."),
        "hut" to listOf("....X....", "...XXX...", "..XXXXX..", ".XXXXXXX.", "XXXXXXXXX", ".XoXXXoX.", ".XXXXXXX.", ".XXXoXXX.", ".XXXoXXX."),
        "light" to listOf("..X..", ".ooo.", ".XXX.", "..X..", ".XXX.", "..X..", ".XXX.", "..X..", ".XXX.", "XXXXX"),
        "lamp" to listOf(".o.", "XXX", ".X.", ".X.", ".X.", ".X.", ".X.", ".X."),
        "lantern" to listOf("..X..", ".XXX.", "XXXXX", ".XoX.", ".XXX.", "..X..", "..X..", ".XXX."),
        "snowman" to listOf(".XXX.", "X...X", "X.X.X", ".XXX.", "X...X", "X...X", "X...X", ".XXX."),
        "flag" to listOf("X....", "XRR..", "XRRRR", "XRR..", "X....", "X....", "X....", "X...."),
    )

    /** Signs down the sides of the town's dark fronts. */
    val SIGNS = listOf("ラーメン", "カラオケ", "ホテル", "スシ", "ネコ", "ニャー", "喵喵")

    /** Six places. The last one is the rare one. */
    val ALL: List<SceneSpec> = listOf(
        SceneSpec(
            name = "山野", kb = Framing.ROWS, walk = 0,
            ground = GroundSpec(GroundKind.PLAIN, top = 0.74, mass = 0.7, pool = "kata"),
            sky = SkySpec(floor = 0.34, clouds = 0.5, birds = 3, disc = DiscSpec(0.8, 0.2, 0.085, sun = true)),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 6, pool = "bopo", seed = 3, speed = 0.5, tone = 0.64, base = 0.74, amp = 0.26, freq = 0.026, edge = 1.0,
                    trees = TreeKind.ROUND, sparse = 0.88, grass = true, haze = 0.3, marks = listOf("hut", "torii")),
                LayerSpec(LayerKind.LAND, cell = 6, pool = "hira", seed = 11, speed = 0.24, tone = 0.44, base = 0.64, amp = 0.36, freq = 0.015, edge = 0.8,
                    haze = 0.2, trees = TreeKind.ROUND, sparse = 0.93),
                LayerSpec(LayerKind.LAND, cell = 4, pool = "pinyin", seed = 23, speed = 0.1, tone = 0.24, base = 0.56, amp = 0.46, freq = 0.008, edge = 0.5, haze = 0.17),
            ),
        ),
        SceneSpec(
            name = "海", walk = 0,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "bopo", seed = 4, speed = 0.5, tone = 0.4, base = 0.84, amp = 0.09, freq = 0.022, edge = 1.0,
                    grass = true, marks = listOf("light", "torii")),
            ),
            ground = GroundSpec(GroundKind.SEA, top = 0.5, pool = "wave"),
            sky = SkySpec(floor = 0.4, clouds = 0.38, birds = 4, disc = DiscSpec(0.62, 0.5, 0.12, sun = true)),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 4, pool = "pinyin", seed = 8, speed = 0.07, tone = 0.34, base = 0.5, amp = 0.2, freq = 0.006, edge = 0.7, shape = LandShape.ISLAND),
            ),
        ),
        SceneSpec(
            name = "街", kb = Framing.ROWS, walk = 0,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "kata", seed = 2, speed = 0.5, tone = 0.8, base = 0.8, amp = 0.0, freq = 0.0, edge = 1.0,
                    shape = LandShape.FLAT, marks = listOf("lamp")),
            ),
            ground = null, kbGround = GroundSpec(GroundKind.STREET),
            sky = SkySpec(floor = 0.3, clouds = 0.12, stars = 0.5, disc = DiscSpec(0.18, 0.17, 0.07, crescent = true)),
            back = listOf(
                LayerSpec(LayerKind.SKYLINE, cell = 6, pool = "kata", seed = 5, speed = 0.3, tone = 1.0, base = 0.8, amp = 0.5, bw = 18),
                LayerSpec(LayerKind.SKYLINE, cell = 4, pool = "window", seed = 9, speed = 0.14, tone = 0.4, base = 0.74, amp = 0.56, bw = 16),
            ),
        ),
        SceneSpec(
            name = "竹林", kb = Framing.ROWS, walk = 0,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "bopo", seed = 6, speed = 0.55, tone = 0.5, base = 0.86, amp = 0.04, freq = 0.03, edge = 1.0,
                    grass = true, marks = listOf("lantern", "torii")),
            ),
            ground = null, kbGround = GroundSpec(GroundKind.FLOOR), kbFirst = true,
            sky = SkySpec(stars = 0.35, fall = FallSpec("ノヽく", -1.5, 1.7)),
            back = listOf(
                LayerSpec(LayerKind.STEMS, cell = 8, pool = "bopo", seed = 3, speed = 0.55, tone = 1.0, stride = 22, w = 2),
                LayerSpec(LayerKind.STEMS, cell = 6, pool = "stem", seed = 7, speed = 0.3, tone = 0.56, stride = 13, w = 1),
                LayerSpec(LayerKind.STEMS, cell = 4, pool = "stem", seed = 12, speed = 0.15, tone = 0.3, stride = 9, w = 1),
            ),
        ),
        SceneSpec(
            name = "雪山", kb = Framing.ROWS, walk = 0,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "snow", seed = 14, speed = 0.5, tone = 0.08, base = 0.82, amp = 0.1, freq = 0.02, edge = 1.0,
                    trees = TreeKind.PINE, sparse = 0.84, marks = listOf("snowman", "hut")),
            ),
            ground = GroundSpec(GroundKind.SNOW, top = 0.8),
            sky = SkySpec(stars = 1.0, fall = FallSpec("・＊゜", -0.7, 2.3), disc = DiscSpec(0.76, 0.15, 0.06, sun = true)),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 6, pool = "rock", seed = 17, speed = 0.2, tone = 0.6, base = 0.8, amp = 0.56, freq = 0.019, edge = 1.0,
                    shape = LandShape.PEAKS, cap = 0.17),
                LayerSpec(LayerKind.LAND, cell = 4, pool = "pinyin", seed = 29, speed = 0.09, tone = 0.26, base = 0.72, amp = 0.52, freq = 0.007, edge = 0.6,
                    shape = LandShape.PEAKS, haze = 0.24, cap = 0.08),
            ),
        ),
        SceneSpec(
            name = "月", rare = true, walk = 0,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "dust", seed = 31, speed = 0.5, tone = 0.14, base = 0.86, amp = 0.05, freq = 0.012, edge = 1.0, marks = listOf("flag")),
            ),
            ground = GroundSpec(GroundKind.SNOW, top = 0.7),
            sky = SkySpec(stars = 1.6, fall = FallSpec("・˙", 0.4, -0.9), disc = DiscSpec(0.5, 0.44, 0.3, sun = true)),
            back = emptyList(),
        ),
    )

    val COMMON: List<Int> = ALL.indices.filter { !ALL[it].rare }
    val RARE: Int = ALL.indexOfFirst { it.rare }
}

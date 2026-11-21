package com.krysta1ll.meowpuut.android.ime.glyph

/** How a layer of the land is built. */
internal enum class LayerKind { LAND, SKYLINE, STEMS }

/** CONE: one mountain, alone and far off. DUNES: long backs and short faces. MESA: table rock. CRATERS: bowls with a raised lip. */
internal enum class LandShape { HILLS, PEAKS, ISLAND, FLAT, CONE, DUNES, MESA, CRATERS }
/** SAKURA and MAPLE are all colour: a crown of petals, or of leaves in three reds. */
internal enum class TreeKind { ROUND, PINE, SAKURA, MAPLE }

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
    /** CONE: where the mountain stands (a part of the width) and how wide its feet are; with [crater] it has a mouth, with [lava] it runs. */
    val at: Double = 0.0,
    val spread: Double = 0.0,
    val crater: Boolean = false,
    val lava: Boolean = false,
    /** DUNES: how far along a dune its crest is. */
    val crest: Double = 0.0,
    /** Something stands on the road every this many blocks, not now and then. */
    val every: Int = 0,
    /** A skyline's own signs, down the sides of its dark fronts, and the share of its towers that are landmarks. */
    val signs: List<String>? = null,
    val landmarks: Double = 0.0,
    /** The place's own ink (see [GlyphHues]) that some of this layer's type is set in, and how much of it. */
    val hue: String? = null,
    val hueShare: Double = 0.0,
    /** On a phone, no stamp stands within [avoid] (a part of the width) of [avoidAt] (a part of the width): the mountain is not built over. */
    val avoidAt: Double = -1.0,
    val avoid: Double = 0.0,
)

internal data class DiscSpec(
    val x: Double, val y: Double, val r: Double, val sun: Boolean = false, val crescent: Boolean = false,
    val hue: String? = null, val earth: Boolean = false,
)
/** What falls: [red] in the vermilion, [hue] in one ink of the place, or [hues] in several (an empty name is the vermilion). */
internal data class FallSpec(val glyphs: String, val dx: Double, val dy: Double, val red: Boolean = false, val hue: String? = null, val hues: List<String>? = null)
internal data class SkySpec(
    val floor: Double = 0.0,
    val clouds: Double = 0.0,
    val stars: Double = 0.0,
    val birds: Int = 0,
    val fall: FallSpec? = null,
    val disc: DiscSpec? = null,
    /** The place has a mountain that breathes out smoke (and throws stones). */
    val plume: Boolean = false,
    /** Tumbleweed rolls along the ridge. */
    val tumble: Boolean = false,
)

internal enum class GroundKind { PLAIN, SEA, STREET, FLOOR, SNOW, REGOLITH, SAND }
/** [lava]: the field has a river of fire; [reflect]: the sea has the mountain of that layer (1-based, in back) upside down in it; [red]: what has fallen is the trees' colour. */
internal data class GroundSpec(val kind: GroundKind, val top: Double = 0.0, val mass: Double = 0.0, val pool: String = "wave", val lava: Boolean = false, val reflect: Int = 0, val red: Boolean = false)

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
    /** How rare the place is, as a share of the places (0 for the common ones). */
    val rare: Double = 0.0,
    /** The place's own inks, by what they are used on ("grass", "leaf", "river", "water", "window", "pine"; "signs" takes several). */
    val tint: Map<String, List<String>> = emptyMap(),
    /** The cats wear suits here. */
    val suit: Boolean = false,
    /**
     * How tall the land stands above the cats' ridge, as a part of the prototype's: the prototype's keyboard has 30 points more sky above
     * its first row than a phone's candidate bar leaves, so the tall places (a mountain, a skyline) are drawn a little lower to stay whole.
     */
    val fit: Double = 1.0,
    /** How large the sun, moon or Earth is, as a part of the prototype's, where the sky above the ridge is too short to hold it whole. */
    val discFit: Double = 1.0,
    /** Where the sun, moon or Earth stands on a phone (a part of the width), when the bar's own type would be over it where the prototype puts it. */
    val discKbX: Double = 0.0,
    /** Whether the place is offered at all: the list of enabled places is this flag, one place at a time. */
    val enabled: Boolean = true,
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
        // The new places. G, Y, B, P, O, C, M are the place's own inks (green, gold, blue, pink, orange, cyan, magenta).
        "pagoda" to listOf(".....X.....", ".....X.....", "...XXXXX...", "....XXX....", "..XXXXXXX..", "....XoX....", ".XXXXXXXXX.", "....XXX....", "XXXXXXXXXXX", "...XXoXX...", "XXXXXXXXXXX", "...XXXXX...", "...XoXoX...", "...XXXXX...", "..XXXXXXX.."),
        "junk" to listOf("....R......", "...RR..R...", "..RRR.RR...", ".RRRR.RRR..", ".RRRR.RRR..", "....X..X...", "XXXXXXXXXXX", ".XXXXXXXXX.", "..XXXXXXX.."),
        "ferry" to listOf("...XXXXX...", "..XoXoXoX..", ".XXXXXXXXX.", "XXXXXXXXXXX", ".XXXXXXXXX."),
        "tram" to listOf("......X....", ".....X.....", ".XXXXXXXXX.", ".XooXooXoX.", ".XXXXXXXXX.", ".XooXooXoX.", ".XXXXXXXXX.", ".XRRRRRRRX.", ".XXXXXXXXX.", "..XX...XX.."),
        "neon" to listOf("XXXXXX.", "X.RRRR.", "X.RooR.", "X.RRRR.", "X.RooR.", "X.RRRR.", "X.RooR.", "X.RRRR.", "X......", "X......", "X......"),
        "neonM" to listOf("XXXXXX.", "X.MMMM.", "X.MooM.", "X.MMMM.", "X.MooM.", "X.MMMM.", "X......", "X......", "X......", "X......", "X......"),
        "neonC" to listOf(".XXXXXX", ".CCCC.X", ".CooC.X", ".CCCC.X", ".CooC.X", ".CCCC.X", ".CooC.X", ".CCCC.X", "......X", "......X", "......X"),
        "cactus" to listOf("...G...", "...G...", "G..G...", "G..G..G", "GGGG..G", "...GGGG", "...G...", "...G...", "...G..."),
        "lander" to listOf("...XXX...", "..XoXoX..", "..XXXXX..", ".XXXXXXX.", ".XYYYYYX.", ".XYYYYYX.", "X..X.X..X", "X.......X"),
        "rover" to listOf("....X....", "..XXXXX..", "XXXXXXXXX", ".X.X.X.X."),
        "wheel" to listOf("...XXXXX...", "..X..X..X..", ".X...X...X.", "X.X..X..X.X", "X..X.X.X..X", "XXXXXXXXXXX", "X..X.X.X..X", "X.X..X..X.X", ".X...X...X.", "..X..X..X..", "...XXXXX...", "....X.X....", "...X...X...", "..XXXXXXX.."),
        "deer" to listOf("......X.X.X", ".......XXX.", ".......XX..", "......XXXX.", "oXXXXXXX...", "XXXXXXXX...", ".XXXXXXX...", ".X.X..X.X..", ".X.X..X.X.."),
        "saguaro" to listOf("...P...", "...G...", "G..G...", "G..G..G", "G..G..G", "GGGGGGG", "...G...", "...G...", "...G...", "...G..."),
    )

    /** The inks a place may have besides the ink and the vermilion: by day, by night. The Earth alone is in all its colours (see GlyphScape.earth). */
    val HUES: Map<String, Pair<Int, Int>> = mapOf(
        "green" to (0xFF4E8B57.toInt() to 0xFF7CC08A.toInt()), "blue" to (0xFF2F6DA8.toInt() to 0xFF6FA9E6.toInt()),
        "pink" to (0xFFDE7C95.toInt() to 0xFFF29DB3.toInt()), "gold" to (0xFFD29A22.toInt() to 0xFFF3C64E.toInt()),
        "orange" to (0xFFDD7A2B.toInt() to 0xFFF39A4A.toInt()), "ice" to (0xFF6FA6CF.toInt() to 0xFFA6D1F0.toInt()),
        "cyan" to (0xFF1F97A3.toInt() to 0xFF55CAD6.toInt()), "magenta" to (0xFFC6427C.toInt() to 0xFFEE74AC.toInt()),
        "ochre" to (0xFFB98236.toInt() to 0xFFD9A65A.toInt()),
    )

    /** Hong Kong's signs: tea restaurants, ice rooms, mahjong, pharmacies. */
    val HK = listOf("茶餐廳", "冰室", "麻雀", "藥房", "酒家", "雲吞麵", "粥麵", "喵喵")

    /** Signs down the sides of the town's dark fronts. */
    val SIGNS = listOf("ラーメン", "カラオケ", "ホテル", "スシ", "ネコ", "ニャー", "喵喵")

    /** Six places. The last one is the rare one. */
    val ALL: List<SceneSpec> = listOf(
        SceneSpec(
            name = "山野", kb = Framing.ROWS, walk = 0, tint = mapOf("grass" to listOf("green"), "leaf" to listOf("green"), "river" to listOf("blue")),
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
            name = "海", walk = 0, tint = mapOf("water" to listOf("blue")),
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
            name = "街", kb = Framing.ROWS, walk = 0, tint = mapOf("window" to listOf("gold"), "signs" to listOf("magenta", "cyan", "gold")),
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
            name = "竹林", kb = Framing.ROWS, walk = 0, tint = mapOf("grass" to listOf("green")),
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "bopo", seed = 6, speed = 0.55, tone = 0.5, base = 0.86, amp = 0.04, freq = 0.03, edge = 1.0,
                    grass = true, marks = listOf("lantern", "torii")),
            ),
            ground = null, kbGround = GroundSpec(GroundKind.FLOOR), kbFirst = true,
            sky = SkySpec(stars = 0.35, fall = FallSpec("ノヽく", -1.5, 1.7)),
            back = listOf(
                LayerSpec(LayerKind.STEMS, cell = 8, pool = "bopo", seed = 3, speed = 0.55, tone = 1.0, stride = 22, w = 2, hue = "green"),
                LayerSpec(LayerKind.STEMS, cell = 6, pool = "stem", seed = 7, speed = 0.3, tone = 0.56, stride = 13, w = 1),
                LayerSpec(LayerKind.STEMS, cell = 4, pool = "stem", seed = 12, speed = 0.15, tone = 0.3, stride = 9, w = 1),
            ),
        ),
        SceneSpec(
            name = "雪山", kb = Framing.ROWS, walk = 0, tint = mapOf("pine" to listOf("green")),
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
                    shape = LandShape.PEAKS, haze = 0.24, cap = 0.08, hue = "ice", hueShare = 0.6),
            ),
        ),
        SceneSpec(
            name = "月", rare = 0.1, walk = 0,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "dust", seed = 31, speed = 0.5, tone = 0.14, base = 0.86, amp = 0.05, freq = 0.012, edge = 1.0, marks = listOf("flag")),
            ),
            ground = GroundSpec(GroundKind.SNOW, top = 0.7),
            sky = SkySpec(stars = 1.6, fall = FallSpec("・˙", 0.4, -0.9), disc = DiscSpec(0.5, 0.44, 0.3, sun = true, hue = "gold")),
            back = emptyList(),
        ),
        // 富士: the one mountain, snow on it, upside down in the lake; blossom on the near shore, and it is falling
        SceneSpec(
            name = "富士", kb = Framing.ROWS, walk = 0, fit = 0.88, tint = mapOf("water" to listOf("blue"), "grass" to listOf("green")),
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "bopo", seed = 61, speed = 0.5, tone = 0.5, base = 0.84, amp = 0.06, freq = 0.02, edge = 1.0,
                    grass = true, trees = TreeKind.SAKURA, sparse = 0.86, marks = listOf("torii", "lantern")),
            ),
            ground = GroundSpec(GroundKind.SEA, top = 0.62, pool = "wave", reflect = 2),
            sky = SkySpec(floor = 0.3, clouds = 0.3, birds = 2, fall = FallSpec("。・゜", -1.3, 1.5, red = true, hue = "pink"), disc = DiscSpec(0.18, 0.2, 0.062, sun = true)),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 6, pool = "hira", seed = 63, speed = 0.22, tone = 0.5, base = 0.62, amp = 0.08, freq = 0.02, edge = 1.0,
                    trees = TreeKind.SAKURA, sparse = 0.8, marks = listOf("pagoda"), every = 130, haze = 0.12, avoidAt = 0.6, avoid = 0.3),
                LayerSpec(LayerKind.LAND, cell = 4, pool = "rock", seed = 67, speed = 0.0, tone = 0.66, base = 0.62, amp = 0.54, edge = 1.0,
                    shape = LandShape.CONE, at = 0.6, spread = 1.55, cap = 0.32, hue = "blue", hueShare = 0.75),
            ),
        ),
        // 香港: the Peak, the towers under it, the harbour and what crosses it; the cats are on the far shore, among the signs
        SceneSpec(
            name = "香港", kb = Framing.ROWS, walk = 0, fit = 0.85, tint = mapOf("window" to listOf("gold"), "signs" to listOf("magenta", "cyan", "gold")),
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "kata", seed = 71, speed = 0.5, tone = 0.8, base = 0.82, amp = 0.0, freq = 0.0, edge = 1.0,
                    shape = LandShape.FLAT, marks = listOf("neon", "neonC", "tram", "neonM", "lamp", "neonC"), every = 44),
            ),
            ground = null, kbGround = GroundSpec(GroundKind.STREET),
            sky = SkySpec(floor = 0.24, clouds = 0.16, birds = 2, stars = 0.3, disc = DiscSpec(0.12, 0.15, 0.055, sun = true)),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 6, pool = "wave", seed = 73, speed = 0.36, tone = 0.16, base = 0.64, amp = 0.0, freq = 0.0, edge = 1.0,
                    shape = LandShape.FLAT, marks = listOf("junk", "wheel", "ferry"), every = 92, hue = "cyan", hueShare = 0.5),
                LayerSpec(LayerKind.SKYLINE, cell = 6, pool = "kata", seed = 75, speed = 0.2, tone = 1.0, base = 0.64, amp = 0.46, bw = 10, signs = HK, landmarks = 0.3),
                LayerSpec(LayerKind.SKYLINE, cell = 4, pool = "window", seed = 77, speed = 0.12, tone = 0.4, base = 0.61, amp = 0.46, bw = 9),
                LayerSpec(LayerKind.LAND, cell = 4, pool = "pinyin", seed = 79, speed = 0.06, tone = 0.2, base = 0.56, amp = 0.44, freq = 0.006, edge = 0.6, haze = 0.3),
            ),
        ),
        // 火山: it is awake. What runs down it is the only colour, and it reaches the feet
        SceneSpec(
            name = "火山", kb = Framing.ROWS, walk = 0, fit = 0.9,
            ground = GroundSpec(GroundKind.PLAIN, top = 0.78, mass = 0.6, pool = "kata", lava = true),
            sky = SkySpec(floor = 0.42, clouds = 0.5, stars = 0.4, plume = true),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 6, pool = "rock", seed = 93, speed = 0.5, tone = 0.8, base = 0.78, amp = 0.14, freq = 0.02, edge = 1.0, haze = 0.2, marks = listOf("hut")),
                LayerSpec(LayerKind.LAND, cell = 4, pool = "rock", seed = 95, speed = 0.0, tone = 0.6, base = 0.78, amp = 0.6, edge = 1.0,
                    shape = LandShape.CONE, at = 0.56, spread = 1.2, crater = true, lava = true),
            ),
        ),
        // 沙漠: dunes with one side in the light, table rock far off, a sun too large
        SceneSpec(
            name = "沙漠", kb = Framing.ROWS, walk = 0, fit = 0.8, discFit = 0.8,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "dust", seed = 101, speed = 0.5, tone = 0.3, base = 0.86, amp = 0.1, freq = 0.014, edge = 1.0,
                    shape = LandShape.DUNES, crest = 0.82, marks = listOf("cactus", "saguaro"), every = 84),
            ),
            ground = GroundSpec(GroundKind.SAND, top = 0.8), sky = SkySpec(birds = 1, tumble = true, disc = DiscSpec(0.72, 0.24, 0.12, sun = true)),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 6, pool = "dust", seed = 103, speed = 0.22, tone = 0.24, base = 0.8, amp = 0.34, freq = 0.012, edge = 1.0, shape = LandShape.DUNES, crest = 0.84),
                LayerSpec(LayerKind.LAND, cell = 6, pool = "rock", seed = 105, speed = 0.1, tone = 0.56, base = 0.72, amp = 0.46, freq = 0.006, edge = 1.0, shape = LandShape.MESA),
                LayerSpec(LayerKind.LAND, cell = 4, pool = "pinyin", seed = 107, speed = 0.06, tone = 0.2, base = 0.68, amp = 0.2, freq = 0.004, edge = 0.6, shape = LandShape.DUNES),
            ),
        ),
        // 紅葉: every tree is the one colour, and so is what is coming down
        SceneSpec(
            name = "紅葉", kb = Framing.ROWS, walk = 0, fit = 0.9,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "bopo", seed = 111, speed = 0.55, tone = 0.5, base = 0.86, amp = 0.05, freq = 0.03, edge = 1.0,
                    grass = true, trees = TreeKind.MAPLE, sparse = 0.8, marks = listOf("deer", "lantern", "deer", "torii"), every = 96),
            ),
            ground = null, kbGround = GroundSpec(GroundKind.FLOOR, red = true), kbFirst = true,
            sky = SkySpec(clouds = 0.2, floor = 0.3, fall = FallSpec("ヽノく、", -1.6, 1.3, red = true, hues = listOf("", "orange", "gold")), disc = DiscSpec(0.84, 0.2, 0.06, sun = true)),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 6, pool = "hira", seed = 113, speed = 0.3, tone = 0.5, base = 0.8, amp = 0.2, freq = 0.02, edge = 1.0, trees = TreeKind.MAPLE, sparse = 0.66, haze = 0.3),
                LayerSpec(LayerKind.LAND, cell = 6, pool = "hira", seed = 115, speed = 0.16, tone = 0.36, base = 0.7, amp = 0.34, freq = 0.012, edge = 0.8, trees = TreeKind.MAPLE, sparse = 0.74, haze = 0.2),
                LayerSpec(LayerKind.LAND, cell = 4, pool = "pinyin", seed = 117, speed = 0.08, tone = 0.22, base = 0.6, amp = 0.44, freq = 0.007, edge = 0.5, haze = 0.17),
            ),
        ),
        // 月球: rarer than the moon itself. The Earth is up, in its own colours; the two of them are in suits
        SceneSpec(
            name = "月球", rare = 0.04, kb = Framing.ROWS, walk = 0, suit = true, fit = 0.7, discFit = 0.7, discKbX = 0.5,
            front = listOf(
                LayerSpec(LayerKind.LAND, cell = 8, pool = "dust", seed = 81, speed = 0.5, tone = 0.12, base = 0.87, amp = 0.07, freq = 0.01, edge = 1.0,
                    shape = LandShape.CRATERS, marks = listOf("flag", "lander", "rover"), every = 104),
            ),
            ground = GroundSpec(GroundKind.REGOLITH, top = 0.68), sky = SkySpec(stars = 2.0, disc = DiscSpec(0.68, 0.39, 0.2, earth = true)),
            back = listOf(
                LayerSpec(LayerKind.LAND, cell = 6, pool = "dust", seed = 83, speed = 0.2, tone = 0.2, base = 0.68, amp = 0.14, freq = 0.006, edge = 1.0, shape = LandShape.CRATERS),
                LayerSpec(LayerKind.LAND, cell = 4, pool = "pinyin", seed = 85, speed = 0.08, tone = 0.14, base = 0.63, amp = 0.2, freq = 0.012, edge = 0.6),
            ),
        ),
    )

    val COMMON: List<Int> = ALL.indices.filter { ALL[it].rare == 0.0 && ALL[it].enabled }
    /** The rare places in order, each with the share of the places it takes (the Moon a tenth, the Moon seen from the Moon four in a hundred). */
    val RARES: List<Int> = ALL.indices.filter { ALL[it].rare > 0.0 && ALL[it].enabled }
    /** Where each rare place's share begins (the shares are laid end to end from 0), for the draw. */
    fun rareFrom(n: Int): Double = RARES.take(n).sumOf { ALL[it].rare }
}

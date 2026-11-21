package com.krysta1ll.meowpuut.android.ime.glyph

import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.round
import kotlin.math.floor

/**
 * The type the land is set in. Every pool is sorted from its lightest glyph to its heaviest, so a tone (0 pale, 1 dark)
 * picks a weight. The order was measured offline (the ink each glyph leaves in a square cell, set in a CJK sans) and is
 * written down here; nothing is measured at run time. The "pixel" pool holds the glyphs that fill a cell the way a pixel
 * does: ink that is square, reaches most of the cell, sits in the middle and spreads evenly over the four quarters,
 * the best few of each weight (design-preview/specs/glyph-skin.template.html, measurePools).
 *
 * [hasGlyph] lets the platform drop a glyph its fonts cannot draw, so no cell ever shows a missing-character box.
 */
internal class GlyphPools(hasGlyph: (String) -> Boolean = { true }) {
    private val lists = HashMap<String, List<String>>()
    private val ranks = HashMap<String, Map<String, Double>>()
    private val poems = HashMap<String, List<String>>()

    init {
        for ((name, text) in ORDER) {
            val seen = HashSet<String>()
            val list = ArrayList<String>()
            for (ch in text) {
                val s = ch.toString()
                if (seen.add(s) && hasGlyph(s)) list += s
            }
            if (list.isEmpty()) list += "・"
            lists[name] = list
            ranks[name] = list.withIndex().associate { (i, ch) -> ch to i.toDouble() / maxOf(1, list.size - 1) }
        }
        for ((name, text) in LINES) poems[name] = text.map { it.toString() }.filter { ranks.getValue(name).containsKey(it) }
    }

    /** What a glitch turns the type into: the look of text read in the wrong encoding, an input method's own kind of ghost. */
    val moji: List<String> = MOJI.map { it.toString() }.filter(hasGlyph).ifEmpty { listOf("縺") }

    fun has(pool: String) = lists.containsKey(pool)
    fun list(pool: String): List<String> = lists.getValue(pool)
    fun rank(pool: String, glyph: String): Double? = ranks[pool]?.get(glyph)
    /** The line of verse a pool's cells read on from, or null for the pools that have none. */
    fun poem(pool: String): List<String>? = poems[pool]?.takeIf { it.isNotEmpty() }

    /** The glyph of [pool] at tone [t]; the cell's own place in the world ([hash], 0..1) picks among its neighbours. */
    fun glyph(pool: String, t: Double, hash: Double): String {
        val list = lists.getValue(pool)
        val n = list.size
        return list[maxOf(0, minOf(n - 1, round(t * (n - 1) + (hash - 0.5) * n * 0.45)))]
    }

    /** One pool picked by weight from the part's shares. Scripts come in patches a few cells wide, as a line of text does. */
    fun script(part: String, wx: Int, row: Int, seed: Int): String {
        val shares = SHARES[part] ?: return part
        var u = h2(floor((wx + (row shr 1) * 3) / 6.0).toInt(), (row shr 1) * 31 + seed + 700)
        for ((name, share) in shares) {
            if (u < share) return name
            u -= share
        }
        return shares[0].first
    }

    companion object {
        private const val BOPO = "˙ˊˋˇㄧㄑㄟㄚㄏㄣㄒㄥㄅㄐㄋㄔㄙㄈㄕㄦㄇㄩㄨㄍㄗㄘㄎㄛㄜㄢㄤㄉㄊㄆㄡㄠㄝㄌㄓㄞㄖ"
        private const val PINYIN = "rlciíìtjfzīsǐxunoyúùehüǖaǚǘǜūwkǔóòèéōǒáàēdmqbpěāǎg"
        private const val HIRA = "へくしりいつこてとうよにんらふけろすそたえひさちやかせもれわのみるむなをまはきゆめねおほあぬ"
        private const val KATA = "ノヘトンニイリレソフハシナツワクアコメヒユウエケテラヤミマスムヲルチヌタサキオネロカモセヨホ"

        /** Pool name to glyphs, lightest first. */
        val ORDER: List<Pair<String, String>> = listOf(
            "bopo" to BOPO, "pinyin" to PINYIN, "hira" to HIRA, "kata" to KATA,
            "dust" to "˙ˊˋ・、゛゜。",
            "rise" to "ˊ／∕ノㄥ", "fall" to "ˋヽ∖＼ㄟ", "flat" to "‐＿‾ㄧー一￣", "grass" to "ˇヾㄑｖㄐㄩｗψ",
            "leaf" to "゜。◦◯○ㄛ〇の●", "wave" to "∽⌒〜～≈しㄙつ", "cloud" to "∽⌒︵っへつの", "star" to "˙・⁺∗＊✧✦",
            "snow" to "˙・、゜。＊", "window" to "ㅁ口日目田回", "rock" to "ㄟ∠∧ㄏヘへㄙ人入", "stem" to "│ㄧǀ｜丨",
            "blade" to "ヽㄑノ㇏〱く",
            "pixel" to "・ㄑㄚㅁ○ㄐ□ㄇㄨㄍㄛㄤㄡ〇ㄝㄌㄞ円",
        )

        private const val MOJI = "縺繧繝蜷荳譁ｧｨｩ\uFFFD▚▞▒"

        /** The verses the cells read on from: the syllables of a quiet poem about the moon, and a cat. */
        val LINES: Map<String, String> = mapOf(
            "bopo" to "ㄔㄨㄤˊㄑㄧㄢˊㄇㄧㄥˊㄩㄝˋㄍㄨㄤㄧˊㄕˋㄉㄧˋㄕㄤˋㄕㄨㄤㄐㄩˇㄊㄡˊㄨㄤˋㄇㄧㄥˊㄩㄝˋㄉㄧㄊㄡˊㄙㄍㄨˋㄒㄧㄤㄇㄧㄠ",
            "pinyin" to "chuángqiánmíngyuèguāngyíshìdìshàngshuāngjǔtóuwàngmíngyuèdītóusīgùxiāngmiāo",
        )

        /**
         * Which script a part is set in. This is a Zhuyin and Pinyin keyboard, so the land is mostly pixel glyphs, then Zhuyin
         * and Pinyin; kana is a guest, about one cell in thirty. A layer names its part and the part sets the shares.
         */
        val SHARES: Map<String, List<Pair<String, Double>>> = mapOf(
            "bopo" to listOf("pixel" to 0.78, "bopo" to 0.12, "pinyin" to 0.07, "hira" to 0.03),
            "hira" to listOf("pixel" to 0.74, "bopo" to 0.10, "pinyin" to 0.13, "kata" to 0.03),
            "pinyin" to listOf("pixel" to 0.62, "pinyin" to 0.38),
            "kata" to listOf("pixel" to 0.80, "bopo" to 0.10, "pinyin" to 0.07, "kata" to 0.03),
        )

        /** A pool drawn in Latin letters: set larger in a monospaced face, since a letter sits small in its square. */
        fun isMono(pool: String) = pool == "pinyin"

        /** The size of the type in a cell of this pool, as a share of the cell. */
        fun faceScale(pool: String) = if (pool == "pinyin") 1.2 else 1.05
    }
}

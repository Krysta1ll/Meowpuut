package com.krysta1ll.meowpuut.android.engine

import kotlin.math.min

/**
 * Pinyin syllable disambiguation and progressive syllable-locking helpers
 * for nine-key (`2`–`9`) input. Mirrors iOS `NineKeySyllables.swift`.
 */
object NineKeySyllables {
    private const val DIGIT_MAP = "22233344455566677778889999"

    private val singleInitials: Map<Char, List<String>> = mapOf(
        '2' to listOf("b", "a", "c"),
        '3' to listOf("d", "f", "e"),
        '4' to listOf("h", "g"),
        '5' to listOf("l", "j", "k"),
        '6' to listOf("n", "m", "o"),
        '7' to listOf("s", "q", "r", "p"),
        '8' to listOf("t"),
        '9' to listOf("w", "z", "y", "x"),
    )

    private val commonByDigits: Map<String, List<String>> = mapOf(
        "22" to listOf("ba", "ca"), "224" to listOf("cai", "bai"), "226" to listOf("ban", "bao", "cao", "can"),
        "2264" to listOf("bang", "cang"), "23" to listOf("ce"), "234" to listOf("bei"), "236" to listOf("ben", "cen"),
        "2364" to listOf("ceng", "beng"), "24" to listOf("bi", "ai", "ci"), "242" to listOf("cha"), "2424" to listOf("chai"),
        "2426" to listOf("bian", "chao", "biao", "chan"), "24264" to listOf("chang"), "243" to listOf("bie", "che"), "2436" to listOf("chen"),
        "24364" to listOf("cheng"), "244" to listOf("chi"), "246" to listOf("bin"), "2464" to listOf("bing"), "24664" to listOf("chong"),
        "2468" to listOf("chou"), "248" to listOf("chu"), "2482" to listOf("chua"), "24824" to listOf("chuai"), "24826" to listOf("chuan"),
        "248264" to listOf("chuang"), "2484" to listOf("chui"), "2486" to listOf("chun", "chuo"), "26" to listOf("an", "bo", "ao"),
        "264" to listOf("ang"), "2664" to listOf("cong"), "268" to listOf("cou"), "28" to listOf("bu", "cu"), "2826" to listOf("cuan"), "284" to listOf("cui"),
        "286" to listOf("cun", "cuo"), "32" to listOf("da", "fa"), "324" to listOf("dai"), "326" to listOf("dao", "dan", "fan"),
        "3264" to listOf("fang", "dang"), "33" to listOf("de"), "334" to listOf("fei"), "336" to listOf("fen"), "3364" to listOf("deng", "feng"),
        "34" to listOf("di", "ei", "eh"), "342" to listOf("dia"), "3426" to listOf("dian", "diao"), "343" to listOf("die"), "3464" to listOf("ding"),
        "348" to listOf("diu"), "36" to listOf("fo", "en"), "3664" to listOf("dong"), "368" to listOf("dou", "fou"), "37" to listOf("er"),
        "38" to listOf("du", "fu"), "3826" to listOf("duan"), "384" to listOf("dui"), "386" to listOf("duo", "dun"), "42" to listOf("ha", "ga"),
        "424" to listOf("hai", "gai"), "426" to listOf("hao", "han", "gao", "gan"), "4264" to listOf("gang", "hang"),
        "43" to listOf("he", "ge"), "434" to listOf("gei", "hei"), "436" to listOf("hen", "gen"), "4364" to listOf("geng", "heng"),
        "4664" to listOf("gong", "hong"), "468" to listOf("hou", "gou"), "48" to listOf("hu", "gu"), "482" to listOf("hua", "gua"),
        "4824" to listOf("guai", "huai"), "4826" to listOf("huan", "guan"), "48264" to listOf("guang", "huang"), "484" to listOf("hui", "gui"),
        "486" to listOf("guo", "huo", "hun", "gun"), "52" to listOf("la", "ka"), "524" to listOf("lai", "kai"),
        "526" to listOf("kan", "lao", "kao", "lan"), "5264" to listOf("lang", "kang"), "53" to listOf("le", "ke"), "534" to listOf("lei"),
        "536" to listOf("ken"), "5364" to listOf("leng", "keng"), "54" to listOf("li", "ji"), "542" to listOf("jia", "lia"),
        "5426" to listOf("liao", "jiao", "jian", "lian"), "54264" to listOf("jiang", "liang"), "543" to listOf("jie", "lie"),
        "546" to listOf("jin", "lin"), "5464" to listOf("ling", "jing"), "54664" to listOf("jiong"), "548" to listOf("jiu", "liu"),
        "5664" to listOf("kong", "long"), "568" to listOf("kou", "lou"), "58" to listOf("lu", "ku", "ju", "lv"), "582" to listOf("kua"),
        "5824" to listOf("kuai"), "5826" to listOf("luan", "kuan", "juan", "lvan"), "58264" to listOf("kuang"), "583" to listOf("jue", "lve"),
        "584" to listOf("kui"), "586" to listOf("jun", "luo", "kun", "lun", "kuo"), "62" to listOf("na", "ma"), "624" to listOf("mai", "nai"),
        "626" to listOf("nan", "man", "mao", "nao"), "6264" to listOf("mang", "nang"), "63" to listOf("ne", "me"),
        "634" to listOf("mei", "nei"), "636" to listOf("men", "nen"), "6364" to listOf("neng", "meng"), "64" to listOf("ni", "mi"),
        "6426" to listOf("nian", "mian", "miao", "niao"), "64264" to listOf("niang"), "643" to listOf("mie", "nie"),
        "646" to listOf("nin", "min"), "6464" to listOf("ming", "ning"), "648" to listOf("niu", "miu"), "66" to listOf("mo"), "6664" to listOf("nong"),
        "668" to listOf("mou", "nou"), "68" to listOf("nv", "mu", "ou", "nu"), "6826" to listOf("nuan"), "683" to listOf("nve"),
        "686" to listOf("nuo"), "72" to listOf("pa", "sa"), "724" to listOf("pai", "sai"),
        "726" to listOf("san", "pao", "pan", "rao", "sao", "ran"), "7264" to listOf("rang", "pang", "sang"),
        "73" to listOf("re", "se"), "734" to listOf("pei"), "736" to listOf("ren", "pen", "sen"), "7364" to listOf("reng", "peng", "seng"),
        "74" to listOf("ri", "qi", "si", "pi"), "742" to listOf("sha", "qia"), "7424" to listOf("shai"),
        "7426" to listOf("qian", "shao", "shan", "qiao", "piao", "pian"), "74264" to listOf("shang", "qiang"),
        "743" to listOf("she", "qie", "pie"), "7434" to listOf("shei"), "7436" to listOf("shen"), "74364" to listOf("sheng"), "744" to listOf("shi"),
        "746" to listOf("qin", "pin"), "7464" to listOf("qing", "ping"), "74664" to listOf("qiong"), "7468" to listOf("shou"),
        "748" to listOf("shu", "qiu"), "7482" to listOf("shua"), "74824" to listOf("shuai"), "74826" to listOf("shuan"), "748264" to listOf("shuang"),
        "7484" to listOf("shui"), "7486" to listOf("shuo", "shun"), "76" to listOf("po"), "7664" to listOf("song", "rong"),
        "768" to listOf("rou", "sou", "pou"), "78" to listOf("qu", "ru", "pu", "su"), "7826" to listOf("quan", "suan", "ruan"),
        "783" to listOf("que"), "784" to listOf("sui", "rui"), "786" to listOf("suo", "qun", "ruo", "run", "sun"), "82" to listOf("ta"),
        "824" to listOf("tai"), "826" to listOf("tan", "tao"), "8264" to listOf("tang"), "83" to listOf("te"), "8364" to listOf("teng"), "84" to listOf("ti"),
        "8426" to listOf("tian", "tiao"), "843" to listOf("tie"), "8464" to listOf("ting"), "8664" to listOf("tong"), "868" to listOf("tou"),
        "88" to listOf("tu"), "8826" to listOf("tuan"), "884" to listOf("tui"), "886" to listOf("tuo", "tun"), "92" to listOf("ya", "wa", "za"),
        "924" to listOf("zai", "wai", "yai"), "926" to listOf("yao", "wan", "zao", "yan", "zan"),
        "9264" to listOf("wang", "yang", "zang"), "93" to listOf("ye", "ze"), "934" to listOf("wei", "zei"), "936" to listOf("wen", "zen"),
        "9364" to listOf("zeng", "weng"), "94" to listOf("yi", "zi", "xi"), "942" to listOf("xia", "zha"), "9424" to listOf("zhai"),
        "9426" to listOf("xiao", "xian", "zhao", "zhan"), "94264" to listOf("xiang", "zhang"), "943" to listOf("zhe", "xie"),
        "9434" to listOf("zhei"), "9436" to listOf("zhen"), "94364" to listOf("zheng"), "944" to listOf("zhi"), "946" to listOf("xin", "yin"),
        "9464" to listOf("xing", "ying"), "94664" to listOf("zhong", "xiong"), "9468" to listOf("zhou"), "948" to listOf("zhu", "xiu"),
        "9482" to listOf("zhua"), "94824" to listOf("zhuai"), "94826" to listOf("zhuan"), "948264" to listOf("zhuang"), "9484" to listOf("zhui"),
        "9486" to listOf("zhun", "zhuo"), "96" to listOf("wo", "yo"), "9664" to listOf("yong", "zong"), "968" to listOf("you", "zou"),
        "98" to listOf("yu", "wu", "zu", "xu"), "9826" to listOf("yuan", "xuan", "zuan"), "983" to listOf("yue", "xue"),
        "984" to listOf("zui"), "986" to listOf("zuo", "yun", "xun", "zun"),
    )

    private val rareByDigits: Map<String, List<String>> = mapOf(
        "24264" to listOf("biang"), "334" to listOf("dei"), "336" to listOf("den"),
        "3426" to listOf("fiao"), "364" to listOf("eng"), "534" to listOf("kei"),
        "686" to listOf("nun"), "734" to listOf("sei"), "742" to listOf("pia"),
    )

    /** Maps an ASCII letter `a`–`z` / `A`–`Z` to its keypad digit `'2'`–`'9'`. */
    fun digit(letter: Char): Char? {
        val lower = letter.lowercaseChar()
        return if (lower in 'a'..'z') DIGIT_MAP[lower - 'a'] else null
    }

    /** Range of the first unlocked digit span (`'2'`–`'9'`) after any locked `'A'`–`'Z'` prefix. */
    fun firstUnlockedRange(input: String): IntRange? {
        var i = 0
        while (i < input.length && (input[i] in 'A'..'Z' || input[i] == '\'')) i++
        val start = i
        while (i < input.length && input[i] in '2'..'9') i++
        return if (start < i) start until i else null
    }

    /** Ordered Pinyin syllables (or single-letter initials) matching prefixes of the first unlocked digit span. */
    fun leading(input: String): List<String> {
        val range = firstUnlockedRange(input) ?: return emptyList()
        val digits = input.substring(range)
        val common = ArrayList<String>()
        val rare = ArrayList<String>()
        val maxLen = min(6, digits.length)
        if (maxLen >= 2) {
            for (len in maxLen downTo 2) {
                val prefix = digits.substring(0, len)
                commonByDigits[prefix]?.let(common::addAll)
                rareByDigits[prefix]?.let(rare::addAll)
            }
        }
        if (common.isNotEmpty() || rare.isNotEmpty()) return common + rare
        return singleInitials[digits[0]].orEmpty()
    }

    /** Locks [syllable] at the start of the first unlocked digit span (`"9264468"` + `"wang"` -> `"WANG'468"`). */
    fun lock(syllable: String, input: String): String? {
        if (syllable.isEmpty()) return null
        val range = firstUnlockedRange(input) ?: return null
        val unlocked = input.substring(range)
        if (unlocked.length < syllable.length) return null
        val expected = buildString(syllable.length) {
            for (ch in syllable) append(digit(ch) ?: return null)
        }
        if (!unlocked.startsWith(expected)) return null
        val tail = input.substring(range.first + syllable.length)
        val separator = if (tail.isNotEmpty() && !tail.startsWith('\'')) "'" else ""
        return input.substring(0, range.first) + syllable.uppercase() + separator + tail
    }

    /** When [input] ends with a locked syllable (`"WANG'"` or `"WANG"`), unlocks it back to digits on Backspace. */
    fun unlockLastOnBackspace(input: String): String? {
        if (input.isEmpty()) return null
        val work = if (input.endsWith('\'')) input.dropLast(1) else input
        if (work.isEmpty() || work.last() !in 'A'..'Z') return null
        var segStart = work.length
        while (segStart > 0 && work[segStart - 1] in 'A'..'Z') segStart--
        val restored = buildString(work.length - segStart) {
            for (i in segStart until work.length) append(digit(work[i]) ?: return null)
        }
        if (restored.isEmpty()) return null
        return work.substring(0, segStart) + restored
    }
}

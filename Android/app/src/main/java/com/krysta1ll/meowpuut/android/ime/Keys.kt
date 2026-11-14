package com.krysta1ll.meowpuut.android.ime

import kotlin.math.min

import com.krysta1ll.meowpuut.android.engine.InputMode

// What each key is and does (the same rows as iOS KeyboardKeys.swift).
// Layout lives in Geometry.kt; drawing and touches in KeysView.

sealed interface KeyAction {
    /** A raw ASCII key for the composer (letters, Zhuyin). */
    data class Engine(val code: Int) : KeyAction
    /** Inserted as is (symbol pages). */
    data class Text(val text: String) : KeyAction
    data class Page(val page: Int) : KeyAction
    data object Shift : KeyAction
    data object Delete : KeyAction
    data object Segment : KeyAction
    data object ClearComposition : KeyAction
    data object Space : KeyAction
    data object Return : KeyAction
    data object Globe : KeyAction
    data object ToggleEnglish : KeyAction
    data object Emoji : KeyAction
}

enum class KeyStyle { CHARACTER, UTILITY, ACCENT }
enum class KeyIcon { SHIFT, SHIFT_ON, CAPS_LOCK, DELETE, GLOBE }
enum class ShiftState { OFF, ONCE, LOCKED }

data class Key(
    val action: KeyAction,
    val label: String,
    val icon: KeyIcon? = null,
    val style: KeyStyle = KeyStyle.CHARACTER,
    val alternates: List<String> = emptyList(),
    val preview: Boolean = true,
    val enabled: Boolean = true,
    val fontSize: Float? = null,
    val spoken: String = label,
    val digit: String? = null,
) {
    val isCharacter get() = action is KeyAction.Engine || action is KeyAction.Text

    /**
     * Characters and space: typed wherever the finger lifts, and typed early
     * when a second finger lands. Return, the language key and the globe are
     * typed only if the finger lifts on them.
     */
    val rollsOver get() = isCharacter || action == KeyAction.Space || action == KeyAction.Segment
}

/** [page]: 0 letters, 1 symbols, 2 more symbols, 3 numeric keypad. */
data class KeySet(
    val rows: List<List<Key>>,
    val specs: List<RowSpec>,
    val referenceColumns: Int,
    val page: Int = 0,
    val maximumWidth: Float? = null,
    val toleratesKeyDrift: Boolean = false,
) {
    fun layoutWidth(available: Float) = min(available, maximumWidth ?: available)
}

/** Everything that changes which keys are shown or how they are labelled. */
data class KeyContext(
    val mode: InputMode,
    val english: Boolean,
    val page: Int,
    val shift: ShiftState,
    val simplified: Boolean,
    val needsGlobe: Boolean,
    val spaceTitle: String,
    val returnTitle: String,
    val returnAccent: Boolean,
    val returnEnabled: Boolean,
    /** The candidate panel covers the upper rows; the bottom row then gets a delete key. */
    val expanded: Boolean = false,
    val emoji: Boolean = false,
    val composing: Boolean = false,
) {
    val showsNineKey get() = mode == InputMode.NINE_KEY && !english && page == 0
}

object KeyBuilder {
    const val NUMERIC_PAGE = 3

    val zhuyin: List<List<Pair<String, Char>>> = listOf(
        listOf("ㄅ" to '1', "ㄉ" to '2', "ˇ" to '3', "ˋ" to '4', "ㄓ" to '5', "ˊ" to '6', "˙" to '7', "ㄚ" to '8', "ㄞ" to '9', "ㄢ" to '0', "ㄦ" to '-'),
        listOf("ㄆ" to 'q', "ㄊ" to 'w', "ㄍ" to 'e', "ㄐ" to 'r', "ㄔ" to 't', "ㄗ" to 'y', "ㄧ" to 'u', "ㄛ" to 'i', "ㄟ" to 'o', "ㄣ" to 'p'),
        listOf("ㄇ" to 'a', "ㄋ" to 's', "ㄎ" to 'd', "ㄑ" to 'f', "ㄕ" to 'g', "ㄘ" to 'h', "ㄨ" to 'j', "ㄜ" to 'k', "ㄠ" to 'l', "ㄤ" to ';'),
        listOf("ㄈ" to 'z', "ㄌ" to 'x', "ㄏ" to 'c', "ㄒ" to 'v', "ㄖ" to 'b', "ㄙ" to 'n', "ㄩ" to 'm', "ㄝ" to ',', "ㄡ" to '.', "ㄥ" to '/'),
    )

    /** Accented letters on a long press in English. */
    val accents: Map<Char, List<String>> = mapOf(
        'a' to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"), 'c' to listOf("ç", "ć", "č"),
        'e' to listOf("è", "é", "ê", "ë", "ē", "ė", "ę"), 'i' to listOf("î", "ï", "í", "ī", "į", "ì"),
        'l' to listOf("ł"), 'n' to listOf("ñ", "ń"), 'o' to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        's' to listOf("ß", "ś", "š"), 'u' to listOf("û", "ü", "ù", "ú", "ū"), 'y' to listOf("ÿ"), 'z' to listOf("ž", "ź", "ż"),
    )

    private val symbolGroups = listOf(
        listOf("：", ":", "；", ";"),
        listOf("，", ",", "、", "､", "；", ";"),
        listOf("。", ".", "．", "｡", "…", "·", "・", "•"),
        listOf("？", "?", "¿", "！", "!", "¡"),
        listOf("“", "「", "『", "‘", "«", "\""),
        listOf("”", "」", "』", "’", "»", "\""),
        listOf("(", "（", "[", "【", "{", "｛", "〔"),
        listOf(")", "）", "]", "】", "}", "｝", "〕"),
        listOf("<", "《", "〈", "«"),
        listOf(">", "》", "〉", "»"),
        listOf("'", "‘", "’", "`", "＇"),
        listOf("-", "—", "–", "_", "－", "ー", "−"),
        listOf("/", "／", "\\", "＼", "|", "｜", "⁄"),
        listOf("~", "～", "〜"),
        listOf("$", "¥", "￥", "€", "£", "¢", "₩"),
        listOf("&", "＆", "§"),
        listOf("@", "＠"),
        listOf("#", "＃", "♯"),
        listOf("%", "％", "‰"),
        listOf("^", "＾", "↑"),
        listOf("*", "＊", "×", "※", "⋅"),
        listOf("+", "＋", "±"),
        listOf("=", "＝", "≠", "≈", "≡"),
        listOf("0", "°", "º"),
    )

    private val quoteAlternates = listOf("\"", "“", "”", "「", "」", "『", "』", "„")

    /** The pressed mark is always the first option, even in another script. */
    fun punctuationAlternates(mark: String): List<String> {
        if (mark == "\"") return quoteAlternates
        val group = symbolGroups.firstOrNull { mark in it } ?: return emptyList()
        return listOf(mark) + group.filterNot { it == mark }
    }

    /** Marks after which the symbol page returns to letters. */
    val autoReturnMarks = setOf("，", "。", "？", "！", "、", "；", "：", ",", ".", "?", "!", ";", ":", "'", "\"")

    fun build(context: KeyContext): KeySet {
        val t = Words(context.simplified)
        val chinese = !context.english && context.mode != InputMode.JAPANESE
        if (context.page == NUMERIC_PAGE) return nineKeyNumbers(context, t)
        if (context.page > 0) return symbols(context, context.page, chinese, t)
        if (!context.english && context.mode == InputMode.ZHUYIN) return zhuyinSet(context, t)
        if (context.showsNineKey) return nineKeySet(context, t)
        return letters(context, t)
    }

    private fun bottomRow(context: KeyContext, t: Words, first: Key, second: Key?): Pair<List<Key>, RowSpec> {
        val utilityWidth = 1.25f
        val keys = mutableListOf(first)
        val units = mutableListOf(utilityWidth)
        if (second != null) { keys += second; units += utilityWidth }
        if (context.needsGlobe) {
            keys += Key(KeyAction.Globe, "", KeyIcon.GLOBE, KeyStyle.UTILITY, preview = false, spoken = t("選擇系統鍵盤", "选择系统键盘"))
            units += utilityWidth
        }
        keys += Key(KeyAction.Space, context.spaceTitle, style = KeyStyle.CHARACTER, preview = false, fontSize = 16f, spoken = "空格")
        units += 0f
        if (context.expanded) {
            keys += deleteKey(t)
            units += utilityWidth
        }
        keys += returnKey(context)
        units += 2.25f
        return keys to RowSpec(units, RowAlignment.JUSTIFIED)
    }

    private fun toggleKey(context: KeyContext): Key {
        val t = Words(context.simplified)
        val label = if (context.english) {
            when (context.mode) {
                InputMode.PINYIN -> "拼"
                InputMode.NINE_KEY -> "中"
                InputMode.ZHUYIN -> "ㄅ"
                InputMode.JAPANESE -> "あ"
            }
        } else if (context.mode == InputMode.NINE_KEY) "英" else "ABC"
        val spoken = when {
            !context.english -> t("切換到英文", "切换到英文")
            context.mode == InputMode.NINE_KEY -> t("回到九鍵拼音", "回到九键拼音")
            context.mode == InputMode.JAPANESE -> t("切換到日文", "切换到日文")
            else -> t("切換到中文", "切换到中文")
        }
        return Key(KeyAction.ToggleEnglish, label, style = KeyStyle.UTILITY, preview = false, fontSize = 16f, spoken = spoken)
    }

    private fun deleteKey(t: Words) =
        Key(KeyAction.Delete, "", KeyIcon.DELETE, KeyStyle.UTILITY, preview = false, spoken = t("刪除", "删除"))

    private fun returnKey(context: KeyContext) = Key(
        KeyAction.Return, context.returnTitle,
        style = if (context.returnAccent) KeyStyle.ACCENT else KeyStyle.UTILITY,
        preview = false, enabled = context.returnEnabled, fontSize = 16f, spoken = context.returnTitle,
    )

    private fun utilityPage(page: Int, label: String, spoken: String = label) =
        Key(KeyAction.Page(page), label, style = KeyStyle.UTILITY, preview = false, fontSize = 15f, spoken = spoken)

    private fun punctuationKey(mark: String, options: List<String> = punctuationAlternates(mark)) =
        Key(KeyAction.Text(mark), mark, style = KeyStyle.UTILITY, alternates = options, fontSize = 20f)

    private fun globeKey(t: Words) = Key(KeyAction.Globe, "", KeyIcon.GLOBE, KeyStyle.UTILITY, preview = false,
        spoken = t("選擇系統鍵盤", "选择系统键盘"))

    private fun nineKeyBottom(context: KeyContext, t: Words, first: Key, second: Key): Pair<List<Key>, RowSpec> {
        val keys = mutableListOf(first, second)
        val widths = mutableListOf(1.2f, 1.0f)
        if (context.needsGlobe) {
            keys += globeKey(t)
            widths += 0.9f
        }
        keys += Key(KeyAction.Space, context.spaceTitle, preview = false, fontSize = 16f, spoken = "空格")
        widths += 0f
        keys += toggleKey(context)
        widths += 1.0f
        if (context.expanded) {
            keys += deleteKey(t)
            widths += 0.95f
        }
        keys += returnKey(context)
        widths += 1.65f
        return keys to RowSpec(widths, RowAlignment.JUSTIFIED)
    }

    private fun nineKeySet(context: KeyContext, t: Words): KeySet {
        val segment = Key(KeyAction.Segment, t("分詞", "分词"), alternates = listOf("1"),
            preview = false, spoken = t("1，分詞", "1，分词"), digit = "1")
        val digits = listOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ").mapIndexed { index, group ->
            val digit = index + 2
            Key(KeyAction.Engine('0'.code + digit), group, alternates = listOf(digit.toString()),
                spoken = "$digit $group", digit = digit.toString())
        }
        val marks = listOf("！", "。", "？").map { mark ->
            punctuationKey(mark, (punctuationAlternates(mark) + listOf("，", "、", "；")).distinct())
        }
        val side = listOf(
            deleteKey(t),
            Key(KeyAction.ClearComposition, t("重輸", "重输"), style = KeyStyle.UTILITY, preview = false,
                enabled = context.composing, spoken = t("清除目前拼音", "清除当前拼音")),
            Key(KeyAction.Text("0"), "0", style = KeyStyle.UTILITY, fontSize = 18f),
        )
        val rows = (listOf(segment) + digits).chunked(3).mapIndexed { index, middle ->
            listOf(marks[index]) + middle + side[index]
        }
        val bottom = nineKeyBottom(context, t, utilityPage(1, t("符號", "符号")),
            utilityPage(NUMERIC_PAGE, "123", t("數字鍵盤", "数字键盘")))
        return KeySet(rows + listOf(bottom.first), listOf(nineKeyRow, nineKeyRow, nineKeyRow, bottom.second),
            referenceColumns = 10, maximumWidth = 460f)
    }

    private val nineKeyRow = RowSpec(listOf(1.35f, 2.3f, 2.3f, 2.3f, 1.35f), RowAlignment.JUSTIFIED)

    private fun nineKeyNumbers(context: KeyContext, t: Words): KeySet {
        fun number(digit: Char) = Key(KeyAction.Text(digit.toString()), digit.toString(), fontSize = 20f)
        val marks = listOf(
            utilityPage(1, t("符號", "符号")),
            if (context.needsGlobe) globeKey(t) else punctuationKey("+"),
            punctuationKey("-"),
        )
        val side = listOf(deleteKey(t), punctuationKey("/"), punctuationKey("#"))
        val rows = ('1'..'9').map(::number).chunked(3).mapIndexed { index, middle ->
            listOf(marks[index]) + middle + side[index]
        }
        val bottom = listOf(utilityPage(0, t("九鍵", "九键"), t("回到九鍵拼音", "回到九键拼音")),
            punctuationKey(","), number('0'), punctuationKey("."), returnKey(context))
        return KeySet(rows + listOf(bottom), List(4) { nineKeyRow },
            referenceColumns = 10, page = NUMERIC_PAGE, maximumWidth = 460f)
    }

    private fun letters(context: KeyContext, t: Words): KeySet {
        // Shift shows capitals in every letters layout: English text, a
        // reading typed in capitals in Pinyin, katakana first in Japanese.
        val upper = context.shift != ShiftState.OFF
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm").map { row ->
            row.map { c ->
                val label = if (upper) c.uppercaseChar().toString() else c.toString()
                val alternates = if (context.english) {
                    accents[c]?.map { if (upper) it.uppercase() else it }.orEmpty()
                } else emptyList()
                Key(KeyAction.Engine(c.code), label, alternates = alternates, spoken = label)
            }
        }
        val shiftIcon = when (context.shift) {
            ShiftState.OFF -> KeyIcon.SHIFT
            ShiftState.ONCE -> KeyIcon.SHIFT_ON
            ShiftState.LOCKED -> KeyIcon.CAPS_LOCK
        }
        // An active Shift turns white, as on Apple's keyboard.
        val shift = Key(KeyAction.Shift, "", shiftIcon, if (context.shift == ShiftState.OFF) KeyStyle.UTILITY else KeyStyle.CHARACTER,
            preview = false, spoken = if (context.shift == ShiftState.LOCKED) t("大寫鎖定", "大写锁定") else "Shift")
        val symbolsKey = Key(KeyAction.Page(1), if (context.english) "123" else t("符號", "符号"), style = KeyStyle.UTILITY,
            preview = false, fontSize = 16f, spoken = t("符號", "符号"))
        val bottom = bottomRow(context, t, symbolsKey, toggleKey(context))
        return KeySet(
            rows = listOf(rows[0], rows[1], listOf(shift) + rows[2] + deleteKey(t), bottom.first),
            specs = listOf(
                RowSpec(List(10) { 1f }, RowAlignment.CENTERED),
                RowSpec(List(9) { 1f }, RowAlignment.CENTERED),
                RowSpec(listOf(1.32f) + List(7) { 1f } + 1.32f, RowAlignment.SPLIT_EDGES),
                bottom.second,
            ),
            referenceColumns = 10,
            toleratesKeyDrift = true,
        )
    }

    private fun zhuyinSet(context: KeyContext, t: Words): KeySet {
        val rows = zhuyin.map { row ->
            row.map { (symbol, raw) -> Key(KeyAction.Engine(raw.code), symbol, fontSize = 21f, spoken = symbol) }
        }.toMutableList()
        rows[3] = rows[3] + deleteKey(t)
        val symbolsKey = Key(KeyAction.Page(1), t("符號", "符号"), style = KeyStyle.UTILITY, preview = false,
            fontSize = 16f, spoken = t("符號", "符号"))
        val bottom = bottomRow(context, t, symbolsKey, toggleKey(context))
        return KeySet(
            rows = rows + listOf(bottom.first),
            specs = listOf(
                RowSpec(List(11) { 1f }, RowAlignment.CENTERED),
                RowSpec(List(10) { 1f }, RowAlignment.CENTERED),
                RowSpec(List(10) { 1f }, RowAlignment.CENTERED),
                RowSpec(List(11) { 1f }, RowAlignment.CENTERED),
                bottom.second,
            ),
            referenceColumns = 11,
            toleratesKeyDrift = true,
        )
    }

    fun symbolRows(page: Int, chinese: Boolean, simplified: Boolean, japanese: Boolean): List<List<String>> = when {
        page == 1 && japanese -> listOf(
            "1234567890".map { it.toString() },
            listOf("ー", "/", "：", "；", "（", "）", "¥", "@", "「", "」"),
            listOf("。", "、", "？", "！", "・", "."),
        )
        japanese -> listOf(
            listOf("【", "】", "｛", "｝", "#", "%", "^", "*", "+", "="),
            listOf("_", "—", "＼", "｜", "～", "『", "』", "€", "&", "♪"),
            listOf("…", "、", "。", "？", "！", "'"),
        )
        page == 1 && !chinese -> listOf(
            "1234567890".map { it.toString() },
            listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
            listOf(".", ",", "?", "!", "'"),
        )
        !chinese -> listOf(
            listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
            listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"),
            listOf(".", ",", "?", "!", "'"),
        )
        page == 1 -> listOf(
            "1234567890".map { it.toString() },
            if (simplified) listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”")
            else listOf("-", "/", "：", "；", "（", "）", "$", "@", "「", "」"),
            listOf("。", "，", "、", "？", "！", "."),
        )
        else -> listOf(
            listOf("【", "】", "｛", "｝", "#", "%", "^", "*", "+", "="),
            listOf("_", "—", "＼", "｜", "～", "《", "》", "€", "&", "·"),
            listOf("…", "，", "^_^", "？", "！", "'"),
        )
    }

    private fun symbols(context: KeyContext, page: Int, chinese: Boolean, t: Words): KeySet {
        val source = symbolRows(page, chinese, context.simplified, !context.english && context.mode == InputMode.JAPANESE)
        fun key(text: String) = Key(
            KeyAction.Text(text), text, alternates = punctuationAlternates(text),
            fontSize = if (text.length > 1) 16f else 21f, spoken = text,
        )
        val nine = context.mode == InputMode.NINE_KEY && !context.english
        val other = if (page == 1) utilityPage(2, "#+=", t("更多符號", "更多符号"))
            else utilityPage(if (nine) NUMERIC_PAGE else 1, "123", t("數字", "数字"))
        val lettersLabel = when {
            context.english -> "ABC"
            context.mode == InputMode.ZHUYIN -> "注音"
            context.mode == InputMode.JAPANESE -> "かな"
            context.mode == InputMode.NINE_KEY -> t("九鍵", "九键")
            else -> "拼音"
        }
        val back = Key(KeyAction.Page(0), lettersLabel, style = KeyStyle.UTILITY, preview = false, fontSize = 16f,
            spoken = "字母")
        val emoji = Key(
            KeyAction.Emoji, "☺", style = if (context.emoji) KeyStyle.ACCENT else KeyStyle.UTILITY,
            preview = false, fontSize = 17f, spoken = t("表情符號", "表情符号"),
        )
        val bottom = bottomRow(context, t, back, emoji)
        val middle = (10f - 2 * 1.32f) / source[2].size
        return KeySet(
            rows = listOf(source[0].map(::key), source[1].map(::key), listOf(other) + source[2].map(::key) + deleteKey(t), bottom.first),
            specs = listOf(
                RowSpec(List(10) { 1f }, RowAlignment.CENTERED),
                RowSpec(List(10) { 1f }, RowAlignment.CENTERED),
                RowSpec(listOf(1.32f) + List(source[2].size) { middle } + 1.32f, RowAlignment.JUSTIFIED),
                bottom.second,
            ),
            referenceColumns = 10,
            page = page,
        )
    }
}

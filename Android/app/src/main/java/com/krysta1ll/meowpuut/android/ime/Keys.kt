package com.krysta1ll.meowpuut.android.ime

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
    data object Space : KeyAction
    data object Return : KeyAction
    data object Globe : KeyAction
    data object ToggleEnglish : KeyAction
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
) {
    val isCharacter get() = action is KeyAction.Engine || action is KeyAction.Text

    /**
     * Characters and space: typed wherever the finger lifts, and typed early
     * when a second finger lands. Return, the language key and the globe are
     * typed only if the finger lifts on them.
     */
    val rollsOver get() = isCharacter || action == KeyAction.Space
}

/** [page]: 0 letters, 1 symbols, 2 more symbols. */
data class KeySet(val rows: List<List<Key>>, val specs: List<RowSpec>, val referenceColumns: Int, val page: Int = 0)

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
)

object KeyBuilder {
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

    val symbolAlternates: Map<String, List<String>> = mapOf(
        "-" to listOf("-", "–", "—", "•"), "/" to listOf("/", "\\"), "$" to listOf("$", "¢", "£", "€", "¥", "₩"),
        "&" to listOf("&", "§"), "\"" to listOf("\"", "“", "”", "„", "»", "«"), "." to listOf(".", "…"),
        "?" to listOf("?", "¿"), "!" to listOf("!", "¡"), "'" to listOf("'", "‘", "’", "`"), "%" to listOf("%", "‰"),
        "0" to listOf("0", "°"), "=" to listOf("=", "≠", "≈"),
        "。" to listOf("。", "．"), "，" to listOf("，", "、"), "“" to listOf("“", "”", "「", "」", "『", "』"),
        "（" to listOf("（", "【", "〔", "《"), "）" to listOf("）", "】", "〕", "》"), "¥" to listOf("¥", "$", "€", "£"),
    )

    /** Marks after which the symbol page returns to letters. */
    val autoReturnMarks = setOf("，", "。", "？", "！", "、", "；", "：", ",", ".", "?", "!", ";", ":", "'", "\"")

    fun build(context: KeyContext): KeySet {
        val t = Words(context.simplified)
        val chinese = !context.english && context.mode != InputMode.JAPANESE
        if (context.page > 0) return symbols(context, context.page, chinese, t)
        if (!context.english && context.mode == InputMode.ZHUYIN) return zhuyinSet(context, t)
        return letters(context, t)
    }

    private fun bottomRow(context: KeyContext, t: Words, first: Key, second: Key?): Pair<List<Key>, RowSpec> {
        val keys = mutableListOf(first)
        val units = mutableListOf(1.25f)
        if (second != null) { keys += second; units += 1.25f }
        if (context.needsGlobe) {
            keys += Key(KeyAction.Globe, "", KeyIcon.GLOBE, KeyStyle.UTILITY, preview = false, spoken = t("切換鍵盤", "切换键盘"))
            units += 1.25f
        }
        keys += Key(KeyAction.Space, context.spaceTitle, style = KeyStyle.CHARACTER, preview = false, fontSize = 16f, spoken = "空格")
        units += 0f
        if (context.expanded) {
            keys += deleteKey(t)
            units += 1.25f
        }
        keys += Key(
            KeyAction.Return, context.returnTitle,
            style = if (context.returnAccent) KeyStyle.ACCENT else KeyStyle.UTILITY,
            preview = false, enabled = context.returnEnabled, fontSize = 16f, spoken = context.returnTitle,
        )
        units += 2.25f
        return keys to RowSpec(units, RowAlignment.JUSTIFIED)
    }

    private fun toggleKey(context: KeyContext): Key {
        val label = if (context.english) {
            when (context.mode) { InputMode.PINYIN -> "拼"; InputMode.ZHUYIN -> "ㄅ"; InputMode.JAPANESE -> "あ" }
        } else "ABC"
        val spoken = when {
            !context.english -> "英文"
            context.mode == InputMode.JAPANESE -> "日文"
            else -> "中文"
        }
        return Key(KeyAction.ToggleEnglish, label, style = KeyStyle.UTILITY, preview = false, fontSize = 16f, spoken = spoken)
    }

    private fun deleteKey(t: Words) =
        Key(KeyAction.Delete, "", KeyIcon.DELETE, KeyStyle.UTILITY, preview = false, spoken = t("刪除", "删除"))

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
        )
    }

    fun symbolRows(page: Int, chinese: Boolean): List<List<String>> = when {
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
            listOf("-", "/", "：", "；", "（", "）", "¥", "@", "“", "”"),
            listOf("。", "，", "、", "？", "！", "."),
        )
        else -> listOf(
            listOf("【", "】", "｛", "｝", "#", "%", "^", "*", "+", "="),
            listOf("_", "—", "＼", "｜", "～", "《", "》", "€", "&", "·"),
            listOf("…", "，", "^_^", "？", "！", "'"),
        )
    }

    private fun symbols(context: KeyContext, page: Int, chinese: Boolean, t: Words): KeySet {
        val source = symbolRows(page, chinese)
        fun key(text: String) = Key(
            KeyAction.Text(text), text, alternates = symbolAlternates[text].orEmpty(),
            fontSize = if (text.length > 1) 16f else 21f, spoken = text,
        )
        val other = Key(KeyAction.Page(if (page == 1) 2 else 1), if (page == 1) "#+=" else "123", style = KeyStyle.UTILITY,
            preview = false, fontSize = 16f, spoken = if (page == 1) t("更多符號", "更多符号") else t("數字", "数字"))
        val lettersLabel = when {
            context.english -> "ABC"
            context.mode == InputMode.ZHUYIN -> "注音"
            context.mode == InputMode.JAPANESE -> "かな"
            else -> "拼音"
        }
        val back = Key(KeyAction.Page(0), lettersLabel, style = KeyStyle.UTILITY, preview = false, fontSize = 16f,
            spoken = "字母")
        val bottom = bottomRow(context, t, back, null)
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

package com.krysta1ll.meowpuut.android.ime

import com.krysta1ll.meowpuut.android.engine.InputMode

/**
 * What holding the language key (ABC, 中/英, 拼, ㄅ, あ) offers: the input modes of 米米莫's rotation, the script, and hiding the
 * keyboard. It is drawn with the long-press alternates strip, so a finger slides to an item and lets go to choose. The first item is
 * where the finger starts: the way of typing now, so letting go without moving does nothing. Pure (no android.*), so it is tested.
 */
object KeyStrip {
    /** The label the strip draws as a keyboard-down icon instead of text. */
    const val HIDE_ICON = "\u0001hide"

    sealed interface Action {
        /** The item under the finger at the start: nothing happens. */
        data object Stay : Action
        data class Mode(val mode: InputMode) : Action
        data object Script : Action
        data object Hide : Action
    }

    data class Entry(val label: String, val spoken: String, val action: Action)

    fun modeLabel(mode: InputMode, t: Words): String = when (mode) {
        InputMode.ZHUYIN -> "注音"
        InputMode.PINYIN -> "拼音"
        InputMode.NINE_KEY -> t("九鍵", "九键")
        InputMode.JAPANESE -> t("日語", "日语")
    }

    /** The name the announcement uses (as the 米米莫 pet gives it). */
    fun modeName(mode: InputMode, t: Words): String = when (mode) {
        InputMode.ZHUYIN -> "注音"
        InputMode.PINYIN -> "拼音"
        InputMode.NINE_KEY -> t("九鍵拼音", "九键拼音")
        InputMode.JAPANESE -> t("日語", "日语")
    }

    /**
     * [rotation]: the ways ticked for the five touches of 米米莫 (null: the ones it has always gone through). The other modes are
     * offered only when two or more are ticked and the field is not numeric (where the pet does not switch either).
     */
    fun entries(current: InputMode, rotation: Set<InputMode>?, simplified: Boolean, numericField: Boolean): List<Entry> {
        val t = Words(simplified)
        val out = ArrayList<Entry>()
        out += Entry(modeLabel(current, t), t("目前：", "当前：") + modeName(current, t), Action.Stay)
        val on = InputMode.SWITCH_ORDER.filter { it in (rotation ?: InputMode.SWITCH_DEFAULT) }
        if (on.size >= 2 && !numericField) {
            val at = on.indexOf(current)
            val others = if (at < 0) on else (1 until on.size).map { on[(at + it) % on.size] }
            for (mode in others) out += Entry(modeLabel(mode, t), t("切換到", "切换到") + modeName(mode, t), Action.Mode(mode))
        }
        out += Entry(if (simplified) "简→繁" else "繁→简", if (simplified) t("切換為繁體中文", "切换为繁体中文") else t("切換為簡體中文", "切换为简体中文"), Action.Script)
        out += Entry(HIDE_ICON, t("收起鍵盤", "收起键盘"), Action.Hide)
        return out
    }
}

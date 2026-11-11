package com.krysta1ll.meowpuut.android.ime

// Colours from docs/DESIGN.md §3–§5 and the shared palette in
// Sources/GlassShapes.swift, as packed ARGB ints.

enum class Palette(val key: String) {
    ROSE("rose"), OCEAN("ocean"), SAGE("sage"), LAVENDER("lavender"), AMBER("amber"), GRAPHITE("graphite");

    /** The selected-candidate capsule: top and bottom of its gradient. */
    fun pill(dark: Boolean): Pair<Int, Int> = when (this) {
        ROSE -> if (dark) 0xFFA88D8C.toInt() to 0xFF8C716F.toInt() else 0xFFAB8689.toInt() to 0xFF946D71.toInt()
        OCEAN -> if (dark) 0xFF6F8FAE.toInt() to 0xFF587894.toInt() else 0xFF7892AD.toInt() to 0xFF5C7A9B.toInt()
        SAGE -> if (dark) 0xFF7F9984.toInt() to 0xFF66806B.toInt() else 0xFF7F9583.toInt() to 0xFF647D69.toInt()
        LAVENDER -> if (dark) 0xFF8F7FA8.toInt() to 0xFF75668D.toInt() else 0xFF9A88B3.toInt() to 0xFF816EA0.toInt()
        AMBER -> if (dark) 0xFFA88B63.toInt() to 0xFF8C714D.toInt() else 0xFFA78B60.toInt() to 0xFF927242.toInt()
        GRAPHITE -> if (dark) 0xFF7E848D.toInt() to 0xFF666C75.toInt() else 0xFF8B9099.toInt() to 0xFF717781.toInt()
    }

    /** The palette's own colour in this appearance: `P` in DESIGN.md §3. */
    fun color(dark: Boolean): Int = when (this) {
        ROSE -> if (dark) 0xFFE69687.toInt() else 0xFF91453D.toInt()
        OCEAN -> if (dark) 0xFF70BAF0.toInt() else 0xFF2966A6.toInt()
        SAGE -> if (dark) 0xFF9CC7A1.toInt() else 0xFF477554.toInt()
        LAVENDER -> if (dark) 0xFFC2A8F0.toInt() else 0xFF7554A6.toInt()
        AMBER -> if (dark) 0xFFF0BF6B.toInt() else 0xFF96611F.toInt()
        GRAPHITE -> if (dark) 0xFFC2CCD9.toInt() else 0xFF525E6E.toInt()
    }

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: ROSE
    }
}

enum class Tone(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class GlassStrength(val key: String) {
    SOFT("soft"), BALANCED("balanced"), VIVID("vivid");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BALANCED
    }
}

/** Everything that decides how the keyboard is painted. */
data class Look(
    val dark: Boolean,
    val shape: GlassShape,
    val palette: Palette,
    val strength: GlassStrength,
    val cats: Boolean,
    /** Festival pill colour (DESIGN: docs/FESTIVALS.md), or null. */
    val pillOverride: Int? = null,
    /** The system asks for more contrast: the glass is painted solid (DESIGN.md §3). */
    val opaque: Boolean = false,
) {
    val keyboardTop get() = if (dark) 0xFF252428.toInt() else 0xFFE9E8EF.toInt()
    val keyboardBottom get() = if (dark) 0xFF1A191C.toInt() else 0xFFD9D8E0.toInt()
    val keyFaceTop get() = if (dark) 0xFF636268.toInt() else 0xFFFFFFFF.toInt()
    val keyFaceBottom get() = if (dark) 0xFF5A595F.toInt() else 0xFFFAFAFC.toInt()
    val keyUtility get() = if (dark) 0xFF3C3B40.toInt() else 0xFFCFCED8.toInt()
    val keyPressed get() = if (dark) 0xFF77767D.toInt() else 0xFFFFFFFF.toInt()
    val keyCharacterPressed get() = if (dark) 0xFF4A494F.toInt() else 0xFFE2E1E8.toInt()
    val keyShadow get() = if (dark) 0x73000000 else 0x33000000
    val keyText get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF18181B.toInt()
    val keyTextDisabled get() = if (dark) 0x66FFFFFF else 0x5518181B
    val candidateText get() = if (dark) 0xFFF5EFE9.toInt() else 0xFF3A3334.toInt()
    val candidateSecondary get() = if (dark) 0xFFCCC2BA.toInt() else 0xFF7A7072.toInt()
    val balloonFace get() = if (dark) 0xFF6B6A70.toInt() else 0xFFFFFFFF.toInt()

    val pill: Pair<Int, Int>
        get() = pillOverride?.let { lighten(it, 0.14f) to it } ?: palette.pill(dark)

    /** Accent for the return key when it performs an action (search, send…). */
    val accent get() = pill.second

    /** Emulated glass body (no live backdrop blur on Android), DESIGN.md §3. */
    val glassFill: Int
        get() {
            if (opaque) return if (dark) 0xFF2B2729.toInt() else 0xFFF4F2F6.toInt()
            val alpha = when (strength) {
                GlassStrength.SOFT -> if (dark) 0.74f else 0.60f
                GlassStrength.BALANCED -> if (dark) 0.62f else 0.46f
                GlassStrength.VIVID -> if (dark) 0.50f else 0.32f
            }
            val rgb = if (dark) 0x3A302C else 0xFFFFFF
            return ((alpha * 255).toInt() shl 24) or rgb
        }

    val tint get() = palette.color(dark)

    /** The glass edge: white in light, the palette lifted toward white in dark. */
    val rim get() = if (dark) lighten(tint, 0.25f) else 0xFFFFFFFF.toInt()

    /** Light: a pastel spectrum. Dark: the palette between two lighter tones of itself. */
    val causticColors: IntArray
        get() = if (dark) intArrayOf(tint, lighten(tint, 0.45f), darken(tint, 0.12f), lighten(tint, 0.45f))
        else intArrayOf(0xFFFFB8D2.toInt(), 0xFFFFD9B0.toInt(), 0xFFFFF4B8.toInt(), 0xFFC9F2DD.toInt(), 0xFFBFD9FF.toInt(), 0xFFDCC8FF.toInt())

    companion object {
        fun lighten(color: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val c = (color shr shift) and 0xFF
                return (c + (255 - c) * amount).toInt().coerceIn(0, 255)
            }
            return (color and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        fun darken(color: Int, amount: Float): Int {
            fun channel(shift: Int) = (((color shr shift) and 0xFF) * (1 - amount)).toInt()
            return (color and 0xFF000000.toInt()) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        fun withAlpha(color: Int, alpha: Float): Int =
            ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0x00FFFFFF)

        /** Parses "#RRGGBB" or "#AARRGGBB"; null when malformed. */
        fun parseColor(text: String?): Int? {
            if (text == null || !text.startsWith("#")) return null
            val hex = text.substring(1)
            val value = hex.toLongOrNull(16) ?: return null
            return when (hex.length) {
                6 -> (0xFF000000 or value).toInt()
                8 -> value.toInt()
                else -> null
            }
        }
    }
}

/** Whether the system's contrast setting (Android 14 and later) is at medium or high. */
fun wantsMoreContrast(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 34) return false
    val manager = context.getSystemService(android.app.UiModeManager::class.java) ?: return false
    return manager.contrast >= 0.5f
}

/** Interface text in both scripts; the keyboard follows the script being typed. */
class Words(val simplified: Boolean) {
    operator fun invoke(traditional: String, simplifiedText: String) = if (simplified) simplifiedText else traditional
}

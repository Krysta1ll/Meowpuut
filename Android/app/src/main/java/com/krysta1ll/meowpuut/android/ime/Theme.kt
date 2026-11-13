package com.krysta1ll.meowpuut.android.ime

import com.krysta1ll.meowpuut.android.R

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

enum class EffectsLevel(val key: String) {
    LIGHTWEIGHT("lightweight"), STANDARD("standard"), FULL("full"), ULTRA("ultra");

    val hasFullEffects: Boolean get() = this == FULL || this == ULTRA

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: STANDARD
    }
}

enum class SkinMaterial { GLASS, PIXEL }
enum class GlassOptics { NEUTRAL, TINTED }
enum class CatSeating { PERCHED, EDGE_HUGGING }
enum class SkinCatArtwork { SCULPTED, LEGACY, PIXEL }

/** A skin selects presentation pieces. Input, layout mechanics and cat state are shared. */
enum class KeyboardSkin(
    val key: String,
    val title: Int,
    val material: SkinMaterial = SkinMaterial.GLASS,
    val catSeating: CatSeating = CatSeating.PERCHED,
    val catArtwork: SkinCatArtwork = SkinCatArtwork.SCULPTED,
    val fixedShape: GlassShape? = null,
    val optics: GlassOptics = GlassOptics.NEUTRAL,
    val supportsFestivals: Boolean = false,
    val requiresQuiz: Boolean = false,
) {
    CURRENT("current", R.string.skin_current, supportsFestivals = true),
    CLASSIC_CLOUD("classicCloud", R.string.skin_classic_cloud, catSeating = CatSeating.EDGE_HUGGING, catArtwork = SkinCatArtwork.LEGACY,
        fixedShape = GlassShape.CLOUD, optics = GlassOptics.TINTED),
    PIXEL("pixel", R.string.skin_pixel, material = SkinMaterial.PIXEL, catArtwork = SkinCatArtwork.PIXEL,
        fixedShape = GlassShape.CAPSULE, requiresQuiz = true);

    fun shape(preferred: GlassShape) = fixedShape ?: preferred

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: CURRENT
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
    val effects: EffectsLevel = EffectsLevel.STANDARD,
    val skin: KeyboardSkin = KeyboardSkin.CURRENT,
) {
    val usesSteppedMaterial get() = skin.material == SkinMaterial.PIXEL
    val edgeHuggingCats get() = skin.catSeating == CatSeating.EDGE_HUGGING
    val tintedGlass get() = skin.material == SkinMaterial.GLASS && skin.optics == GlassOptics.TINTED &&
        !opaque && effects != EffectsLevel.LIGHTWEIGHT
    val tint = palette.color(dark)
    val keyboardTop = when {
        usesSteppedMaterial -> blend(if (dark) 0xFF29243C.toInt() else 0xFFECE5F0.toInt(), tint, 0.10f)
        tintedGlass -> blend(if (dark) 0xFF29272C.toInt() else 0xFFF0EBEE.toInt(), tint, if (dark) 0.095f else 0.075f)
        else -> blend(if (dark) 0xFF29272C.toInt() else 0xFFF0EEF3.toInt(), tint, if (dark) 0.045f else 0.025f)
    }
    val keyboardMiddle = when {
        usesSteppedMaterial -> keyboardTop
        tintedGlass -> blend(if (dark) 0xFF242226.toInt() else 0xFFE7E2E5.toInt(), tint, if (dark) 0.075f else 0.035f)
        else -> blend(if (dark) 0xFF242226.toInt() else 0xFFE8E5EC.toInt(), tint, if (dark) 0.03f else 0.015f)
    }
    val keyboardBottom = if (usesSteppedMaterial) keyboardTop else if (dark) 0xFF1C1B1F.toInt() else 0xFFDCD9E1.toInt()
    val keyFaceTop get() = if (dark) 0xFF57565E.toInt() else 0xFFF9F7FA.toInt()
    val keyFaceBottom get() = if (dark) 0xFF4B4A52.toInt() else 0xFFF1EFF4.toInt()
    val keyUtility get() = if (dark) 0xFF3C3B40.toInt() else 0xFFCFCED8.toInt()
    val keyUtilityTop = blend(if (dark) 0xFF46444D.toInt() else 0xFFEBE8EF.toInt(), tint, 0.035f)
    val keyUtilityBottom = blend(if (dark) 0xFF3B3941.toInt() else 0xFFDEDBE4.toInt(), tint, 0.035f)
    /** Pale material on top of the keyboard's own tinted deck. */
    fun keyGlassTone(utility: Boolean): Int {
        val neutral = when {
            dark && utility -> 0xFF4F4C55.toInt()
            dark -> 0xFF726F78.toInt()
            utility -> 0xFFE3E0E9.toInt()
            else -> 0xFFFEFCFF.toInt()
        }
        val amount = if (tintedGlass) {
            when {
                utility -> if (dark) 0.14f else 0.10f
                dark -> 0.19f
                else -> 0.29f
            }
        } else if (dark) 0.075f else 0.035f
        return blend(neutral, tint, amount)
    }
    val keyGlassReflection = lighten(palette.color(dark = true), if (dark) 0.28f else 0.46f)
    val keyShadow get() = if (dark) 0x35000000 else 0x16000000
    val keyText get() = if (dark) 0xFFFFFFFF.toInt() else 0xFF18181B.toInt()
    val keyTextDisabled get() = if (dark) 0x66FFFFFF else 0x5518181B
    val candidateText get() = if (dark) 0xFFF5EFE9.toInt() else 0xFF3A3334.toInt()
    val candidateSecondary get() = if (dark) 0xFFCCC2BA.toInt() else 0xFF7A7072.toInt()
    val balloonFace get() = if (dark) 0xFF6B6A70.toInt() else 0xFFFFFFFF.toInt()
    val pixelSurface = blend(if (dark) 0xFF3B3352.toInt() else 0xFFFFF8F5.toInt(), tint, 0.06f)
    val pixelUtility = blend(if (dark) 0xFF4F4165.toInt() else 0xFFF1E3EA.toInt(), tint, 0.10f)
    val pixelEdge = if (dark) 0xFFAC95CE.toInt() else 0xFF72617F.toInt()
    val pixelAccent = blend(if (dark) 0xFF5E487B.toInt() else 0xFF674874.toInt(), tint, 0.14f)
    val pixelAccentPress = darken(pixelAccent, 0.22f)
    val pixelPress = if (dark) 0xFF685481.toInt() else 0xFFDBC7E2.toInt()

    val pill: Pair<Int, Int> = pillOverride?.let { lighten(it, 0.14f) to it } ?: palette.pill(dark)
    val selectedPill = darken(pill.first, 0.20f) to darken(pill.second, 0.10f)

    /** Accent for the return key when it performs an action (search, send…). */
    val accent get() = if (tintedGlass) selectedPill.second else pill.second

    /** Emulated glass body (no live backdrop blur on Android), DESIGN.md §3. */
    val glassFill: Int
        get() {
            if (opaque) return if (dark) 0xFF2B2729.toInt() else 0xFFF4F2F6.toInt()
            val alpha = when (strength) {
                GlassStrength.SOFT -> if (dark) 0.74f else 0.60f
                GlassStrength.BALANCED -> if (dark) 0.62f else 0.46f
                GlassStrength.VIVID -> if (dark) 0.50f else 0.32f
            }
            val rgb = if (dark) 0x353137 else 0xFFFFFF
            val transmission = if (effects == EffectsLevel.ULTRA) 0.78f else 1f
            return ((alpha * transmission * 255).toInt() shl 24) or rgb
        }

    /** The glass edge: white in light, the palette lifted toward white in dark. */
    val rim get() = if (dark) blend(0xFFDEDAE4.toInt(), tint, 0.35f) else 0xFFFFFFFF.toInt()

    /** Light: a pastel spectrum. Dark: the palette between two lighter tones of itself. */
    val causticColors: IntArray
        get() = if (dark) intArrayOf(tint, lighten(tint, 0.45f), darken(tint, 0.12f), lighten(tint, 0.45f))
        else intArrayOf(0xFFFFB8D2.toInt(), 0xFFFFD9B0.toInt(), 0xFFFFF4B8.toInt(), 0xFFC9F2DD.toInt(), 0xFFBFD9FF.toInt(), 0xFFDCC8FF.toInt())

    companion object {
        private fun blend(base: Int, tint: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val start = (base shr shift) and 0xFF
                val end = (tint shr shift) and 0xFF
                return (start + (end - start) * amount).toInt()
            }
            return 0xFF000000.toInt() or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

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
data class Words(val simplified: Boolean) {
    operator fun invoke(traditional: String, simplifiedText: String) = if (simplified) simplifiedText else traditional
}

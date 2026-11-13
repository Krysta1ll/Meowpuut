package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.content.SharedPreferences
import com.krysta1ll.meowpuut.android.engine.InputMode

/** Settings shared by the keyboard and the app (one process, one file). */
class Prefs(context: Context) {
    val store: SharedPreferences = context.getSharedPreferences("meowpuut", Context.MODE_PRIVATE)

    var mode: InputMode
        get() = InputMode.of(store.getString(MODE, null))
        set(value) = store.edit().putString(MODE, value.key).apply()
    var simplified: Boolean
        get() = store.getBoolean(SIMPLIFIED, false)
        set(value) = store.edit().putBoolean(SIMPLIFIED, value).apply()
    var shape: GlassShape
        get() = GlassShape.of(store.getString(SHAPE, null))
        set(value) = store.edit().putString(SHAPE, value.key).apply()
    var tone: Tone
        get() = Tone.of(store.getString(skinKey(TONE), null))
        set(value) = store.edit().putString(skinKey(TONE), value.key).apply()
    var palette: Palette
        get() = Palette.of(store.getString(skinKey(PALETTE), null))
        set(value) = store.edit().putString(skinKey(PALETTE), value.key).apply()
    var strength: GlassStrength
        get() = GlassStrength.of(store.getString(skinKey(STRENGTH), null))
        set(value) = store.edit().putString(skinKey(STRENGTH), value.key).apply()
    var effects: EffectsLevel
        get() = EffectsLevel.of(store.getString(EFFECTS, null))
        set(value) = store.edit().putString(EFFECTS, value.key).apply()
    var skin: KeyboardSkin
        get() = KeyboardSkin.of(store.getString(SKIN, null)).let {
            if (isSkinUnlocked(it)) it else KeyboardSkin.CURRENT
        }
        set(value) {
            if (isSkinUnlocked(value)) store.edit().putString(SKIN, value.key).apply()
        }

    fun isSkinUnlocked(skin: KeyboardSkin): Boolean {
        if (!skin.requiresQuiz) return true
        val unlocked = store.getStringSet(UNLOCKED_SKINS, emptySet()).orEmpty()
        return skin.key in unlocked || skin == KeyboardSkin.PIXEL && store.getBoolean(PIXEL_UNLOCKED, false)
    }

    fun unlockSkin(skin: KeyboardSkin) {
        val unlocked = store.getStringSet(UNLOCKED_SKINS, emptySet()).orEmpty()
        // Publish the new unlock and active skin together so the keyboard can
        // always read the selected skin when the preference listener fires.
        store.edit().putStringSet(UNLOCKED_SKINS, unlocked + skin.key).putString(SKIN, skin.key).apply()
    }

    private fun skinKey(base: String): String = when (val selected = skin) {
        KeyboardSkin.CURRENT -> base
        else -> "${selected.key}.$base"
    }

    fun appearanceSetting(key: String?): String? = key?.substringAfter('.')
    var keyboardHeight: KeyboardHeight
        get() = KeyboardHeight.of(store.getString(KEYBOARD_HEIGHT, null))
        set(value) = store.edit().putString(KEYBOARD_HEIGHT, value.key).remove(PANEL_HEIGHT_DELTA).apply()
    val panelHeightDelta: Int?
        get() = if (store.contains(PANEL_HEIGHT_DELTA)) {
            store.getInt(PANEL_HEIGHT_DELTA, 0).coerceIn(PanelAdjustment.MIN_HEIGHT, PanelAdjustment.MAX_HEIGHT)
        } else null
    val panelBottomGap: Int
        get() = store.getInt(PANEL_BOTTOM_GAP, 0).coerceIn(0, PanelAdjustment.MAX_BOTTOM_GAP)

    fun setDockedPanel(heightDelta: Int?, bottomGap: Int, heightPreset: KeyboardHeight) {
        val edit = store.edit().putString(KEYBOARD_HEIGHT, heightPreset.key)
            .putInt(PANEL_BOTTOM_GAP, bottomGap.coerceIn(0, PanelAdjustment.MAX_BOTTOM_GAP))
        if (heightDelta == null) edit.remove(PANEL_HEIGHT_DELTA)
        else edit.putInt(PANEL_HEIGHT_DELTA, heightDelta.coerceIn(PanelAdjustment.MIN_HEIGHT, PanelAdjustment.MAX_HEIGHT))
        edit.apply()
    }
    var candidateTextSize: KeyboardTextSize
        get() = KeyboardTextSize.of(store.getString(CANDIDATE_TEXT_SIZE, null))
        set(value) = store.edit().putString(CANDIDATE_TEXT_SIZE, value.key).apply()
    var keyTextSize: KeyboardTextSize
        get() = KeyboardTextSize.of(store.getString(KEY_TEXT_SIZE, null))
        set(value) = store.edit().putString(KEY_TEXT_SIZE, value.key).apply()
    var floatingKeyboard: Boolean
        get() = store.getBoolean(FLOATING_KEYBOARD, false)
        set(value) = store.edit().putBoolean(FLOATING_KEYBOARD, value).apply()
    val floatingX: Float get() = floatingFraction(FLOATING_X, 0.5f)
    val floatingY: Float get() = floatingFraction(FLOATING_Y, 1f)

    fun saveFloatingPosition(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        store.edit().putFloat(FLOATING_X, x.coerceIn(0f, 1f)).putFloat(FLOATING_Y, y.coerceIn(0f, 1f)).apply()
    }

    private fun floatingFraction(key: String, fallback: Float): Float {
        val value = store.getFloat(key, fallback)
        return if (value.isFinite()) value.coerceIn(0f, 1f) else fallback
    }

    var cats: Boolean
        get() = store.getBoolean(skinKey(CATS), true)
        set(value) = store.edit().putBoolean(skinKey(CATS), value).apply()
    var festivals: Boolean
        get() = store.getBoolean(FESTIVALS, true)
        set(value) = store.edit().putBoolean(FESTIVALS, value).apply()
    var keyPopups: Boolean
        get() = store.getBoolean(KEY_POPUPS, true)
        set(value) = store.edit().putBoolean(KEY_POPUPS, value).apply()
    var haptics: Boolean
        get() = store.getBoolean(HAPTICS, true)
        set(value) = store.edit().putBoolean(HAPTICS, value).apply()
    var hapticStrength: HapticStrength
        get() = HapticStrength.entries.getOrElse(store.getInt(HAPTIC_STRENGTH, 1)) { HapticStrength.STANDARD }
        set(value) = store.edit().putInt(HAPTIC_STRENGTH, value.ordinal).apply()
    var keySound: KeySound
        get() {
            val saved = store.getString(KEY_SOUND, null)
            return KeySound.entries.firstOrNull { it.key == saved }
                ?: if (store.getBoolean(SOUND, false)) KeySound.SYSTEM else KeySound.OFF
        }
        set(value) = store.edit().putString(KEY_SOUND, value.key).apply()
    var keySoundVolume: Int
        get() = store.getInt(KEY_SOUND_VOLUME, 100).coerceIn(0, 100)
        set(value) = store.edit().putInt(KEY_SOUND_VOLUME, value.coerceIn(0, 100)).apply()

    companion object {
        const val MODE = "mode"
        const val SIMPLIFIED = "simplified"
        const val SHAPE = "shape"
        const val TONE = "tone"
        const val PALETTE = "palette"
        const val STRENGTH = "strength"
        const val EFFECTS = "effects"
        const val SKIN = "skin"
        private const val UNLOCKED_SKINS = "unlockedSkins"
        private const val PIXEL_UNLOCKED = "pixelUnlocked"
        const val KEYBOARD_HEIGHT = "keyboardHeight"
        const val PANEL_HEIGHT_DELTA = "panelHeightDelta"
        const val PANEL_BOTTOM_GAP = "panelBottomGap"
        const val CANDIDATE_TEXT_SIZE = "candidateTextSize"
        const val KEY_TEXT_SIZE = "keyTextSize"
        const val FLOATING_KEYBOARD = "floatingKeyboard"
        const val FLOATING_X = "floatingKeyboardX"
        const val FLOATING_Y = "floatingKeyboardY"
        const val CATS = "cats"
        const val FESTIVALS = "festivals"
        const val KEY_POPUPS = "keyPopups"
        const val HAPTICS = "haptics"
        const val HAPTIC_STRENGTH = "hapticStrength"
        const val KEY_SOUND = "keySound"
        const val KEY_SOUND_VOLUME = "keySoundVolume"
        private const val SOUND = "sound"
    }
}

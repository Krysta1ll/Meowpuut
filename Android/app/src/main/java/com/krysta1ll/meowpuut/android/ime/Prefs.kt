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
        get() = Tone.of(store.getString(TONE, null))
        set(value) = store.edit().putString(TONE, value.key).apply()
    var palette: Palette
        get() = Palette.of(store.getString(PALETTE, null))
        set(value) = store.edit().putString(PALETTE, value.key).apply()
    var strength: GlassStrength
        get() = GlassStrength.of(store.getString(STRENGTH, null))
        set(value) = store.edit().putString(STRENGTH, value.key).apply()
    var cats: Boolean
        get() = store.getBoolean(CATS, true)
        set(value) = store.edit().putBoolean(CATS, value).apply()
    var festivals: Boolean
        get() = store.getBoolean(FESTIVALS, true)
        set(value) = store.edit().putBoolean(FESTIVALS, value).apply()
    var haptics: Boolean
        get() = store.getBoolean(HAPTICS, true)
        set(value) = store.edit().putBoolean(HAPTICS, value).apply()
    var sound: Boolean
        get() = store.getBoolean(SOUND, false)
        set(value) = store.edit().putBoolean(SOUND, value).apply()

    companion object {
        const val MODE = "mode"
        const val SIMPLIFIED = "simplified"
        const val SHAPE = "shape"
        const val TONE = "tone"
        const val PALETTE = "palette"
        const val STRENGTH = "strength"
        const val CATS = "cats"
        const val FESTIVALS = "festivals"
        const val HAPTICS = "haptics"
        const val SOUND = "sound"
    }
}

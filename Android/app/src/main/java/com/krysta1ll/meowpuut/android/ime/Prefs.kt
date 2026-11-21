package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.content.SharedPreferences
import com.krysta1ll.meowpuut.android.engine.CustomDictionary
import com.krysta1ll.meowpuut.android.engine.InputDefaults
import com.krysta1ll.meowpuut.android.engine.InputMode

/** Settings shared by the keyboard and the app (one process, one file). */
class Prefs(context: Context) {
    val store: SharedPreferences = context.getSharedPreferences("meowpuut", Context.MODE_PRIVATE)

    init {
        firstRun(context)
        if (store.contains(OLD_GLYPH_DISTANCE)) store.edit().remove(OLD_GLYPH_DISTANCE).apply()
    }

    /**
     * Runs once per install, before anything else is written. Settings from
     * before the switch existed mean an existing user, who keeps mixed typing
     * on and the mode they have; a store with nothing in it is a new install,
     * which starts with mixed typing off and the mode its system language and
     * region suggest. Only the resulting values are saved, never the region.
     */
    private fun firstRun(context: Context) {
        if (store.contains(MIXED_JAPANESE)) return
        val locales = context.resources.configuration.locales
        val tags = (0 until locales.size()).map { locales.get(it).toLanguageTag() }
        val plan = InputDefaults.firstRun(
            existingSettings = store.all.isNotEmpty() || hadEarlierVersion(context),
            hasModeOrScript = store.contains(MODE) || store.contains(SIMPLIFIED),
            region = if (locales.isEmpty) null else locales.get(0).country,
            languageTags = tags,
        )
        val edit = store.edit().putBoolean(MIXED_JAPANESE, plan.mixedJapanese)
        plan.start?.let { edit.putString(MODE, it.mode.key).putBoolean(SIMPLIFIED, it.simplified) }
        edit.apply()
    }

    /**
     * An earlier version only wrote this store when something was changed, so an empty store
     * does not mean a new install. Other files that version wrote by itself (the cat records,
     * the choice memory) or an update after the first install do.
     */
    private fun hadEarlierVersion(context: Context): Boolean {
        val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        if (listOf("cat_affinity", "choice-memory").any { java.io.File(dir, "$it.xml").exists() }) return true
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime - info.firstInstallTime > UPDATE_GAP_MS
        } catch (_: Exception) {
            false
        }
    }

    /** Japanese words among Zhuyin, Pinyin and nine-key candidates. The separate Japanese mode ignores it. */
    var mixedJapanese: Boolean
        get() = store.getBoolean(MIXED_JAPANESE, false)
        set(value) = store.edit().putBoolean(MIXED_JAPANESE, value).apply()

    var mode: InputMode
        get() = InputMode.of(store.getString(MODE, null))
        set(value) = store.edit().putString(MODE, value.key).apply()
    var zhuyinPinyinHints: Boolean
        get() = store.getBoolean(ZHUYIN_PINYIN_HINTS, false)
        set(value) = store.edit().putBoolean(ZHUYIN_PINYIN_HINTS, value).apply()
    var simplified: Boolean
        get() = store.getBoolean(SIMPLIFIED, false)
        set(value) = store.edit().putBoolean(SIMPLIFIED, value).apply()
    var englishAutoCaps: Boolean
        get() = store.getBoolean(ENGLISH_AUTO_CAPS, true)
        set(value) = store.edit().putBoolean(ENGLISH_AUTO_CAPS, value).apply()
    var rememberChoices: Boolean
        get() = store.getBoolean(REMEMBER_CHOICES, true)
        set(value) = store.edit().putBoolean(REMEMBER_CHOICES, value).apply()
    internal var customDictionary: List<CustomDictionary.Entry>
        get() = CustomDictionary.decode(store.getString(CUSTOM_DICTIONARY, null))
        set(value) = store.edit().putString(CUSTOM_DICTIONARY, CustomDictionary.encode(value)).apply()
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

    /** The applied custom skin as "<id>@<rev>" (always re-parsed, never a path), or null for a built-in skin. */
    var customSkin: String?
        get() = SkinRef.parse(store.getString(CUSTOM_SKIN, null))?.toString()
        set(value) {
            val ref = SkinRef.parse(value)
            store.edit().apply { if (ref == null) remove(CUSTOM_SKIN) else putString(CUSTOM_SKIN, ref.toString()) }.apply()
        }

    /** Changed whenever installed skins, the device state or revocations change: the keyboard then verifies again. */
    fun touchCustomSkins() {
        store.edit().putLong(CUSTOM_SKIN_EPOCH, store.getLong(CUSTOM_SKIN_EPOCH, 0L) + 1).apply()
    }

    /** Whether the person has chosen a glass shape themselves (a custom skin's own shape applies until then). */
    val hasShape: Boolean get() = store.contains(SHAPE)

    /** The debug-only settings (the guest crab, holding one place or one moment of 字景) answer only in a debuggable build. */
    private val debuggable = CustomSkinLoader.debuggable(context)

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

    /** The guest crab of 字景 (Anthropic's mascot): off, and offered in debug builds only, until it is licensed. */
    var glyphCrab: Boolean
        get() = debuggable && store.getBoolean(GLYPH_CRAB, false)
        set(value) = store.edit().putBoolean(GLYPH_CRAB, value).apply()

    /** Debug builds only: -1 travels as usual, 0..5 stays in that place of 字景. */
    val glyphScene: Int? get() = if (debuggable) store.getInt(GLYPH_SCENE, -1).takeIf { it >= 0 } else null

    /** Debug builds only: holds one moment of one of 字景's effects, as "storm:6"; unset lets them happen. */
    val glyphFx: String? get() = if (debuggable) store.getString("glyphFx", null) else null

    /** Debug builds only: holds one moment of 字景's arrival, 0..1; unset lets it run. */
    val glyphForm: Double? get() = if (debuggable && store.contains(GLYPH_FORM)) store.getFloat(GLYPH_FORM, 1f).toDouble() else null

    var cats: Boolean
        get() = store.getBoolean(skinKey(CATS), true)
        set(value) = store.edit().putBoolean(skinKey(CATS), value).apply()
    /** How much the cats do by themselves (docs/PETS.md): full, quiet or off. */
    var petMoments: PetMoments
        get() = PetMoments.of(store.getString(PET_MOMENTS, null))
        set(value) = store.edit().putString(PET_MOMENTS, value.key).apply()
    var festivals: Boolean
        get() = store.getBoolean(FESTIVALS, true)
        set(value) = store.edit().putBoolean(FESTIVALS, value).apply()
    var keyPopups: Boolean
        get() = store.getBoolean(KEY_POPUPS, true)
        set(value) = store.edit().putBoolean(KEY_POPUPS, value).apply()
    var haptics: Boolean
        get() = store.getBoolean(HAPTICS, false)
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
        const val ZHUYIN_PINYIN_HINTS = "zhuyinPinyinHints"
        const val SIMPLIFIED = "simplified"
        const val MIXED_JAPANESE = "mixedJapanese"
        const val ENGLISH_AUTO_CAPS = "englishAutoCaps"
        const val REMEMBER_CHOICES = "rememberChoices"
        const val CLEAR_CHOICE_MEMORY = "clearChoiceMemory"
        const val CUSTOM_DICTIONARY = "customDictionary"
        const val PET_MOMENTS = "petMoments"
        /** Written by the app when the cat records are cleared; the running keyboard clears its copy on seeing it. */
        const val CLEAR_PETS = "clearPetsRequest"
        const val SHAPE = "shape"
        const val TONE = "tone"
        const val PALETTE = "palette"
        const val STRENGTH = "strength"
        const val EFFECTS = "effects"
        const val SKIN = "skin"
        const val CUSTOM_SKIN = "customSkin"
        const val CUSTOM_SKIN_EPOCH = "customSkinEpoch"
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
        /** The journey's distance used to be kept here; it is worked out from the cats' character count now, and the old number is removed once. */
        private const val OLD_GLYPH_DISTANCE = "glyphDistance"
        const val GLYPH_CRAB = "glyphCrab"
        const val GLYPH_SCENE = "glyphScene"
        const val GLYPH_FORM = "glyphForm"
        const val FESTIVALS = "festivals"
        const val KEY_POPUPS = "keyPopups"
        const val HAPTICS = "haptics"
        const val HAPTIC_STRENGTH = "hapticStrength"
        const val KEY_SOUND = "keySound"
        const val KEY_SOUND_VOLUME = "keySoundVolume"
        private const val SOUND = "sound"
        private const val UPDATE_GAP_MS = 60_000L
    }
}

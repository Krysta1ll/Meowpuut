package com.krysta1ll.meowpuut.android.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.app.home.Brush
import com.krysta1ll.meowpuut.android.app.home.HomeEntries
import com.krysta1ll.meowpuut.android.app.home.HomeEntry
import com.krysta1ll.meowpuut.android.app.home.HomeHero
import com.krysta1ll.meowpuut.android.app.stats.JourneyStats
import com.krysta1ll.meowpuut.android.app.stats.BundledFestivals
import com.krysta1ll.meowpuut.android.app.stats.PetSyncStore
import com.krysta1ll.meowpuut.android.app.stats.StatsStore
import com.krysta1ll.meowpuut.android.engine.Candidate
import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.ChoiceMemory
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.CandidateBarView
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.ClipboardShelf
import com.krysta1ll.meowpuut.android.ime.Cat
import com.krysta1ll.meowpuut.android.ime.CatSprites
import com.krysta1ll.meowpuut.android.ime.CustomSkinLoader
import com.krysta1ll.meowpuut.android.ime.LoadedSkin
import com.krysta1ll.meowpuut.android.ime.EffectsLevel
import com.krysta1ll.meowpuut.android.ime.GlassOutline
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.GlassStrength
import com.krysta1ll.meowpuut.android.ime.HapticStrength
import com.krysta1ll.meowpuut.android.ime.Idiom
import com.krysta1ll.meowpuut.android.ime.KeyBuilder
import com.krysta1ll.meowpuut.android.ime.KeyContext
import com.krysta1ll.meowpuut.android.ime.KeyMetrics
import com.krysta1ll.meowpuut.android.ime.KeySound
import com.krysta1ll.meowpuut.android.ime.LocalCatAffinity
import com.krysta1ll.meowpuut.android.ime.PetFormPreference
import com.krysta1ll.meowpuut.android.ime.PetMoments
import com.krysta1ll.meowpuut.android.ime.KeysView
import com.krysta1ll.meowpuut.android.ime.KeyboardHeight
import com.krysta1ll.meowpuut.android.ime.KeyboardTextSize
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.Palette
import com.krysta1ll.meowpuut.android.ime.PanelAdjustment
import com.krysta1ll.meowpuut.android.ime.Prefs
import com.krysta1ll.meowpuut.android.ime.PreviewOverlay
import com.krysta1ll.meowpuut.android.ime.ShiftState
import com.krysta1ll.meowpuut.android.ime.SkinMaterial
import com.krysta1ll.meowpuut.android.ime.Tone
import com.krysta1ll.meowpuut.android.ime.Words
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphJourney
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import com.krysta1ll.meowpuut.android.ime.wantsMoreContrast
import kotlin.math.roundToInt

/** Setup steps, a test field and the keyboard's settings. */
class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var preview: BarPreview
    private lateinit var gallery: SkinGallery
    private lateinit var customSkinNote: TextView
    private lateinit var shapeRow: View
    private lateinit var paletteRow: View
    private lateinit var commemorativeColorNote: TextView
    private lateinit var glassRow: View
    private lateinit var catsSwitch: Switch
    private lateinit var festivalSwitch: Switch
    private lateinit var glyphCrabSwitch: Switch
    private lateinit var festivalHint: TextView
    private val updateChoices = mutableMapOf<String, () -> Unit>()
    private val density get() = resources.displayMetrics.density
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    // Paper colours (assets/brand/paper/paper-tokens.json) for the chosen colour system. Read when the page opens.
    private lateinit var ui: PaperUi
    private lateinit var stamp: StampToast
    private lateinit var photoStore: CatPhotoStore
    private lateinit var photoWall: CatPhotoWall
    private lateinit var statsStore: StatsStore
    private lateinit var scroller: ScrollView
    private lateinit var homeColumn: LinearLayout
    private lateinit var setupEntry: HomeEntry
    private var setupCard: View? = null
    private var inputCard: View? = null
    private val ink get() = ui.p.ink
    private val quietInk get() = ui.p.inkSecondary
    private val accentInk get() = ui.p.stamp
    private val cardFill get() = ui.p.paper2
    private val outline get() = ui.line
    private val effectNames = listOf(R.string.effects_lightweight, R.string.effects_standard, R.string.effects_full, R.string.effects_ultra)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        com.krysta1ll.meowpuut.android.app.stats.DemoHooks.enable(this, intent)   // (debug builds only)
        if ((savedInstanceState == null && com.krysta1ll.meowpuut.android.app.onboarding.Onboarding.due(this, prefs.store)) || com.krysta1ll.meowpuut.android.app.onboarding.Onboarding.resumable(prefs.store)) startActivity(Intent(this, OnboardingActivity::class.java))   // 第一行字: every device sees the guide once; one the system ended (even a crash that brings only this page back) is taken up again where it was
        ui = PaperUi(this, AppSkins.worn(prefs, dark), dark)
        stamp = StampToast(this, ui)
        val column = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(View.MeasureSpec.getSize(widthMeasureSpec), dp(680))
                super.onMeasure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(16), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            background = ui.background()
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            isVerticalScrollBarEnabled = false
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    stamp.translationY = bars.top.toFloat()
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                    stamp.translationY = insets.systemWindowInsetTop.toFloat()
                }
                insets
            }
        }

        scroller = scroll
        homeColumn = column
        statsStore = StatsStore(this, BundledFestivals.of(this))
        when (ui.skin.slots.hero) {
            HeroSlot.MASTHEAD -> addMasthead(column)
            HeroSlot.SCENE -> addSceneHero(column)
        }
        addEntries(column)

        preview = BarPreview(this, prefs)
        val look = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(16)
            clipToOutline = true
            visibility = View.GONE
        }
        gallery = SkinGallery(this, ui, preview, getString(R.string.skin_wardrobe),
            KeyboardSkin.entries.map { SkinGallery.Option(it, getString(it.title)) },
            getString(R.string.skin_customize), ::selectSkin) {
            look.visibility = if (look.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (look.visibility == View.VISIBLE) look.post { scroll.smoothScrollTo(0, look.top - dp(8)) }
        }
        column.addView(gallery, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })
        column.addView(look, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        gallery.render(prefs.skin, prefs::isSkinUnlocked)
        customSkinNote = textView(13f, quietInk).apply {
            setPadding(dp(14), dp(4), dp(14), dp(2))
            visibility = View.GONE
        }
        column.addView(customSkinNote)
        if (CustomSkinLoader.supported(this)) {
            column.addView(action(R.string.cs_open) { startActivity(Intent(this, RedeemActivity::class.java)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.END
                })
        }

        val setup = section(column, R.string.setup_title)
        setupCard = setup
        status = textView(14f, quietInk).apply { setPadding(dp(16), dp(12), dp(16), dp(2)) }
        setup.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(action(R.string.setup_open_settings) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, weighted())
        buttons.addView(action(R.string.setup_pick) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }, weighted())
        setup.addView(buttons)

        photoStore = CatPhotoStore(this)
        photoWall = CatPhotoWall(this, ui, photoStore, Brush(this, dark, wantsMoreContrast(this)))
        column.addView(photoWall, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(20)
        })

        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            background = glassSurface(14)
            clipToOutline = true
        }
        column.addView(shortcuts, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        val input = section(column, R.string.input_title)
        inputCard = input
        choice(input, R.string.input_mode, Prefs.MODE, listOf(
            R.string.mode_zhuyin to InputMode.ZHUYIN,
            R.string.mode_pinyin to InputMode.PINYIN,
            R.string.mode_nine_key to InputMode.NINE_KEY,
            R.string.mode_japanese to InputMode.JAPANESE,
        ), { prefs.mode }) { prefs.mode = it }
        mimimoRow(input)
        toggle(input, R.string.zhuyin_pinyin_hints, prefs.zhuyinPinyinHints) { prefs.zhuyinPinyinHints = it }
        choice(input, R.string.script, Prefs.SIMPLIFIED, listOf(R.string.script_traditional to false, R.string.script_simplified to true),
            { prefs.simplified }) { prefs.simplified = it }
        note(input, R.string.script_note)
        toggle(input, R.string.mixed_japanese, prefs.mixedJapanese) { prefs.mixedJapanese = it }
        note(input, R.string.mixed_japanese_note)
        toggle(input, R.string.english_auto_caps, prefs.englishAutoCaps) { prefs.englishAutoCaps = it }
        toggle(input, R.string.remember_choices, prefs.rememberChoices) { prefs.rememberChoices = it }
        note(input, R.string.remember_choices_note)
        toggle(input, R.string.clipboard, prefs.clipboard) {
            prefs.clipboard = it
            // Off: what was kept goes too, the pinned file included (the keyboard, if it is running, forgets its copy on seeing the switch).
            if (!it) ClipboardShelf.deleteFile(java.io.File(noBackupFilesDir, ClipboardShelf.FILE_NAME))
        }
        note(input, R.string.clipboard_note)
        input.addView(action(R.string.clear_clipboard) {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_clipboard_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_clipboard) { _, _ ->
                    ClipboardShelf.deleteFile(java.io.File(noBackupFilesDir, ClipboardShelf.FILE_NAME))
                    prefs.requestClearClipboard()
                    stamp.show(getString(R.string.stamp_ok), getString(R.string.stamp_clipboard_cleared))
                }
                .show()
        })
        input.addView(action(R.string.dictionary_title) { CustomDictionaryDialog(this, prefs).show() })
        val memoryEmpty = note(input, R.string.choice_memory_empty).apply { visibility = View.GONE }
        input.addView(action(R.string.clear_choice_memory) {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_choice_memory_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_choice_memory) { _, _ ->
                    ChoiceMemory(this).clear()
                    prefs.store.edit().putLong(Prefs.CLEAR_CHOICE_MEMORY, System.nanoTime()).apply()
                    memoryEmpty.visibility = View.VISIBLE
                    stamp.show(getString(R.string.stamp_ok), getString(R.string.stamp_memory_cleared))
                }
                .show()
        })

        sectionTitle(column, R.string.try_title)
        column.addView(EditText(this).apply {
            setHint(R.string.try_hint)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ink)
            setHintTextColor(quietInk)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = surface(16)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val display = section(column, R.string.look_title)
        feedbackSlider(display, R.string.effects, EffectsLevel.entries.lastIndex, prefs.effects.ordinal, true,
            label = { getString(effectNames[it]) }, change = { prefs.effects = EffectsLevel.entries[it] })
        note(display, R.string.effects_note)
        choice(display, R.string.keyboard_height, Prefs.KEYBOARD_HEIGHT, listOf(
            R.string.keyboard_height_compact to KeyboardHeight.COMPACT,
            R.string.effects_standard to KeyboardHeight.STANDARD,
            R.string.keyboard_height_tall to KeyboardHeight.TALL,
        ), { prefs.keyboardHeight }) { prefs.keyboardHeight = it }
        panelAdjustmentRow(display)
        val textSizes = listOf(
            R.string.keyboard_text_size_small to KeyboardTextSize.SMALL,
            R.string.effects_standard to KeyboardTextSize.STANDARD,
            R.string.keyboard_text_size_large to KeyboardTextSize.LARGE,
        )
        choice(display, R.string.candidate_text_size, Prefs.CANDIDATE_TEXT_SIZE, textSizes,
            { prefs.candidateTextSize }) { prefs.candidateTextSize = it }
        choice(display, R.string.key_text_size, Prefs.KEY_TEXT_SIZE, textSizes,
            { prefs.keyTextSize }) { prefs.keyTextSize = it }
        val updatePosition = choice(display, R.string.keyboard_position, Prefs.FLOATING_KEYBOARD, listOf(
            R.string.keyboard_position_docked to false,
            R.string.keyboard_position_floating to true,
        ), { prefs.floatingKeyboard }) { prefs.floatingKeyboard = it }
        val floatingHint = note(display, R.string.floating_keyboard_hint)
        val updateFloating = {
            updatePosition()
            floatingHint.visibility = if (prefs.floatingKeyboard) View.VISIBLE else View.GONE
        }
        updateChoices[Prefs.FLOATING_KEYBOARD] = updateFloating
        updateFloating()
        choice(look, R.string.shape, Prefs.SHAPE, listOf(R.string.shape_capsule to GlassShape.CAPSULE, R.string.shape_wave to GlassShape.WAVE,
            R.string.shape_cloud to GlassShape.CLOUD), { prefs.shape }) { prefs.shape = it }
        shapeRow = look.getChildAt(look.childCount - 1)
        choice(look, R.string.tone, Prefs.TONE, listOf(R.string.tone_system to Tone.SYSTEM, R.string.tone_light to Tone.LIGHT,
            R.string.tone_dark to Tone.DARK), { prefs.tone }) { prefs.tone = it }
        choice(look, R.string.palette, Prefs.PALETTE, listOf(R.string.palette_rose to Palette.ROSE, R.string.palette_ocean to Palette.OCEAN,
            R.string.palette_sage to Palette.SAGE, R.string.palette_lavender to Palette.LAVENDER, R.string.palette_amber to Palette.AMBER,
            R.string.palette_graphite to Palette.GRAPHITE), { prefs.palette }) { prefs.palette = it }
        paletteRow = look.getChildAt(look.childCount - 1)
        commemorativeColorNote = note(look, R.string.skin_centenary_colors)
        choice(look, R.string.strength, Prefs.STRENGTH, listOf(R.string.strength_soft to GlassStrength.SOFT,
            R.string.strength_balanced to GlassStrength.BALANCED, R.string.strength_vivid to GlassStrength.VIVID), { prefs.strength }) { prefs.strength = it }
        glassRow = look.getChildAt(look.childCount - 1)
        catsSwitch = toggle(look, R.string.cats, prefs.cats) { prefs.cats = it }
        choice(look, R.string.pet_form, Prefs.PET_FORM, listOf(
            R.string.pet_form_auto to PetFormPreference.AUTO,
            R.string.pet_form_3d to PetFormPreference.THREE_D,
            R.string.pet_form_pixel to PetFormPreference.PIXEL,
        ), { prefs.petForm }) { prefs.petForm = it }
        note(look, R.string.pet_form_note)
        festivalSwitch = toggle(look, R.string.festivals, prefs.festivals) { prefs.festivals = it }
        festivalHint = note(look, R.string.festivals_note)
        // The guest crab of 字景 is Anthropic's mascot: off, and offered in debug builds only, until it is licensed.
        glyphCrabSwitch = toggle(look, R.string.glyph_crab_title, prefs.glyphCrab) { prefs.glyphCrab = it }
        choice(look, R.string.pet_moments, Prefs.PET_MOMENTS, PetMoments.entries.map { it.label to it }, { prefs.petMoments }) { prefs.petMoments = it }
        note(look, R.string.pet_moments_note)
        look.addView(action(R.string.pet_hub_open) { startActivity(Intent(this, PetHubActivity::class.java)) })
        look.addView(action(R.string.clear_pets) {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_pets_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_pets) { _, _ ->
                    // The records and the statistics go together: the file is emptied (at a higher epoch), the ledger starts over, the journey too.
                    PetSyncStore(filesDir).resetEpoch()
                    statsStore.forget()
                    // A keyboard that is running clears its own copy, so it cannot write the old counts back.
                    prefs.store.edit().putLong(Prefs.CLEAR_PETS, System.nanoTime()).apply()
                }
                .show()
        })
        refreshSkin()

        val feedback = section(column, R.string.feedback_title)
        feedback.setPadding(0, 0, 0, dp(8))
        toggle(feedback, R.string.key_popups, prefs.keyPopups) { prefs.keyPopups = it }
        toggle(feedback, R.string.haptics, prefs.haptics) { prefs.haptics = it }
        val strengthSlider = feedbackSlider(
            feedback, R.string.haptic_strength, HapticStrength.entries.lastIndex,
            prefs.hapticStrength.ordinal, prefs.haptics,
            label = { getString(HapticStrength.entries[it].label) },
            change = { prefs.hapticStrength = HapticStrength.entries[it] },
        )
        updateChoices[Prefs.HAPTICS] = { strengthSlider.isEnabled = prefs.haptics }
        val updateSound = choice(feedback, R.string.sound, Prefs.KEY_SOUND, KeySound.entries.map { it.label to it }, { prefs.keySound }) {
            prefs.keySound = it
        }
        val volumeSlider = feedbackSlider(
            feedback, R.string.sound_volume, 100, prefs.keySoundVolume, prefs.keySound != KeySound.OFF,
            label = { getString(R.string.sound_volume_percent, it) },
            change = { prefs.keySoundVolume = it },
        )

        updateChoices[Prefs.KEY_SOUND] = {
            updateSound()
            volumeSlider.isEnabled = prefs.keySound != KeySound.OFF
        }
        listOf(R.string.input_title to input, R.string.look_title to display, R.string.feedback_title to feedback)
            .forEach { (label, card) ->
                shortcuts.addView(action(label) {
                    val heading = column.getChildAt(column.indexOfChild(card) - 1)
                    scroll.smoothScrollTo(0, (heading.top - dp(8)).coerceAtLeast(0))
                }.apply {
                    setTextColor(ink)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }, weighted())
            }
        column.addView(action(R.string.onboarding_replay) { startActivity(Intent(this, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_REPLAY, true)) })   // 第一行字: see the guide again
        column.addView(action(R.string.licenses, ::showLicenses), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        packageManager.getPackageInfo(packageName, 0).versionName?.let { version ->
            column.addView(TextView(this).apply {
                text = version
                textSize = 12f
                alpha = 0.72f
            })
        }
        setContentView(FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(stamp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(12) })
        })
        prefs.store.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        photoWall.refreshIfDayChanged()
        refreshStatsEntry()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    override fun onDestroy() {
        photoStore.shutdown()
        statsStore.shutdown()
        prefs.store.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(store: SharedPreferences?, key: String?) {
        val setting = prefs.appearanceSetting(key)
        if (setting != null) updateChoices[setting]?.invoke()
        if (setting == Prefs.SKIN || key == Prefs.CUSTOM_SKIN) {
            // A skin that answers the pages differently (its own paper, its own top) is a different page: it is built again.
            if (AppSkins.worn(prefs, dark).key != ui.skin.key) { recreate(); return }
        }
        if (setting == Prefs.SKIN || setting == Prefs.CATS || key == Prefs.CUSTOM_SKIN) refreshSkin()
        if (key == Prefs.CUSTOM_SKIN || key == Prefs.CUSTOM_SKIN_EPOCH) preview.refresh()
        when (setting) {
            Prefs.MODE, Prefs.ZHUYIN_PINYIN_HINTS, Prefs.SIMPLIFIED, Prefs.TONE, Prefs.SHAPE, Prefs.PALETTE, Prefs.STRENGTH,
            Prefs.CATS, Prefs.EFFECTS, Prefs.KEYBOARD_HEIGHT, Prefs.CANDIDATE_TEXT_SIZE, Prefs.KEY_TEXT_SIZE, Prefs.SKIN -> preview.refresh()
        }
    }

    private fun selectSkin(skin: KeyboardSkin) {
        if (!prefs.isSkinUnlocked(skin)) showPixelQuiz(skin)
        else {
            // Choosing a built-in skin drops any applied custom skin.
            prefs.customSkin = null
            prefs.skin = skin
        }
    }

    private fun refreshSkin() {
        gallery.render(prefs.skin, prefs::isSkinUnlocked)
        val skin = prefs.skin
        // A custom skin brings its own palette and glass strength; the person's shape still applies unless it locks one.
        val custom = prefs.customSkin
        customSkinNote.text = if (custom == null) "" else getString(R.string.cs_in_use_note, custom)
        customSkinNote.visibility = if (custom == null) View.GONE else View.VISIBLE
        shapeRow.visibility = if (custom != null || skin.fixedShape == null) View.VISIBLE else View.GONE
        // 字景 has its own paper and ink: the palette would change nothing in it.
        paletteRow.visibility = if (custom == null && skin != KeyboardSkin.CENTENARY && skin != KeyboardSkin.GLYPH) View.VISIBLE else View.GONE
        commemorativeColorNote.visibility = if (custom == null && skin == KeyboardSkin.CENTENARY) View.VISIBLE else View.GONE
        glassRow.visibility = if (custom == null && skin.material == SkinMaterial.GLASS) View.VISIBLE else View.GONE
        festivalSwitch.visibility = if (skin.supportsFestivals) View.VISIBLE else View.GONE
        festivalHint.visibility = festivalSwitch.visibility
        glyphCrabSwitch.visibility = if (skin == KeyboardSkin.GLYPH && CustomSkinLoader.debuggable(this)) View.VISIBLE else View.GONE
        for (key in listOf(Prefs.TONE, Prefs.PALETTE, Prefs.STRENGTH)) updateChoices[key]?.invoke()
        if (catsSwitch.isChecked != prefs.cats) catsSwitch.isChecked = prefs.cats
    }

    private fun updateStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        status.setText(when {
            current.startsWith("$packageName/") -> R.string.setup_selected
            enabled -> R.string.setup_enabled
            else -> R.string.setup_off
        })
        if (::setupEntry.isInitialized) updateSetupEntry()
    }

    private fun showLicenses() {
        // The lexicon's licences, then those of the fonts the app carries (the app pages' and the 字景 skin's pixel type).
        val files = listOf("licenses", "font-licenses").flatMap { dir -> assets.list(dir)?.sorted().orEmpty().map { "$dir/$it" to it } }
        val text = files.joinToString("\n\n") { (path, name) ->
            val body = runCatching { assets.open(path).use { it.readBytes().decodeToString() } }.getOrDefault("")
            "■ $name\n\n$body"
        }
        val view = TextView(this).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.licenses)
            .setView(ScrollView(this).apply { addView(view) })
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showPixelQuiz(skin: KeyboardSkin, previous: PixelQuestion? = null) {
        val question = pixelQuestions.filterNot { it == previous }.random()
        lateinit var dialog: AlertDialog
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(textView(16f).apply {
                setText(R.string.skin_pixel_question)
                setPadding(dp(4), dp(4), dp(4), dp(8))
            })
            addView(textView(16f).apply {
                text = question.code
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = surface(8)
            })
            for (answer in question.answers.shuffled()) {
                addView(Button(this@SettingsActivity).apply {
                    text = answer
                    isAllCaps = false
                    typeface = Typeface.MONOSPACE
                    setOnClickListener {
                        dialog.dismiss()
                        if (answer == question.answers.first()) {
                            prefs.customSkin = null
                            prefs.unlockSkin(skin)
                            stamp.show(getString(R.string.stamp_pass), getString(R.string.stamp_skin_unlocked))
                        }
                        else AlertDialog.Builder(this@SettingsActivity)
                            .setMessage(R.string.skin_pixel_try_again)
                            .setPositiveButton(R.string.skin_pixel_retry) { _, _ -> showPixelQuiz(skin, question) }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                })
            }
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.skin_unlock_title, getString(skin.title)))
            .setNegativeButton(R.string.cancel, null)
            .setView(content)
            .create()
        dialog.show()
    }

    private data class PixelQuestion(val code: String, val answers: List<String>)

    private val pixelQuestions = listOf(
        PixelQuestion("print(2 ** 3)", listOf("8", "6", "9")),
        PixelQuestion("print(len('momo'))", listOf("4", "3", "5")),
        PixelQuestion("print([2, 4, 6][1])", listOf("4", "2", "6")),
        PixelQuestion("print(7 // 2)", listOf("3", "3.5", "4")),
        PixelQuestion("print('meow' * 2)", listOf("meowmeow", "meow2", "meow meow")),
        PixelQuestion("print(11 % 4)", listOf("3", "2", "4")),
    )

    /** The top of the page as it has always been: the name, and the two mascots that answer a touch. */
    private fun addMasthead(column: LinearLayout) {
        val masthead = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassSurface(24, hero = true)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        masthead.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            isBaselineAligned = false
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(textView(28f).apply {
                    setText(R.string.app_name)
                    typeface = PaperFonts.title(this@SettingsActivity)
                    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                })
                addView(textView(16f, quietInk).apply {
                    text = "Meowpuut"
                    typeface = PaperFonts.wordmark(this@SettingsActivity)
                })
            }, weighted())
            addView(mascot("米米莫", R.drawable.cat_classic_mimimo_open, R.drawable.cat_classic_mimimo_blink, 1.5f, 450),
                LinearLayout.LayoutParams(dp(54), dp(58)))
            addView(mascot("莫莫", R.drawable.cat_classic_momo_open, R.drawable.cat_classic_momo_happy, 4f, 650),
                LinearLayout.LayoutParams(dp(49), dp(54)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(masthead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(ui.stitch(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)).apply { topMargin = dp(10) })
    }

    /** The top of the page in a skin that has a picture for it: where the two cats have got to, alive. */
    private fun addSceneHero(column: LinearLayout) {
        val distance = GlyphJourney.distanceOf(statsStore.committedCharacters())
        val places = JourneyStats.places(distance)
        val name = GlyphScenes.ALL[places.here].name.let { if (simplifiedUi()) HOME_SIMPLIFIED_PLACES[it] ?: it else it }
        val describe = getString(R.string.home_hero_desc, name, places.stop)
        val hero = HomeHero.build(this, ui, getString(R.string.home_where, name), getString(R.string.home_stop, places.stop),
            describe, distance, prefs.effects.ordinal, wantsMoreContrast(this), dp(20))
        column.addView(hero.view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // On a festival's day its stamp stands by the road in the picture (not on a quiet day). The festivals are read off the main thread.
        val simplified = simplifiedUi()
        Thread {
            val festivals = BundledFestivals.of(this)
            val today = JourneyStats.today(festivals.occurrences(), com.krysta1ll.meowpuut.android.app.stats.DemoHooks.today())
            val rows = today?.let { f -> com.krysta1ll.meowpuut.android.app.home.Stamps.ofFestival(f.stamp, festivals) }
            if (today != null && rows != null) runOnUiThread {
                hero.picture.contentDescription = describe + getString(R.string.home_festival_desc, today.name(simplified))
                hero.picture.festival = com.krysta1ll.meowpuut.android.app.home.Standing(rows)
            }
        }.apply { isDaemon = true }.start()
    }

    /** The ways in: the settings, the wardrobe, how to add the keyboard, and 行旅. A skin chooses how they are shown. */
    private fun addEntries(column: LinearLayout) {
        val modeName = getString(when (prefs.mode) {
            InputMode.ZHUYIN -> R.string.mode_zhuyin
            InputMode.PINYIN -> R.string.mode_pinyin
            InputMode.NINE_KEY -> R.string.mode_nine_key
            InputMode.JAPANESE -> R.string.mode_japanese
        })
        val skinName = prefs.customSkin ?: getString(prefs.skin.title)
        val stats = HomeEntry(getString(R.string.stats_open), "", "light", own = true) { startActivity(Intent(this, StatsActivity::class.java)) }
        setupEntry = HomeEntry(getString(R.string.home_sign_setup), "", "torii", own = false) { jump(setupCard) }
        val list = listOf(
            HomeEntry(getString(R.string.home_sign_settings), modeName, "hut", own = false) { jump(inputCard) },
            HomeEntry(getString(R.string.home_sign_wardrobe), skinName, "lantern", own = false) { jump(gallery) },
            setupEntry,
            stats,
        )
        val view = HomeEntries.build(this, ui, ui.skin.slots.entries, list)
        column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (ui.skin.slots.entries == EntryStyle.SIGNPOSTS) 6 else 14)
        })
        statsEntry = stats
        refreshStatsEntry()
        updateSetupEntry()
    }

    private var statsEntry: HomeEntry? = null

    /** How many mountains 行旅 has, read from the ledger (which also takes in what the keyboard has written since). */
    private fun refreshStatsEntry() {
        val entry = statsEntry ?: return
        statsStore.refreshAsync { s -> entry.valueView?.invoke(if (s.daysMet > 0) getString(R.string.stats_open_value, s.daysMet) else getString(R.string.stats_open_none)) }
    }

    private fun updateSetupEntry() {
        val value = when {
            !::status.isInitialized -> return
            status.text == getString(R.string.setup_selected) -> getString(R.string.home_setup_selected)
            status.text == getString(R.string.setup_enabled) -> getString(R.string.home_setup_on)
            else -> getString(R.string.home_setup_off)
        }
        setupEntry.valueView?.invoke(value)
    }

    /** Scrolls the page to the start of a part of it (its heading is the view before it). */
    private fun jump(target: View?) {
        val view = target ?: return
        val column = homeColumn
        val heading = if (view === gallery || view.parent !== column) view else column.getChildAt(column.indexOfChild(view) - 1)
        scroller.smoothScrollTo(0, (heading.top - dp(8)).coerceAtLeast(0))
    }

    private fun simplifiedUi(): Boolean {
        val locale = resources.configuration.locales[0]
        return locale.language == "zh" && (locale.script == "Hans" || (locale.script != "Hant" && locale.country !in setOf("TW", "HK", "MO")))
    }

    private fun dp(value: Int) = (value * density).roundToInt()

    private fun textView(size: Float, color: Int = ink) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    private fun glassSurface(radius: Int, hero: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (hero) intArrayOf(ui.p.paper, ui.p.paper2) else intArrayOf(ui.p.paper2, ui.p.paper2),
    ).apply {
        cornerRadius = dp(ui.skin.corner(radius)).toFloat()
        setStroke(dp(ui.skin.lineDp), outline)
    }

    private fun mascot(name: String, idle: Int, response: Int, lift: Float, duration: Long) = ImageView(this).apply {
        contentDescription = name
        isFocusable = true
        setPadding(dp(2), dp(4), dp(2), 0)
        setImageResource(idle)
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        val settle = Runnable {
            setImageResource(idle)
            if (translationY != 0f) animate().translationY(0f).setDuration(180).start()
        }
        setOnClickListener {
            removeCallbacks(settle)
            setImageResource(response)
            if (ValueAnimator.areAnimatorsEnabled()) {
                animate().translationY(-lift * density).setDuration(160).start()
            }
            postDelayed(settle, duration)
        }
    }

    private fun surface(radius: Int) = ui.surface(radius, cardFill)

    private fun weighted() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun action(title: Int, perform: () -> Unit) = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
        setText(title)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(accentInk)
        setAllCaps(false)
        minHeight = dp(48)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { perform() }
    }

    private fun sectionTitle(parent: LinearLayout, title: Int) {
        parent.addView(ui.sectionTitle(getString(title)), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(22)
            bottomMargin = dp(8)
        })
    }

    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = surface(18)
        clipToOutline = true
        parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun section(parent: LinearLayout, title: Int): LinearLayout {
        sectionTitle(parent, title)
        return card(parent)
    }

    private fun note(parent: LinearLayout, text: Int): TextView =
        textView(13f, quietInk).apply {
            setText(text)
            setPadding(dp(14), 0, dp(14), dp(10))
            parent.addView(this)
        }

    private fun settingLine(label: Int, value: TextView) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(8))
        addView(textView(16f).apply { setText(label) }, weighted())
        addView(value, weighted().apply { marginStart = dp(8) })
    }

    private fun settingValue() = textView(14f, quietInk).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun panelAdjustmentRow(parent: LinearLayout) {
        divider(parent)
        val value = settingValue()
        val row = settingLine(R.string.panel_adjustment, value).apply {
            minimumHeight = dp(50)
            isFocusable = true
            val selectable = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                setBackgroundResource(selectable.resourceId)
            }
            setOnClickListener { showPanelAdjustment() }
        }
        parent.addView(row)
        val update = {
            value.setText(if (prefs.panelHeightDelta != null || prefs.panelBottomGap > 0) R.string.panel_custom else R.string.panel_adjustment_open)
            row.contentDescription = getString(R.string.panel_adjustment) + "，" + value.text
        }
        updateChoices[Prefs.PANEL_HEIGHT_DELTA] = update
        updateChoices[Prefs.PANEL_BOTTOM_GAP] = update
        update()
    }

    private fun showPanelAdjustment() {
        val preset = prefs.keyboardHeight
        var heightDelta = prefs.panelHeightDelta
        var bottomGap = prefs.panelBottomGap
        var resetPreset = false
        val sample = BarPreview(this, prefs, dockedPanel = true).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        fun refreshSample() {
            val height = if (resetPreset) KeyboardHeight.STANDARD else preset
            sample.setDockedPanel(height, heightDelta, bottomGap)
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(8))
            addView(sample, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val minHeight = PanelAdjustment.MIN_HEIGHT
        val initialHeight = heightDelta ?: preset.offset.toInt()
        val height = feedbackSlider(fields, R.string.panel_key_height, PanelAdjustment.MAX_HEIGHT - minHeight,
            initialHeight - minHeight, true, { position ->
                val delta = position + minHeight
                if (delta == 0) getString(R.string.effects_standard) else getString(R.string.panel_height_change, delta)
            }) { heightDelta = it + minHeight; refreshSample() }
        val lift = feedbackSlider(fields, R.string.panel_bottom_gap, PanelAdjustment.MAX_BOTTOM_GAP, bottomGap, true,
            { if (it == 0) getString(R.string.panel_no_gap) else getString(R.string.panel_gap_value, it) }) {
            bottomGap = it
            refreshSample()
        }
        for (slider in listOf(height, lift)) {
            slider.progressTintList = ColorStateList.valueOf(ui.p.pill)
            slider.thumbTintList = ColorStateList.valueOf(ui.p.pill)
        }
        note(fields, R.string.panel_adjustment_hint).setPadding(dp(14), dp(6), dp(14), dp(4))
        refreshSample()
        val scroll = ScrollView(this).apply { addView(fields) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.panel_adjustment)
            .setView(scroll)
            .setNeutralButton(R.string.panel_reset, null)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.panel_done) { _, _ ->
                prefs.setDockedPanel(heightDelta, bottomGap, if (resetPreset) KeyboardHeight.STANDARD else preset)
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            resetPreset = true
            heightDelta = null
            bottomGap = 0
            height.progress = -minHeight
            lift.progress = 0
            refreshSample()
        }
    }

    private fun divider(parent: LinearLayout) {
        if (parent.childCount == 0) return
        parent.addView(View(this).apply {
            setBackgroundColor(outline)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            marginStart = dp(14)
            marginEnd = dp(14)
        })
    }

    private fun feedbackSlider(
        parent: LinearLayout,
        name: Int,
        steps: Int,
        value: Int,
        enabled: Boolean,
        label: (Int) -> String,
        change: (Int) -> Unit,
    ): SeekBar {
        val valueText = settingValue()
        parent.addView(settingLine(name, valueText).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setPadding(dp(14), dp(9), dp(14), 0)
        })
        val effects = if (name == R.string.effects) EffectsSlider(this, dark) else null
        return (effects ?: SeekBar(this)).apply {
            max = steps
            progress = value
            if (effects == null) {
                minimumHeight = dp(48)
                progressTintList = ColorStateList.valueOf(ui.p.pill)
                thumbTintList = ColorStateList.valueOf(ui.p.pill)
            } else valueText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isEnabled = enabled
            fun describe(position: Int, animate: Boolean = false) {
                val valueLabel = label(position)
                valueText.text = valueLabel
                if (effects != null) valueText.setTextColor(effects.updateArtwork(position, animate))
                if (Build.VERSION.SDK_INT >= 30) {
                    contentDescription = getString(name)
                    stateDescription = valueLabel
                } else {
                    contentDescription = "${getString(name)}，$valueLabel"
                }
            }
            describe(value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var dragging = false

                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    describe(progress, animate = fromUser)
                    if (!fromUser) return
                    if (effects != null && dragging) preview.refresh(EffectsLevel.entries[progress])
                    else change(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    dragging = true
                }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    dragging = false
                    if (effects != null) change(effects.progress)
                }
            })
            val height = if (effects == null) ViewGroup.LayoutParams.WRAP_CONTENT else dp(52)
            parent.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                marginStart = dp(5)
                marginEnd = dp(5)
            })
        }
    }

    /** 「摸米米莫五下」: which ways of typing the five touches go through (two or more: in this order; fewer: the gesture is off). */
    private fun mimimoRow(parent: LinearLayout) {
        divider(parent)
        val value = settingValue()
        val names = listOf(
            InputMode.ZHUYIN to R.string.mode_zhuyin, InputMode.PINYIN to R.string.mode_pinyin,
            InputMode.NINE_KEY to R.string.mode_nine_key, InputMode.JAPANESE to R.string.mode_japanese,
        )
        fun chosen() = prefs.mimimoModes ?: InputMode.SWITCH_DEFAULT
        lateinit var row: LinearLayout
        fun update() {
            val on = chosen()
            val ticked = names.filter { it.first in on }
            value.text = if (ticked.size < 2) getString(R.string.mimimo_switch_off) else ticked.joinToString("、") { getString(it.second) }
            row.contentDescription = "${getString(R.string.mimimo_switch)}, ${value.text}"
        }
        row = settingLine(R.string.mimimo_switch, value).apply {
            minimumHeight = dp(50)
            isFocusable = true
            val selectable = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) setBackgroundResource(selectable.resourceId)
            for (index in 0 until childCount) getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(textView(21f, quietInk).apply { text = "›"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            setOnClickListener {
                val on = chosen().toMutableSet()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.mimimo_switch)
                    .setMultiChoiceItems(names.map { getString(it.second) }.toTypedArray(), BooleanArray(names.size) { names[it].first in on }) { _, index, checked ->
                        if (checked) on += names[index].first else on -= names[index].first
                    }
                    .setPositiveButton(android.R.string.ok) { _, _ -> prefs.mimimoModes = on.toSet(); update() }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        parent.addView(row)
        update()
    }

    private fun <T> choice(
        parent: LinearLayout,
        label: Int,
        key: String,
        options: List<Pair<Int, T>>,
        current: () -> T,
        set: (T) -> Unit,
    ): () -> Unit {
        divider(parent)
        val value = settingValue()
        val row = settingLine(label, value).apply {
            minimumHeight = dp(50)
            isFocusable = true
            val selectable = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                setBackgroundResource(selectable.resourceId)
            }
            for (index in 0 until childCount) getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(textView(21f, quietInk).apply {
                text = "›"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            setOnClickListener {
                val selected = current()
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(label)
                    .setSingleChoiceItems(options.map { getString(it.first) }.toTypedArray(), options.indexOfFirst { it.second == selected }) { dialog, index ->
                        set(options[index].second)
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
        parent.addView(row)
        val update = {
            val selected = current()
            val title = options.firstOrNull { it.second == selected }?.first
            value.text = title?.let { getString(it) }.orEmpty()
            row.contentDescription = "${getString(label)}, ${value.text}"
        }
        updateChoices[key] = update
        update()
        return update
    }

    @Suppress("DEPRECATION")
    private fun toggle(parent: LinearLayout, label: Int, current: Boolean, set: (Boolean) -> Unit): Switch {
        divider(parent)
        val control = Switch(this).apply {
            setText(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(ink)
            ui.tint(this)
            minHeight = dp(50)
            isChecked = current
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }
        parent.addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return control
    }
}

private val HOME_SIMPLIFIED_PLACES = mapOf("紅葉" to "红叶")

/** The candidate bar and, for nine-key Pinyin, a sample row of the chosen keypad. */
@SuppressLint("ViewConstructor")
private class BarPreview(context: Context, private val prefs: Prefs, private val dockedPanel: Boolean = false) : FrameLayout(context) {
    private val sprites = CatSprites(context.resources)
    private val bar = CandidateBarView(context, sprites)
    private val skinLoader = CustomSkinLoader(context)
    /** The applied custom skin once verified, as the keyboard would show it. */
    private var custom: LoadedSkin? = null
    private var customFor: String? = null
    private val keypad = KeysView(context, PreviewOverlay(context)).apply {
        isEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private var sampleKeys = sampleKeys()
    private val density = resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private val navigationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backgroundKey = 0L
    private val rect = RectF()
    private var heightPreset = KeyboardHeight.STANDARD
    private var heightDelta: Int? = null
    private var bottomGap = 0
    private val navigationPreview = 16
    private var panelTop = 0

    init {
        setWillNotDraw(false)
        addView(bar)
        addView(keypad)
        contentDescription = context.getString(R.string.preview_label)
        bar.listener = object : CandidateBarView.Listener {
            override fun onCandidate(index: Int) = show(index)
            override fun onPrediction(text: String) = Unit
            override fun onPunctuation(mark: String) = Unit
            override fun onToggleExpanded() = Unit
            override fun onPet(cat: Cat) { bar.pet(cat, prefs.simplified) }
        }
        refresh()
    }

    fun setDockedPanel(height: KeyboardHeight, delta: Int?, gap: Int) {
        heightPreset = height
        heightDelta = delta
        bottomGap = gap
        requestLayout()
        invalidate()
    }

    private val idiom: Idiom get() = if (dockedPanel) {
        val config = resources.configuration
        Idiom.resolve(config.screenHeightDp.toFloat(), config.smallestScreenWidthDp.toFloat())
    } else Idiom.PHONE

    private fun look(effects: EffectsLevel): Look {
        val dark = when (prefs.tone) {
            Tone.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            Tone.LIGHT -> false
            Tone.DARK -> true
        }
        val overlay = custom?.overlay
        val skin = overlay?.base ?: prefs.skin
        return Look(dark, overlay?.shape(prefs.shape, prefs.hasShape) ?: skin.shape(prefs.shape), prefs.palette,
            overlay?.strength ?: prefs.strength, prefs.cats,
            opaque = wantsMoreContrast(context), effects = effects, skin = skin, overlay = overlay, petForm = prefs.petForm)
    }

    /** Verifies and reads the applied custom skin off the main thread, then shows it; the built-in skin shows meanwhile. */
    private fun syncCustomSkin() {
        val wanted = prefs.customSkin
        if (wanted == customFor) return
        customFor = wanted
        if (wanted == null) {
            custom = null
            sprites.useSkin(emptyMap(), null)
            return
        }
        skinLoader.load(wanted) { loaded ->
            if (prefs.customSkin != wanted) return@load
            custom = loaded
            sprites.useSkin(loaded?.cats.orEmpty(), loaded?.end, loaded?.leftEnd, loaded?.spaceMark)
            refresh()
        }
    }

    override fun onDetachedFromWindow() {
        skinLoader.shutdown()
        super.onDetachedFromWindow()
    }

    fun refresh(effects: EffectsLevel = prefs.effects) {
        syncCustomSkin()
        val appearance = look(effects)
        bar.look = appearance
        bar.candidateTextSize = prefs.candidateTextSize
        keypad.look = appearance
        keypad.keyTextSize = prefs.keyTextSize
        sampleKeys = sampleKeys()
        contentDescription = context.getString(if (prefs.mode == InputMode.NINE_KEY) R.string.preview_nine_key else R.string.preview_label)
        backgroundKey = 0
        requestLayout()
        show(0)
        invalidate()
    }

    private fun show(highlighted: Int) {
        val samples = if (prefs.simplified) listOf("你好", "您好", "你好呀", "拟好") else listOf("你好", "妳好", "你好呀", "擬好")
        bar.update(CandidateBarView.Content(
            candidates = samples.mapIndexed { i, s -> Candidate(s, CandidateKind.CHINESE, i, 5) },
            visible = samples.indices.toList(),
            highlighted = highlighted,
            canExpand = true,
            words = Words(prefs.simplified),
        ))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val region = regionFor(width / density)
        val barHeight = (region.height * density).roundToInt()
        bar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(barHeight, MeasureSpec.EXACTLY))
        val widthDp = width / density
        val rows = sampleKeys.rows.size
        val idiom = idiom
        val available = if (dockedPanel) PanelAdjustment.availableHeight(resources.configuration.screenHeightDp.toFloat(), idiom,
            navigationPreview.toFloat()) else Float.POSITIVE_INFINITY
        val metrics = if (dockedPanel) {
            KeyMetrics.fitted(idiom, widthDp, rows, rows, heightPreset, heightDelta, available - region.height)
        } else {
            KeyMetrics.of(Idiom.PHONE_LANDSCAPE, widthDp, rows).copy(
                sideMargin = 7f, keyHeight = if (rows >= 5) 26f else 30f,
                rowGap = 5f, letterFont = if (rows >= 5) 17f else 19f, utilityFont = 13f,
            )
        }
        val sampleWidth = (sampleKeys.layoutWidth(widthDp) * density).roundToInt()
        val keysHeight = (metrics.areaHeight(rows) * density).roundToInt()
        keypad.setKeys(sampleKeys, metrics, idiom)
        keypad.measure(MeasureSpec.makeMeasureSpec(sampleWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(keysHeight, MeasureSpec.EXACTLY))
        val content = region.height + metrics.areaHeight(rows)
        val gap = if (dockedPanel) PanelAdjustment.bottomGap(bottomGap, available, content) else 0f
        val height = barHeight + keysHeight + ((7 + if (dockedPanel) navigationPreview + gap else 7f) * density).roundToInt()
        // Keep the sliders in place while a drag changes the preview height.
        val slotHeight = if (dockedPanel) {
            val tall = KeyMetrics.fitted(idiom, widthDp, rows, rows, heightPreset, PanelAdjustment.MAX_HEIGHT,
                available - region.height)
            val maxContent = region.height + tall.areaHeight(rows)
            val maxGap = PanelAdjustment.bottomGap(PanelAdjustment.MAX_BOTTOM_GAP, available, maxContent)
            maxOf(height, ((7 + navigationPreview + maxContent + maxGap) * density).roundToInt())
        } else height
        panelTop = slotHeight - height
        setMeasuredDimension(width, slotHeight)
    }

    /** The bar's layout for this width; a new one only when something changed. */
    private fun regionFor(widthDp: Float): CandidateRegionLayout {
        val cats = prefs.cats
        val current = bar.region
        val idiom = idiom
        val rim = GlassOutline.contentBand(bar.look.shape, 1f).first
        if (current.width == widthDp && current.idiom == idiom && current.cats == cats && current.rim == rim) return current
        return CandidateRegionLayout(widthDp, idiom, cats, rim).also { bar.region = it }
    }

    private fun sampleKeys() = KeyBuilder.build(KeyContext(
        mode = prefs.mode, english = false, page = 0, shift = ShiftState.OFF, simplified = prefs.simplified,
        needsGlobe = false, spaceTitle = "空格", returnTitle = Words(prefs.simplified)("確認", "确认"),
        returnAccent = true, returnEnabled = true,
        zhuyinPinyinHints = prefs.zhuyinPinyinHints,
    ))

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pad = panelTop + (7 * density).roundToInt()
        bar.layout(0, pad, r - l, pad + bar.measuredHeight)
        val left = (r - l - keypad.measuredWidth) / 2
        keypad.layout(left, bar.bottom, left + keypad.measuredWidth, bar.bottom + keypad.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val l = bar.look
        val key = ((height - panelTop).toLong() shl 32) xor l.keyboardTop.toLong() xor (l.keyboardBottom.toLong() shl 8)
        if (key != backgroundKey) {
            backgroundKey = key
            background.shader = LinearGradient(0f, panelTop.toFloat(), 0f, height.toFloat(), l.keyboardTop, l.keyboardBottom, Shader.TileMode.CLAMP)
        }
        rect.set(0f, panelTop.toFloat(), width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 18 * density, 18 * density, background)
        if (l.isCentenary) {
            navigationPaint.style = Paint.Style.STROKE
            navigationPaint.strokeWidth = density
            navigationPaint.color = Look.withAlpha(l.tint, if (l.dark) 0.42f else 0.36f)
            rect.inset(density * 0.5f, density * 0.5f)
            canvas.drawRoundRect(rect, 17.5f * density, 17.5f * density, navigationPaint)
            navigationPaint.style = Paint.Style.FILL
        }
        if (dockedPanel) {
            val center = width / 2f
            val y = height - navigationPreview * density / 2
            navigationPaint.color = Look.withAlpha(l.keyText, 0.38f)
            canvas.drawRoundRect(center - 27 * density, y - 1.5f * density, center + 27 * density, y + 1.5f * density,
                1.5f * density, 1.5f * density, navigationPaint)
        }
    }
}

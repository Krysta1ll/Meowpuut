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
import com.krysta1ll.meowpuut.android.engine.Candidate
import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.ChoiceMemory
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.CandidateBarView
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.Cat
import com.krysta1ll.meowpuut.android.ime.CatSprites
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
import com.krysta1ll.meowpuut.android.ime.wantsMoreContrast
import kotlin.math.roundToInt

/** Setup steps, a test field and the keyboard's settings. */
class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var preview: BarPreview
    private lateinit var gallery: SkinGallery
    private lateinit var shapeRow: View
    private lateinit var glassRow: View
    private lateinit var catsSwitch: Switch
    private lateinit var festivalSwitch: Switch
    private lateinit var festivalHint: TextView
    private val updateChoices = mutableMapOf<String, () -> Unit>()
    private val density get() = resources.displayMetrics.density
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if (dark) 0xfff3ece9.toInt() else 0xff292124.toInt()
    private val quietInk get() = if (dark) 0xffc5b8bf.toInt() else 0xff705e66.toInt()
    private val accentInk get() = if (dark) 0xffebbac2.toInt() else 0xff85515d.toInt()
    private val cardFill get() = if (dark) 0xff28242c.toInt() else 0xfffffaf4.toInt()
    private val outline get() = if (dark) 0xff443b48.toInt() else 0xffe7dce5.toInt()
    private val effectNames = listOf(R.string.effects_lightweight, R.string.effects_standard, R.string.effects_full, R.string.effects_ultra)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
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
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            isVerticalScrollBarEnabled = false
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }

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
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                })
                addView(textView(13f, quietInk).apply {
                    text = "Meowpuut"
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
            }, weighted())
            addView(mascot("米米莫", R.drawable.cat_classic_mimimo_open, R.drawable.cat_classic_mimimo_blink, 1.5f, 450),
                LinearLayout.LayoutParams(dp(54), dp(58)))
            addView(mascot("莫莫", R.drawable.cat_classic_momo_open, R.drawable.cat_classic_momo_happy, 4f, 650),
                LinearLayout.LayoutParams(dp(49), dp(54)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(masthead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        preview = BarPreview(this, prefs)
        val look = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = surface(16)
            clipToOutline = true
            visibility = View.GONE
        }
        gallery = SkinGallery(this, preview, getString(R.string.skin_wardrobe),
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

        val setup = section(column, R.string.setup_title)
        status = textView(14f, quietInk).apply { setPadding(dp(16), dp(12), dp(16), dp(2)) }
        setup.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(action(R.string.setup_open_settings) { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, weighted())
        buttons.addView(action(R.string.setup_pick) { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }, weighted())
        setup.addView(buttons)

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
        choice(input, R.string.input_mode, Prefs.MODE, listOf(
            R.string.mode_zhuyin to InputMode.ZHUYIN,
            R.string.mode_pinyin to InputMode.PINYIN,
            R.string.mode_nine_key to InputMode.NINE_KEY,
            R.string.mode_japanese to InputMode.JAPANESE,
        ), { prefs.mode }) { prefs.mode = it }
        choice(input, R.string.script, Prefs.SIMPLIFIED, listOf(R.string.script_traditional to false, R.string.script_simplified to true),
            { prefs.simplified }) { prefs.simplified = it }
        note(input, R.string.script_note)
        toggle(input, R.string.english_auto_caps, prefs.englishAutoCaps) { prefs.englishAutoCaps = it }
        toggle(input, R.string.remember_choices, prefs.rememberChoices) { prefs.rememberChoices = it }
        note(input, R.string.remember_choices_note)
        input.addView(action(R.string.clear_choice_memory) {
            AlertDialog.Builder(this)
                .setMessage(R.string.clear_choice_memory_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear_choice_memory) { _, _ ->
                    ChoiceMemory(this).clear()
                    prefs.store.edit().putLong(Prefs.CLEAR_CHOICE_MEMORY, System.nanoTime()).apply()
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
        choice(look, R.string.strength, Prefs.STRENGTH, listOf(R.string.strength_soft to GlassStrength.SOFT,
            R.string.strength_balanced to GlassStrength.BALANCED, R.string.strength_vivid to GlassStrength.VIVID), { prefs.strength }) { prefs.strength = it }
        glassRow = look.getChildAt(look.childCount - 1)
        catsSwitch = toggle(look, R.string.cats, prefs.cats) { prefs.cats = it }
        festivalSwitch = toggle(look, R.string.festivals, prefs.festivals) { prefs.festivals = it }
        festivalHint = note(look, R.string.festivals_note)
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
        column.addView(action(R.string.licenses, ::showLicenses), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        packageManager.getPackageInfo(packageName, 0).versionName?.let { version ->
            column.addView(TextView(this).apply {
                text = version
                textSize = 12f
                alpha = 0.72f
            })
        }
        setContentView(scroll)
        prefs.store.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateStatus()
    }

    override fun onDestroy() {
        prefs.store.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(store: SharedPreferences?, key: String?) {
        val setting = prefs.appearanceSetting(key)
        if (setting != null) updateChoices[setting]?.invoke()
        if (setting == Prefs.SKIN || setting == Prefs.CATS) refreshSkin()
        when (setting) {
            Prefs.MODE, Prefs.SIMPLIFIED, Prefs.TONE, Prefs.SHAPE, Prefs.PALETTE, Prefs.STRENGTH,
            Prefs.CATS, Prefs.EFFECTS, Prefs.KEYBOARD_HEIGHT, Prefs.CANDIDATE_TEXT_SIZE, Prefs.KEY_TEXT_SIZE, Prefs.SKIN -> preview.refresh()
        }
    }

    private fun selectSkin(skin: KeyboardSkin) {
        if (!prefs.isSkinUnlocked(skin)) showPixelQuiz(skin)
        else prefs.skin = skin
    }

    private fun refreshSkin() {
        gallery.render(prefs.skin, prefs::isSkinUnlocked)
        val skin = prefs.skin
        shapeRow.visibility = if (skin.fixedShape == null) View.VISIBLE else View.GONE
        glassRow.visibility = if (skin.material == SkinMaterial.GLASS) View.VISIBLE else View.GONE
        festivalSwitch.visibility = if (skin.supportsFestivals) View.VISIBLE else View.GONE
        festivalHint.visibility = festivalSwitch.visibility
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
    }

    private fun showLicenses() {
        val files = assets.list("licenses")?.sorted().orEmpty()
        val text = files.joinToString("\n\n") { name ->
            val body = runCatching { assets.open("licenses/$name").use { it.readBytes().decodeToString() } }.getOrDefault("")
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
                        if (answer == question.answers.first()) prefs.unlockSkin(skin)
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

    private fun dp(value: Int) = (value * density).roundToInt()

    private fun textView(size: Float, color: Int = ink) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
    }

    private fun glassSurface(radius: Int, hero: Boolean = false) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (dark) {
            if (hero) intArrayOf(0xff413649.toInt(), 0xff27232e.toInt())
            else intArrayOf(0xff3a3341.toInt(), cardFill)
        } else {
            if (hero) intArrayOf(0xfffffcf6.toInt(), 0xffe8dfee.toInt())
            else intArrayOf(0xfffffdf9.toInt(), 0xfff1e9f2.toInt())
        }
    ).apply {
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), outline)
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

    private fun surface(radius: Int) = GradientDrawable().apply {
        setColor(cardFill)
        setStroke(dp(1), outline)
        cornerRadius = dp(radius).toFloat()
    }

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
        parent.addView(textView(16f).apply {
            setText(title)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(2), dp(20), dp(2), dp(8))
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
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
            slider.progressTintList = ColorStateList.valueOf(accentInk)
            slider.thumbTintList = ColorStateList.valueOf(accentInk)
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
            if (effects == null) minimumHeight = dp(48)
            else valueText.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
            minHeight = dp(50)
            isChecked = current
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }
        parent.addView(control, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return control
    }
}

/** The candidate bar and, for nine-key Pinyin, a sample row of the chosen keypad. */
@SuppressLint("ViewConstructor")
private class BarPreview(context: Context, private val prefs: Prefs, private val dockedPanel: Boolean = false) : FrameLayout(context) {
    private val bar = CandidateBarView(context, CatSprites(context.resources))
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
        val skin = prefs.skin
        return Look(dark, skin.shape(prefs.shape), prefs.palette, prefs.strength, prefs.cats,
            opaque = wantsMoreContrast(context), effects = effects, skin = skin)
    }

    fun refresh(effects: EffectsLevel = prefs.effects) {
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
        if (dockedPanel) {
            val center = width / 2f
            val y = height - navigationPreview * density / 2
            navigationPaint.color = Look.withAlpha(l.keyText, 0.38f)
            canvas.drawRoundRect(center - 27 * density, y - 1.5f * density, center + 27 * density, y + 1.5f * density,
                1.5f * density, 1.5f * density, navigationPaint)
        }
    }
}

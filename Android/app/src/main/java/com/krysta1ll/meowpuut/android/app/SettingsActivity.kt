package com.krysta1ll.meowpuut.android.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.engine.Candidate
import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.CandidateBarView
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.Cat
import com.krysta1ll.meowpuut.android.ime.CatSprites
import com.krysta1ll.meowpuut.android.ime.GlassOutline
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.GlassStrength
import com.krysta1ll.meowpuut.android.ime.Idiom
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.Palette
import com.krysta1ll.meowpuut.android.ime.Prefs
import com.krysta1ll.meowpuut.android.ime.Tone
import com.krysta1ll.meowpuut.android.ime.Words
import com.krysta1ll.meowpuut.android.ime.wantsMoreContrast
import kotlin.math.roundToInt

/** Setup steps, a test field and the keyboard's settings. */
class SettingsActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var preview: BarPreview
    private val density get() = resources.displayMetrics.density
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.store.registerOnSharedPreferenceChangeListener(this)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column)
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    v.setPadding(0, bars.top, 0, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                }
                insets
            }
        }

        column.addView(TextView(this).apply {
            setText(R.string.app_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(14))
        })
        preview = BarPreview(this, prefs)
        column.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        section(column, R.string.setup_title)
        status = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setPadding(0, 0, 0, dp(8)) }
        column.addView(status)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            setText(R.string.setup_open_settings)
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        buttons.addView(Button(this).apply {
            setText(R.string.setup_pick)
            setOnClickListener { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        })
        column.addView(buttons)

        section(column, R.string.try_title)
        column.addView(EditText(this).apply {
            setHint(R.string.try_hint)
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        section(column, R.string.input_title)
        choice(column, R.string.input_mode, listOf(R.string.mode_zhuyin to InputMode.ZHUYIN, R.string.mode_pinyin to InputMode.PINYIN,
            R.string.mode_japanese to InputMode.JAPANESE), prefs.mode) { prefs.mode = it }
        choice(column, R.string.script, listOf(R.string.script_traditional to false, R.string.script_simplified to true), prefs.simplified) { prefs.simplified = it }
        note(column, R.string.script_note)

        section(column, R.string.look_title)
        choice(column, R.string.shape, listOf(R.string.shape_capsule to GlassShape.CAPSULE, R.string.shape_wave to GlassShape.WAVE,
            R.string.shape_cloud to GlassShape.CLOUD), prefs.shape) { prefs.shape = it }
        choice(column, R.string.tone, listOf(R.string.tone_system to Tone.SYSTEM, R.string.tone_light to Tone.LIGHT, R.string.tone_dark to Tone.DARK), prefs.tone) { prefs.tone = it }
        choice(column, R.string.palette, listOf(R.string.palette_rose to Palette.ROSE, R.string.palette_ocean to Palette.OCEAN,
            R.string.palette_sage to Palette.SAGE, R.string.palette_lavender to Palette.LAVENDER, R.string.palette_amber to Palette.AMBER,
            R.string.palette_graphite to Palette.GRAPHITE), prefs.palette) { prefs.palette = it }
        choice(column, R.string.strength, listOf(R.string.strength_soft to GlassStrength.SOFT, R.string.strength_balanced to GlassStrength.BALANCED,
            R.string.strength_vivid to GlassStrength.VIVID), prefs.strength) { prefs.strength = it }
        toggle(column, R.string.cats, prefs.cats) { prefs.cats = it }
        toggle(column, R.string.festivals, prefs.festivals) { prefs.festivals = it }
        note(column, R.string.festivals_note)

        section(column, R.string.feedback_title)
        toggle(column, R.string.haptics, prefs.haptics) { prefs.haptics = it }
        toggle(column, R.string.sound, prefs.sound) { prefs.sound = it }

        column.addView(Button(this).apply {
            setText(R.string.licenses)
            setOnClickListener { showLicenses() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })

        setContentView(scroll)
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

    override fun onSharedPreferenceChanged(store: SharedPreferences?, key: String?) = preview.refresh()

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

    private fun dp(value: Int) = (value * density).roundToInt()

    private fun section(parent: LinearLayout, title: Int) {
        parent.addView(TextView(this).apply {
            setText(title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(8))
        })
    }

    private fun note(parent: LinearLayout, text: Int) {
        parent.addView(TextView(this).apply {
            setText(text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.7f
            setPadding(0, dp(2), 0, dp(6))
        })
    }

    private fun <T> choice(parent: LinearLayout, label: Int, options: List<Pair<Int, T>>, current: T, set: (T) -> Unit) {
        parent.addView(TextView(this).apply {
            setText(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            alpha = 0.75f
            setPadding(0, dp(8), 0, dp(2))
        })
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for ((title, value) in options) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                setText(title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(2), 0, dp(12), 0)
            }
            group.addView(button)
            if (value == current) button.isChecked = true
            button.setOnCheckedChangeListener { _, checked -> if (checked) set(value) }
        }
        parent.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        })
    }

    @Suppress("DEPRECATION")
    private fun toggle(parent: LinearLayout, label: Int, current: Boolean, set: (Boolean) -> Unit) {
        parent.addView(Switch(this).apply {
            setText(label)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            isChecked = current
            setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> set(checked) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
}

/** The candidate bar as the keyboard draws it, with sample candidates. */
@SuppressLint("ViewConstructor")
private class BarPreview(context: Context, private val prefs: Prefs) : FrameLayout(context) {
    private val bar = CandidateBarView(context, CatSprites(context.resources, context.assets))
    private val density = resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backgroundKey = 0L
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        addView(bar)
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

    private val look: Look
        get() {
            val dark = when (prefs.tone) {
                Tone.SYSTEM -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                Tone.LIGHT -> false
                Tone.DARK -> true
            }
            return Look(dark, prefs.shape, prefs.palette, prefs.strength, prefs.cats, opaque = wantsMoreContrast(context))
        }

    fun refresh() {
        bar.look = look
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
        setMeasuredDimension(width, barHeight + (14 * density).roundToInt())
    }

    /** The bar's layout for this width; a new one only when something changed. */
    private fun regionFor(widthDp: Float): CandidateRegionLayout {
        val cats = prefs.cats
        val current = bar.region
        val rim = GlassOutline.contentBand(prefs.shape, 1f).first
        if (current.width == widthDp && current.cats == cats && current.rim == rim) return current
        return CandidateRegionLayout(widthDp, Idiom.PHONE, cats, rim).also { bar.region = it }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pad = (7 * density).roundToInt()
        bar.layout(0, pad, r - l, pad + bar.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val l = bar.look
        val key = (height.toLong() shl 32) xor l.keyboardTop.toLong() xor (l.keyboardBottom.toLong() shl 8)
        if (key != backgroundKey) {
            backgroundKey = key
            background.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), l.keyboardTop, l.keyboardBottom, Shader.TileMode.CLAMP)
        }
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 18 * density, 18 * density, background)
    }
}

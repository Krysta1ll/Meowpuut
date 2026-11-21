package com.krysta1ll.meowpuut.android.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.app.onboarding.Hot
import com.krysta1ll.meowpuut.android.app.onboarding.ONBOARDING_KEYS
import com.krysta1ll.meowpuut.android.app.onboarding.Onboarding
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingHost
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingTyping
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingView
import com.krysta1ll.meowpuut.android.app.onboarding.Scheme
import com.krysta1ll.meowpuut.android.app.onboarding.onboardingCopy
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.Prefs
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.wantsMoreContrast

/**
 * 第一行字: the first-run guide, on a screen. The guide itself is [com.krysta1ll.meowpuut.android.app.onboarding.OnboardingState];
 * this is what it asks of the app. It opens the system's keyboard settings and watches for the keyboard to be enabled, holds the one
 * real text field (the line to type on), lays a real control over everything on the page that can be touched, speaks to TalkBack,
 * and writes the three things that are kept: that the guide is done, the way of typing that was chosen, and (only if asked) the skin.
 * What is typed on the line goes nowhere: only its length is read, and the line is emptied when the act ends.
 *
 * While it is on, the keyboard wears 字景 ([Onboarding.wear]); the skin that is chosen is not touched unless the person asks at the end.
 */
class OnboardingActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var page: OnboardingView
    private lateinit var controls: FrameLayout
    private lateinit var field: EditText
    private val main = Handler(Looper.getMainLooper())
    private val typing = OnboardingTyping()
    private var begun = false
    private var topInset = 0
    private var bottomInset = 0
    private var barsBottom = 0
    private var away = false
    private var lastEnabled: Boolean? = null
    private var lastOurs: Boolean? = null
    private var hots: List<Hot> = emptyList()
    /** Someone already using the app: what is done is not asked again, and nothing of theirs is changed unless they choose it. */
    private var using = false
    /** A guide the system ended a moment ago, taken up where it was. */
    private var resume: Onboarding.Progress? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val replay = intent.getBooleanExtra(EXTRA_REPLAY, false)
        if (replay && savedInstanceState == null) Onboarding.forget(prefs.store)       // asked for again: from the beginning, not from an old stop
        using = Onboarding.usingNow(prefs.store, replay)
        resume = Onboarding.progress(prefs.store)                                       // nothing was saved of the page itself: this is all there is to go on
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val colors = GlyphColors.of(dark)
        window.decorView.setBackgroundColor(colors.paper)               // the decor first: the bars' appearance is asked of it
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.decorView.windowInsetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        }
        @Suppress("DEPRECATION")
        run { window.statusBarColor = 0; window.navigationBarColor = 0 }
        if (Build.VERSION.SDK_INT >= 29) { window.isNavigationBarContrastEnforced = false; window.isStatusBarContrastEnforced = false }   // the paper runs under the bars

        page = OnboardingView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES; isFocusable = true }
        controls = FrameLayout(this)
        field = LineField(this) { page.tell { it.committed() } }.apply {
            background = null
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            isSingleLine = true
            setTextColor(colors.ink)
            highlightColor = GlyphColors.withAlpha(colors.red, 0.35)
            typeface = try { Typeface.createFromAsset(assets, "fonts/pixel_a.ttf") } catch (_: Exception) { Typeface.DEFAULT }
            paintFlags = paintFlags and Paint.ANTI_ALIAS_FLAG.inv()
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
            isSaveEnabled = false                                     // what is typed here is not kept, not even across a rotation
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            contentDescription = getString(R.string.onboarding_a3_line2)
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    val from = BaseInputConnection.getComposingSpanStart(s)
                    val to = BaseInputConnection.getComposingSpanEnd(s)
                    if (typing.changed(s.length, if (from in 0 until to) to - from else 0)) page.tell { it.typed() }   // lengths only: the text is not read
                }
            })
        }
        val column = object : FrameLayout(this) {                       // on a wide screen the page keeps a phone's width
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                val width = minOf(MeasureSpec.getSize(widthSpec), (480 * resources.displayMetrics.density).toInt())
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec)
            }
        }
        column.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(field)
        val root = FrameLayout(this).apply {
            setBackgroundColor(colors.paper)
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { _, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    topInset = bars.top; barsBottom = bars.bottom
                    bottomInset = maxOf(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
                } else {
                    @Suppress("DEPRECATION") run { topInset = insets.systemWindowInsetTop; bottomInset = insets.systemWindowInsetBottom }
                }
                place(); insets
            }
        }
        setContentView(root)
        page.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        main.post(watch)
        // From Android 13 the system asks for back through this; onBackPressed is no longer called for an app that targets 16.
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { backOut() }
    }

    /** Begins the guide once the page has a size, and keeps it told how much of the page the keyboard leaves. */
    private fun place() {
        if (page.width <= 0 || page.height <= 0) return
        val scale = page.scale
        if (!begun) {
            begun = true
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val copy = onboardingCopy { key -> getString(STRINGS.getValue(key)) }
            page.begin(OnboardingView.Setup(copy, host, topInset / scale.toDouble(), barsBottom / scale.toDouble(), dark, still = !ValueAnimator.areAnimatorsEnabled(), contrast = wantsMoreContrast(this),
                big = resources.configuration.fontScale >= 1.3f, resume = resume))
            // The line to type on: the field lies just above the rule the page draws, in the page's own pixel type.
            val lineY = topInset / scale + 198
            field.setTextSize(TypedValue.COMPLEX_UNIT_PX, 36 * scale)
            field.layoutParams = FrameLayout.LayoutParams(((393 - 2 * 28 - 20) * scale).toInt(), (46 * scale).toInt()).apply {
                leftMargin = (38 * scale).toInt(); topMargin = ((lineY - 50) * scale).toInt()
            }
            if (Build.VERSION.SDK_INT >= 29) field.textCursorDrawable = android.graphics.drawable.GradientDrawable().apply { setColor(GlyphColors.of(dark).red); setSize((5 * scale).toInt(), (33 * scale).toInt()) }   // the page's one caret, handed over
            lastEnabled = null; lastOurs = null; check()            // anything looked at before the guide began was told to nobody: say it again
        }
        val bottom = (page.height - bottomInset) / scale.toDouble()
        page.tell { it.visible(bottom) }
    }

    // ---- what the system says: is the keyboard enabled, and is it the one in use ---------------------------
    private fun check() {
        if (!begun) return
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val ours = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty().startsWith("$packageName/")
        if (enabled != lastEnabled) {
            lastEnabled = enabled
            page.tell { it.enabled(enabled) }
            // Enabled while the person is still in the system's settings: come back by ourselves, as the AOSP keyboard's own setup does.
            if (enabled && away) try { startActivity(Intent(this, OnboardingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (_: Exception) {}
        }
        if (ours != lastOurs) { lastOurs = ours; page.tell { it.keyboardIsOurs(ours) } }
    }

    /** The system tells nobody when these change, so they are looked at a few times a second while the guide is on. */
    private val watch = object : Runnable {
        override fun run() { if (isFinishing || isDestroyed) return; check(); main.postDelayed(this, 300) }
    }

    override fun onResume() {
        super.onResume()
        page.resume(); check()
        if (away) { away = false; page.tell { it.returned() } }
    }

    override fun onPause() { super.onPause(); if (!away) page.pause() }
    /** The keyboard wears 字景 only while the guide is on the screen: leave for another app, and it is the chosen skin again. */
    override fun onStart() { super.onStart(); Onboarding.wear(prefs.store) }
    override fun onStop() { super.onStop(); page.pause(); Onboarding.takeOff(prefs.store) }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) check() }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        field.text?.clear()
        Onboarding.takeOff(prefs.store)
        page.release()
        super.onDestroy()
    }

    /** Back is the way out too: the guide does not come up by itself again. */
    private fun backOut() { Onboarding.markDone(prefs.store); leaveNow() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = backOut()

    private fun leaveNow() {
        if (isFinishing) return
        Onboarding.takeOff(prefs.store)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    // ---- what the guide asks (called on its own thread) ----------------------------------------------------
    private val host = object : OnboardingHost {
    override fun openKeyboardSettings() = runOnUiThread {
        away = true
        try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Exception) { away = false }
    }

    override fun showKeyboardPicker() = runOnUiThread { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }

    override fun keyboard(show: Boolean) = runOnUiThread {
        val imm = getSystemService(InputMethodManager::class.java)
        if (show) {
            typing.reset(); field.text?.clear(); field.visibility = View.VISIBLE; field.requestFocus()
            field.post { imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            imm?.hideSoftInputFromWindow(field.windowToken, 0)
            typing.reset(); field.text?.clear(); field.clearFocus(); field.visibility = View.GONE
        }
    }

    override fun say(text: String) = runOnUiThread { page.announceForAccessibility(text) }
    override fun describe(text: String) = runOnUiThread { page.contentDescription = text }

    /** Everything on the page that can be touched gets a real button over it: it can be found by touch exploration and has a name. */
    override fun hots(list: List<Hot>) = runOnUiThread {
        hots = list
        controls.removeAllViews()
        val scale = page.scale
        for (hot in list) controls.addView(Button(this@OnboardingActivity).apply {
            background = null
            contentDescription = hot.label
            minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
            setOnClickListener { page.tell { it.tap(hot.id) } }
        }, FrameLayout.LayoutParams((hot.w * scale).toInt(), (hot.h * scale).toInt()).apply { leftMargin = (hot.x * scale).toInt(); topMargin = (hot.y * scale).toInt() })
    }

    override fun chose(scheme: Scheme) = runOnUiThread { prefs.mode = InputMode.of(scheme.key) }
    override fun current(): Scheme? = if (!using) null else when (prefs.mode) {
        InputMode.ZHUYIN -> Scheme.ZHUYIN
        InputMode.PINYIN -> Scheme.PINYIN
        InputMode.NINE_KEY -> Scheme.NINE_KEY
        else -> null                                                  // Japanese is none of the three roads: none is marked
    }
    override fun reached(act: Int, pick: Int) = Onboarding.reached(prefs.store, act, pick)
    override fun keepSkin() = runOnUiThread { prefs.skin = KeyboardSkin.GLYPH }
    override fun done() = runOnUiThread { Onboarding.markDone(prefs.store) }
    override fun leave() = runOnUiThread { leaveNow() }
    }

    /**
     * The line to type on. It listens to the keyboard for one thing: text committed while a reading was being composed, which is a word
     * chosen. Only the length of what is committed is read.
     */
    private class LineField(context: android.content.Context, private val onCommit: () -> Unit) : EditText(context) {
        override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.InputConnection? {
            val base = super.onCreateInputConnection(outAttrs) ?: return null
            return object : android.view.inputmethod.InputConnectionWrapper(base, true) {
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    val composing = BaseInputConnection.getComposingSpanStart(editableText) >= 0
                    val ok = super.commitText(text, newCursorPosition)
                    if (ok && OnboardingTyping.isCommit(composing, text?.length ?: 0)) onCommit()
                    return ok
                }
            }
        }
    }

    companion object {
        /** Set by the settings' "see it again": from the beginning, as for someone who is already using the app. */
        const val EXTRA_REPLAY = "replay"

        /** The guide's strings by name: named one by one so the release build's resource shrinker keeps them. */
        private val STRINGS: Map<String, Int> = mapOf(
            "skip" to R.string.onboarding_skip, "skip_label" to R.string.onboarding_skip_label,
            "a1_line" to R.string.onboarding_a1_line, "a1_hint" to R.string.onboarding_a1_hint, "a1_after" to R.string.onboarding_a1_after,
            "a1_alt" to R.string.onboarding_a1_alt, "a1_alt_after" to R.string.onboarding_a1_alt_after,
            "a2_line1" to R.string.onboarding_a2_line1, "a2_line2" to R.string.onboarding_a2_line2, "a2_step1" to R.string.onboarding_a2_step1,
            "a2_step2" to R.string.onboarding_a2_step2, "a2_step3" to R.string.onboarding_a2_step3, "a2_note" to R.string.onboarding_a2_note,
            "a2_go" to R.string.onboarding_a2_go, "a2_go_label" to R.string.onboarding_a2_go_label, "a2_back" to R.string.onboarding_a2_back,
            "a2_done" to R.string.onboarding_a2_done, "a2_announce" to R.string.onboarding_a2_announce, "a2_alt" to R.string.onboarding_a2_alt,
            "a2_alt_done" to R.string.onboarding_a2_alt_done,
            "a3_line1" to R.string.onboarding_a3_line1, "a3_line2" to R.string.onboarding_a3_line2, "a3_tag_switch" to R.string.onboarding_a3_tag_switch,
            "a3_tag_type" to R.string.onboarding_a3_tag_type, "a3_tag_pick" to R.string.onboarding_a3_tag_pick, "a3_tag_label" to R.string.onboarding_a3_tag_label,
            "a3_other" to R.string.onboarding_a3_other, "a3_onward" to R.string.onboarding_a3_onward, "a3_eyes" to R.string.onboarding_a3_eyes,
            "a3_mimi" to R.string.onboarding_a3_mimi, "a3_pick" to R.string.onboarding_a3_pick, "a3_momo" to R.string.onboarding_a3_momo,
            "a3_announce_eyes" to R.string.onboarding_a3_announce_eyes, "a3_announce_mimi" to R.string.onboarding_a3_announce_mimi,
            "a3_announce_momo" to R.string.onboarding_a3_announce_momo, "a3_alt" to R.string.onboarding_a3_alt,
            "a4_line1" to R.string.onboarding_a4_line1, "a4_line2" to R.string.onboarding_a4_line2, "a4_note" to R.string.onboarding_a4_note,
            "a4_order" to R.string.onboarding_a4_order, "scheme_zhuyin" to R.string.onboarding_scheme_zhuyin, "scheme_pinyin" to R.string.onboarding_scheme_pinyin,
            "scheme_nine_key" to R.string.onboarding_scheme_nine_key, "a4_chosen" to R.string.onboarding_a4_chosen, "a4_announce" to R.string.onboarding_a4_announce,
            "a4_alt" to R.string.onboarding_a4_alt, "a5_line" to R.string.onboarding_a5_line, "a5_alt" to R.string.onboarding_a5_alt,
            "a6_line1" to R.string.onboarding_a6_line1, "a6_line2" to R.string.onboarding_a6_line2, "a6_foot1" to R.string.onboarding_a6_foot1,
            "a6_foot2" to R.string.onboarding_a6_foot2, "a6_wear" to R.string.onboarding_a6_wear, "a6_worn" to R.string.onboarding_a6_worn,
            "a6_worn_line" to R.string.onboarding_a6_worn_line, "a6_wear_label" to R.string.onboarding_a6_wear_label, "a6_enter" to R.string.onboarding_a6_enter,
            "a6_alt" to R.string.onboarding_a6_alt, "a6_announce" to R.string.onboarding_a6_announce,
        ).also { check(it.keys == ONBOARDING_KEYS.toSet()) }
    }
}

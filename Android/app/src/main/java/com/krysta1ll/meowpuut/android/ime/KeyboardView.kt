package com.krysta1ll.meowpuut.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Region
import android.graphics.Shader
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import kotlin.math.roundToInt

/**
 * The whole keyboard: candidate region on top, keys below, the expanded
 * candidate panel over the key rows, and the balloon overlay above all.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, sprites: CatSprites, catSchedule: CatSchedule) : FrameLayout(context) {
    /** Key balloons and alternates. Not called overlay: every View has one of those already. */
    val previews = PreviewOverlay(context)
    val bar = CandidateBarView(context, sprites, catSchedule, previews)
    val keys = KeysView(context, previews)
    val panel = ExpandedPanelView(context)

    private val density = resources.displayMetrics.density
    private val background = Paint()
    private val light = Paint()
    private val experimentalGlass = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var backgroundHeight = 0
    private var backgroundWidth = 0
    private var backgroundRim = 0f
    var floating = false
        set(value) {
            if (field == value) return
            field = value
            backgroundHeight = 0
            requestLayout()
        }
    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
        set(value) {
            if (field == value) return
            val shapeChanged = field.shape != value.shape || field.skin != value.skin
            field = value
            bar.look = value
            keys.look = value
            panel.look = value
            previews.look = value
            backgroundHeight = 0
            // The expand key follows the shape's rim, so a new shape moves it.
            if (shapeChanged) requestLayout()
            invalidate()
        }
    var idiom = Idiom.PHONE
        private set
    var cats = true
        set(value) { if (field != value) { field = value; requestLayout() } }
    var keyHeight = KeyboardHeight.STANDARD
        set(value) { if (field != value) { field = value; requestLayout() } }
    private var panelHeightDelta: Int? = null
    private var panelBottomGap = 0
    private var rows = 4
    private var baseRows = 4
    private var bottomInset = 0
    private var measuredLimit = Float.POSITIVE_INFINITY
    var expanded = false
        set(value) {
            if (field == value) return
            field = value
            panel.visibility = if (value) VISIBLE else GONE
            requestLayout()
        }

    init {
        setWillNotDraw(false)
        clipChildren = false
        addView(bar)
        addView(keys)
        addView(panel)
        addView(previews)
        panel.visibility = GONE
    }

    /**
     * [count] rows of keys in the height that [base] rows take: Zhuyin's five
     * rows also set the height of its symbol pages.
     */
    fun setRows(count: Int, base: Int = count) {
        if (rows != count || baseRows != base) {
            rows = count
            baseRows = base
            requestLayout()
        }
    }

    fun setKeyboardHaptics(enabled: Boolean) {
        isHapticFeedbackEnabled = enabled
        keys.isHapticFeedbackEnabled = enabled
        bar.isHapticFeedbackEnabled = enabled
        panel.isHapticFeedbackEnabled = enabled
        previews.isHapticFeedbackEnabled = enabled
    }

    fun idiomFor(): Idiom {
        val config = resources.configuration
        return Idiom.resolve(config.screenHeightDp.toFloat(), config.smallestScreenWidthDp.toFloat())
    }

    fun setDockedPanel(heightDelta: Int?, bottomGap: Int) {
        if (panelHeightDelta == heightDelta && panelBottomGap == bottomGap) return
        panelHeightDelta = heightDelta
        panelBottomGap = bottomGap
        requestLayout()
    }

    private val hasDockedAdjustment get() = !floating && (panelHeightDelta != null || panelBottomGap > 0)

    private fun dockedRoom(idiom: Idiom): Float {
        if (!hasDockedAdjustment) return Float.POSITIVE_INFINITY
        val navigation = bottomInset / density
        val screen = PanelAdjustment.availableHeight(resources.configuration.screenHeightDp.toFloat(), idiom, navigation)
        return minOf(screen, (measuredLimit - navigation).coerceAtLeast(0f))
    }

    fun metrics(widthDp: Float = width / density): KeyMetrics {
        val idiom = idiomFor()
        val room = dockedRoom(idiom)
        val regionHeight = if (hasDockedAdjustment) regionFor(widthDp, idiom).height else 0f
        return KeyMetrics.fitted(idiom, widthDp, rows, baseRows, keyHeight, panelHeightDelta.takeUnless { floating },
            availableHeight = room - regionHeight)
    }

    /**
     * Height of the system's strip under the keyboard. With gesture navigation
     * the system draws its back and keyboard switch buttons in a strip taller
     * than the gesture bar itself; that strip is reported as a tappable
     * element, not as the navigation bar.
     */
    private fun bottomOf(insets: WindowInsets): Int = if (Build.VERSION.SDK_INT >= 30) {
        insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()).bottom
    } else {
        @Suppress("DEPRECATION") insets.systemWindowInsetBottom
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = bottomOf(insets)
        if (bottom != bottomInset) { bottomInset = bottom; requestLayout() }
        return insets
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // After a configuration change the service builds a new view and puts
        // it into a window that is already up. Such a view is not sent the
        // insets by itself, and the bottom row would end up under the
        // navigation bar.
        requestApplyInsets()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthDp = width / density
        rootWindowInsets?.let { bottomInset = bottomOf(it) }
        measuredLimit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Float.POSITIVE_INFINITY
        else MeasureSpec.getSize(heightMeasureSpec) / density
        idiom = idiomFor()
        val region = regionFor(widthDp, idiom)
        val room = dockedRoom(idiom)
        // The keys were given metrics before this width was known (the first
        // time the keyboard shows, or after a rotation): correct them here.
        var metrics = metrics(widthDp)
        if (floating && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            val available = MeasureSpec.getSize(heightMeasureSpec) / density - region.height
            val extra = metrics.areaHeight(rows) - available
            if (extra > 0f) metrics = metrics.copy(keyHeight = (metrics.keyHeight - extra / rows).coerceAtLeast(12f))
        }
        keys.setMetrics(metrics, idiom)
        val regionPx = (region.height * density).roundToInt()
        bar.firstKeyTop = regionPx / density + metrics.topPadding
        val keysPx = (metrics.areaHeight(rows) * density).roundToInt()
        val keysWidth = keys.keySet?.layoutWidth(widthDp) ?: widthDp
        val gap = if (hasDockedAdjustment) PanelAdjustment.bottomGap(panelBottomGap, room, region.height + metrics.areaHeight(rows)) else 0f
        val total = regionPx + keysPx + if (floating) 0 else bottomInset + (gap * density).roundToInt()
        bar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(regionPx, MeasureSpec.EXACTLY))
        keys.measure(MeasureSpec.makeMeasureSpec((keysWidth * density).roundToInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(keysPx, MeasureSpec.EXACTLY))
        val panelPx = (panelHeight(metrics) * density).roundToInt()
        keys.coveredHeight = if (expanded) panelHeight(metrics) else 0f
        panel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(panelPx, MeasureSpec.EXACTLY))
        previews.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, total)
    }

    /** The bar's layout for this width; a new one only when something changed. */
    private fun regionFor(widthDp: Float, idiom: Idiom): CandidateRegionLayout {
        val current = bar.region
        val rim = GlassOutline.contentBand(look.shape, 1f).first
        if (current.width == widthDp && current.idiom == idiom && current.cats == cats &&
            current.rim == rim && current.edgeHuggingCats == look.edgeHuggingCats) return current
        return CandidateRegionLayout(widthDp, idiom, cats, rim, edgeHuggingCats = look.edgeHuggingCats).also { bar.region = it }
    }

    /** The panel covers the key rows above the bottom row. */
    private fun panelHeight(metrics: KeyMetrics): Float =
        metrics.topPadding + (rows - 1) * (metrics.keyHeight + metrics.rowGap) - metrics.rowGap / 2

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        bar.layout(0, 0, width, bar.measuredHeight)
        val keyLeft = (width - keys.measuredWidth) / 2
        keys.layout(keyLeft, bar.measuredHeight, keyLeft + keys.measuredWidth, bar.measuredHeight + keys.measuredHeight)
        panel.layout(0, bar.measuredHeight, width, bar.measuredHeight + panel.measuredHeight)
        previews.layout(0, 0, width, previews.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val glass = (look.effects.hasFullEffects || look.tintedGlass) && !look.opaque && !look.usesSteppedMaterial
        val rim = bar.region.glass.top * density
        if (height != backgroundHeight || width != backgroundWidth || rim != backgroundRim) {
            backgroundHeight = height
            backgroundWidth = width
            backgroundRim = rim
            val opacity = if (floating && glass) 0.97f else 1f
            background.color = look.keyboardTop
            background.shader = if (look.effects == EffectsLevel.LIGHTWEIGHT || look.opaque || look.usesSteppedMaterial) null else
                LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(look.keyboardTop, look.keyboardMiddle, look.keyboardBottom)
                    .map { Look.withAlpha(it, opacity) }.toIntArray(),
                    floatArrayOf(0f, 0.37f, 1f), Shader.TileMode.CLAMP)
            if (glass) {
                // One continuous wash behind the candidate strip and keys.
                val glow = Look.withAlpha(Color.WHITE, if (look.dark) 0.065f else 0.35f)
                val reflected = Look.withAlpha(look.tint, if (look.tintedGlass) 0.11f else if (look.dark) 0.085f else 0.045f)
                light.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(glow, Color.TRANSPARENT, reflected),
                    floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
            }
            val ground = background.shader
            val wash = light.shader.takeIf { glass }
            if (experimentalGlass && Build.VERSION.SDK_INT >= 33 && isHardwareAccelerated && !floating &&
                look.effects == EffectsLevel.ULTRA && look.shape == GlassShape.CAPSULE && ground != null && wash != null) {
                // Give the lens the same continuous field painted under the keys. The offset
                // light creates a color boundary the rim can bend, even on a plain chat page.
                val glow = RadialGradient(width * 0.27f, rim, width * 0.52f,
                    Look.withAlpha(look.keyGlassReflection, if (look.dark) 0.32f else 0.29f),
                    Color.TRANSPARENT, Shader.TileMode.CLAMP)
                val base = ComposeShader(ground, wash, PorterDuff.Mode.SRC_OVER)
                background.shader = ComposeShader(base, glow, PorterDuff.Mode.SRC_OVER)
                light.shader = null
                bar.lensBackdrop = background.shader
                keys.usesLensKeycaps = true
            } else {
                bar.lensBackdrop = null
                keys.usesLensKeycaps = false
            }
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        if (glass && light.shader != null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), light)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) previews.clear()
    }
}

/** A full input window with one movable, touchable card. */
@SuppressLint("ViewConstructor")
class KeyboardWindowView(context: Context, val keyboard: KeyboardView, private val prefs: Prefs) : FrameLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val toolbar = FrameLayout(context)
    private val grip = View(context)
    private val handle = FrameLayout(context)
    private val dock = TextView(context).apply {
        setText(R.string.keyboard_position_docked)
        contentDescription = context.getString(R.string.floating_keyboard_dock)
        gravity = Gravity.CENTER
        setOnClickListener { prefs.floatingKeyboard = false }
    }
    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(toolbar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(36)))
        addView(keyboard, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val location = IntArray(2)
    private var safe = Rect()
    private var fractionX = prefs.floatingX
    private var fractionY = prefs.floatingY
    private var fingerX = 0f
    private var fingerY = 0f
    private var originX = 0f
    private var originY = 0f

    init {
        clipChildren = false
        addView(card)
        toolbar.addView(handle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        handle.addView(grip, LayoutParams(dp(36), dp(4), Gravity.CENTER))
        toolbar.addView(dock, LayoutParams(dp(64), LayoutParams.MATCH_PARENT, Gravity.END))
        handle.contentDescription = context.getString(R.string.floating_keyboard_handle)
        handle.setOnTouchListener { _, event -> drag(event) }
    }

    fun configure(look: Look) {
        val floating = prefs.floatingKeyboard
        keyboard.floating = floating
        toolbar.visibility = if (floating) VISIBLE else GONE
        dock.textSize = 13f * prefs.keyTextSize.scale
        card.clipToOutline = floating
        card.elevation = if (floating) dp(9).toFloat() else 0f
        card.background = if (floating) GradientDrawable().apply {
            setColor(Look.withAlpha(look.keyboardBottom, 0.35f))
            cornerRadius = dp(24).toFloat()
        } else null
        card.foreground = if (floating && look.effects.hasFullEffects && !look.opaque) GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), Look.withAlpha(if (look.dark) Color.WHITE else look.tint, if (look.dark) 0.23f else 0.18f))
            cornerRadius = dp(24).toFloat()
        } else null
        toolbar.setBackgroundColor(look.keyboardTop)
        grip.background = GradientDrawable().apply {
            setColor(Look.withAlpha(look.keyText, 0.38f))
            cornerRadius = dp(2).toFloat()
        }
        dock.setTextColor(look.keyText)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val next = if (Build.VERSION.SDK_INT >= 30) {
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                WindowInsets.Type.tappableElement())
            Rect(bars.left, bars.top, bars.right, bars.bottom)
        } else {
            @Suppress("DEPRECATION")
            Rect(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        if (safe != next) {
            safe = next
            requestLayout()
        }
        return super.onApplyWindowInsets(insets)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (!keyboard.floating) {
            val heightSpec = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) heightMeasureSpec
            else MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST)
            card.measure(widthMeasureSpec, heightSpec)
            setMeasuredDimension(width, card.measuredHeight)
            return
        }
        val availableWidth = (width - safe.left - safe.right - 2 * dp(8)).coerceAtLeast(1)
        val cardWidth = (availableWidth * 0.84f).roundToInt()
            .coerceIn(minOf(dp(276), availableWidth), minOf(dp(440), availableWidth))
        val room = (height - safe.top - safe.bottom - 2 * dp(8)).coerceAtLeast(1)
        card.measure(MeasureSpec.makeMeasureSpec(cardWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
        card.translationX = if (keyboard.floating) minX + roomX * fractionX else 0f
        card.translationY = if (keyboard.floating) minY + roomY * fractionY else 0f
    }

    private val minX get() = (safe.left + dp(8)).toFloat()
    private val minY get() = (safe.top + dp(8)).toFloat()
    private val roomX get() = (width - card.width - safe.right - dp(8) - minX).coerceAtLeast(0f)
    private val roomY get() = (height - card.height - safe.bottom - dp(8) - minY).coerceAtLeast(0f)

    @SuppressLint("ClickableViewAccessibility")
    private fun drag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fingerX = event.rawX
                fingerY = event.rawY
                originX = card.translationX
                originY = card.translationY
            }
            MotionEvent.ACTION_MOVE -> {
                card.translationX = (originX + event.rawX - fingerX).coerceIn(minX, minX + roomX)
                card.translationY = (originY + event.rawY - fingerY).coerceIn(minY, minY + roomY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (roomX > 0) fractionX = (card.translationX - minX) / roomX
                if (roomY > 0) fractionY = (card.translationY - minY) / roomY
                prefs.saveFloatingPosition(fractionX, fractionY)
                // Refresh the IME's touchable region once the drag ends.
                requestLayout()
            }
        }
        return true
    }

    fun setTouchableRegion(region: Region) {
        card.getLocationInWindow(location)
        region.set(location[0], location[1], location[0] + card.width, location[1] + card.height)
    }
}

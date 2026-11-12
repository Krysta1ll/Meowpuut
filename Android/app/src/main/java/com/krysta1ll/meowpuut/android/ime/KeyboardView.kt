package com.krysta1ll.meowpuut.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import android.view.WindowInsets
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * The whole keyboard: candidate region on top, keys below, the expanded
 * candidate panel over the key rows, and the balloon overlay above all.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, sprites: CatSprites) : FrameLayout(context) {
    /** Key balloons and alternates. Not called overlay: every View has one of those already. */
    val previews = PreviewOverlay(context)
    val bar = CandidateBarView(context, sprites)
    val keys = KeysView(context, previews)
    val panel = ExpandedPanelView(context)

    private val density = resources.displayMetrics.density
    private val background = Paint()
    private var backgroundKey = 0L
    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
        set(value) {
            field = value
            bar.look = value
            keys.look = value
            panel.look = value
            previews.look = value
            backgroundKey = 0
            // The expand key follows the shape's rim, so a new shape moves it.
            requestLayout()
            invalidate()
        }
    var idiom = Idiom.PHONE
        private set
    var cats = true
        set(value) { if (field != value) { field = value; requestLayout() } }
    private var rows = 4
    private var baseRows = 4
    private var bottomInset = 0
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

    fun idiomFor(): Idiom {
        val config = resources.configuration
        return Idiom.resolve(config.screenHeightDp.toFloat(), config.smallestScreenWidthDp.toFloat())
    }

    fun metrics(widthDp: Float = width / density): KeyMetrics = KeyMetrics.fitted(idiomFor(), widthDp, rows, baseRows)

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
        idiom = idiomFor()
        val region = regionFor(widthDp)
        // The keys were given metrics before this width was known (the first
        // time the keyboard shows, or after a rotation): correct them here.
        val metrics = metrics(widthDp)
        keys.setMetrics(metrics, idiom)
        val regionPx = (region.height * density).roundToInt()
        val keysPx = (metrics.areaHeight(rows) * density).roundToInt()
        val total = regionPx + keysPx + bottomInset
        bar.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(regionPx, MeasureSpec.EXACTLY))
        keys.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(keysPx, MeasureSpec.EXACTLY))
        val panelPx = (panelHeight(metrics) * density).roundToInt()
        keys.coveredHeight = if (expanded) panelHeight(metrics) else 0f
        panel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(panelPx, MeasureSpec.EXACTLY))
        previews.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, total)
    }

    /** The bar's layout for this width; a new one only when something changed. */
    private fun regionFor(widthDp: Float): CandidateRegionLayout {
        val current = bar.region
        val rim = GlassOutline.contentBand(look.shape, 1f).first
        if (current.width == widthDp && current.idiom == idiom && current.cats == cats && current.rim == rim) return current
        return CandidateRegionLayout(widthDp, idiom, cats, rim).also { bar.region = it }
    }

    /** The panel covers the key rows above the bottom row. */
    private fun panelHeight(metrics: KeyMetrics): Float =
        metrics.topPadding + (rows - 1) * (metrics.keyHeight + metrics.rowGap) - metrics.rowGap / 2

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        bar.layout(0, 0, width, bar.measuredHeight)
        keys.layout(0, bar.measuredHeight, width, bar.measuredHeight + keys.measuredHeight)
        panel.layout(0, bar.measuredHeight, width, bar.measuredHeight + panel.measuredHeight)
        previews.layout(0, 0, width, previews.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val key = (height.toLong() shl 32) xor look.keyboardTop.toLong() xor (look.keyboardBottom.toLong() shl 8)
        if (key != backgroundKey) {
            backgroundKey = key
            background.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), look.keyboardTop, look.keyboardBottom, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) previews.clear()
    }
}

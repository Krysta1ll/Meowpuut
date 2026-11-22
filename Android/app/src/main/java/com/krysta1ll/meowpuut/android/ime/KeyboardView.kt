package com.krysta1ll.meowpuut.android.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
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
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphController
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphLayout
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSeam
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
    val syllableRail = NineKeySyllableRailView(context)
    val panel = ExpandedPanelView(context, previews)

    private val density = resources.displayMetrics.density
    private val background = Paint()
    private val light = Paint()
    private val seam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seamRim = Path()
    private val topRadius = (12f * density).roundToInt().toFloat()
    private val experimentalGlass = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private var backgroundHeight = 0
    private var backgroundWidth = 0
    private var backgroundRim = 0f
    var floating = false
        set(value) {
            if (field == value) return
            field = value
            clipToOutline = !value && !look.usesSteppedMaterial
            backgroundHeight = 0
            if (glyph != null) configureGlyph()
            requestLayout()
        }
    /** 字景, while that skin is on: a world drawn under the keys and over them, on its own thread. Null for every other skin. */
    internal var glyph: GlyphController? = null
        private set
    /** How far the 字景 journey has got (the cats' count of characters, times eight), set before the skin is first shown. */
    var glyphDistance = 0L
    /**
     * The sky at the top of the keyboard that the seam thins into, in points: the paper does not end in a line but scatters into the plate
     * behind it ([Look.groundPlate]). Nothing is added above the keyboard, so the window, its insets and its touches are as for any skin.
     */
    internal val glyphInset: Double get() = if (glyph != null) GlyphSeam.inset(look.opaque, floating) else 0.0

    /** Debug builds: hold one moment (0..1) of the arrival to look at it; null lets it run. */
    var glyphForm: Double? = null
        set(value) { if (field != value) { field = value; glyph?.holdForm(value) } }

    /** Debug builds: hold one moment of an effect ("storm:6", "burst:5", "run"); null lets them happen. */
    var glyphFx: String? = null
        set(value) { if (field != value) { field = value; glyph?.holdFx(value) } }

    /** The guest crab (a debug build's setting). */
    var glyphCrab = false
        set(value) { if (field != value) { field = value; configureGlyph() } }
    /** Debug builds: stay in one place (0..5) to look at it; null travels as usual. */
    var glyphScene: Int? = null
        set(value) { if (field != value) { field = value; glyph?.force(value) } }

    /** Debug builds: hold what passes in front at this part of the width (shots). */
    var glyphFore: Double? = null
        set(value) { if (field != value) { field = value; glyph?.holdFore(value) } }

    private fun configureGlyph() {
        glyph?.configure(look.dark, look.effects.ordinal, look.opaque, glyphCrab, cats, arrival = !floating)
    }

    /** Puts the 字景 views in, or takes them out, as the skin asks. */
    private fun updateGlyph() {
        val want = look.isGlyph
        if (want && glyph == null) {
            val controller = GlyphController(context)
            glyph = controller
            controller.warm()
            addView(controller.groundView, 0)
            addView(controller.scene, 1)
            addView(controller.ringView, 2)
            addView(controller.nearView, 3)
            // The cats and the words that rise are under the bar and the keys: a legend, with its halo, is always the clearest thing.
            addView(controller.front, 4)
            keys.glyph = controller
            bar.glyph = controller
            controller.overlays = listOf(bar, keys)
            controller.signs = keys
            controller.typeOverCats = keys::catsMoved
            controller.scene.paper = if (look.dark) 0xFF151413.toInt() else 0xFFECE8DC.toInt()
            controller.groundView.paper = controller.scene.paper
            configureGlyph()
            controller.force(glyphScene)
            controller.holdFore(glyphFore)
            controller.holdForm(glyphForm)
            controller.holdFx(glyphFx)
            if (isAttachedToWindow) controller.start(glyphDistance)
            requestLayout()
        } else if (!want && glyph != null) {
            val controller = glyph ?: return
            keys.glyph = null
            bar.glyph = null
            removeView(controller.groundView)
            removeView(controller.scene)
            removeView(controller.ringView)
            removeView(controller.nearView)
            removeView(controller.front)
            controller.stop()
            glyph = null
            requestLayout()
        } else if (want) {
            glyph?.scene?.paper = if (look.dark) 0xFF151413.toInt() else 0xFFECE8DC.toInt()
            glyph?.groundView?.paper = glyph?.scene?.paper ?: 0xFFECE8DC.toInt()
            configureGlyph()
        }
    }

    /** What 字景 is told about the keyboard it is drawn behind, in dp from the keyboard's top left. */
    private fun glyphLayout(): GlyphLayout? {
        val rows = keys.rowCentersDp()
        if (rows.isEmpty() || width == 0) return null
        val top = bar.measuredHeight / density.toDouble()
        for (i in rows.indices) rows[i] += top
        return GlyphLayout(width / density.toDouble(), (bar.measuredHeight + keys.measuredHeight) / density.toDouble(), rows,
            GLYPH_LEGEND_HEIGHT, bar.glyphMid().toDouble(), bar.region.glass.top.toDouble(), bar.region.glass.height.toDouble(),
            lift = GlyphSeam.lift(look.opaque, floating), inset = glyphInset,
            horizon = rows[0] - GLYPH_LEGEND_HEIGHT / 2 - 1,
            catUnit = glyphCatUnit(rows),
            tail = glyph?.scene?.let { (it.measuredHeight - bar.measuredHeight - keys.measuredHeight) / density.toDouble() }?.coerceAtLeast(0.0) ?: 0.0)
    }

    /**
     * The size of a cat's pixel: the cats walk in the gap between two rows of keys, and a cat (Mimi is sixteen rows tall, seventeen in a
     * suit) must not stand under a legend. A whole number of device pixels, two points at most, and smaller where the rows are close.
     */
    private fun glyphCatUnit(rows: DoubleArray): Double {
        if (rows.size < 2) return 2.0
        val gap = (rows[1] - rows[0]) - GLYPH_KEY_INK                // between the foot of one row's type and the head of the next
        val device = Math.floor(gap * density / GLYPH_CAT_ROWS).toInt().coerceAtLeast(2)
        return minOf(2.0, device / density.toDouble())
    }

    /** Text was committed to the editor: the journey goes on, and the words that do something are looked for. */
    fun glyphCommitted(text: String, graphemes: Int, japanese: Boolean, secret: Boolean) {
        glyph?.committed(text, graphemes, japanese, secret)
    }

    var look: Look = Look(false, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true)
        set(value) {
            if (field == value) return
            val shapeChanged = field.shape != value.shape || field.skin != value.skin
            field = value
            updateGlyph()
            clipToOutline = !floating && !value.usesSteppedMaterial
            bar.look = value
            keys.look = value
            syllableRail.look = value
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
        set(value) { if (field != value) { field = value; configureGlyph(); requestLayout() } }
    var keyHeight = KeyboardHeight.STANDARD
        set(value) { if (field != value) { field = value; requestLayout() } }
    private var panelHeightDelta: Int? = null
    private var panelBottomGap = 0
    private var rows = 4
    private var baseRows = 4
    private var bottomInset = 0
    /** Whether this view has been given its window insets yet (see [onMeasure]). */
    private var insetsDispatched = false
    private var measuredLimit = Float.POSITIVE_INFINITY
    private var keyPointerMask = 0L
    private var superPointerMask = 0L
    private val singleProps = arrayOf(MotionEvent.PointerProperties())
    private val singleCoords = arrayOf(MotionEvent.PointerCoords())
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
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // The bottom stays flush against the navigation area.
                outline.setRoundRect(0, 0, view.width, view.height + topRadius.toInt(), topRadius)
            }
        }
        clipToOutline = true
        addView(bar)
        addView(keys)
        addView(syllableRail)
        addView(panel)
        addView(previews)
        syllableRail.visibility = GONE
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
        syllableRail.isHapticFeedbackEnabled = enabled
        bar.isHapticFeedbackEnabled = enabled
        panel.isHapticFeedbackEnabled = enabled
        previews.isHapticFeedbackEnabled = enabled
    }

    fun setNineKeySyllables(syllables: List<String>, showRail: Boolean, words: Words) {
        val active = showRail && syllables.isNotEmpty()
        keys.hideLeftMarks = active
        syllableRail.words = words
        syllableRail.update(if (active) syllables else emptyList(), metrics().cornerRadius + 1f)
        val nextVis = if (active) VISIBLE else GONE
        if (syllableRail.visibility != nextVis) {
            syllableRail.visibility = nextVis
            requestLayout()
        }
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

    /**
     * [bottomOf], but never less than the navigation bar's own height while the bar is showing. The window's first insets say the bar is
     * there and give it no height yet; taking that at its word sizes the window without the bar, and the window cannot grow afterwards.
     */
    private fun bottomWithBar(insets: WindowInsets): Int {
        val bottom = bottomOf(insets)
        if (Build.VERSION.SDK_INT < 30 || !insets.isVisible(WindowInsets.Type.navigationBars())) return bottom
        return maxOf(bottom, systemBottom() ?: 0)
    }

    /** The height of the navigation strip under the keyboard as the window manager reports it (API 30+); null where it cannot say. */
    private fun systemBottom(): Int? {
        if (Build.VERSION.SDK_INT < 30 || floating) return null
        return try {
            val wm = context.getSystemService(android.view.WindowManager::class.java)
            wm.maximumWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars() or WindowInsets.Type.tappableElement()).bottom
        } catch (_: Exception) {
            null
        }
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = bottomWithBar(insets)
        insetsDispatched = true
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
        glyph?.start(glyphDistance)
    }

    override fun onDetachedFromWindow() {
        glyph?.stop()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthDp = width / density
        // The window's insets are dispatched after the first measure. Sized without the navigation bar, the window is later measured with
        // its own height as the most it may have and can no longer grow, which would leave the lowest row under the bar. So until the
        // insets have been dispatched, the bar's height is taken from the window manager, which knows it before any window exists.
        if (!insetsDispatched) systemBottom()?.let { bottomInset = it }
        else rootWindowInsets?.let { bottomInset = bottomWithBar(it) }
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
        if (syllableRail.visibility == VISIBLE) {
            keys.leftColumnBox(keysWidth)?.let { box ->
                syllableRail.measure(MeasureSpec.makeMeasureSpec((box.width * density).roundToInt(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((box.height * density).roundToInt(), MeasureSpec.EXACTLY))
            }
        }
        val panelPx = (panelHeight(metrics) * density).roundToInt()
        keys.coveredHeight = if (expanded) panelHeight(metrics) else 0f
        panel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(panelPx, MeasureSpec.EXACTLY))
        glyph?.let {
            val h = MeasureSpec.makeMeasureSpec(regionPx + keysPx, MeasureSpec.EXACTLY)
            // The picture runs on, faintly, under the navigation strip and the dock's gap.
            val tailPx = if (floating) 0 else total - regionPx - keysPx
            it.scene.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(regionPx + keysPx + tailPx, MeasureSpec.EXACTLY))
            it.groundView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(regionPx + keysPx + tailPx, MeasureSpec.EXACTLY))
            it.nearView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(regionPx + keysPx + tailPx, MeasureSpec.EXACTLY))
            it.ringView.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(regionPx + keysPx + tailPx, MeasureSpec.EXACTLY))
            it.front.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), h)
        }
        previews.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(total, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, total)
    }

    /** The bar's layout for this width; a new one only when something changed. */
    private fun regionFor(widthDp: Float, idiom: Idiom): CandidateRegionLayout {
        val current = bar.region
        val rim = GlassOutline.contentBand(look.shape, 1f).first
        // 字景 has its own cats, walking in the land: the bar keeps none.
        val barCats = cats && !look.isGlyph
        if (current.width == widthDp && current.idiom == idiom && current.cats == barCats && current.rim == rim) return current
        return CandidateRegionLayout(widthDp, idiom, barCats, rim).also { bar.region = it }
    }

    /** The panel covers the key rows above the bottom row. */
    private fun panelHeight(metrics: KeyMetrics): Float =
        metrics.topPadding + (rows - 1) * (metrics.keyHeight + metrics.rowGap) - metrics.rowGap / 2

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        bar.layout(0, 0, width, bar.measuredHeight)
        val keyLeft = (width - keys.measuredWidth) / 2
        val glassContentBottom = bar.region.glass.top + GlassOutline.contentBand(look.shape, bar.region.glass.height).second
        keys.topTouchExtension = maxOf(0f, bar.region.height - glassContentBottom)
        keys.layout(keyLeft, bar.measuredHeight, keyLeft + keys.measuredWidth, bar.measuredHeight + keys.measuredHeight)
        if (syllableRail.visibility == VISIBLE) {
            keys.leftColumnBox()?.let { box ->
                val rl = keyLeft + (box.left * density).roundToInt()
                val rt = bar.measuredHeight + (box.top * density).roundToInt()
                syllableRail.layout(rl, rt, rl + syllableRail.measuredWidth, rt + syllableRail.measuredHeight)
            }
        }
        panel.layout(0, bar.measuredHeight, width, bar.measuredHeight + panel.measuredHeight)
        glyph?.let {
            it.scene.layout(0, 0, width, it.scene.measuredHeight)
            it.nearView.layout(0, 0, width, it.scene.measuredHeight)
            it.ringView.layout(0, 0, width, it.scene.measuredHeight)
            it.groundView.layout(0, 0, width, it.scene.measuredHeight)
            it.front.layout(0, 0, width, it.front.measuredHeight)
            glyphLayout()?.let(it::setLayout)
        }
        previews.layout(0, 0, width, previews.measuredHeight)
    }

    private fun pointerBit(pointerId: Int): Long = 1L shl (pointerId and 63)

    private fun forwardToKeys(event: MotionEvent): Boolean {
        val dx = -keys.left.toFloat()
        val dy = -keys.top.toFloat()
        event.offsetLocation(dx, dy)
        val handled = keys.dispatchTouchEvent(event)
        event.offsetLocation(-dx, -dy)
        return handled
    }

    private fun dispatchSinglePointer(event: MotionEvent, pointerIndex: Int, action: Int, toKeys: Boolean): Boolean {
        event.getPointerProperties(pointerIndex, singleProps[0])
        event.getPointerCoords(pointerIndex, singleCoords[0])
        if (toKeys) {
            singleCoords[0].x -= keys.left
            singleCoords[0].y -= keys.top
        }
        val synthetic = MotionEvent.obtain(
            event.downTime, event.eventTime, action, 1, singleProps, singleCoords,
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags, event.source, event.flags,
        )
        return try {
            if (toKeys) keys.dispatchTouchEvent(synthetic) else super.dispatchTouchEvent(synthetic)
        } finally {
            synthetic.recycle()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                keyPointerMask = 0L
                superPointerMask = 0L
                val idx = event.actionIndex
                val bit = pointerBit(event.getPointerId(idx))
                return if (shouldRouteTouchToKeys(event.getX(idx), event.getY(idx))) {
                    keyPointerMask = bit
                    forwardToKeys(event)
                } else {
                    superPointerMask = bit
                    super.dispatchTouchEvent(event)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val bit = pointerBit(event.getPointerId(idx))
                val forKeys = shouldRouteTouchToKeys(event.getX(idx), event.getY(idx))
                if (forKeys && superPointerMask == 0L) {
                    keyPointerMask = keyPointerMask or bit
                    return forwardToKeys(event)
                }
                if (!forKeys && keyPointerMask == 0L) {
                    superPointerMask = superPointerMask or bit
                    return super.dispatchTouchEvent(event)
                }
                return if (forKeys) {
                    val wasEmpty = keyPointerMask == 0L
                    keyPointerMask = keyPointerMask or bit
                    dispatchSinglePointer(event, idx, if (wasEmpty) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, toKeys = true)
                } else {
                    val wasEmpty = superPointerMask == 0L
                    superPointerMask = superPointerMask or bit
                    dispatchSinglePointer(event, idx, if (wasEmpty) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, toKeys = false)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (keyPointerMask != 0L && superPointerMask == 0L) return forwardToKeys(event)
                if (superPointerMask != 0L && keyPointerMask == 0L) return super.dispatchTouchEvent(event)
                var handled = false
                if (keyPointerMask != 0L) handled = forwardToKeys(event) || handled
                if (superPointerMask != 0L) {
                    for (i in 0 until event.pointerCount) {
                        if ((superPointerMask and pointerBit(event.getPointerId(i))) != 0L) {
                            handled = dispatchSinglePointer(event, i, MotionEvent.ACTION_MOVE, toKeys = false) || handled
                            break
                        }
                    }
                }
                return handled
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val idx = event.actionIndex
                val bit = pointerBit(event.getPointerId(idx))
                val isKeysPointer = (keyPointerMask and bit) != 0L
                val hadOnlyKeys = superPointerMask == 0L && isKeysPointer
                val hadOnlySuper = keyPointerMask == 0L && (superPointerMask and bit) != 0L
                keyPointerMask = keyPointerMask and bit.inv()
                superPointerMask = superPointerMask and bit.inv()
                val handled = when {
                    hadOnlyKeys -> forwardToKeys(event)
                    hadOnlySuper -> super.dispatchTouchEvent(event)
                    isKeysPointer -> dispatchSinglePointer(
                        event, idx,
                        if (keyPointerMask == 0L) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP,
                        toKeys = true,
                    )
                    else -> dispatchSinglePointer(
                        event, idx,
                        if (superPointerMask == 0L) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP,
                        toKeys = false,
                    )
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    keyPointerMask = 0L
                    superPointerMask = 0L
                }
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                var handled = false
                if (keyPointerMask != 0L) handled = forwardToKeys(event) || handled
                if (superPointerMask != 0L) handled = super.dispatchTouchEvent(event) || handled
                keyPointerMask = 0L
                superPointerMask = 0L
                return handled
            }
            else -> return super.dispatchTouchEvent(event)
        }
    }

    internal fun shouldRouteTouchToKeys(x: Float, y: Float): Boolean {
        if (keys.visibility != VISIBLE || !keys.isEnabled) return false
        if (expanded) return y >= keys.bottom
        if (syllableRail.visibility == VISIBLE &&
            x >= syllableRail.left && x < syllableRail.right &&
            y >= syllableRail.top && y < syllableRail.bottom
        ) {
            return false
        }
        if (y < keys.top) {
            val glassContentBottom = bar.region.glass.top + GlassOutline.contentBand(look.shape, bar.region.glass.height).second
            return y >= glassContentBottom * density && !bar.claimsPoint(x / density, y / density)
        }
        return true
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
            val wash = if (glass) {
                // One continuous wash behind the candidate strip and keys.
                val glow = Look.withAlpha(Color.WHITE, if (look.dark) 0.065f else 0.35f)
                val reflected = Look.withAlpha(look.tint, if (look.tintedGlass) 0.11f else if (look.dark) 0.085f else 0.045f)
                LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(glow, Color.TRANSPARENT, reflected),
                    floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
            } else null
            light.shader = wash
            val ground = background.shader
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
            updateSeam(rim)
        }
        val plate = look.groundPlate
        if (plate != null && glyph != null && !look.opaque && !floating) {
            // 字景: the plate lies behind its picture, and the paper thins out into it at the top. The navigation area under the keys is paper.
            val under = (bar.measuredHeight + keys.measuredHeight).toFloat()
            background.shader = null
            background.color = plate
            canvas.drawRect(0f, 0f, width.toFloat(), under, background)
            if (under < height) {
                background.color = look.keyboardBottom
                canvas.drawRect(0f, under, width.toFloat(), height.toFloat(), background)
            }
            backgroundHeight = 0
            return
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        if (light.shader != null) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), light)
        if (seam.shader != null) canvas.drawPath(seamRim, seam)
    }

    private fun updateSeam(rim: Float) {
        seam.shader = null
        if (floating || look.usesSteppedMaterial || look.opaque || look.effects == EffectsLevel.LIGHTWEIGHT) return
        val inset = density / 2
        val radius = topRadius - inset
        val right = width - inset
        val depth = minOf(8f * density, maxOf(3f * density, rim * 0.48f))
        // A narrow reflection at the lip, then a softer highlight on the deck.
        seam.shader = LinearGradient(0f, inset, 0f, inset + depth,
            intArrayOf(Look.withAlpha(Color.WHITE, if (look.dark) 0.43f else 0.85f),
                Look.withAlpha(if (look.dark) 0xFFEBDBED.toInt() else Color.WHITE, if (look.dark) 0.13f else 0.50f),
                Color.TRANSPARENT),
            floatArrayOf(0f, 0.20f, 1f), Shader.TileMode.CLAMP)
        seamRim.rewind()
        seamRim.moveTo(inset, topRadius)
        seamRim.arcTo(inset, inset, inset + 2 * radius, inset + 2 * radius, 180f, 90f, false)
        seamRim.lineTo(right - radius, inset)
        seamRim.arcTo(right - 2 * radius, inset, right, inset + 2 * radius, 270f, 90f, false)
        seamRim.close()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) previews.clear()
        if (isVisible) glyph?.resume() else glyph?.pause()
    }
}

/** How tall the legend of a key is in 字景 (seven pixels of three points, and a halo): the cats are as tall as the gap that leaves. */
private const val GLYPH_LEGEND_HEIGHT = 27.0
/** The tallest a key's type stands: twelve pixels of two points. */
private const val GLYPH_KEY_INK = 24.0
/** The rows of the tallest cat (Mimi in a suit). */
private const val GLYPH_CAT_ROWS = 17

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

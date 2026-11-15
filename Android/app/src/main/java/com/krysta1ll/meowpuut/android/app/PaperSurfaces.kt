package com.krysta1ll.meowpuut.android.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.ime.wantsMoreContrast
import kotlin.math.roundToInt

// Kami-Biyori for the settings page and the skin gallery only. The keyboard
// (ime/) does not use any of this. Colours come from PaperTokens.

/**
 * The fonts of the app pages, in one place (settings and the skin gallery; never the keyboard).
 *  - Latin wordmark: Fraunces (SIL Open Font License 1.1), static 600 instance, Latin subset (res/font).
 *  - Chinese titles and labels: jf open 粉圓 (SIL Open Font License 1.1, renamed "Meowpuut Paper Round"),
 *    subset to the fixed UI strings, Traditional and Simplified forms.
 * Both come from scripts/subset_app_fonts.py. A character outside the subset is drawn by the system
 * font on its own. Simplified Chinese (粉圓 is a Traditional design) and large font sizes use the plain
 * system font, and so does any text the user wrote: only fixed interface text takes these faces.
 */
internal object PaperFonts {
    private fun large(context: Context) = context.resources.configuration.fontScale >= 1.3f

    private fun simplified(context: Context): Boolean {
        val locale = context.resources.configuration.locales[0]
        if (locale.language != "zh") return false
        if (locale.script == "Hans") return true
        return locale.script != "Hant" && locale.country !in setOf("TW", "HK", "MO")
    }

    private fun font(context: Context, id: Int): Typeface? =
        try { context.resources.getFont(id) } catch (_: Exception) { null }

    private fun real(context: Context, id: Int, simplifiedMatters: Boolean): Typeface? =
        if (large(context) || (simplifiedMatters && simplified(context))) null else font(context, id)

    fun wordmark(context: Context): Typeface =
        real(context, R.font.paper_serif_semibold, false) ?: if (large(context)) Typeface.DEFAULT_BOLD else Typeface.create(Typeface.SERIF, Typeface.BOLD)
    fun title(context: Context): Typeface =
        real(context, R.font.paper_round, true) ?: if (large(context)) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.BOLD)
    fun label(context: Context): Typeface =
        real(context, R.font.paper_round, true) ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    /** The stamp glyph is one fixed Chinese character, so it takes the round face. */
    fun stamp(context: Context): Typeface =
        real(context, R.font.paper_round, true) ?: Typeface.create(Typeface.SERIF, Typeface.BOLD)
}

/** Paper colours plus the small pieces built from them. [plain] is the system's medium/high contrast: solid colours, no grain. */
internal class PaperUi(private val context: Context, theme: PaperTheme, val dark: Boolean) {
    val p: PaperPalette = PaperTokens.palette(theme, dark)
    val plain: Boolean = wantsMoreContrast(context)
    private val density = context.resources.displayMetrics.density

    fun dp(value: Int) = (value * density).roundToInt()

    /** A line, for borders: the ink at about 18%. */
    val line: Int get() = (p.ink and 0x00FFFFFF) or (0x2E shl 24)

    fun background(): Drawable = PaperDrawable(context, p, plain)

    /** A quiet heading (no strip) for pages that already spend their label strips elsewhere. */
    fun heading(title: CharSequence): TextView = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(p.ink)
        typeface = PaperFonts.label(context)
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    /** A section heading: ink strip with paper-coloured text. */
    fun labelStrip(title: CharSequence): TextView = TextView(context).apply {
        text = title
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(p.paper)
        typeface = PaperFonts.label(context)
        includeFontPadding = false
        setPadding(dp(10), dp(5), dp(10), dp(5))
        background = GradientDrawable().apply {
            setColor(p.ink)
            cornerRadius = dp(4).toFloat()
            if (plain) setStroke(dp(1), p.paper)
        }
        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    /** A dashed stitch line, 4 on / 3 off. */
    fun stitch(): View = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.inkSecondary
            alpha = 180
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = density
            pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
        }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onDraw(canvas: Canvas) {
            val y = height / 2f
            canvas.drawLine(paint.strokeWidth, y, width - paint.strokeWidth, y, paint)
        }
    }

    /** The switch in brand colours: pill track with a white thumb when on, a quiet track when off. */
    @Suppress("DEPRECATION")
    fun tint(control: Switch) {
        val on = intArrayOf(android.R.attr.state_checked)
        val off = intArrayOf()
        control.thumbTintList = ColorStateList(arrayOf(on, off), intArrayOf(p.pillText, p.paper))
        control.trackTintList = ColorStateList(arrayOf(on, off), intArrayOf(p.pill, (p.inkSecondary and 0x00FFFFFF) or (0x66 shl 24)))
    }
}

/** Paper with a little grain: a gradient, and the tileable grain image through a BitmapShader. */
private class PaperDrawable(context: Context, private val p: PaperPalette, private val plain: Boolean) : Drawable() {
    private val base = Paint()
    private val grain = Paint().apply { isFilterBitmap = false }
    private val tile: Bitmap? = if (plain) null else runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.paper_grain, BitmapFactory.Options().apply { inScaled = false })
    }.getOrNull()

    init {
        if (tile != null) {
            grain.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            // The image is white with the grain in its alpha; tint it with the token (its alpha scales it).
            grain.colorFilter = PorterDuffColorFilter(p.grain, PorterDuff.Mode.SRC_IN)
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        base.shader = if (plain) null else {
            val bottom = blend(p.paper, p.paper2, 0.55f)
            LinearGradient(0f, 0f, 0f, bounds.height().toFloat(), p.paper, bottom, Shader.TileMode.CLAMP)
        }
        base.color = p.paper
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, base)
        if (tile != null) canvas.drawRect(bounds, grain)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - t) + ((b shr shift) and 0xFF) * t).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/**
 * A seal and a sentence for something that was saved or cleared. It scales in over 140 ms
 * (1.12 to 1), or only fades when animations are off, and the sentence is announced.
 */
@SuppressLint("ViewConstructor")
internal class StampToast(context: Context, private val ui: PaperUi) : LinearLayout(context) {
    private val glyph = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setTextColor(ui.p.stamp)
        typeface = PaperFonts.stamp(context)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = ui.dp(36)
        minHeight = ui.dp(36)
        setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        background = GradientDrawable().apply {
            setStroke(ui.dp(2), ui.p.stamp)
            cornerRadius = ui.dp(5).toFloat()
        }
        rotation = -2f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val message = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(ui.p.ink)
    }
    private val hide = Runnable { visibility = GONE }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        background = GradientDrawable().apply {
            setColor(ui.p.paper)
            setStroke(ui.dp(1), ui.line)
            cornerRadius = ui.dp(10).toFloat()
        }
        elevation = ui.dp(4).toFloat()
        addView(glyph)
        addView(message, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(10) })
        visibility = GONE
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun show(seal: String, text: String) {
        glyph.text = seal
        message.text = text
        removeCallbacks(hide)
        visibility = VISIBLE
        animate().cancel()
        if (ValueAnimator.areAnimatorsEnabled()) {
            alpha = 0f; scaleX = 1.12f; scaleY = 1.12f
            glyph.rotation = -3f
            glyph.animate().rotation(-2f).setDuration(140).start()
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
        } else {
            alpha = 1f; scaleX = 1f; scaleY = 1f
        }
        postDelayed(hide, 2400)
    }
}

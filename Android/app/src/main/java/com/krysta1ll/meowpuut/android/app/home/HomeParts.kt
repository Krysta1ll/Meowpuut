package com.krysta1ll.meowpuut.android.app.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.krysta1ll.meowpuut.android.app.EntryStyle
import com.krysta1ll.meowpuut.android.app.PaperFonts
import com.krysta1ll.meowpuut.android.app.PaperUi
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import kotlin.math.max
import kotlin.math.roundToInt

/** The wordmark of the home page, "meowpuut", in the keyboard's hand-cut letters (5 × 7 pixels each), [unit] dp to a pixel. */
@SuppressLint("ViewConstructor")
internal class PixelMark(context: Context, private val ink: Int, private val unit: Int = 3) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply { isAntiAlias = false }
    private val letters = "meowpuut".map { GlyphKeyArt.bits(it.toString())!! }
    private val u get() = max(1, (unit * density).roundToInt())

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(resolveSize(letters.size * 6 * u, widthMeasureSpec), 9 * u)

    override fun onDraw(canvas: Canvas) {
        paint.color = ink
        var x = 0
        for (rows in letters) {
            for ((r, row) in rows.withIndex()) for ((k, ch) in row.withIndex()) if (ch == 'X') canvas.drawRect((x + k * u).toFloat(), ((r + 1) * u).toFloat(), (x + (k + 1) * u).toFloat(), ((r + 2) * u).toFloat(), paint)
            x += 6 * u
        }
    }
}

/** The way back: a chevron cut in the same pixels as the wordmark, centred in a 48 dp square so it is easy to hit. */
@SuppressLint("ViewConstructor")
internal class BackChevron(context: Context, private val ink: Int) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply { isAntiAlias = false }
    private val rows = listOf("...X", "..X.", ".X..", "X...", ".X..", "..X.", "...X")

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension((48 * density).roundToInt(), (48 * density).roundToInt())

    override fun onDraw(canvas: Canvas) {
        val u = max(1, (3 * density).roundToInt())
        val x0 = (width - 4 * u) / 2
        val y0 = (height - rows.size * u) / 2
        paint.color = ink
        for ((r, row) in rows.withIndex()) for ((k, ch) in row.withIndex()) if (ch == 'X') canvas.drawRect((x0 + k * u).toFloat(), (y0 + r * u).toFloat(), (x0 + (k + 1) * u).toFloat(), (y0 + (r + 1) * u).toFloat(), paint)
    }
}

/** A landmark as a small icon, bottom-aligned in a [boxDp] square, [unitDp] dp to a pixel. */
@SuppressLint("ViewConstructor")
internal class StampIcon(context: Context, private val rows: List<String>, private val ui: PaperUi, private val boxDp: Int = 30, private val unitDp: Int = 2) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val paint = Paint().apply { isAntiAlias = false }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension((boxDp * density).roundToInt(), (boxDp * density).roundToInt())

    override fun onDraw(canvas: Canvas) {
        val u = max(1, (unitDp * density).roundToInt())
        val x0 = (width - rows[0].length * u) / 2
        val y0 = height - rows.size * u - (2 * density).roundToInt()
        for ((r, row) in rows.withIndex()) for ((k, ch) in row.withIndex()) {
            if (ch == '.') continue
            paint.color = when (ch) { 'R' -> ui.p.stamp; 'o' -> if (ui.dark) ui.p.ink else ui.p.paper; else -> ui.p.ink }
            canvas.drawRect((x0 + k * u).toFloat(), (y0 + r * u).toFloat(), (x0 + (k + 1) * u).toFloat(), (y0 + (r + 1) * u).toFloat(), paint)
        }
    }
}

/** One way in from the home page: where it goes, what it says, and a landmark for the skins that hang signposts. */
internal class HomeEntry(
    val title: String,
    val value: String,
    val stamp: String,
    /** The page has nothing else that opens this (行旅): a skin that keeps the page as it was still shows it, as a card. */
    val own: Boolean,
    val open: () -> Unit,
) {
    var valueView: ((String) -> Unit)? = null
}

/**
 * The ways in at the top of the home page, in the style a skin asks for ([EntryStyle]): cards (only the ones that have no section of their own
 * on the page), or a signpost for each with its landmark, as the prototype hangs them.
 */
internal object HomeEntries {
    fun build(context: Context, ui: PaperUi, style: EntryStyle, entries: List<HomeEntry>): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val shown = if (style == EntryStyle.SIGNPOSTS) entries else entries.filter { it.own }
        for ((i, e) in shown.withIndex()) {
            val row = if (style == EntryStyle.SIGNPOSTS) signpost(context, ui, e) else card(context, ui, e)
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0 && style == EntryStyle.CARDS) topMargin = (8 * context.resources.displayMetrics.density).roundToInt() })
        }
        return box
    }

    private fun signpost(context: Context, ui: PaperUi, e: HomeEntry): View {
        val density = context.resources.displayMetrics.density
        val title = PixelText(context, ui.pixelType, e.title, 24, ui.p.ink, lineSpacing = 1.2f)
        val value = PixelText(context, ui.pixelType, e.value, 12, ui.p.inkSecondary, Gravity.END)
        e.valueView = { value.setText(it); describe(value.parent as View, e.title, it) }
        val row = object : LinearLayout(context) {
            private val dash = Paint().apply {
                color = ui.p.ink; alpha = 140; style = Paint.Style.STROKE; strokeWidth = ui.dp(2).toFloat()
                pathEffect = DashPathEffect(floatArrayOf(ui.dp(6).toFloat(), ui.dp(4).toFloat()), 0f)
            }
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                val y = height - dash.strokeWidth / 2
                canvas.drawLine(0f, y, width.toFloat(), y, dash)
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(60)
            setPadding(0, ui.dp(6), 0, ui.dp(8))
            isClickable = true
            isFocusable = true
            setWillNotDraw(false)
            setOnClickListener { e.open() }
            // Pressed, the title turns vermilion.
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> title.setInk(ui.p.stamp)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> title.setInk(ui.p.ink)
                }
                false
            }
        }
        row.addView(StampIcon(context, Stamps.rows(e.stamp), ui), LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)).apply { marginEnd = ui.dp(12) })
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        title.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        value.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        describe(row, e.title, e.value)
        row.accessibilityDelegate = button()
        return row
    }

    private fun card(context: Context, ui: PaperUi, e: HomeEntry): View {
        val title = TextView(context).apply {
            text = e.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(ui.p.ink)
            typeface = PaperFonts.label(context)
        }
        val value = TextView(context).apply {
            text = e.value
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(ui.p.inkSecondary)
            gravity = Gravity.END
        }
        e.valueView = { value.text = it; describe(value.parent as View, e.title, it) }
        val chevron = TextView(context).apply { text = "›"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f); setTextColor(ui.p.inkSecondary) }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(56)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(14), ui.dp(8))
            background = ui.surface(18)
            isClickable = true
            isFocusable = true
            setOnClickListener { e.open() }
        }
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) })
        row.addView(chevron)
        for (c in listOf(title, value, chevron)) c.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        describe(row, e.title, e.value)
        row.accessibilityDelegate = button()
        return row
    }

    private fun describe(row: View, title: String, value: String) { row.contentDescription = if (value.isEmpty()) title else "$title，$value" }

    private fun button() = object : View.AccessibilityDelegate() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    }
}

/**
 * The home page's top in 字景: the wordmark, the name of the place and the number of the stop at the right, and under them a living picture
 * of where the two cats have got to, running the full width of the page (a tap sends them for a short walk).
 */
internal object HomeHero {
    /** The whole top of the page, and the living picture in it (the festival of the day is set on that when it is known). */
    class Built(val view: View, val picture: GlyphLiveView)

    fun build(context: Context, ui: PaperUi, where: String, stop: String, description: String, distance: Long, level: Int, plain: Boolean, bleed: Int): Built {
        val density = context.resources.displayMetrics.density
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(PixelMark(context, ui.p.ink), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val right = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(PixelText(context, ui.pixelType, where, 12, ui.p.inkSecondary, Gravity.END, 1.4f))
        right.addView(PixelText(context, ui.pixelType, stop, 12, ui.p.inkSecondary, Gravity.END, 1.4f))
        head.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        head.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        box.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * density).roundToInt(); bottomMargin = (8 * density).roundToInt() })
        val picture = GlyphLiveView(context, LiveSpec(distance = distance, cats = true, heightDp = 208), ui.dark, plain, level).apply {
            contentDescription = description
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            isClickable = true
            setOnClickListener { nudge() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
        }
        // The picture runs to the page's edges: the page's own padding is stepped back over.
        box.addView(picture, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = -bleed; marginEnd = -bleed })
        return Built(box, picture)
    }
}

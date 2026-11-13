package com.krysta1ll.meowpuut.android.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.SkinCatArtwork
import com.krysta1ll.meowpuut.android.ime.SkinMaterial
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class SkinGallery(
    context: Context,
    preview: View,
    title: String,
    options: List<Option>,
    detailLabel: String,
    onSkin: (KeyboardSkin) -> Unit,
    onCustomize: () -> Unit,
) : LinearLayout(context) {
    data class Option(val skin: KeyboardSkin, val title: String)

    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink = if (dark) 0xfff3ece9.toInt() else 0xff292124.toInt()
    private val accent = if (dark) 0xffebbac2.toInt() else 0xff85515d.toInt()
    private val outline = if (dark) 0xff4a4149.toInt() else 0xffe9dcd6.toInt()
    private val fill = if (dark) 0xff2b272c.toInt() else 0xfffffaf4.toInt()
    private val activeFill = if (dark) 0xff3a2c36.toInt() else 0xfff5e5e3.toInt()
    private val tiles = options.associateWith { SkinTile(it, onSkin) }

    init {
        orientation = VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 19f
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            tiles.values.forEachIndexed { index, tile ->
                addView(tile, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                })
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        addView(TextView(context).apply {
            text = detailLabel
            textSize = 13f
            setTextColor(accent)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            isFocusable = true
            setPadding(dp(10), dp(4), dp(4), dp(4))
            setOnClickListener { onCustomize() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.Button::class.java.name
                }
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render(KeyboardSkin.CURRENT) { !it.requiresQuiz }
    }

    fun render(selected: KeyboardSkin, isUnlocked: (KeyboardSkin) -> Boolean) {
        for ((option, tile) in tiles) {
            tile.render(option.skin == selected, !isUnlocked(option.skin))
        }
    }

    private inner class SkinTile(
        private val option: Option,
        onSkin: (KeyboardSkin) -> Unit,
    ) : LinearLayout(this@SkinGallery.context) {
        private val swatch = SkinSwatch(context, option.skin, dark)
        private val label = TextView(context).apply {
            text = option.title
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(ink)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(5), dp(5), dp(5), dp(7))
            isFocusable = true
            val selectable = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)) {
                foreground = context.getDrawable(selectable.resourceId)
            }
            addView(swatch, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
            setOnClickListener { onSkin(option.skin) }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = isSelected
                }
            }
        }

        fun render(active: Boolean, locked: Boolean) {
            isSelected = active
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (active) activeFill else fill)
                setStroke(dp(1), if (active) accent else outline)
            }
            label.setTextColor(if (active) accent else ink)
            contentDescription = if (locked) context.getString(R.string.skin_unlock_title, option.title) else option.title
            swatch.render(active, locked)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

@SuppressLint("ViewConstructor")
private class SkinSwatch(context: Context, private val skin: KeyboardSkin, dark: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val pixel = skin.material == SkinMaterial.PIXEL
    private val deck = when (skin.catArtwork) {
        SkinCatArtwork.SCULPTED -> if (dark) 0xff42343e.toInt() else 0xffe8dadd.toInt()
        SkinCatArtwork.LEGACY -> if (dark) 0xff354350.toInt() else 0xffe1e7ef.toInt()
        SkinCatArtwork.PIXEL -> if (dark) 0xff342c4b.toInt() else 0xffe8dff0.toInt()
    }
    private val rim = if (pixel) 0xff827196.toInt() else if (dark) 0xffaaadb8.toInt() else Color.WHITE
    private val key = if (dark) 0xff857a8a.toInt() else 0xfffffbfa.toInt()
    private val statusInk = if (dark) 0xffe5d5e1.toInt() else 0xff685062.toInt()
    private val catImages = when (skin.catArtwork) {
        SkinCatArtwork.SCULPTED -> R.drawable.cat_mimimo_edge_peek to R.drawable.cat_momo_edge_peek
        SkinCatArtwork.LEGACY -> R.drawable.cat_classic_mimimo_open to R.drawable.cat_classic_momo_open
        SkinCatArtwork.PIXEL -> R.drawable.cat_pixel_mimimo to R.drawable.cat_pixel_momo
    }.let { (left, right) ->
        val options = BitmapFactory.Options().apply { inSampleSize = if (pixel) 1 else 2 }
        BitmapFactory.decodeResource(resources, left, options) to BitmapFactory.decodeResource(resources, right, options)
    }
    private val glass = LinearGradient(0f, 17f, 0f, 27f, rim, deck, Shader.TileMode.CLAMP)
    private var active = false
    private var locked = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun render(active: Boolean, locked: Boolean) {
        this.active = active
        this.locked = locked
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 88f, height / 48f)
        box(canvas, 0f, 0f, width.toFloat(), height.toFloat(), (if (pixel) 5f else 9f) * scale, deck)
        canvas.save()
        canvas.translate((width - 88f * scale) / 2, (height - 48f * scale) / 2)
        canvas.scale(scale, scale)
        paint.isFilterBitmap = !pixel
        cat(canvas, catImages.first, 5f, 2f)
        cat(canvas, catImages.second, 62f, 2f)
        val barRadius = if (pixel) 1f else 5f
        if (!pixel) paint.shader = glass
        box(canvas, 5f, 17f, 83f, 27f, barRadius, rim)
        paint.shader = null
        if (pixel) box(canvas, 6f, 18f, 82f, 26f, barRadius, deck)
        box(canvas, 33f, 20f, 54f, 24f, if (pixel) 0f else 2f, statusInk)
        for (row in 0 until 2) {
            for (column in 0 until 5) {
                val x = 9f + column * 14.5f
                val y = 30f + row * 7f
                box(canvas, x, y, x + 11.5f, y + 5f, if (pixel) 0f else 2f, key)
            }
        }
        if (locked || active) drawStatus(canvas)
        canvas.restore()
    }

    private fun box(canvas: Canvas, x: Float, y: Float, right: Float, bottom: Float, radius: Float, color: Int) {
        paint.color = color
        rect.set(x, y, right, bottom)
        canvas.drawRoundRect(rect, radius, radius, paint)
    }

    private fun cat(canvas: Canvas, image: Bitmap, x: Float, y: Float) {
        val scale = 20f / maxOf(image.width, image.height)
        val width = image.width * scale
        val height = image.height * scale
        rect.set(x + (20f - width) / 2, y + 20f - height, x + (20f + width) / 2, y + 20f)
        canvas.drawBitmap(image, null, rect, paint)
    }

    private fun drawStatus(canvas: Canvas) {
        paint.color = statusInk
        canvas.drawCircle(44f, 9f, 6.5f, paint)
        paint.color = Color.WHITE
        paint.strokeWidth = 1.1f
        paint.strokeCap = Paint.Cap.ROUND
        if (locked) {
            paint.style = Paint.Style.STROKE
            rect.set(42.5f, 5.9f, 45.5f, 9.1f)
            canvas.drawArc(rect, 180f, 180f, false, paint)
            paint.style = Paint.Style.FILL
            canvas.drawRoundRect(41.7f, 8.6f, 46.3f, 11.9f, 0.7f, 0.7f, paint)
        } else {
            canvas.drawLine(41.6f, 9.2f, 43.4f, 11f, paint)
            canvas.drawLine(43.4f, 11f, 46.7f, 7f, paint)
        }
    }
}

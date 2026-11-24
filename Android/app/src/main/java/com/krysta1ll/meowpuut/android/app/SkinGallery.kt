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
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphColors
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.GlassStrength
import com.krysta1ll.meowpuut.android.ime.Palette
import com.krysta1ll.meowpuut.android.ime.SkinCatArtwork
import com.krysta1ll.meowpuut.android.ime.SkinMaterial
import kotlin.math.roundToInt

@SuppressLint("ViewConstructor")
internal class SkinGallery(
    context: Context,
    private val ui: PaperUi,
    preview: View,
    title: String,
    options: List<Option>,
    detailLabel: String,
    onSkin: (KeyboardSkin) -> Unit,
    onCustomize: () -> Unit,
) : LinearLayout(context) {
    data class Option(val skin: KeyboardSkin, val title: String)

    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink = ui.p.ink
    private val accent = ui.p.pill
    private val outline = ui.line
    private val fill = ui.p.paper
    private val activeFill = ui.p.paper2
    private val tiles = options.associateWith { SkinTile(it, onSkin) }
    private val commemorativeCard = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(16), dp(12))
        val deckColors = if (dark) intArrayOf(0xFF1D2838.toInt(), 0xFF0F1724.toInt())
        else intArrayOf(0xFFF7EFE0.toInt(), 0xFFE9DBC3.toInt())
        val foilStroke = if (dark) 0xFF9C7B3F.toInt() else 0xFFC8A66B.toInt()
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, deckColors).apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), foilStroke)
        }
        addView(ImageView(context).apply {
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.centenary_duo, opts)
            if (bmp != null) setImageBitmap(bmp) else setImageResource(R.drawable.centenary_duo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = context.getString(R.string.skin_centenary_art)
        }, LayoutParams(dp(106), dp(84)).apply { marginEnd = dp(12) })
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = "No.100 · MEOWPUUT"
                textSize = 10f
                letterSpacing = 0.08f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (dark) 0xFFDFBD7A.toInt() else 0xFF8C6530.toInt())
            })
            addView(TextView(context).apply {
                setText(R.string.skin_centenary)
                textSize = 17f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(if (dark) 0xFFF8EFDC.toInt() else 0xFF302A22.toInt())
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            addView(TextView(context).apply {
                setText(R.string.skin_centenary_note)
                textSize = 11.5f
                setTextColor(if (dark) 0xFFD4BE96.toInt() else 0xFF6D5D48.toInt())
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            addView(TextView(context).apply {
                setText(R.string.skin_centenary_thanks)
                textSize = 13f
                setTextColor(if (dark) 0xFFD4BE96.toInt() else 0xFF6D5D48.toInt())
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        addView(ui.heading(title), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })

        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        addView(commemorativeCard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        tiles.values.toList().chunked(2).forEach { row ->
            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                clipChildren = false
                clipToPadding = false
                setPadding(0, dp(4), 0, dp(4))
                row.forEachIndexed { index, tile ->
                    addView(tile, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                        if (index > 0) marginStart = dp(8)
                    })
                }
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        addView(TextView(context).apply {
            text = detailLabel
            textSize = 13f
            setTextColor(ui.p.stamp)
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
        commemorativeCard.visibility = if (selected == KeyboardSkin.CENTENARY) View.VISIBLE else View.GONE
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
            addView(swatch, LayoutParams(LayoutParams.MATCH_PARENT, dp(60)))
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
            if (option.skin == KeyboardSkin.CENTENARY) {
                addView(TextView(context).apply {
                    setText(R.string.skin_centenary_note)
                    textSize = 11f
                    setTextColor(ui.p.inkSecondary)
                    gravity = Gravity.CENTER
                    maxLines = 2
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            }
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

        /** A strip of tape on the corner of the chosen card (the one place tape is used). */
        private val tape = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ui.p.tapeA
            alpha = (PaperTokens.TAPE_ALPHA * 255).roundToInt()
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (!isSelected) return
            canvas.save()
            canvas.rotate(8f, width - dp(14).toFloat(), dp(2).toFloat())
            canvas.drawRect(width - dp(30).toFloat(), -dp(3).toFloat(), width.toFloat(), dp(8).toFloat(), tape)
            canvas.restore()
        }

        fun render(active: Boolean, locked: Boolean) {
            isSelected = active
            translationY = if (active) -dp(3).toFloat() else 0f
            background = GradientDrawable().apply {
                cornerRadius = dp(ui.skin.corner(12)).toFloat()
                setColor(if (active) activeFill else fill)
                setStroke(dp(ui.skin.lineDp), if (active) accent else outline)
            }
            label.setTextColor(ink)
            contentDescription = when {
                locked -> context.getString(R.string.skin_unlock_title, option.title)
                option.skin == KeyboardSkin.CENTENARY -> "${option.title}。${context.getString(R.string.skin_centenary_note)}"
                else -> option.title
            }
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
    private val centenary = if (skin == KeyboardSkin.CENTENARY) Look(dark, GlassShape.CAPSULE, Palette.AMBER,
        GlassStrength.BALANCED, true, skin = skin) else null
    private val deck = centenary?.keyboardTop ?: when (skin.catArtwork) {
        SkinCatArtwork.SCULPTED, SkinCatArtwork.CENTENARY -> if (dark) 0xff42343e.toInt() else 0xffe8dadd.toInt()
        SkinCatArtwork.LEGACY -> if (dark) 0xff354350.toInt() else 0xffe1e7ef.toInt()
        SkinCatArtwork.PIXEL -> if (dark) 0xff342c4b.toInt() else 0xffe8dff0.toInt()
    }
    private val rim = centenary?.rim ?: if (pixel) 0xff827196.toInt() else if (dark) 0xffaaadb8.toInt() else Color.WHITE
    private val key = centenary?.keyFaceTop ?: if (dark) 0xff857a8a.toInt() else 0xfffffbfa.toInt()
    private val statusInk = centenary?.tint ?: if (dark) 0xffe5d5e1.toInt() else 0xff685062.toInt()
    private val catImages = when (skin.catArtwork) {
        SkinCatArtwork.SCULPTED -> R.drawable.cat_mimimo_edge_peek to R.drawable.cat_momo_edge_peek
        SkinCatArtwork.LEGACY -> R.drawable.cat_classic_mimimo_open to R.drawable.cat_classic_momo_open
        SkinCatArtwork.PIXEL -> R.drawable.cat_pixel_mimimo to R.drawable.cat_pixel_momo
        SkinCatArtwork.CENTENARY -> R.drawable.centenary_mimimo_idle to R.drawable.centenary_momo_idle
    }.let { (left, right) ->
        val options = BitmapFactory.Options().apply { inSampleSize = if (pixel) 1 else 2 }
        BitmapFactory.decodeResource(resources, left, options) to BitmapFactory.decodeResource(resources, right, options)
    }
    private val commemorativeArt = if (skin == KeyboardSkin.CENTENARY) BitmapFactory.decodeResource(resources,
        R.drawable.centenary_duo, BitmapFactory.Options().apply { inSampleSize = 4 }) else null
    private val glass = LinearGradient(0f, 17f, 0f, 27f, rim, deck, Shader.TileMode.CLAMP)
    private val centenaryBarFill = if (dark) 0xFF212E40.toInt() else 0xFFFFFBF2.toInt()
    private val centenaryFoil = if (dark) 0xFFDFBD7A.toInt() else 0xFFA67C3D.toInt()
    private val isDark = dark
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

    /** 字景: a few blocks of land, a red sun, the two cats between rows of hand-cut letters, all on paper. */
    private fun drawGlyphSwatch(canvas: Canvas, scale: Float) {
        val c = GlyphColors.of(isDark)
        paint.style = Paint.Style.FILL
        box(canvas, 0f, 0f, width.toFloat(), height.toFloat(), 5f * scale, c.paper)
        canvas.save()
        canvas.translate((width - 88f * scale) / 2, (height - 48f * scale) / 2)
        canvas.scale(scale, scale)
        paint.isFilterBitmap = false
        paint.color = c.red
        for (y in 4..16 step 2) for (x in 56..74 step 2) {
            val dx = x + 1 - 65f
            val dy = y + 1 - 10f
            if (dx * dx + dy * dy <= 42f) canvas.drawRect(x.toFloat(), y.toFloat(), x + 2f, y + 2f, paint)
        }
        paint.color = c.ink
        for (i in 0 until 44) {                           // a ridge of blocks, higher at the left
            val h = 3 + ((i * 7) % 5) + (if (i < 18) 3 else 0)
            canvas.drawRect(i * 2f, 28f - h, i * 2f + 2f, 28f - h + 2f, paint)
            if (i % 3 == 0) canvas.drawRect(i * 2f, 30f - h, i * 2f + 2f, 31f - h, paint)
        }
        for ((n, cat) in listOf(CatId.MOMO to 26f, CatId.MIMI to 46f).withIndex()) {
            val r = GlyphCats.raster(cat.first, "sit", "neutral", isDark, false, c)
            for (py in 0 until r.h) for (px in 0 until r.w) {
                val color = r.pixels[py * r.w + px]
                if (color ushr 24 == 0) continue
                paint.color = color
                canvas.drawRect(cat.second + px - 2f, 12f + py - 2f + n * 0f, cat.second + px - 1f, 13f + py - 2f, paint)
            }
        }
        paint.color = c.ink
        for ((r, letters) in listOf("qwertyui", "asdfghjk").withIndex()) for ((i, ch) in letters.withIndex()) {
            val rows = GlyphKeyArt.bits(ch.toString()) ?: continue
            for (py in rows.indices) for (px in rows[py].indices) if (rows[py][px] == 'X') {
                val x = 8f + i * 9.5f + px * 0.7f
                val y = 31f + r * 8.5f + py * 0.7f
                canvas.drawRect(x, y, x + 0.7f, y + 0.7f, paint)
            }
        }
        paint.color = c.red
        canvas.drawRect(70f, 41f, 83f, 46f, paint)
        if (locked || active) drawStatus(canvas)
        canvas.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 88f, height / 48f)
        if (skin == KeyboardSkin.GLYPH) {
            drawGlyphSwatch(canvas, scale)
            return
        }
        box(canvas, 0f, 0f, width.toFloat(), height.toFloat(), (if (pixel) 5f else 9f) * scale, deck)
        canvas.save()
        canvas.translate((width - 88f * scale) / 2, (height - 48f * scale) / 2)
        canvas.scale(scale, scale)
        paint.isFilterBitmap = !pixel
        if (commemorativeArt != null) {
            val aspect = commemorativeArt.width.toFloat() / commemorativeArt.height.coerceAtLeast(1)
            val maxW = 34f
            val maxH = 19.5f
            val w = minOf(maxW, maxH * aspect)
            val h = w / aspect
            rect.set(44f - w / 2f, 20.5f - h, 44f + w / 2f, 20.5f)
            canvas.drawBitmap(commemorativeArt, null, rect, paint)
        } else {
            cat(canvas, catImages.first, 5f, 2f)
            cat(canvas, catImages.second, 62f, 2f)
        }
        val barRadius = if (pixel) 1f else 5f
        if (centenary != null) {
            box(canvas, 5f, 17f, 83f, 27f, barRadius, centenaryBarFill)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 0.7f
            paint.color = Look.withAlpha(centenaryFoil, 0.7f)
            rect.set(5f, 17f, 83f, 27f)
            canvas.drawRoundRect(rect, barRadius, barRadius, paint)
            paint.style = Paint.Style.FILL
        } else {
            if (!pixel) paint.shader = glass
            box(canvas, 5f, 17f, 83f, 27f, barRadius, rim)
            paint.shader = null
            if (pixel) box(canvas, 6f, 18f, 82f, 26f, barRadius, deck)
        }
        box(canvas, 33f, 20f, 54f, 24f, if (pixel) 0f else 2f, statusInk)
        for (row in 0 until 2) {
            for (column in 0 until 5) {
                val x = 9f + column * 14.5f
                val y = 30f + row * 7f
                box(canvas, x, y, x + 11.5f, y + 5f, if (pixel) 0f else 2f, key)
                if (centenary != null) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.45f
                    paint.color = Look.withAlpha(centenaryFoil, if (isDark) 0.45f else 0.40f)
                    rect.set(x, y, x + 11.5f, y + 5f)
                    canvas.drawRoundRect(rect, 2f, 2f, paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
        if (locked || active) {
            canvas.save()
            if (centenary != null) canvas.translate(29f, -1f)
            drawStatus(canvas)
            canvas.restore()
        }
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

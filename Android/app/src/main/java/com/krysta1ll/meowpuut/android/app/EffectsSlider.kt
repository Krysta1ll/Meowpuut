package com.krysta1ll.meowpuut.android.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import android.widget.SeekBar
import com.krysta1ll.meowpuut.android.ime.wantsMoreContrast
import kotlin.math.roundToInt

/** A native four-stop SeekBar with a painted track and thumb. */
@SuppressLint("ViewConstructor")
internal class EffectsSlider(context: Context, private val dark: Boolean) : SeekBar(context) {
    private data class Artwork(val colors: IntArray, val dayInk: Int, val nightInk: Int, val glints: Int)

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private val fill = RectF()
    private val visible = Rect()
    private val trackHalf = 13.5f * density
    private val thumbRadius = 18f * density
    private val contrast = wantsMoreContrast(context)
    private val accessibility = context.getSystemService(AccessibilityManager::class.java)
    private var tier = 0
    private var gradient: LinearGradient? = null
    private var animator: ValueAnimator? = null
    private var shine = 0f

    init {
        // Match the visual track to the platform's position and touch mapping.
        layoutDirection = LAYOUT_DIRECTION_LTR
        val inset = (19f * density).roundToInt()
        setPadding(inset, 0, inset, 0)
        minimumHeight = (52f * density).roundToInt()
        background = null
    }

    fun updateArtwork(position: Int, animate: Boolean): Int {
        if (tier != position) {
            tier = position
            updateGradient()
            stopShine()
            if (animate && tier > 0 && !contrast && accessibility?.isTouchExplorationEnabled != true &&
                ValueAnimator.areAnimatorsEnabled() && isShown && getLocalVisibleRect(visible)) {
                animator = ValueAnimator.ofFloat(1f, 0f).apply {
                    duration = 520
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        shine = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            invalidate()
        }
        val artwork = palettes[tier]
        return if (dark) artwork.nightInk else artwork.dayInk
    }

    private fun updateGradient() {
        gradient = if (width > 0) {
            LinearGradient(0f, 0f, width.toFloat(), 0f, palettes[tier].colors, null, Shader.TileMode.CLAMP)
        } else null
    }

    private fun stopShine() {
        animator?.cancel()
        animator = null
        shine = 0f
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) stopShine()
    }

    override fun onDetachedFromWindow() {
        stopShine()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGradient()
    }

    override fun onDraw(canvas: Canvas) {
        val y = height / 2f
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right <= left) return
        val x = left + (right - left) * progress / max.coerceAtLeast(1)
        val extra = 7f * density
        track.set(left - extra, y - trackHalf, right + extra, y + trackHalf)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = if (dark) 0xff4a414c.toInt() else 0xffe8dfe9.toInt()
        canvas.drawRoundRect(track, trackHalf, trackHalf, paint)
        if (progress > 0) {
            fill.set(track.left, track.top, x, track.bottom)
            paint.shader = if (contrast) null else gradient
            paint.color = palettes[tier].colors.last()
            canvas.drawRoundRect(fill, trackHalf, trackHalf, paint)
            paint.shader = null
            if (!contrast) drawGlints(canvas, y)
        }

        paint.color = if (dark) 0x28000000 else 0x260b0920
        canvas.drawCircle(x, y + 1f * density, thumbRadius + 0.5f * density, paint)
        paint.color = 0xfffdfaff.toInt()
        canvas.drawCircle(x, y, thumbRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (isFocused) 2f * density else density
        paint.color = if (isFocused) palettes[tier].colors.last() else 0xffe3dce8.toInt()
        canvas.drawCircle(x, y, thumbRadius - paint.strokeWidth / 2, paint)
    }

    private fun drawGlints(canvas: Canvas, y: Float) {
        val count = palettes[tier].glints
        for (index in 0 until count) {
            val fraction = (index + 1f) / (count + 1f)
            val alpha = (163 + 82 * shine - 20 * (index % 3)).roundToInt()
            paint.color = (alpha shl 24) or (Color.WHITE and 0x00ffffff)
            val offset = ((index * 5) % 9 - 4) * 1.25f * density
            val radius = (0.85f + (index % 3) * 0.28f) * density
            val x = fill.left + (fill.width() - 12f * density).coerceAtLeast(0f) * fraction + 6f * density
            paint.alpha = alpha / 4
            canvas.drawCircle(x, y + offset, radius * 2f, paint)
            paint.alpha = alpha
            canvas.drawCircle(x, y + offset, radius, paint)
        }
    }

    private companion object {
        val palettes = arrayOf(
            Artwork(intArrayOf(0xff26645f.toInt(), 0xff51948b.toInt()), 0xff2e6660.toInt(), 0xffa7d6ce.toInt(), 0),
            Artwork(intArrayOf(0xff25539f.toInt(), 0xff5982d4.toInt()), 0xff305e91.toInt(), 0xffabcbf2.toInt(), 4),
            Artwork(intArrayOf(0xff9c4472.toInt(), 0xffd37da5.toInt(), 0xff9763bd.toInt()), 0xff8e4268.toInt(), 0xffefb2cf.toInt(), 7),
            Artwork(intArrayOf(0xff1944d1.toInt(), 0xffa16aff.toInt(), 0xff5930ce.toInt()), 0xff6435a5.toInt(), 0xffd5b1ff.toInt(), 11),
        )
    }
}

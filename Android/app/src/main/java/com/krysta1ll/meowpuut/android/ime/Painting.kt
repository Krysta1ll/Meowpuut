package com.krysta1ll.meowpuut.android.ime

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.min

/** Feeds the shared outline math into an android.graphics.Path. */
class AndroidPathSink(private val path: Path) : PathSink {
    private val oval = RectF()
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun arc(cx: Float, cy: Float, radius: Float, startDegrees: Float, sweepDegrees: Float) {
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        path.arcTo(oval, startDegrees, sweepDegrees, false)
    }
    override fun close() = path.close()
}

fun Path.outline(shape: GlassShape, box: Box, inset: Float = 0f): Path {
    reset()
    GlassOutline.trace(shape, box, inset, AndroidPathSink(this))
    return this
}

/**
 * The emulated glass (Android has no live backdrop blur for an input
 * window): frosted body carrying a little of the palette, inner glaze, a
 * bright band inside the rim, iridescent caustic along the lower rim, inner
 * highlight, specular spot and a polished edge, with the caustic's light
 * showing through the lower half. Painted once into a bitmap per size and
 * look; canvases here are in dp.
 */
object GlassPainter {
    private val dstIn = PorterDuffXfermode(PorterDuff.Mode.DST_IN)

    /** How far below the glass its shadow reaches. */
    const val REACH = 20f

    fun shadow(canvas: Canvas, shape: GlassShape, glass: Box, dark: Boolean) {
        val path = Path().outline(shape, Box(glass.left + 6, glass.top + 5, glass.width - 12, glass.height))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (dark) {
            paint.color = Color.argb(128, 0, 0, 0)
            paint.maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        } else {
            paint.color = Color.argb(33, 61, 41, 69)
            paint.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
        }
        // Only around the glass: under a see-through body it would grey the glass.
        canvas.clipOutPath(Path().outline(shape, glass))
        canvas.drawPath(path, paint)
    }

    fun body(canvas: Canvas, shape: GlassShape, glass: Box, look: Look) {
        val dark = look.dark
        val path = Path().outline(shape, glass)
        val bounds = RectF(glass.left - 2, glass.top - 2, glass.right + 2, glass.bottom + 2)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        if (!look.opaque) {
            // Light that has passed through the glass: the caustic's colours,
            // soft, under the lower half of the body.
            val spill = Box(glass.left + 14, glass.top + 6, glass.width - 28, glass.height)
            fill.shader = LinearGradient(spill.left, 0f, spill.right, 0f, look.causticColors, null, Shader.TileMode.CLAMP)
            fill.alpha = ((if (dark) 0.20f else 0.30f) * 255).toInt()
            fill.maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
            masked(canvas, RectF(glass.left, glass.top, glass.right, glass.bottom), LinearGradient(0f, glass.centerY, 0f, glass.bottom,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)) {
                it.drawPath(Path().outline(shape, spill), fill)
            }
            fill.shader = null
            fill.maskFilter = null
        }
        fill.color = look.glassFill
        canvas.drawPath(path, fill)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        if (look.opaque) {
            // Solid for more contrast: only the edge is kept.
            edge(canvas, path, glass, look, stroke)
            return
        }
        fill.color = Look.withAlpha(look.tint, 0.07f)
        canvas.drawPath(path, fill)

        // Inner glaze, brighter at the top.
        fill.color = Color.WHITE
        fill.shader = LinearGradient(0f, glass.top, 0f, glass.bottom,
            Look.withAlpha(Color.WHITE, if (dark) 0.09f else 0.22f), Look.withAlpha(Color.WHITE, if (dark) 0f else 0.04f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        fill.shader = null

        // Thick glass bends light at its rim: a soft bright band just inside
        // the edge.
        canvas.save()
        canvas.clipPath(path)
        stroke.strokeWidth = 3.5f
        stroke.maskFilter = BlurMaskFilter(1.8f, BlurMaskFilter.Blur.NORMAL)
        stroke.color = Look.withAlpha(Color.WHITE, if (dark) 0.07f else 0.38f)
        canvas.drawPath(Path().outline(shape, glass, 3f), stroke)
        stroke.maskFilter = null
        canvas.restore()

        // Iridescent caustic along the lower rim, fading upward.
        masked(canvas, bounds, LinearGradient(0f, glass.top, 0f, glass.bottom,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)) {
            stroke.strokeWidth = 2.4f
            stroke.shader = LinearGradient(glass.left, 0f, glass.right, 0f, look.causticColors, null, Shader.TileMode.CLAMP)
            stroke.alpha = ((if (dark) 0.6f else 0.8f) * 255).toInt()
            it.drawPath(Path().outline(shape, glass, 2f), stroke)
            stroke.alpha = 255
        }

        // Light along the top inside edge gives the glass its thickness.
        stroke.strokeWidth = 1.4f
        stroke.shader = LinearGradient(0f, glass.top, 0f, glass.bottom,
            intArrayOf(Look.withAlpha(Color.WHITE, if (dark) 0.24f else 0.9f), Look.withAlpha(Color.WHITE, 0f)),
            floatArrayOf(0f, 0.45f), Shader.TileMode.CLAMP)
        canvas.drawPath(Path().outline(shape, glass, 1.2f), stroke)

        // Specular spot on the upper left.
        masked(canvas, bounds, RadialGradient(glass.left + 0.12f * glass.width, glass.top, 70f,
            Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)) {
            stroke.strokeWidth = 2f
            stroke.shader = null
            stroke.color = Look.withAlpha(Color.WHITE, if (dark) 0.4f else 0.9f)
            it.drawPath(Path().outline(shape, glass, 1.5f), stroke)
        }

        edge(canvas, path, glass, look, stroke)

        // The glass is thick: a soft shade inside its lower edge (light
        // only) and a faint darker line under the rim.
        masked(canvas, bounds, LinearGradient(0f, glass.top, 0f, glass.bottom,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)) {
            stroke.shader = null
            if (!dark) {
                it.save()
                it.clipPath(path)
                stroke.strokeWidth = 5f
                stroke.maskFilter = BlurMaskFilter(2.5f, BlurMaskFilter.Blur.NORMAL)
                stroke.color = Look.withAlpha(Color.rgb(77, 56, 87), 0.07f)
                it.drawPath(Path().outline(shape, glass, 3f), stroke)
                stroke.maskFilter = null
                it.restore()
            }
            stroke.strokeWidth = 0.5f
            stroke.color = Look.withAlpha(Color.BLACK, if (dark) 0.35f else 0.08f)
            it.save()
            it.translate(0f, 0.75f)
            it.drawPath(path, stroke)
            it.restore()
        }
    }

    /** The polished 1.5 dp edge: brightest at the upper left, bright again at the lower right. */
    private fun edge(canvas: Canvas, path: Path, glass: Box, look: Look, stroke: Paint) {
        val dark = look.dark
        stroke.strokeWidth = 1.5f
        stroke.shader = LinearGradient(glass.left, glass.top, glass.right, glass.bottom,
            intArrayOf(Look.withAlpha(look.rim, if (dark) 0.95f else 1f), Look.withAlpha(look.rim, if (dark) 0.16f else 0.4f),
                Look.withAlpha(look.rim, if (dark) 0.5f else 0.8f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, stroke)
        stroke.shader = null
    }

    private inline fun masked(canvas: Canvas, bounds: RectF, mask: Shader, draw: (Canvas) -> Unit) {
        val layer = canvas.saveLayer(bounds, null)
        draw(canvas)
        val paint = Paint().apply { shader = mask; xfermode = dstIn }
        canvas.drawRect(bounds, paint)
        canvas.restoreToCount(layer)
    }
}

/** The selected candidate's capsule, a bead of tinted glass (DESIGN.md §3–4). */
class PillPainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.8f }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f
        maskFilter = BlurMaskFilter(0.8f, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()
    private val clip = Path()
    private var cachedKey = 0L
    private var cachedShader: Shader? = null
    private var cachedLine: Shader? = null
    private var cachedGlow: Shader? = null

    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, colors: Pair<Int, Int>, dark: Boolean, shadow: Boolean) {
        rect.set(left, top, left + width, top + height)
        val radius = height / 2
        val key = (colors.first.toLong() shl 32) xor colors.second.toLong() xor (height.toBits().toLong() shl 7) xor top.toBits().toLong() xor
            (if (dark) 1L shl 62 else 0L)
        if (key != cachedKey) {
            cachedKey = key
            val middle = top + height / 2
            cachedShader = LinearGradient(0f, top, 0f, top + height, colors.first, colors.second, Shader.TileMode.CLAMP)
            cachedLine = LinearGradient(0f, top, 0f, middle,
                Look.withAlpha(Color.WHITE, if (dark) 0.34f else 0.55f), Look.withAlpha(Color.WHITE, 0f), Shader.TileMode.CLAMP)
            cachedGlow = LinearGradient(0f, middle, 0f, top + height,
                Look.withAlpha(Color.WHITE, 0f), Look.withAlpha(Color.WHITE, if (dark) 0.16f else 0.26f), Shader.TileMode.CLAMP)
        }
        fill.shader = cachedShader
        // The shadow is the pill's own colour, not grey.
        if (shadow && android.os.Build.VERSION.SDK_INT >= 28) {
            fill.setShadowLayer(3.5f, 0f, 1.5f, Look.withAlpha(colors.second, if (dark) 0.30f else 0.38f))
        } else {
            fill.clearShadowLayer()
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        clip.rewind()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        // Light on the upper edge.
        line.shader = cachedLine
        rect.inset(0.5f, 0.5f)
        canvas.drawRoundRect(rect, radius - 0.5f, radius - 0.5f, line)
        // The light that crossed the bead gathers along its lower edge.
        glow.shader = cachedGlow
        rect.inset(0.7f, 0.7f)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRoundRect(rect, radius - 1.2f, radius - 1.2f, glow)
        canvas.restore()
    }
}

/** Small vector glyphs drawn in dp around a centre point. */
object Glyphs {
    private val path = Path()
    private val oval = RectF()

    /** A paw: one pad and four toe beans, tilted −20° (the Japanese mark). */
    fun paw(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        canvas.save()
        canvas.rotate(-20f, cx, cy)
        val s = size / 10f
        paint.style = Paint.Style.FILL
        oval.set(cx - 3.1f * s, cy - 0.2f * s, cx + 3.1f * s, cy + 4.6f * s)
        canvas.drawOval(oval, paint)
        val toes = floatArrayOf(-3.9f, -1.3f, -1.5f, -3.6f, 1.5f, -3.6f, 3.9f, -1.3f)
        for (i in toes.indices step 2) {
            oval.set(cx + (toes[i] - 1.25f) * s, cy + (toes[i + 1] - 1.6f) * s, cx + (toes[i] + 1.25f) * s, cy + (toes[i + 1] + 1.6f) * s)
            canvas.drawOval(oval, paint)
        }
        canvas.restore()
    }

    fun chevron(canvas: Canvas, cx: Float, cy: Float, width: Float, up: Boolean, paint: Paint) {
        val h = width * 0.5f
        val dir = if (up) -1 else 1
        path.reset()
        path.moveTo(cx - width / 2, cy - dir * h / 2)
        path.lineTo(cx, cy + dir * h / 2)
        path.lineTo(cx + width / 2, cy - dir * h / 2)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
    }

    fun shift(canvas: Canvas, cx: Float, cy: Float, size: Float, filled: Boolean, lock: Boolean, paint: Paint) {
        val w = size * 0.92f
        val head = size * 0.5f
        val stem = w * 0.42f
        val top = cy - size * 0.55f
        val bottom = cy + size * (if (lock) 0.18f else 0.42f)
        path.reset()
        path.moveTo(cx, top)
        path.lineTo(cx + w / 2, top + head)
        path.lineTo(cx + stem / 2, top + head)
        path.lineTo(cx + stem / 2, bottom)
        path.lineTo(cx - stem / 2, bottom)
        path.lineTo(cx - stem / 2, top + head)
        path.lineTo(cx - w / 2, top + head)
        path.close()
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = size * 0.085f
        paint.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        canvas.drawPath(path, paint)
        if (lock) {
            paint.style = Paint.Style.FILL_AND_STROKE
            oval.set(cx - stem / 2, cy + size * 0.32f, cx + stem / 2, cy + size * 0.46f)
            canvas.drawRect(oval, paint)
        }
    }

    fun delete(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val w = size * 1.18f
        val h = size * 0.82f
        val left = cx - w / 2
        val point = left + h * 0.42f
        path.reset()
        path.moveTo(left, cy)
        path.lineTo(point, cy - h / 2)
        path.lineTo(cx + w / 2, cy - h / 2)
        path.lineTo(cx + w / 2, cy + h / 2)
        path.lineTo(point, cy + h / 2)
        path.close()
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = size * 0.085f
        canvas.drawPath(path, paint)
        val xc = (point + cx + w / 2) / 2 + size * 0.02f
        val d = h * 0.2f
        canvas.drawLine(xc - d, cy - d, xc + d, cy + d, paint)
        canvas.drawLine(xc - d, cy + d, xc + d, cy - d, paint)
    }

    fun globe(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val r = size * 0.5f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.075f
        canvas.drawCircle(cx, cy, r, paint)
        oval.set(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r)
        canvas.drawOval(oval, paint)
        canvas.drawLine(cx - r, cy, cx + r, cy, paint)
        canvas.drawLine(cx, cy - r, cx, cy + r, paint)
    }

    fun heart(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val s = size / 2
        path.reset()
        path.moveTo(cx, cy + s * 0.85f)
        path.cubicTo(cx - s * 1.25f, cy - s * 0.05f, cx - s * 0.75f, cy - s * 1.05f, cx, cy - s * 0.42f)
        path.cubicTo(cx + s * 0.75f, cy - s * 1.05f, cx + s * 1.25f, cy - s * 0.05f, cx, cy + s * 0.85f)
        path.close()
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
    }

    fun sparkle(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val s = size / 2
        val k = s * 0.22f
        path.reset()
        path.moveTo(cx, cy - s)
        path.quadTo(cx + k, cy - k, cx + s, cy)
        path.quadTo(cx + k, cy + k, cx, cy + s)
        path.quadTo(cx - k, cy + k, cx - s, cy)
        path.quadTo(cx - k, cy - k, cx, cy - s)
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
    }

    fun note(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        val s = size / 2
        paint.style = Paint.Style.FILL
        oval.set(cx - s * 0.9f, cy + s * 0.25f, cx - s * 0.05f, cy + s * 0.9f)
        canvas.drawOval(oval, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, s * 0.22f)
        canvas.drawLine(cx - s * 0.1f, cy + s * 0.55f, cx - s * 0.1f, cy - s * 0.9f, paint)
        canvas.drawLine(cx - s * 0.1f, cy - s * 0.9f, cx + s * 0.7f, cy - s * min(0.5f, 0.6f), paint)
    }
}

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

/** Two hard steps per corner, a fine top glint and a two-pixel lower ledge. */
internal fun drawPixelPanel(canvas: Canvas, bounds: RectF, fill: Int, edge: Int, paint: Paint) {
    val left = bounds.left
    val top = bounds.top
    val right = bounds.right
    val bottom = bounds.bottom
    val antialias = paint.isAntiAlias
    paint.isAntiAlias = false
    paint.color = edge
    val step = min(1.5f, min(bounds.width(), bounds.height()) / 8f)
    val shoulder = step * 2
    canvas.drawRect(left + shoulder, top, right - shoulder, bottom, paint)
    canvas.drawRect(left + step, top + step, right - step, top + shoulder, paint)
    canvas.drawRect(left, top + shoulder, right, bottom - shoulder, paint)
    canvas.drawRect(left + step, bottom - shoulder, right - step, bottom - step, paint)
    paint.color = fill
    canvas.drawRect(left + shoulder, top + 1f, right - shoulder, bottom - 2f, paint)
    canvas.drawRect(left + 1f, top + shoulder, right - 1f, bottom - shoulder - 1f, paint)
    paint.color = Look.lighten(fill, 0.30f)
    canvas.drawRect(left + shoulder, top + 1f, right - shoulder, top + 2f, paint)
    paint.isAntiAlias = antialias
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
        val full = look.effects.hasFullEffects
        if (full && !look.opaque) {
            // Light that has passed through the glass: the caustic's colours,
            // soft, under the lower half of the body.
            val spill = Box(glass.left + 14, glass.top + 6, glass.width - 28, glass.height)
            fill.shader = LinearGradient(spill.left, 0f, spill.right, 0f, look.causticColors, null, Shader.TileMode.CLAMP)
            fill.alpha = ((if (dark) 0.08f else 0.13f) * 255).toInt()
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
        if (look.opaque || look.effects == EffectsLevel.LIGHTWEIGHT) {
            stroke.strokeWidth = 0.9f
            stroke.color = Look.withAlpha(look.rim, if (dark) 0.44f else 0.76f)
            canvas.drawPath(path, stroke)
            return
        }
        fill.color = Look.withAlpha(look.tint, 0.045f)
        canvas.drawPath(path, fill)

        // Inner glaze, brighter at the top.
        fill.color = Color.WHITE
        fill.shader = LinearGradient(0f, glass.top, 0f, glass.bottom, intArrayOf(
            Look.withAlpha(Color.WHITE, if (dark) 0.09f else 0.32f),
            Look.withAlpha(Color.WHITE, if (dark) 0.025f else 0.08f),
            Look.withAlpha(look.tint, if (dark) 0.06f else 0.04f),
        ), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        fill.shader = null

        if (!full) {
            stroke.strokeWidth = 0.9f
            stroke.shader = LinearGradient(0f, glass.top, 0f, glass.bottom, intArrayOf(
                Look.withAlpha(Color.WHITE, if (dark) 0.24f else 0.65f),
                Look.withAlpha(Color.WHITE, 0f),
                Look.withAlpha(look.tint, if (dark) 0.14f else 0.11f),
            ), floatArrayOf(0f, 0.46f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(Path().outline(shape, glass, 1.3f), stroke)
            edge(canvas, path, glass, look, stroke)
            return
        }

        // Thick glass bends light at its rim: a soft bright band just inside
        // the edge.
        canvas.save()
        canvas.clipPath(path)
        stroke.strokeWidth = 2.7f
        stroke.maskFilter = BlurMaskFilter(1.8f, BlurMaskFilter.Blur.NORMAL)
        stroke.color = Look.withAlpha(Color.WHITE, if (dark) 0.05f else 0.28f)
        canvas.drawPath(Path().outline(shape, glass, 3f), stroke)
        stroke.maskFilter = null
        canvas.restore()
        stroke.alpha = 255

        // Reflected colour gathers at the ends of the lower rim.
        val caustic = look.causticColors
        for (i in caustic.indices) {
            val alpha = when (i) {
                0 -> if (dark) 0.25f else 0.36f
                caustic.lastIndex -> if (dark) 0.41f else 0.52f
                else -> if (dark) 0.10f else 0.17f
            }
            caustic[i] = Look.withAlpha(caustic[i], alpha)
        }
        masked(canvas, bounds, LinearGradient(0f, glass.top, 0f, glass.bottom,
            intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.BLACK), floatArrayOf(0f, 0.53f, 1f), Shader.TileMode.CLAMP)) {
            stroke.strokeWidth = 1.25f
            stroke.shader = LinearGradient(glass.left, 0f, glass.right, 0f, caustic, null, Shader.TileMode.CLAMP)
            it.drawPath(Path().outline(shape, glass, 1.65f), stroke)
        }

        // Light along the top inside edge gives the glass its thickness.
        stroke.strokeWidth = 1.0f
        stroke.shader = LinearGradient(0f, glass.top, 0f, glass.bottom,
            intArrayOf(Look.withAlpha(Color.WHITE, if (dark) 0.18f else 0.62f), Look.withAlpha(Color.WHITE, 0f)),
            floatArrayOf(0f, 0.45f), Shader.TileMode.CLAMP)
        canvas.drawPath(Path().outline(shape, glass, 1.2f), stroke)

        // Specular spot on the upper left.
        masked(canvas, bounds, RadialGradient(glass.left + 0.12f * glass.width, glass.top, 70f,
            Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)) {
            stroke.strokeWidth = 1.25f
            stroke.shader = null
            stroke.color = Look.withAlpha(Color.WHITE, if (dark) 0.29f else 0.60f)
            it.drawPath(Path().outline(shape, glass, 1.5f), stroke)
        }

        edge(canvas, path, glass, look, stroke)
        if (look.effects == EffectsLevel.ULTRA) prism(canvas, shape, glass, look, stroke)

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

    /** A narrow outer edge, brightest at the upper left and lower right. */
    private fun edge(canvas: Canvas, path: Path, glass: Box, look: Look, stroke: Paint) {
        val dark = look.dark
        stroke.alpha = 255
        stroke.strokeWidth = if (look.effects.hasFullEffects) 1.05f else 0.9f
        stroke.shader = LinearGradient(glass.left, glass.top, glass.right, glass.bottom,
            intArrayOf(Look.withAlpha(look.rim, if (dark) 0.69f else 0.92f), Look.withAlpha(look.rim, if (dark) 0.13f else 0.32f),
                Look.withAlpha(look.rim, if (dark) 0.37f else 0.72f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(path, stroke)
        stroke.shader = null
    }

    /** A second bevel and two focused reflections, baked with the body. */
    private fun prism(canvas: Canvas, shape: GlassShape, glass: Box, look: Look, stroke: Paint) {
        val white = Look.withAlpha(Color.WHITE, if (look.dark) 0.47f else 0.82f)
        val tint = Look.withAlpha(look.tint, if (look.dark) 0.34f else 0.29f)
        val bevel = Path().outline(shape, glass, 2.35f)
        stroke.strokeWidth = 1.15f
        stroke.shader = LinearGradient(glass.left, glass.top, glass.right, glass.bottom,
            intArrayOf(white, tint, Color.TRANSPARENT, tint, white),
            floatArrayOf(0f, 0.20f, 0.49f, 0.81f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(bevel, stroke)

        // The contour stays outside the text band. The long, quiet centre
        // carries no flare, even on the brightest palette.
        val highlight = Path().outline(shape, glass, 0.7f)
        val radius = glass.height * 0.92f
        stroke.strokeWidth = 1.7f
        stroke.shader = RadialGradient(glass.left + glass.height * 0.44f, glass.top + 0.4f,
            radius, white, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(highlight, stroke)
        stroke.shader = RadialGradient(glass.right - glass.height * 0.44f, glass.bottom - 0.4f,
            radius, white, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(highlight, stroke)
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

    fun draw(canvas: Canvas, left: Float, top: Float, width: Float, height: Float, look: Look, shadow: Boolean) {
        rect.set(left, top, left + width, top + height)
        if (look.usesSteppedMaterial) {
            fill.shader = null
            fill.clearShadowLayer()
            drawPixelPanel(canvas, rect, look.pixelAccent, look.pixelEdge, fill)
            return
        }
        val radius = height / 2
        val colors = look.selectedPill
        val dark = look.dark
        if (look.effects == EffectsLevel.LIGHTWEIGHT || look.opaque) {
            fill.shader = null
            fill.clearShadowLayer()
            fill.color = Look.darken(colors.second, if (dark) 0f else 0.08f)
            canvas.drawRoundRect(rect, radius, radius, fill)
            return
        }
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
        val full = look.effects.hasFullEffects
        if (full) {
            clip.rewind()
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        // Light on the upper edge.
        line.shader = cachedLine
        rect.inset(0.5f, 0.5f)
        canvas.drawRoundRect(rect, radius - 0.5f, radius - 0.5f, line)
        if (!full) return
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
    private val pawToes = floatArrayOf(-3.9f, -1.3f, -1.5f, -3.6f, 1.5f, -3.6f, 3.9f, -1.3f)

    /** A paw: one pad and four toe beans, tilted −20° (the Japanese mark). */
    fun paw(canvas: Canvas, cx: Float, cy: Float, size: Float, paint: Paint) {
        canvas.save()
        canvas.rotate(-20f, cx, cy)
        val s = size / 10f
        paint.style = Paint.Style.FILL
        oval.set(cx - 3.1f * s, cy - 0.2f * s, cx + 3.1f * s, cy + 4.6f * s)
        canvas.drawOval(oval, paint)
        for (i in pawToes.indices step 2) {
            oval.set(cx + (pawToes[i] - 1.25f) * s, cy + (pawToes[i + 1] - 1.6f) * s, cx + (pawToes[i] + 1.25f) * s, cy + (pawToes[i + 1] + 1.6f) * s)
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

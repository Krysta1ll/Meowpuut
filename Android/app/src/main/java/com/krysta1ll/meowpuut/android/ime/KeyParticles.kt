package com.krysta1ll.meowpuut.android.ime

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

/** Kind of keystroke particle driven declaratively by a custom skin manifest. */
enum class KeyParticleKind { PETAL, SPARKLE, SNOW }

/**
 * Zero-allocation Structure-of-Arrays (SoA) particle pool for keystroke effects.
 * Driven purely by generic [KeyParticleKind] and ARGB palette data from [SkinOverlay].
 */
internal class KeyParticlePool {
    private var kind: KeyParticleKind? = null
    private var colors: IntArray = IntArray(0)
    private var fullEffects: Boolean = true
    private var lastFrameMs: Long = 0L
    private var cursor: Int = 0
    private var rng: Long = 0x5EED_4D30_9A1B_C271L

    private val active = BooleanArray(CAPACITY)
    private val px = FloatArray(CAPACITY)
    private val py = FloatArray(CAPACITY)
    private val vx = FloatArray(CAPACITY)
    private val vy = FloatArray(CAPACITY)
    private val size = FloatArray(CAPACITY)
    private val angle = FloatArray(CAPACITY)
    private val spin = FloatArray(CAPACITY)
    private val age = FloatArray(CAPACITY)
    private val life = FloatArray(CAPACITY)
    private val phase = FloatArray(CAPACITY)
    private val colorIdx = IntArray(CAPACITY)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    val isRunning: Boolean
        get() {
            for (i in 0 until CAPACITY) {
                if (active[i]) return true
            }
            return false
        }

    fun activeCount(): Int {
        var count = 0
        for (i in 0 until CAPACITY) if (active[i]) count++
        return count
    }

    fun configure(kind: KeyParticleKind?, palette: IntArray, fullEffects: Boolean) {
        val samePalette = this.kind == kind && this.colors.contentEquals(palette)
        this.fullEffects = fullEffects
        if (samePalette && fullEffects) return
        if (!samePalette || !fullEffects) clear()
        this.kind = kind
        this.colors = palette
    }

    fun clear() {
        for (i in 0 until CAPACITY) active[i] = false
        lastFrameMs = 0L
    }

    fun spawn(x: Float, y: Float, density: Float) {
        val currentKind = kind ?: return
        if (!fullEffects || colors.isEmpty() || !x.isFinite() || !y.isFinite() || !density.isFinite() || density <= 0f) return
        val burst = 2
        for (n in 0 until burst) {
            val slot = cursor
            cursor = (cursor + 1) % CAPACITY
            active[slot] = true
            age[slot] = 0f
            phase[slot] = nextUnit() * 6.2831855f
            colorIdx[slot] = ((nextUnit() * colors.size).toInt()).coerceIn(0, colors.size - 1)
            px[slot] = x + (nextUnit() - 0.5f) * 14f * density
            py[slot] = y + (nextUnit() - 0.5f) * 6f * density
            when (currentKind) {
                KeyParticleKind.PETAL -> {
                    vx[slot] = (nextUnit() - 0.5f) * 44f * density
                    vy[slot] = -(14f + nextUnit() * 16f) * density
                    size[slot] = (3.4f + nextUnit() * 1.8f) * density
                    angle[slot] = nextUnit() * 360f
                    spin[slot] = (nextUnit() - 0.5f) * 170f
                    life[slot] = 0.88f + nextUnit() * 0.34f
                }
                KeyParticleKind.SPARKLE -> {
                    val theta = nextUnit() * 6.2831855f
                    val speed = (22f + nextUnit() * 28f) * density
                    vx[slot] = cos(theta) * speed
                    vy[slot] = sin(theta) * speed - 8f * density
                    size[slot] = (2.8f + nextUnit() * 2.0f) * density
                    angle[slot] = nextUnit() * 45f
                    spin[slot] = (nextUnit() - 0.5f) * 90f
                    life[slot] = 0.48f + nextUnit() * 0.22f
                }
                KeyParticleKind.SNOW -> {
                    vx[slot] = (nextUnit() - 0.5f) * 18f * density
                    vy[slot] = (14f + nextUnit() * 18f) * density
                    size[slot] = (2.6f + nextUnit() * 2.0f) * density
                    angle[slot] = 0f
                    spin[slot] = 0f
                    life[slot] = 0.80f + nextUnit() * 0.30f
                }
            }
        }
    }

    /** Advances simulation state in seconds without heap allocations. Returns true while active. */
    fun step(dt: Float, density: Float): Boolean {
        val currentKind = kind ?: return false
        if (!dt.isFinite() || dt <= 0f || !density.isFinite() || density <= 0f) return isRunning
        var any = false
        for (i in 0 until CAPACITY) {
            if (!active[i]) continue
            val nextAge = age[i] + dt
            if (nextAge >= life[i]) {
                active[i] = false
                continue
            }
            age[i] = nextAge
            any = true
            when (currentKind) {
                KeyParticleKind.PETAL -> {
                    vy[i] += 86f * density * dt
                    val flutter = sin((nextAge * 6.5f + phase[i]).toDouble()).toFloat() * 16f * density
                    px[i] += (vx[i] + flutter) * dt
                    py[i] += vy[i] * dt
                    angle[i] += spin[i] * dt
                }
                KeyParticleKind.SPARKLE -> {
                    vx[i] *= (1f - 1.8f * dt).coerceAtLeast(0.4f)
                    vy[i] *= (1f - 1.8f * dt).coerceAtLeast(0.4f)
                    px[i] += vx[i] * dt
                    py[i] += vy[i] * dt
                    angle[i] += spin[i] * dt
                }
                KeyParticleKind.SNOW -> {
                    val drift = sin((nextAge * 4.2f + phase[i]).toDouble()).toFloat() * 14f * density
                    px[i] += (vx[i] + drift) * dt
                    py[i] += vy[i] * dt
                }
            }
        }
        return any
    }

    fun stepAndDraw(canvas: Canvas, nowMs: Long, density: Float): Boolean {
        val currentKind = kind ?: return false
        if (!fullEffects || colors.isEmpty()) return false
        val dt = if (lastFrameMs == 0L) 1f / 60f else ((nowMs - lastFrameMs).coerceIn(1L, 50L) / 1000f)
        lastFrameMs = nowMs
        val alive = step(dt, density)
        for (i in 0 until CAPACITY) {
            if (!active[i]) continue
            val t = age[i] / life[i]
            val fade = when {
                t < 0.14f -> t / 0.14f
                t > 0.65f -> (1f - t) / 0.35f
                else -> 1f
            }.coerceIn(0f, 1f)
            val baseColor = colors[colorIdx[i] % colors.size]
            paint.color = baseColor
            paint.alpha = (Color.alpha(baseColor) * fade * 0.88f).toInt().coerceIn(0, 255)
            val s = size[i] * (1f - 0.18f * t)
            when (currentKind) {
                KeyParticleKind.PETAL -> drawPetal(canvas, px[i], py[i], s, angle[i])
                KeyParticleKind.SPARKLE -> drawSparkle(canvas, px[i], py[i], s, angle[i])
                KeyParticleKind.SNOW -> canvas.drawCircle(px[i], py[i], s * 0.62f, paint)
            }
        }
        if (!alive) lastFrameMs = 0L
        return alive
    }

    private fun drawPetal(canvas: Canvas, x: Float, y: Float, s: Float, deg: Float) {
        path.rewind()
        path.moveTo(-0.04f * s, s)
        path.cubicTo(-s * 0.62f, s * 0.48f, -s * 0.68f, -s * 0.58f, -s * 0.18f, -s)
        path.lineTo(0f, -s * 0.72f)
        path.lineTo(s * 0.22f, -s * 0.96f)
        path.cubicTo(s * 0.68f, -s * 0.54f, s * 0.62f, s * 0.48f, -0.04f * s, s)
        path.close()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(deg)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun drawSparkle(canvas: Canvas, x: Float, y: Float, s: Float, deg: Float) {
        val inner = s * 0.28f
        path.rewind()
        path.moveTo(0f, -s)
        path.lineTo(inner, -inner)
        path.lineTo(s, 0f)
        path.lineTo(inner, inner)
        path.lineTo(0f, s)
        path.lineTo(-inner, inner)
        path.lineTo(-s, 0f)
        path.lineTo(-inner, -inner)
        path.close()
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(deg)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun nextUnit(): Float {
        var x = rng
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        rng = x
        return ((x ushr 40) and 0xFFFFFFL) / 16777216f
    }

    private companion object {
        const val CAPACITY = 44
    }
}

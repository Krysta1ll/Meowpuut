package com.krysta1ll.meowpuut.android.ime

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The candidate glass outline (DESIGN.md §2). Dark is an appearance, not a shape. */
enum class GlassShape(val key: String) {
    CAPSULE("capsule"), WAVE("wave"), CLOUD("cloud");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: CAPSULE
    }
}

/** Receives outline segments; lets tests sample the outline without android.graphics. */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    /** Arc around (cx, cy); angles in degrees, positive sweep is clockwise on screen. */
    fun arc(cx: Float, cy: Float, radius: Float, startDegrees: Float, sweepDegrees: Float)
    fun close()
}

/** The same outlines as Sources/GlassShapes.swift, point for point. */
object GlassOutline {
    private const val WAVE_AMPLITUDE = 0.065f
    private const val CLOUD_BASE = 0.16f
    private const val CLOUD_SMOOTHING = 0.10f
    private val CLOUD_BUMPS = listOf(
        floatArrayOf(0.15f, 0.58f, 0f),
        floatArrayOf(0.47f, 0.75f, 0.05f),
        floatArrayOf(0.77f, 0.56f, 0.07f),
    )

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = min(1f, max(0f, (x - edge0) / (edge1 - edge0)))
        return t * t * (3 - 2 * t)
    }

    /** Polynomial smooth minimum; rounds the valleys between cloud bumps. */
    fun smoothMin(a: Float, b: Float, k: Float): Float {
        val h = max(k - abs(a - b), 0f) / k
        return min(a, b) - h * h * k / 4
    }

    /** The vertical band candidates sit in, relative to the outline's box. */
    fun contentBand(shape: GlassShape, height: Float): Pair<Float, Float> = when (shape) {
        GlassShape.CAPSULE -> 0f to height
        GlassShape.WAVE -> WAVE_AMPLITUDE * height to height * (1 - WAVE_AMPLITUDE)
        GlassShape.CLOUD -> CLOUD_BASE * height to height
    }

    /** About one wave per 170 dp of the outline's width, at least two. */
    fun waveCount(width: Float): Int = max(2, (width / 170f).roundToInt())

    /**
     * Vertical offset of both wave edges at [x]; zero at the round ends.
     * Inset strokes pass the outline's own [waves] and [amplitude] so they
     * follow the same curve.
     */
    fun waveOffset(
        x: Float,
        width: Float,
        height: Float,
        waves: Int = waveCount(width),
        amplitude: Float = WAVE_AMPLITUDE * height,
    ): Float {
        val radius = max(1f, height / 2 - amplitude)
        val straight = max(1f, width - 2 * radius)
        val u = (x - radius) / straight
        val envelope = smoothstep(0f, 0.12f, u) * smoothstep(0f, 0.12f, 1 - u)
        return (-amplitude * sin(2 * PI * waves * u)).toFloat() * envelope
    }

    private class Circle(val cx: Float, val r: Float, val cy: Float)

    private fun cloudCircles(width: Float, height: Float): List<Circle> {
        val base = CLOUD_BASE * height
        val radius = (height - base) / 2
        val smoothing = CLOUD_SMOOTHING * height
        return CLOUD_BUMPS.map { bump ->
            val r = bump[1] * height
            val cy = bump[2] * height + r
            // Keep each bump's reach inside the straight section.
            val reach = sqrt(max(0f, r * r - (cy - base) * (cy - base))) + smoothing + 2
            val lower = radius + reach
            val upper = width - radius - reach
            val cx = if (upper > lower) min(max(bump[0] * width, lower), upper) else width / 2
            Circle(cx, r, cy)
        }
    }

    /** Top edge of the cloud at [x] (relative to its box). */
    fun cloudTop(x: Float, width: Float, height: Float): Float = cloudTop(x, cloudCircles(width, height), height)

    private fun cloudTop(x: Float, circles: List<Circle>, height: Float): Float {
        var y = CLOUD_BASE * height
        for (c in circles) {
            val dx = x - c.cx
            if (abs(dx) >= c.r) continue
            y = smoothMin(y, c.cy - sqrt(c.r * c.r - dx * dx), CLOUD_SMOOTHING * height)
        }
        return y
    }

    /** Emits the outline of [box] inset by [inset] into [sink]. */
    fun trace(shape: GlassShape, box: Box, inset: Float, sink: PathSink) {
        val left = box.left + inset
        val top = box.top + inset
        val width = box.width - 2 * inset
        val height = box.height - 2 * inset
        if (width <= 2 || height <= 2) return
        when (shape) {
            GlassShape.CAPSULE -> {
                val r = height / 2
                sink.moveTo(left + r, top)
                sink.lineTo(left + width - r, top)
                sink.arc(left + width - r, top + r, r, -90f, 180f)
                sink.lineTo(left + r, top + height)
                sink.arc(left + r, top + r, r, 90f, 180f)
                sink.close()
            }
            GlassShape.WAVE -> {
                // Wave count and height come from the outer box, not the inset one.
                val waves = waveCount(box.width)
                val amplitude = WAVE_AMPLITUDE * box.height
                val radius = max(1f, height / 2 - amplitude)
                val straight = max(1f, width - 2 * radius)
                val steps = 64
                for (i in 0..steps) {
                    val x = radius + straight * i / steps
                    val y = top + amplitude + waveOffset(x, width, height, waves, amplitude)
                    if (i == 0) sink.moveTo(left + x, y) else sink.lineTo(left + x, y)
                }
                sink.arc(left + width - radius, top + height / 2, radius, -90f, 180f)
                for (i in steps downTo 0) {
                    val x = radius + straight * i / steps
                    sink.lineTo(left + x, top + height - amplitude + waveOffset(x, width, height, waves, amplitude))
                }
                sink.arc(left + radius, top + height / 2, radius, 90f, 180f)
                sink.close()
            }
            GlassShape.CLOUD -> {
                val base = CLOUD_BASE * height
                val radius = (height - base) / 2
                val centerY = base + radius
                val circles = cloudCircles(width, height)
                val straight = max(1f, width - 2 * radius)
                val steps = 96
                for (i in 0..steps) {
                    val x = radius + straight * i / steps
                    val y = top + cloudTop(x, circles, height)
                    if (i == 0) sink.moveTo(left + x, y) else sink.lineTo(left + x, y)
                }
                sink.arc(left + width - radius, top + centerY, radius, -90f, 180f)
                sink.lineTo(left + radius, top + height)
                sink.arc(left + radius, top + centerY, radius, 90f, 180f)
                sink.close()
            }
        }
    }
}

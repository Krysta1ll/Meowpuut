package com.krysta1ll.meowpuut.android.app.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.HandlerThread
import android.view.View
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCanvasSink
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelFonts
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPixelType
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools

/**
 * The page of the guide on a screen. One background thread owns the guide ([OnboardingState]) and draws it, twelve times a second
 * while anything changes, into a bitmap the view then shows: the main thread only swaps pictures, so typing on the line never waits
 * for the land. Everything said to the guide goes through [tell], which runs on that thread.
 */
internal class OnboardingView(context: Context) : View(context) {
    private val thread = HandlerThread("meowpuut-onboarding").apply { start() }
    private val worker = Handler(thread.looper)
    private val lock = Any()
    private val show = Paint().apply { isFilterBitmap = false }
    private var front: Bitmap? = null
    private var back: Bitmap? = null
    private var state: OnboardingState? = null
    private var painter: OnboardingPainter? = null
    private var sink: GlyphCanvasSink? = null
    private var running = false
    @Volatile private var released = false

    /** Device pixels to a point: the page is always 393 points wide. */
    val scale: Float get() = width / 393f

    class Setup(val copy: OnboardingCopy, val host: OnboardingHost, val topInset: Double, val bottomInset: Double, val dark: Boolean, val still: Boolean, val contrast: Boolean, val big: Boolean)

    /** Begins the guide. Call once, on the main thread, when the view has its size. */
    fun begin(setup: Setup) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val perPoint = scale
        worker.post {
            if (state != null || released) return@post
            val probe = Paint()
            val fonts = GlyphPixelFonts(context)
            val p = OnboardingPainter(GlyphPools { s -> probe.hasGlyph(s) }) { ch -> fonts.mask(ch, GlyphPixelType.EM, 500, false) }
            p.dark = setup.dark; p.contrast = setup.contrast
            val s = OnboardingState(setup.copy, setup.host, OnboardingGeo(h / perPoint.toDouble(), setup.topInset, setup.big, setup.bottomInset)) { ch, size -> p.adv(ch, size) }
            s.still = setup.still
            painter = p; state = s; sink = GlyphCanvasSink(perPoint, gather = true)
            synchronized(lock) { front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); back = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
            s.start()
            if (running) worker.post(tick)
        }
    }

    /** Something for the guide to know or do, on its own thread. */
    fun tell(what: (OnboardingState) -> Unit) { worker.post { state?.let(what) } }

    fun resume() { if (!running) { running = true; worker.removeCallbacks(tick); worker.post(tick) } }
    fun pause() { running = false; worker.removeCallbacks(tick) }

    private val tick = object : Runnable {
        override fun run() {
            if (!running || released) return
            val started = System.nanoTime()
            val s = state
            if (s != null && s.tick()) draw(s)
            worker.postDelayed(this, (TICK_MS - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(4))
        }
    }

    private fun draw(s: OnboardingState) {
        val into = back ?: return
        val g = sink ?: return
        g.begin(Canvas(into))
        painter?.draw(g, s)
        synchronized(lock) { back = front; front = into }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        synchronized(lock) { front?.let { canvas.drawBitmap(it, 0f, 0f, show) } }
    }

    fun release() {
        released = true; running = false
        worker.removeCallbacksAndMessages(null)
        worker.post { sink?.release(); state = null }
        thread.quitSafely()
    }

    private companion object { const val TICK_MS = 83L }
}

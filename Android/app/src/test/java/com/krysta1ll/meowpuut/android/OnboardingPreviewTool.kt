package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.onboarding.Hot
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingGeo
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingHost
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingPainter
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingState
import com.krysta1ll.meowpuut.android.app.onboarding.Scheme
import com.krysta1ll.meowpuut.android.app.onboarding.onboardingCopy
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphKeyArt
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphPools
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphSink
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** The guide's strings, read from the app's own resource files, so tests and stills use the words that ship. */
internal object OnboardingStrings {
    private val entry = Regex("<string name=\"onboarding_([a-z0-9_]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
    fun read(simplified: Boolean): Map<String, String> {
        val file = File("src/main/res/" + (if (simplified) "values-zh-rCN" else "values") + "/strings.xml")
        return entry.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2].replace("&amp;", "&").replace("&lt;", "<").replace("\\'", "'") }
    }
    fun copy(simplified: Boolean) = read(simplified).let { all -> onboardingCopy { key -> all.getValue(key) } }
}

/** A host that does nothing but remember what it was asked. */
internal class RecordingHost : OnboardingHost {
    val said = ArrayList<String>(); var described = ""; var hots: List<Hot> = emptyList(); var chosen: Scheme? = null
    var settings = 0; var picker = 0; var keyboardUp = false; var keyboardCalls = ArrayList<Boolean>(); var kept = false; var done = false; var left = false
    override fun openKeyboardSettings() { settings++ }
    override fun showKeyboardPicker() { picker++ }
    override fun keyboard(show: Boolean) { keyboardUp = show; keyboardCalls += show }
    override fun say(text: String) { said += text }
    override fun describe(text: String) { described = text }
    override fun hots(list: List<Hot>) { hots = list }
    override fun chose(scheme: Scheme) { chosen = scheme }
    override fun keepSkin() { kept = true }
    override fun done() { done = true }
    override fun leave() { left = true }
}

/**
 * A development tool, not a test: with ONBOARDING_PREVIEW_DIR set it draws stills of every act of the guide, light and dark, with the
 * shared pure code and the desktop's fonts (the land's type) and the app's own pixel face (the narration), to set beside the browser
 * prototype (design-preview/onboarding) before anything runs on a phone. Without the variable it does nothing.
 */
class OnboardingPreviewTool {
    @Test
    fun drawsEveryAct() {
        val dir = System.getenv("ONBOARDING_PREVIEW_DIR")
        assumeTrue(dir != null)
        val out = File(dir!!).apply { mkdirs() }
        val scale = 2
        val face = Font.createFont(Font.TRUETYPE_FONT, File("src/main/assets/fonts/pixel_a.ttf")).deriveFont(12f)
        val land = Font("PingFang TC", Font.PLAIN, 12)
        val pools = GlyphPools { s -> land.canDisplay(s[0]) }
        // One character of pixel type as the app cuts it: twelve pixels to the em, no smoothing, the glyph one pixel in from the left.
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().apply { font = face }
        fun glyph(ch: String): GlyphKeyArt.Mask {
            val w = probe.fontMetrics.stringWidth(ch) + 2
            val img = BufferedImage(w, 14, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
            g.font = face; g.color = Color.BLACK; g.drawString(ch, 1, 11); g.dispose()
            return GlyphKeyArt.Mask(w, 14, BooleanArray(w * 14) { (img.getRGB(it % w, it / w) ushr 24) > 100 })
        }
        val stills = listOf("01-blank" to (1 to 0), "01b-ground" to (1 to 1), "02-signpost" to (2 to 0), "02b-sunrise" to (2 to 1), "03-line-other-keyboard" to (3 to 0), "03b-eyes" to (3 to 1),
            "03c-mimimo" to (3 to 2), "03d-both-cats" to (3 to 3), "03e-other-keyboard-hint" to (3 to 8), "04-three-roads" to (4 to 0), "04b-chosen" to (4 to 2), "05-gate" to (5 to 0), "06-arrival" to (6 to 0), "06b-wearing" to (6 to 1))
        val only = System.getenv("ONBOARDING_ONLY")
        for (simplified in listOf(false, true)) for (dark in listOf(false, true)) for ((name, at) in stills) {
            if (simplified && (dark || at.first !in listOf(2, 4, 6))) continue
            if (only != null && !name.startsWith(only)) continue
            val h = (System.getenv("ONBOARDING_H") ?: "852").toDouble()
            val painter = OnboardingPainter(pools, ::glyph).apply { this.dark = dark; contrast = System.getenv("ONBOARDING_CONTRAST") != null }
            val state = OnboardingState(OnboardingStrings.copy(simplified), RecordingHost(), OnboardingGeo(h, 40.0, System.getenv("ONBOARDING_BIG") != null, 24.0)) { ch, size -> painter.adv(ch, size) }
            if (at.first == 3) state.visible(h - 330)
            state.stillOf(at.first, at.second)
            repeat(4) { state.tick() }
            val image = BufferedImage(393 * scale, (h * scale).toInt(), BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.scale(scale.toDouble(), scale.toDouble())
            val t0 = System.nanoTime()
            painter.draw(object : GlyphSink {
                override fun rect(x: Double, y: Double, w: Double, h: Double, color: Int) { g.color = Color(color, true); g.fill(java.awt.geom.Rectangle2D.Double(x, y, w, h)) }
                override fun text(ch: String, cx: Double, cy: Double, size: Int, mono: Boolean, color: Int) {
                    g.font = if (mono) Font(Font.MONOSPACED, Font.PLAIN, size) else land.deriveFont(size.toFloat()); g.color = Color(color, true)
                    val m = g.fontMetrics
                    g.drawString(ch, (cx - m.stringWidth(ch) / 2.0).toFloat(), (cy + (m.ascent - m.descent) / 2.0).toFloat())
                }
            }, state)
            val ms = (System.nanoTime() - t0) / 1_000_000
            g.dispose()
            val file = File(out, name + (if (simplified) "-hans" else "") + (if (dark) "-dark" else "-light") + ".png")
            ImageIO.write(image, "png", file)
            println("$file  ${ms} ms")
        }
    }
}

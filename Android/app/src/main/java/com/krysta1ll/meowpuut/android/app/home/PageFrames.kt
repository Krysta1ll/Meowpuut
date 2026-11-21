package com.krysta1ll.meowpuut.android.app.home

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import com.krysta1ll.meowpuut.android.app.PaperFonts
import com.krysta1ll.meowpuut.android.app.PaperUi
import com.krysta1ll.meowpuut.android.app.StatsFrame

/**
 * What surrounds 行旅's pictures: its title, its headings and notes, the way a picture is framed. The pictures themselves are always drawn in
 * 字景's way; this is the skin's part (AppSlots.statsFrame): 字景 sets every word in its own pixel type and lets the pictures stand on the
 * page, any other skin sets them in its own type and variables and hangs each picture in a frame of its own line and corner.
 */
internal interface PageFrame {
    fun title(text: String): View
    fun since(text: String): View
    fun bigNumber(text: String, description: String): View
    fun sub(text: String): View
    fun heading(text: String): View
    fun note(text: String): View
    fun footer(text: String): View
    fun placeName(text: String): View
    fun placeCount(text: String): View
    /** Whether the picture at the head of the page runs to the page's edges (the page is the picture) or is hung in the frame like the others. */
    val bleed: Boolean

    /** A picture as the frame shows it. [edge]: the picture is small and always has its edge (the places). */
    fun framed(picture: View, edge: Boolean = false): View

    companion object {
        fun of(context: Context, ui: PaperUi): PageFrame = when (ui.skin.slots.statsFrame) {
            StatsFrame.GLYPH -> GlyphPageFrame(context, ui)
            StatsFrame.SKIN -> SkinPageFrame(context, ui)
        }
    }
}

internal class GlyphPageFrame(private val context: Context, private val ui: PaperUi) : PageFrame {
    override val bleed = true
    private fun text(value: String, em: Int, ink: Int = ui.p.ink, heading: Boolean = false, gravity: Int = Gravity.START, spacing: Float = 1.5f) =
        PixelText(context, ui.pixelType, value, em, ink, gravity, spacing, heading)

    override fun title(text: String) = text(text, 36, heading = true, spacing = 1.2f)
    override fun since(text: String) = text(text, 12, ui.p.inkSecondary, gravity = Gravity.END)
    override fun bigNumber(text: String, description: String) = text(text, 48, spacing = 1.2f).also { it.contentDescription = description }
    override fun sub(text: String) = text(text, 12, ui.p.inkSecondary).also { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun heading(text: String) = text(text, 24, heading = true, spacing = 1.2f)
    override fun note(text: String) = text(text, 12, ui.p.inkSecondary)
    override fun footer(text: String) = text(text, 12, ui.p.inkSecondary)
    override fun placeName(text: String) = text(text, 24, spacing = 1.2f)
    override fun placeCount(text: String) = text(text, 12, ui.p.inkSecondary)

    override fun framed(picture: View, edge: Boolean): View {
        if (!edge) return picture
        val w = ui.dp(ui.skin.lineDp)
        return FrameLayout(context).apply {
            setBackgroundColor(ui.p.ink)
            setPadding(w, w, w, w)
            addView(picture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}

internal class SkinPageFrame(private val context: Context, private val ui: PaperUi) : PageFrame {
    override val bleed = false
    private fun text(value: String, sp: Float, ink: Int = ui.p.ink, face: Typeface? = null, heading: Boolean = false, gravity: Int = Gravity.START) = TextView(context).apply {
        this.text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(ink)
        this.gravity = gravity
        if (face != null) typeface = face
        if (heading && Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    override fun title(text: String) = text(text, 32f, face = PaperFonts.title(context), heading = true)
    override fun since(text: String) = text(text, 13f, ui.p.inkSecondary, gravity = Gravity.END)
    override fun bigNumber(text: String, description: String) = text(text, 44f, face = PaperFonts.wordmark(context)).also { it.contentDescription = description }
    override fun sub(text: String) = text(text, 14f, ui.p.inkSecondary).also { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun heading(text: String) = ui.labelStrip(text)
    override fun note(text: String) = text(text, 13f, ui.p.inkSecondary)
    override fun footer(text: String) = text(text, 13f, ui.p.inkSecondary)
    override fun placeName(text: String) = text(text, 17f, face = PaperFonts.title(context))
    override fun placeCount(text: String) = text(text, 13f, ui.p.inkSecondary)

    override fun framed(picture: View, edge: Boolean): View {
        val w = ui.dp(ui.skin.lineDp)
        return FrameLayout(context).apply {
            background = ui.surface(12, fill = ui.p.paper)
            clipToOutline = true
            setPadding(w, w, w, w)
            addView(picture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}

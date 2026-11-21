package com.krysta1ll.meowpuut.android.app

import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.Prefs

/**
 * What a keyboard skin says to the app's own pages: the home page and 行旅. A skin is data, as it is for the keyboard: a few design variables
 * (paper, second paper, ink, quiet ink, the accent, the corner and the line, the type) and a few slots a skin may fill. The pages ask this and
 * never ask which skin it is; a skin that fills nothing leaves the page as it was.
 *
 * The variables are a [PaperPalette] (the one the settings page has always read: paper, paper2, ink, inkSecondary, stamp, pill, pillText), so
 * every piece built from them (surfaces, label strips, switches, the stamp toast) follows without being touched.
 */
internal class AppSkin(
    val key: String,
    val palette: PaperPalette,
    /** Corners: null keeps each surface's own radius; a number sets every one to it (0 is square). */
    val cornerDp: Int?,
    /** The width of a surface's line in dp, and whether it is the ink (not a faint rule). */
    val lineDp: Int,
    val inkLines: Boolean,
    /** Headings and entries are set in the pixel type of the keyboard. */
    val pixelType: Boolean,
    val slots: AppSlots,
) {
    /** A surface's corner radius: the one it asked for unless the skin sets its own. */
    fun corner(asked: Int): Int = cornerDp ?: asked
}

/** The places in the home page and 行旅 a skin may fill. The default of each is the page as it was. */
internal class AppSlots(
    /** The top of the home page: the app's masthead and its two mascots, or a living picture of where the cats have got to. */
    val hero: HeroSlot = HeroSlot.MASTHEAD,
    /** How the ways in (settings, wardrobe, 行旅) look: cards, or signposts with a landmark each. */
    val entries: EntryStyle = EntryStyle.CARDS,
    /** How the cats' life photographs first appear. */
    val photos: PhotoStyle = PhotoStyle.PHOTO,
    /** What surrounds 行旅: the skin's own paper and type, or (字景) the page itself is the picture. */
    val statsFrame: StatsFrame = StatsFrame.SKIN,
)

internal enum class HeroSlot { MASTHEAD, SCENE }
internal enum class EntryStyle { CARDS, SIGNPOSTS }
internal enum class PhotoStyle { PHOTO, TYPE_FIRST }
internal enum class StatsFrame { SKIN, GLYPH }

/** The answers of the built-in skins, for one appearance. */
internal object AppSkins {
    /**
     * Every skin but 字景 keeps the page as it is (the paper of the palette the person chose), and gets one more way in: 行旅. 字景 has its own
     * paper and ink and fills every slot.
     */
    fun of(skin: KeyboardSkin, theme: PaperTheme, dark: Boolean): AppSkin = when (skin) {
        KeyboardSkin.GLYPH -> glyph(dark)
        else -> paper(theme, dark)
    }

    /** The skin the app pages take their answers from: the one worn on the keyboard (a custom skin leaves the pages as they are). */
    fun worn(prefs: Prefs, dark: Boolean): AppSkin {
        val theme = PaperTheme.fromKey(prefs.palette.key)
        return if (prefs.customSkin != null) paper(theme, dark) else of(prefs.skin, theme, dark)
    }

    /** The page as it has always been, for [theme]. */
    fun paper(theme: PaperTheme, dark: Boolean): AppSkin = AppSkin(
        key = "paper-${theme.key}", palette = PaperTokens.palette(theme, dark), cornerDp = null, lineDp = 1, inkLines = false, pixelType = false,
        slots = AppSlots(),
    )

    /** 字景: the keyboard's own paper and ink (so the picture at the top of the page is the same sheet), square corners, ink lines, the pixel type. */
    fun glyph(dark: Boolean): AppSkin = AppSkin(
        key = "glyph", palette = if (dark) GLYPH_DARK else GLYPH_LIGHT, cornerDp = 0, lineDp = 2, inkLines = true, pixelType = true,
        slots = AppSlots(HeroSlot.SCENE, EntryStyle.SIGNPOSTS, PhotoStyle.TYPE_FIRST, StatsFrame.GLYPH),
    )

    // The keyboard's colours (Look.kt, SkinPalette of the glyph skin): paper #ECE8DC / #151413, ink #1E1C1A / #E9E4D6, vermilion #C4402A / #E2573A.
    private val GLYPH_LIGHT = PaperPalette(
        paper = 0xFFECE8DC.toInt(), paper2 = 0xFFE2DDCE.toInt(), ink = 0xFF1E1C1A.toInt(), inkSecondary = 0xFF5E5A50.toInt(),
        pill = 0xFF1E1C1A.toInt(), pillText = 0xFFECE8DC.toInt(), stamp = 0xFFC4402A.toInt(),
        tapeA = 0xFFC4402A.toInt(), tapeB = 0xFF1E1C1A.toInt(), grain = 0x00000000, shadowWarm = 0x00000000,
    )
    private val GLYPH_DARK = PaperPalette(
        paper = 0xFF151413.toInt(), paper2 = 0xFF1F1E1B.toInt(), ink = 0xFFE9E4D6.toInt(), inkSecondary = 0xFFA8A397.toInt(),
        pill = 0xFFE9E4D6.toInt(), pillText = 0xFF151413.toInt(), stamp = 0xFFE2573A.toInt(),
        tapeA = 0xFFE2573A.toInt(), tapeB = 0xFFE9E4D6.toInt(), grain = 0x00000000, shadowWarm = 0x00000000,
    )
}

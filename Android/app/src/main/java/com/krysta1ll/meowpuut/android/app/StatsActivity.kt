package com.krysta1ll.meowpuut.android.app

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.krysta1ll.meowpuut.android.R
import com.krysta1ll.meowpuut.android.app.home.Brush
import com.krysta1ll.meowpuut.android.app.home.GlyphLiveView
import com.krysta1ll.meowpuut.android.app.home.LiveSpec
import com.krysta1ll.meowpuut.android.app.home.PageFrame
import com.krysta1ll.meowpuut.android.app.home.PictureText
import com.krysta1ll.meowpuut.android.app.home.PictureView
import com.krysta1ll.meowpuut.android.app.home.PixelText
import com.krysta1ll.meowpuut.android.app.home.RidgeView
import com.krysta1ll.meowpuut.android.app.home.StatsPictures
import com.krysta1ll.meowpuut.android.app.stats.JourneyStats
import com.krysta1ll.meowpuut.android.app.stats.ManifestFestivals
import com.krysta1ll.meowpuut.android.app.stats.StatsStore
import com.krysta1ll.meowpuut.android.ime.Prefs
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphScenes
import com.krysta1ll.meowpuut.android.ime.wantsMoreContrast
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 行旅: what the cats' records add up to, drawn as a landscape in 字景's type. Every number is a running total that only grows; there are no
 * streaks, no averages, no bests. It reads the records (PetBook.bin and the journey's distance, through the ledger of [StatsStore]) when it
 * opens and when it comes back; nothing leaves the phone and nothing here is on the key path. Whatever skin is worn, the pictures are 字景's;
 * the frame round them is the skin's ([PageFrame]).
 */
class StatsActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var ui: PaperUi
    private lateinit var frame: PageFrame
    private lateinit var brush: Brush
    private lateinit var store: StatsStore
    private lateinit var column: LinearLayout
    private lateinit var scroll: ScrollView
    private val density get() = resources.displayMetrics.density
    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private var shown: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = PaperUi(this, AppSkins.worn(prefs, dark), dark)
        frame = PageFrame.of(this, ui)
        brush = Brush(this, dark, wantsMoreContrast(this))
        store = StatsStore(this, ManifestFestivals(this))
        column = object : LinearLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val width = minOf(MeasureSpec.getSize(widthMeasureSpec), dp(680))
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(36)) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            background = ui.background()
            isVerticalScrollBarEnabled = false
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        setContentView(scroll)
        title = getString(R.string.stats_title)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (shown != null) refresh()
    }

    override fun onDestroy() {
        store.shutdown()
        super.onDestroy()
    }

    private fun refresh() = store.refreshAsync { stats ->
        // The page is rebuilt only when what it shows has changed.
        val key = "${stats.graphemes}/${stats.distance}/${stats.daysMet}/${stats.mimimo.pets}/${stats.momo.pets}/${stats.cleared}/${stats.festivals.size}/${stats.mimimo.plays}/${stats.momo.plays}"
        if (key != shown) { shown = key; build(stats) }
    }

    // ---- the page --------------------------------------------------------------------------------------------

    private fun build(stats: JourneyStats) {
        column.removeAllViews()
        val simplified = isSimplified()
        val number = NumberFormat.getIntegerInstance(Locale.US)
        val level = prefs.effects.ordinal
        val plain = wantsMoreContrast(this)

        // The head: back, the name, and since when.
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(back(), LinearLayout.LayoutParams(dp(48), dp(48)))
        head.addView(frame.title(getString(R.string.stats_title)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.sinceDate?.let { d -> head.addView(frame.since(getString(R.string.stats_since, d.year, month(d.monthValue))), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(18) }) }
        column.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The place the cats have got to.
        val where = placeName(stats.here, simplified)
        val hero = GlyphLiveView(this, LiveSpec(distance = stats.distance, cats = true, heightDp = 150), dark, plain, level).apply {
            contentDescription = getString(R.string.stats_hero_desc, where, stats.stop)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        column.addView(frame.framed(hero), margins(top = 6, sides = if (frame.bleed) 0 else 18))

        if (stats.isEmpty) {
            val sentence = getString(if (stats.cleared) R.string.stats_empty_cleared else R.string.stats_empty_new)
            column.addView(frame.note(sentence), margins(top = 28, sides = 18))
            column.addView(frame.footer(getString(R.string.stats_footer)), margins(top = 40, sides = 18))
            return
        }

        val words = number.format(stats.graphemes)
        column.addView(frame.bigNumber(words, getString(R.string.stats_words_desc, words, stats.stop, where)), margins(top = 14, sides = 18))
        column.addView(frame.sub(getString(R.string.stats_words_sub, stats.stop)), margins(top = 8, sides = 18))

        val text = pictureText(simplified)
        // 日子的山
        section(R.string.stats_ridge_title, R.string.stats_ridge_note)
        val ridge = StatsPictures.Ridge(stats.days, stats.months)
        val first = stats.sinceDate!!
        val tallest = stats.daysReaching[4]
        val ridgeView = RidgeView(this, brush, ridge, text, getString(R.string.stats_ridge_desc, stats.daysMet, getString(R.string.stats_year_month, first.year, month(first.monthValue)), tallest))
        val strip = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(ridgeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // It starts at today; the past is a swipe to the right.
            post { fullScroll(View.FOCUS_RIGHT) }
        }
        column.addView(frame.framed(strip), margins(top = 4, sides = if (frame.bleed) 0 else 18))

        // 去過的地方
        section(R.string.stats_places_title, R.string.stats_places_note)
        column.addView(places(stats, simplified, level, plain), margins(top = 0, sides = 18))

        // 一天裡的時刻
        section(R.string.stats_parts_title, R.string.stats_parts_note)
        val p = stats.dayParts
        column.addView(
            frame.framed(PictureView(this, brush, { StatsPictures.PARTS_HEIGHT }, getString(R.string.stats_parts_desc, p[0], p[1], p[2], p[3], p[4])) { pen, w -> StatsPictures.parts(pen, w, stats, text) }),
            margins(top = 0, sides = 18),
        )

        // 兩隻貓
        section(R.string.stats_cats_title, R.string.stats_cats_note)
        val a = stats.mimimo
        val b = stats.momo
        column.addView(
            frame.framed(PictureView(this, brush, { StatsPictures.catsHeight(stats) }, getString(R.string.stats_cats_desc, a.level, a.pets, a.plays, b.level, b.pets, b.plays)) { pen, w -> StatsPictures.cats(pen, w, stats, text) }),
            margins(top = 0, sides = 18),
        )

        // 山有多高
        section(R.string.stats_bands_title, R.string.stats_bands_note)
        val r = stats.daysReaching
        column.addView(
            frame.framed(PictureView(this, brush, { StatsPictures.BANDS_HEIGHT }, getString(R.string.stats_bands_desc, r[0], r[1], r[2], r[3], r[4])) { pen, w -> StatsPictures.bands(pen, w, stats, text) }),
            margins(top = 0, sides = 18),
        )

        // 遇見的節日
        section(R.string.stats_festivals_title, R.string.stats_festivals_note)
        if (stats.festivals.isEmpty()) {
            column.addView(frame.note(getString(R.string.stats_festivals_none)), margins(top = 0, sides = 18))
        } else {
            val names = stats.festivals.joinToString("、") { it.name(simplified) }
            column.addView(
                frame.framed(PictureView(this, brush, { StatsPictures.festivalsHeight(stats.festivals.size, it) }, getString(R.string.stats_festivals_desc, names)) { pen, w -> StatsPictures.festivals(pen, w, stats, text) }),
                margins(top = 0, sides = 18),
            )
        }

        column.addView(frame.footer(getString(R.string.stats_footer)), margins(top = 34, sides = 18))
    }

    private fun section(heading: Int, note: Int) {
        column.addView(frame.heading(getString(heading)), margins(top = 26, sides = 18, bottom = 6))
        column.addView(frame.note(getString(note)), margins(top = 0, sides = 18, bottom = 10))
    }

    private fun back(): View = com.krysta1ll.meowpuut.android.app.home.BackChevron(this, ui.p.ink).apply {
        contentDescription = getString(R.string.stats_back)
        isClickable = true
        isFocusable = true
        setOnClickListener { finish() }
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Button::class.java.name
            }
        }
    }

    /** The twelve places, two to a row: each a small living picture, how often the cats came there, or a question mark and a veil. */
    private fun places(stats: JourneyStats, simplified: Boolean, level: Int, plain: Boolean): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val count = GlyphScenes.ALL.size
        var row: LinearLayout? = null
        for (i in 0 until count) {
            if (i % 2 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(12) })
            }
            val seen = stats.visits[i] > 0
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val name = if (seen) placeName(i, simplified) else null
                contentDescription = if (seen) getString(R.string.stats_place_seen, name, stats.visits[i]) else getString(R.string.stats_place_unseen)
                if (Build.VERSION.SDK_INT >= 28) isScreenReaderFocusable = true else isFocusable = true
            }
            val live = GlyphLiveView(this, LiveSpec(scene = i, veiled = !seen, aspect = 108.0 / 172.0, small = true), dark, plain, level)
            tile.addView(frame.framed(live, edge = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            tile.addView(frame.placeName(if (seen) placeName(i, simplified) else "？").also { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, margins(top = 8, sides = 0))
            tile.addView(frame.placeCount(if (seen) getString(R.string.stats_place_times, stats.visits[i]) else getString(R.string.stats_place_not_yet)).also { it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, margins(top = 4, sides = 0))
            row!!.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i % 2 == 0) marginEnd = dp(5) else marginStart = dp(5) })
        }
        if (count % 2 == 1) row?.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        return grid
    }

    private fun pictureText(simplified: Boolean) = PictureText(
        partNames = resources.getStringArray(R.array.stats_part_names).toList(),
        bandNames = resources.getStringArray(R.array.stats_band_names).toList(),
        bandNotes = resources.getStringArray(R.array.stats_band_notes).toList(),
        month = { year, month, withYear -> if (withYear) "$year ${month(month)}" else month(month) },
        festival = { f -> if (f.years > 1) getString(R.string.stats_festival_times, f.name(simplified), f.years) else f.name(simplified) },
        dayFestival = { it.name(simplified) },
        catNames = listOf("米米莫", "莫莫"),
        catFacts = { c -> getString(R.string.stats_cat_facts, c.level, c.pets, c.plays) },
        years = { it.toString() },
    )

    private fun month(m: Int) = resources.getStringArray(R.array.stats_months)[m - 1]

    private fun placeName(scene: Int, simplified: Boolean): String {
        val name = GlyphScenes.ALL[scene].name
        return if (simplified) SIMPLIFIED_PLACES[name] ?: name else name
    }

    private fun isSimplified(): Boolean {
        val locale = resources.configuration.locales[0]
        if (locale.language != "zh") return false
        if (locale.script == "Hans") return true
        return locale.script != "Hant" && locale.country !in setOf("TW", "HK", "MO")
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    private fun margins(top: Int = 0, sides: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
            marginStart = dp(sides)
            marginEnd = marginStart
        }

    companion object {
        /** The places whose names are written differently in Simplified Chinese. */
        private val SIMPLIFIED_PLACES = mapOf("紅葉" to "红叶")
    }
}

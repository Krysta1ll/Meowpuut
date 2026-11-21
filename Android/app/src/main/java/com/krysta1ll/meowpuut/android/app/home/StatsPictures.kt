package com.krysta1ll.meowpuut.android.app.home

import com.krysta1ll.meowpuut.android.app.stats.FestivalStamps
import com.krysta1ll.meowpuut.android.app.stats.JourneyStats
import com.krysta1ll.meowpuut.android.ime.glyph.CatId
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphCats
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.h2
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.n1
import com.krysta1ll.meowpuut.android.ime.glyph.GlyphNoise.q
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The words the pictures write in themselves, in the page's language; gathered on the main thread so the pictures can be drawn off it. */
internal class PictureText(
    val partNames: List<String>,
    val bandNames: List<String>,
    val bandNotes: List<String>,
    /** "九月", or "2025 九月" where a year begins. */
    val month: (year: Int, month: Int, withYear: Boolean) -> String,
    val festival: (JourneyStats.FestivalMet) -> String,
    val dayFestival: (com.krysta1ll.meowpuut.android.app.stats.FestivalOccurrence) -> String,
    val catNames: List<String>,
    val catFacts: (JourneyStats.CatTotals) -> String,
    val years: (Int) -> String,
)

/**
 * 行旅 in 字景: each section a picture set in the land's own type. Every number drawn is a running total that only grows; a day with nothing
 * typed is not drawn at all. These are the prototype's pictures (design-preview/glyph-home.html) on the pen of the app pages.
 */
internal object StatsPictures {
    // ---- 日子的山 --------------------------------------------------------------------------------------------

    /** The shape of the ridge: where each day's mountain stands and how high, and the pale months behind them. */
    class Ridge(val days: List<JourneyStats.DayEntry>, val months: List<JourneyStats.Month>) {
        val colW = COL_W
        val width = days.size * colW + 140
        /** The road's y; the picture is as tall as its highest summit (and the name of a festival over it) asks, so it has no dead sky. */
        val base: Double
        val height: Double
        private val n = ceil(width / 3).toInt() + 2
        val top: FloatArray
        val cap = FloatArray(n)
        val far: FloatArray
        /** Each day's summit: x and y. */
        val xs = DoubleArray(days.size)
        val ys = DoubleArray(days.size)
        /** The highest day of all (its summit carries the flag). */
        val peak: Int
        /** Where the sun stands (its middle): over the end of the road, where the cats are. */
        val sunX = width - 78.0
        val sunY: Double

        init {
            var reach = 0.0
            var monthReach = 0.0
            for ((i, d) in days.withIndex()) reach = max(reach, HEIGHTS[d.size] * 1.12 + if (d.festival != null) 64 else 34)
            for (m in months) monthReach = max(monthReach, HEIGHTS[m.largestBand] * 1.22 + 22)
            base = (max(reach, monthReach) + 40).coerceIn(150.0, 250.0)
            height = base + 40
            sunY = base - 78
            top = FloatArray(n) { base.toFloat() }
            far = FloatArray(n) { base.toFloat() }
            for ((i, d) in days.withIndex()) {
                val x0 = 40 + i * colW + colW / 2
                val hh = HEIGHTS[d.size] * (0.86 + 0.26 * h2(i, 41))
                xs[i] = x0; ys[i] = base - hh
                lift(top, x0, hh, 17.0 + d.size * 9, 1.2, 2.0, if (d.size == 4) 15.0 else 0.0)
            }
            // Behind the days, the months: each a broad pale mountain as high as its biggest day.
            var from = 0
            for (i in 0..days.size) {
                if (i == days.size || (i > 0 && !sameMonth(days[i], days[i - 1]))) {
                    if (i > from) {
                        var most = 0
                        for (k in from until i) most = max(most, days[k].size)
                        val x0 = (xs[from] + xs[i - 1]) / 2
                        lift(far, x0, HEIGHTS[most] * 1.22 + 14, (xs[i - 1] - xs[from]) / 2 + 70, 1.6, 4.0, null)
                    }
                    from = i
                }
            }
            var best = 0
            for (i in days.indices) if (ys[i] < ys[best]) best = i
            peak = best
        }

        private fun sameMonth(a: JourneyStats.DayEntry, b: JourneyStats.DayEntry) = a.date.year == b.date.year && a.date.monthValue == b.date.monthValue

        private fun lift(arr: FloatArray, x0: Double, hh: Double, half: Double, pow: Double, rough: Double, capOf: Double?) {
            for (k in max(0, floor((x0 - half) / 3).toInt())..min(n - 1, ceil((x0 + half) / 3).toInt())) {
                val y = base - hh * max(0.0, 1 - abs(k * 3 + 1.5 - x0) / half).pow(pow) - rough * n1(k * 0.7, 3)
                if (y < arr[k]) {
                    arr[k] = y.toFloat()
                    if (capOf != null) cap[k] = (capOf * (0.6 + 0.8 * n1(k * 0.5, 8))).toFloat()
                }
            }
        }

        companion object {
            const val COL_W = 20.0
            /** How high a day of each size band stands. */
            val HEIGHTS = doubleArrayOf(20.0, 46.0, 78.0, 114.0, 158.0)
        }
    }

    /**
     * The sky over the ridge: a vermilion sun (a pale moon at night) rising behind the last mountains, ringed with its own type, and clouds and
     * birds that follow no day. The land is painted over it, so the sun is as high as the hills let it be.
     */
    private fun sky(pen: Pen, r: Ridge, from: Double, to: Double) {
        val v = pen.tones()
        // Clouds, set in the cloud pool, one in a slot of 170 dp or none.
        var k = floor((from - 200) / 170).toInt()
        while (k * 170.0 < to + 120) {
            if (k >= 0 && h2(k, 501) < 0.62) {
                val cx = k * 170.0 + h2(k, 502) * 80
                val cy = 14 + h2(k, 503) * max(8.0, r.base - 190)
                val cols = 8 + (h2(k, 504) * 9).toInt()
                for (row in 0 until 3) for (col in 0 until cols) {
                    val m = (cols - 1) / 2.0
                    val shape = 1 - abs(col - m) / (m + 1)
                    val t = (shape * (if (row == 1) 1.0 else 0.62) * (0.5 + 0.5 * h2(col + k * 31, row + 600))) * 0.55
                    if (t > 0.1) { v.fill(t); pen.cell(cx + col * 6, cy + row * 6, 6, v, "cloud", col + k * 40, row, 77 + k) }
                }
            }
            k++
        }
        // Birds in the open sky, a few to a flock.
        k = floor((from - 300) / 380).toInt()
        while (k * 380.0 < to + 100) {
            if (k >= 1 && h2(k, 511) < 0.7) {
                val fx = k * 380.0 + h2(k, 512) * 160
                val fy = 22 + h2(k, 513) * max(10.0, r.base - 200)
                for (b in 0 until 3 + (h2(k, 514) * 3).toInt()) pen.stamp(BIRD, fx + b * 15 + h2(b, k) * 6, fy + (b % 2) * 7 + h2(k, b + 9) * 5, 2.0, false)
            }
            k++
        }
        // The sun, and the type that rings it.
        val rad = 30.0
        if (r.sunX + 70 >= from && r.sunX - 70 <= to) {
            val ring = pen.red.takeUnless { pen.dark }
            val reachR = 64.0
            var gy = r.sunY - reachR
            while (gy < r.sunY + reachR) {
                var gx = r.sunX - reachR
                while (gx < r.sunX + reachR) {
                    val d = Math.hypot(gx + 3 - r.sunX, gy + 3 - r.sunY)
                    if (d > rad + 5 && d < reachR) {
                        val t = (1 - (d - rad - 5) / (reachR - rad - 5)) * 0.5
                        v.fill(t); pen.cell(gx, gy, 6, v, "star", (gx / 6).toInt(), (gy / 6).toInt(), 91, ring)
                    }
                    gx += 6
                }
                gy += 6
            }
            val colour = if (pen.dark) pen.ink else pen.red
            var y = -rad
            while (y < rad) {
                var x = -rad
                while (x < rad) {
                    val dx = x + 1.5
                    val dy = y + 1.5
                    if (dx * dx + dy * dy <= rad * rad) pen.rect(q(r.sunX + x, 3.0), q(r.sunY + y, 3.0), 3.0, 3.0, colour)
                    x += 3
                }
                y += 3
            }
        }
    }

    private val BIRD = listOf("X...X", ".X.X.", "..X..")

    /** One tile of the ridge, [from] to [to] dp; the pen's origin is set to [from]. */
    fun ridge(pen: Pen, r: Ridge, from: Double, to: Double, text: PictureText) {
        pen.ox = from
        val w = r.width
        sky(pen, r, from, to)
        pen.range(w, r.base, r.far, "pinyin", 23, 0.34, null, pen.hue("ice"), tileWidth = to - from)
        pen.range(w, r.base, r.top, "hira", 11, 1.0, r.cap, null, tileWidth = to - from)
        pen.rect(from, r.base, to - from, 2.0, pen.ink)
        val gold = pen.hue("gold")
        val first = max(0, ((from - 60) / Ridge.COL_W).toInt() - 1)
        val last = min(r.days.size - 1, ((to + 60) / Ridge.COL_W).toInt() + 1)
        for (i in first..last) {
            val d = r.days[i]
            val x = r.xs[i]
            val y = r.ys[i]
            if (d.part and 0b11000 != 0) {                            // stars over the days that ran into the night
                pen.rect(q(x + 6, 2.0), q(y - 12 - 6 * h2(i, 9), 2.0), 2.0, 2.0, gold)
                if (d.part and 0b10000 != 0) {
                    pen.rect(q(x - 8, 2.0), q(y - 20, 2.0), 6.0, 2.0, gold)
                    pen.rect(q(x - 6, 2.0), q(y - 22, 2.0), 2.0, 6.0, gold)
                }
            }
            d.festival?.let { f ->
                val rows = Stamps.ofFestival(f.id, stamps)
                pen.stamp(rows, (x - rows[0].length).roundToInt().toDouble(), (y - rows.size * 2 - 3).roundToInt().toDouble(), 2.0, true)
                pen.say(text.dayFestival(f), x, y - rows.size * 2 - 14, Say(ink = pen.red, halo = true, centre = true))
            }
            val date = d.date
            val newMonth = i == 0 || date.monthValue != r.days[i - 1].date.monthValue || date.year != r.days[i - 1].date.year
            if (newMonth) {
                pen.rect(q(x - Ridge.COL_W / 2, 2.0), r.base + 2, 2.0, 10.0, pen.ink)
                pen.say(text.month(date.year, date.monthValue, date.monthValue == 1 || i == 0), x - Ridge.COL_W / 2 + 6, r.base + 20, Say())
            }
        }
        if (r.peak in first..last) pen.stamp("flag", (r.xs[r.peak] - 1).roundToInt().toDouble(), (r.ys[r.peak] - 16).roundToInt().toDouble(), 2.0, true)
        // and the two cats are on today's
        val lastDay = r.days.size - 1
        if (lastDay in first..last) {
            pen.cat(CatId.MOMO, "sit", "neutral", (r.xs[lastDay] - 22).roundToInt().toDouble(), (r.ys[lastDay] - GlyphCats.h(CatId.MOMO)).roundToInt().toDouble(), 1.0)
            pen.cat(CatId.MIMI, "sit", "neutral", (r.xs[lastDay] + 2).roundToInt().toDouble(), (r.ys[lastDay] - GlyphCats.h(CatId.MIMI)).roundToInt().toDouble(), 1.0)
        }
        var x = (floor(from / 8) * 8)
        while (x < to) {
            if (h2(x.toInt(), 77) > 0.5) pen.rect(x, r.base + 32 + (if (h2(x.toInt(), 78) > 0.5) 2 else 0), 4.0, 1.0, pen.ink)
            x += 8
        }
    }

    /** Where a festival's own stamps come from (the world list can replace it). */
    @Volatile var stamps: FestivalStamps? = null

    // ---- 一天裡的時刻 ----------------------------------------------------------------------------------------

    const val PARTS_HEIGHT = 118.0

    fun parts(pen: Pen, w: Double, stats: JourneyStats, text: PictureText) {
        val seg = w / 5
        val most = max(1, stats.dayParts.max())
        val pools = listOf("cloud", "dust", "cloud", "star", "star")
        val v = pen.tones()
        val sky = 74.0
        val gold = pen.hue("gold")
        for (p in 0 until 5) {
            val fill = stats.dayParts[p].toDouble() / most
            val x0 = (p * seg).roundToInt().toDouble()
            for (r in 0 until (sky / 6).toInt()) for (col in 0 until (seg / 6 - 1).toInt()) {
                val t = if (h2(col + p * 40, r + 300) < 0.22 + 0.7 * fill) 0.14 + 0.5 * fill * h2(col, r + p) else 0.0
                if (t > 0) { v.fill(t); pen.cell(x0 + col * 6, r * 6.0, 6, v, pools[p], col + p * 40, r, 5 + p) }
            }
            // where the sun or the moon stands at that hour
            val rad = 9.0
            val cx = x0 + seg / 2 + doubleArrayOf(-14.0, 0.0, 14.0, -6.0, 8.0)[p]
            val cy = doubleArrayOf(58.0, 20.0, 56.0, 24.0, 30.0)[p]
            val colour = if (p < 3) pen.red else gold
            var y = -rad
            while (y < rad) {
                var x = -rad
                while (x < rad) {
                    val dx = x + 1.5
                    val dy = y + 1.5
                    if (dx * dx + dy * dy <= rad * rad && !(p == 3 && (dx - 4) * (dx - 4) + (dy + 1) * (dy + 1) < rad * rad * 0.8)) pen.rect(q(cx + x, 3.0), q(cy + y, 3.0), 3.0, 3.0, colour)
                    x += 3
                }
                y += 3
            }
            pen.rect(x0, sky, (seg - 4).roundToInt().toDouble(), 2.0, pen.ink)
            pen.say(text.partNames[p], x0 + (seg - 4) / 2, sky + 16, Say(centre = true))
            pen.say(stats.dayParts[p].toString(), x0 + (seg - 4) / 2, sky + 34, Say(centre = true))
        }
    }

    // ---- 兩隻貓 ----------------------------------------------------------------------------------------------

    private const val CATS_BASE = 132.0
    private const val PER_ROW = 13
    /** Rows of 正 shown at most; a cat petted more than that says its number in words instead (the picture would only grow taller). */
    private const val MAX_ROWS = 14

    fun catsHeight(stats: JourneyStats): Double {
        val rows = max(tallyRows(stats.mimimo.pets), tallyRows(stats.momo.pets))
        return CATS_BASE + 58 + rows * 15 + 10
    }

    private fun tallyRows(pets: Long): Int {
        val marks = pets / 5 + if (pets % 5 > 0) 1 else 0
        return min(MAX_ROWS.toLong(), (marks + PER_ROW - 1) / PER_ROW).toInt()
    }

    fun cats(pen: Pen, w: Double, stats: JourneyStats, text: PictureText) {
        val half = w / 2
        val cats = listOf(Triple(CatId.MIMI, stats.mimimo, text.catNames[0]), Triple(CatId.MOMO, stats.momo, text.catNames[1]))
        for ((n, c) in cats.withIndex()) {
            val (id, s, name) = c
            val x0 = n * half
            pen.tx = x0
            val tops = FloatArray(ceil(half / 3).toInt() + 1)
            val hh = 20 + s.level * 0.62
            val cx = half / 2
            for (k in tops.indices) {
                val x = k * 3 + 1.5
                tops[k] = (CATS_BASE - hh * max(0.0, 1 - (abs(x - cx) / (half * 0.52)).pow(1.6)) - 3 * n1(k * 0.4, 5 + n)).toFloat()
            }
            pen.range(half - 6, CATS_BASE, tops, if (n == 1) "bopo" else "hira", 21 + n, 0.7)
            val ty = q(tops[floor(cx / 3).toInt()].toDouble(), 2.0)
            pen.cat(id, "sit", "neutral", (cx - GlyphCats.w(id) * 1.5).roundToInt().toDouble(), (ty - GlyphCats.h(id) * 3.0).roundToInt().toDouble(), 3.0)       // shown whole even at night: this is a portrait
            pen.rect(0.0, CATS_BASE, half - 6, 2.0, pen.ink)
            pen.say(name, 2.0, CATS_BASE + 18, Say(scale = 2.0))
            pen.say(text.catFacts(s), 2.0, CATS_BASE + 40, Say())
            // 正: five strokes to a character, written one stroke at a time
            val marks = (s.pets / 5).toInt()
            val rest = (s.pets % 5).toInt()
            val rows = tallyRows(s.pets)
            val shown = min(marks + (if (rest > 0) 1 else 0), rows * PER_ROW)
            for (i in 0 until shown) {
                val strokes = if (i < marks) 5 else rest
                tally(pen, 2.0 + (i % PER_ROW) * 13, CATS_BASE + 58 + (i / PER_ROW) * 15, strokes)
            }
        }
        pen.tx = 0.0
    }

    /** The strokes of 正 in their order: 一 丨 一 丨 一. */
    private fun tally(pen: Pen, x: Double, y: Double, n: Int) {
        val strokes = arrayOf(doubleArrayOf(0.0, 0.0, 11.0, 2.0), doubleArrayOf(5.0, 0.0, 2.0, 11.0), doubleArrayOf(5.0, 5.0, 6.0, 2.0), doubleArrayOf(1.0, 4.0, 2.0, 7.0), doubleArrayOf(0.0, 9.0, 11.0, 2.0))
        for (i in 0 until n) pen.rect(x + strokes[i][0], y + strokes[i][1], strokes[i][2], strokes[i][3], pen.ink)
    }

    // ---- 山有多高 --------------------------------------------------------------------------------------------

    const val BANDS_HEIGHT = 222.0

    fun bands(pen: Pen, w: Double, stats: JourneyStats, text: PictureText) {
        val base = 176.0
        val seg = w / 5
        val heights = doubleArrayOf(18.0, 38.0, 64.0, 96.0, 132.0)
        val n = ceil(w / 3).toInt() + 2
        val top = FloatArray(n) { base.toFloat() }
        val cap = FloatArray(n)
        for (b in 0 until 5) {
            val cx = seg * (b + 0.5)
            val half = seg * 0.62
            for (k in 0 until n) {
                val y = base - heights[b] * max(0.0, 1 - abs(k * 3 + 1.5 - cx) / half).pow(1.3) - 2 * n1(k * 0.6, 13)
                if (y < top[k]) { top[k] = y.toFloat(); cap[k] = if (b == 4) (16 * (0.6 + 0.8 * n1(k * 0.5, 8))).toFloat() else 0f }
            }
        }
        pen.range(w, base, top, "hira", 44, 0.95, cap)
        pen.rect(0.0, base, w, 2.0, pen.ink)
        for (b in 0 until 5) {
            val cx = (seg * (b + 0.5)).roundToInt().toDouble()
            pen.say(text.bandNames[b], cx, base + 16, Say(centre = true))
            pen.say(text.bandNotes[b], cx, base + 32, Say(centre = true))
            pen.say(stats.daysReaching[b].toString(), cx, base - heights[b] - 16, Say(scale = 2.0, halo = true, centre = true))
        }
    }

    // ---- 遇見的節日 ------------------------------------------------------------------------------------------

    private const val FEST_ROW = 108.0

    fun festivalsHeight(count: Int, w: Double): Double = max(1, ceil(count / festivalsPerRow(w).toDouble()).toInt()) * FEST_ROW + 4

    fun festivalsPerRow(w: Double): Int = max(3, (w / 76).toInt())

    fun festivals(pen: Pen, w: Double, stats: JourneyStats, text: PictureText) {
        val per = festivalsPerRow(w)
        val gap = w / per
        val top = FloatArray(ceil(w / 3).toInt() + 2)
        val rows = ceil(stats.festivals.size / per.toDouble()).toInt()
        for (row in 0 until rows) {
            val base = row * FEST_ROW + 76
            for (k in top.indices) top[k] = (base - 5 * n1(k * 0.2, 3 + row)).toFloat()
            // a low range of hills for them to stand on, drawn on this row's own band of the picture
            pen.range(w, base + 8, top, "bopo", 51 + row, 0.4, null, null, null, w)
            for (i in 0 until per) {
                val index = row * per + i
                if (index >= stats.festivals.size) break
                val f = stats.festivals[index]
                val x = (gap * (i + 0.5)).roundToInt().toDouble()
                val rowsOf = Stamps.ofFestival(f.id, stamps)
                pen.stamp(rowsOf, (x - rowsOf[0].length * 1.5).roundToInt().toDouble(), base - rowsOf.size * 3 - 2, 3.0, true)
                pen.say(text.festival(f), x, base + 22, Say(halo = true, centre = true))
            }
        }
    }
}

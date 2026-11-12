package com.krysta1ll.meowpuut.android.ime

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/*
 * The festival wardrobe (docs/FESTIVALS.md), the same rules as
 * Sources/Festivals.swift: a theme is worn by the calendar only, from
 * leadDays before the festival's first day through its last day, in the
 * device's own date. The manifest is assets/festivals/festivals.json.
 */

data class FestivalInsets(val top: Float, val left: Float, val bottom: Float, val right: Float)

/**
 * Where the bar's top edge crosses a dressed cat seated behind the glass, as
 * fractions of the picture: its opaque bounds (left, top, width, height), the
 * row the edge runs along, and the opaque span on that row.
 */
data class FestivalBust(val bounds: List<Float>, val row: Float, val cutLeft: Float, val cutRight: Float)

/** A dressed cat: logical size in points, feet as a fraction of the size from the top left. */
data class FestivalSprite(
    val image: String, val width: Float, val height: Float, val anchorX: Float, val anchorY: Float, val bust: FestivalBust? = null,
) {
    /** The cat as the bar seats it (Sources/FestivalTrim.swift catSprite): behind the glass, no paws in front. */
    fun catSprite(): CatSprite? = bust?.let { b ->
        CatSprite(image, width, height, (b.cutLeft + b.cutRight) / 2, b.row,
            b.bounds[0], b.bounds[1], b.bounds[0] + b.bounds[2], b.bounds[1] + b.bounds[3], b.cutLeft, b.cutRight, listOf())
    }
}

/** An ornament cluster the importer cut off the trim, size in dp. */
data class FestivalPiece(val image: String, val width: Float, val height: Float)

/** The decoration around the glass; only the middle between the caps stretches. */
data class FestivalTrim(
    val image: String,
    val width: Float,
    val height: Float,
    val caps: FestivalInsets,
    val safeText: FestivalInsets,
    val rim: FestivalInsets,
)

/** #RRGGBB: `light` and `dark` are the selected capsule's top and bottom, `contrast` replaces both. */
data class FestivalColors(val accent: String, val light: List<String>, val dark: List<String>, val contrast: String)

/** A festival's own days in one year, inclusive. */
data class FestivalDays(val first: LocalDate, val main: LocalDate, val last: LocalDate)

data class FestivalTheme(
    val id: String,
    val hant: String,
    val hans: String,
    val ja: String,
    val en: String,
    val mimimo: FestivalSprite,
    val momo: FestivalSprite,
    val trim: FestivalTrim,
    /** The right end's ornament cluster, or null. */
    val right: FestivalPiece?,
    val colors: FestivalColors,
    val days: List<FestivalDays>,
) {
    fun name(simplified: Boolean) = if (simplified) hans else hant
}

/** What the person chose: off means the plain cats always; `skipped` are themes switched off. */
data class FestivalWardrobe(val on: Boolean = true, val skipped: Set<String> = emptySet())

object Festivals {
    const val MANIFEST = "festivals/festivals.json"

    class Loaded(val leadDays: Int, val themes: List<FestivalTheme>)

    val EMPTY = Loaded(7, emptyList())

    /** A manifest that is not version 2 gives no themes; a theme that does not read is left out. */
    fun parse(json: String): Loaded {
        val root = try { JSONObject(json) } catch (_: Exception) { return EMPTY }
        if (root.optInt("version") != 2 || !root.has("leadDays")) return EMPTY
        val leadDays = root.optInt("leadDays")
        val list = root.optJSONArray("themes") ?: return EMPTY
        val themes = (0 until list.length()).mapNotNull { i ->
            try { theme(list.getJSONObject(i)) } catch (_: Exception) { null }
        }
        return Loaded(leadDays, themes)
    }

    private fun theme(o: JSONObject): FestivalTheme? {
        val names = o.getJSONObject("names")
        val colors = o.getJSONObject("colors")
        val t = o.getJSONObject("trim")
        val trimSize = pair(t.getJSONArray("size"))
        val days = o.getJSONArray("days").let { a -> (0 until a.length()).map { days(a.getJSONArray(it)) } }
        val theme = FestivalTheme(
            id = o.getString("id"),
            hant = names.getString("hant"), hans = names.getString("hans"),
            ja = names.getString("ja"), en = names.getString("en"),
            mimimo = sprite(o.getJSONObject("mimimo")), momo = sprite(o.getJSONObject("momo")),
            trim = FestivalTrim(t.getString("image"), trimSize.first, trimSize.second,
                insets(t.getJSONArray("caps")), insets(t.getJSONArray("safeText")), insets(t.getJSONArray("rim"))),
            right = o.optJSONObject("ends")?.optJSONObject("right")?.let { r ->
                val size = pair(r.getJSONArray("size"))
                FestivalPiece(r.getString("image"), size.first, size.second)
            },
            colors = FestivalColors(colors.getString("accent"), strings(colors.getJSONArray("light")),
                strings(colors.getJSONArray("dark")), colors.getString("contrast")),
            days = days,
        )
        return theme.takeIf { listOfNotNull(it.id, it.mimimo.image, it.momo.image, it.trim.image, it.right?.image).all(::isName) }
    }

    private fun sprite(o: JSONObject): FestivalSprite {
        val size = pair(o.getJSONArray("size"))
        val anchor = pair(o.getJSONArray("anchor"))
        val bust = o.optJSONObject("bust")?.let { b ->
            val cut = numbers(b.getJSONArray("cut"), 2)
            FestivalBust(numbers(b.getJSONArray("bounds"), 4), b.getDouble("row").toFloat(), cut[0], cut[1])
        }
        return FestivalSprite(o.getString("image"), size.first, size.second, anchor.first, anchor.second, bust)
    }

    private fun numbers(a: JSONArray, count: Int): List<Float> {
        require(a.length() == count)
        return (0 until count).map { a.getDouble(it).toFloat() }
    }

    private fun pair(a: JSONArray) = numbers(a, 2).let { it[0] to it[1] }

    private fun insets(a: JSONArray) = numbers(a, 4).let { FestivalInsets(it[0], it[1], it[2], it[3]) }

    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }

    private fun days(a: JSONArray): FestivalDays {
        require(a.length() == 3)
        val d = (0 until 3).map { day(a.getString(it)) }
        require(!d[1].isBefore(d[0]) && !d[2].isBefore(d[1]))
        return FestivalDays(d[0], d[1], d[2])
    }

    /** yyyy-MM-dd, exactly. */
    private fun day(text: String): LocalDate {
        require(text.length == 10)
        return LocalDate.parse(text)
    }

    /** 1 to 64 ASCII letters, digits, `_` or `-`: safe as a file name. */
    fun isName(text: String) = text.length in 1..64 &&
        text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    private class Standing(val step: Int, val theme: FestivalTheme, val days: FestivalDays)

    /**
     * The theme worn on [today], from the date and the switches alone. Where
     * several are worn: a festival on its main day; then one on its other own
     * days (the one that began last, then the one that ends first); then one
     * in its lead-up week (the one that begins soonest). Festivals that share
     * a main day and stand on the same step take turns by day.
     */
    fun theme(today: LocalDate, loaded: Loaded, wardrobe: FestivalWardrobe): FestivalTheme? {
        if (!wardrobe.on) return null
        val pool = ArrayList<Standing>()
        for (theme in loaded.themes) {
            if (theme.id in wardrobe.skipped) continue
            for (days in theme.days) {
                if (today.isBefore(days.first.minusDays(loaded.leadDays.toLong())) || today.isAfter(days.last)) continue
                pool += Standing(if (today == days.main) 1 else if (!today.isBefore(days.first)) 2 else 3, theme, days)
            }
        }
        val step = pool.minOfOrNull { it.step } ?: return null
        val standing = pool.filter { it.step == step }
        val leader = when (step) {
            2 -> standing.minWith(compareByDescending<Standing> { it.days.first }.thenBy { it.days.last })
            3 -> standing.minBy { it.days.first }
            else -> standing.first()
        }
        val turns = standing.filter { it.days.main == leader.days.main }
        return turns[Math.floorMod(today.toEpochDay(), turns.size.toLong()).toInt()].theme
    }

    /** Themes whose worn range has begun at least once between [since] and [today], in manifest order. */
    fun met(since: LocalDate, today: LocalDate, loaded: Loaded): List<FestivalTheme> = loaded.themes.filter { theme ->
        theme.days.any { !it.first.minusDays(loaded.leadDays.toLong()).isAfter(today) && !it.last.isBefore(since) }
    }

    /**
     * Where the right end's cluster goes (Sources/Festivals.swift
     * FestivalEndsLayout): between the expand arrow's glyph and the screen
     * edge, on the glass's lower edge, no taller than the glass; null when it
     * would come out under 60 % of the glass height.
     */
    fun placeRight(piece: FestivalPiece, glass: Box, band: Pair<Float, Float>, width: Float, arrowRight: Float): Box? {
        val tallest = band.second - band.first
        val bottom = glass.top + band.second + 1
        val end = minOf(width - 1, glass.right + 4)
        val k = minOf(tallest / piece.height, (end - arrowRight - 2) / piece.width)
        if (piece.height * k < 0.6f * tallest) return null
        return Box(end - piece.width * k, bottom - piece.height * k, piece.width * k, piece.height * k)
    }
}

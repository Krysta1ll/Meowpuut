package com.krysta1ll.meowpuut.android.engine

import java.util.Locale

/**
 * The input mode and script a fresh install starts with, from the system
 * region and language list. The region is only a local heuristic: it is
 * read here, never stored or sent; only the resulting mode and script are
 * saved.
 */
object InputDefaults {
    data class Start(val mode: InputMode, val simplified: Boolean)

    /** What the first run of this version saves: the mixed typing switch, and a start mode only for a fresh install. */
    data class FirstRun(val mixedJapanese: Boolean, val start: Start?)

    /**
     * [existingSettings]: the store already held settings from an earlier version, so the person
     * keeps mixed typing on and whatever mode they have. A fresh install gets it off and, if it
     * has no mode or script yet, the start for its language and region.
     */
    fun firstRun(existingSettings: Boolean, hasModeOrScript: Boolean, region: String?, languageTags: List<String>): FirstRun =
        if (existingSettings) FirstRun(true, null)
        else FirstRun(false, if (hasModeOrScript) null else choose(region, languageTags))

    private val simplifiedRegions = setOf("CN", "SG", "MY")
    private val traditionalRegions = setOf("HK", "MO")

    /** [region] is an ISO 3166 code (any case, may be empty); [languageTags] are BCP 47 tags, preferred first. */
    fun choose(region: String?, languageTags: List<String>): Start {
        val code = region.orEmpty().uppercase(Locale.ROOT)
        val chinese = languageTags.map { Locale.forLanguageTag(it) }.filter { it.language == "zh" }
        val scripts = chinese.map { it.script() }

        if (code == "TW" || chinese.any { it.country == "TW" && it.getScript() != "Hans" }) return Start(InputMode.ZHUYIN, false)
        if (code in simplifiedRegions || scripts.any { it == Script.HANS }) return Start(InputMode.PINYIN, true)
        if (code in traditionalRegions) return Start(InputMode.PINYIN, false)
        val first = scripts.firstOrNull { it != Script.NONE }
        return Start(InputMode.PINYIN, first != Script.HANT)
    }

    private enum class Script { HANS, HANT, NONE }

    /** The script a tag names, with the usual region implying one when the tag has none. */
    private fun Locale.script(): Script = when {
        getScript() == "Hans" -> Script.HANS
        getScript() == "Hant" -> Script.HANT
        country in simplifiedRegions -> Script.HANS
        country == "TW" || country in traditionalRegions -> Script.HANT
        else -> Script.NONE
    }
}

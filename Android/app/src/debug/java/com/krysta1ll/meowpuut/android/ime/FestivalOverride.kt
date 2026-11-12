package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import java.io.File
import java.time.LocalDate

/**
 * Debug builds only (src/debug): look at a festival on another day, as the
 * iOS lab's -festivalDay and -festivalTheme do. The file files/festival holds
 * a day (2026-12-24) or a theme id (christmas):
 *
 *   adb shell run-as com.krysta1ll.meowpuut.android sh -c 'echo christmas > files/festival'
 *   adb shell run-as com.krysta1ll.meowpuut.android rm files/festival
 *
 * The release build has an empty stand-in (src/release), so nothing can put
 * a theme on early there.
 */
object FestivalOverride {
    fun theme(context: Context, loaded: Festivals.Loaded, wardrobe: FestivalWardrobe): FestivalTheme? {
        val text = File(context.filesDir, "festival").takeIf { it.exists() }?.readText()?.trim() ?: return null
        loaded.themes.firstOrNull { it.id == text }?.let { return it }
        val day = runCatching { LocalDate.parse(text) }.getOrNull() ?: return null
        return Festivals.theme(day, loaded, wardrobe)
    }
}

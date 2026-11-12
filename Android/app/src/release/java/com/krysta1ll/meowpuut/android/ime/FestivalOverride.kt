package com.krysta1ll.meowpuut.android.ime

import android.content.Context

/** Release builds: no way to look at a festival on another day (see src/debug). */
object FestivalOverride {
    @Suppress("UNUSED_PARAMETER")
    fun theme(context: Context, loaded: Festivals.Loaded, wardrobe: FestivalWardrobe): FestivalTheme? = null
}

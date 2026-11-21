package com.krysta1ll.meowpuut.android.app.stats

import android.content.Context
import android.content.Intent

/** Release builds: no demo records and no pretend date (see src/debug). */
internal object DemoHooks {
    @Suppress("UNUSED_PARAMETER")
    fun enable(context: Context, intent: Intent?) = Unit

    /** Today, as a local day number. */
    fun today(): Int = java.time.LocalDate.now().toEpochDay().toInt()

    @Suppress("UNUSED_PARAMETER")
    fun stats(context: Context, festivals: FestivalSource): JourneyStats? = null

    @Suppress("UNUSED_PARAMETER")
    fun characters(context: Context): Long? = null
}

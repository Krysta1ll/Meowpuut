package com.krysta1ll.meowpuut.android.app

import android.content.Context
import android.content.Intent

/** Release builds: no way to plant a test request key (see src/debug). */
object SkinDebugHooks {
    @Suppress("UNUSED_PARAMETER")
    fun handle(context: Context, intent: Intent?): Boolean = false
}

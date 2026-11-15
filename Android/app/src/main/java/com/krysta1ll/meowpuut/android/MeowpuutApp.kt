package com.krysta1ll.meowpuut.android

import android.app.Application
import com.krysta1ll.meowpuut.android.app.BundledSkins

/** Installs the skins shipped in the APK on every process start (a no-op once they are in place). */
class MeowpuutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BundledSkins.installAsync(this)
    }
}

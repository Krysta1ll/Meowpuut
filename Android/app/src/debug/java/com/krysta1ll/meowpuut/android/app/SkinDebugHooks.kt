package com.krysta1ll.meowpuut.android.app

import android.content.Context
import android.content.Intent
import com.krysta1ll.meowpuut.android.engine.SkinNative
import com.krysta1ll.meowpuut.android.ime.SkinKeystore
import java.security.MessageDigest

/**
 * Debug builds only (src/debug): lets a test script give this device the published test request key
 * (Tests/skin-vectors: seed = SHA-256("meowpuut-skin test key request a")), so the shared test vector
 * can be redeemed on an emulator:
 *
 *   adb shell am start -n com.krysta1ll.meowpuut.android/.app.RedeemActivity \
 *     -a com.krysta1ll.meowpuut.android.debug.IMPORT_TEST_REQUEST --es which a
 *
 * The release build has an empty stand-in (src/release), and a release library trusts no test key anyway.
 */
object SkinDebugHooks {
    const val ACTION = "com.krysta1ll.meowpuut.android.debug.IMPORT_TEST_REQUEST"

    fun handle(context: Context, intent: Intent?): Boolean {
        if (intent?.action != ACTION) return false
        val which = intent.getStringExtra("which")?.takeIf { it == "a" || it == "b" } ?: return true
        val seed = MessageDigest.getInstance("SHA-256").digest("meowpuut-skin test key request $which".toByteArray())
        try {
            val public = SkinNative.requestPublicKey(seed) ?: return true
            SkinKeystore.vault(context).addRequest(seed, public).wipe()
        } finally {
            java.util.Arrays.fill(seed, 0)
        }
        return true
    }
}

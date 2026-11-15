package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Where the custom skins live and the key that seals their secrets. */
object SkinKeystore {
    private const val ALIAS = "meowpuut.skin.vault.v1"
    @Volatile private var cached: SecretKey? = null

    /** The AES-256-GCM key in the Android Keystore, made on first use. Throws when the Keystore is unusable. */
    private fun key(): SecretKey {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(ALIAS, null) as? SecretKey
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
                generateKey()
            }
            cached = key
            return key
        }
    }

    fun vault(context: Context) = SkinVault(File(context.filesDir, "skinvault"), Sealer(::key))

    /** `filesDir/skins/`: `<id>/<rev>/` folders; the app's backup is disabled (AndroidManifest). */
    fun skinsDir(context: Context) = File(context.filesDir, "skins")
}

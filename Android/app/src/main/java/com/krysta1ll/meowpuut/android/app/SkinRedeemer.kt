package com.krysta1ll.meowpuut.android.app

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.krysta1ll.meowpuut.android.engine.SkinCore
import com.krysta1ll.meowpuut.android.engine.SkinNative
import com.krysta1ll.meowpuut.android.ime.RequestBook
import com.krysta1ll.meowpuut.android.ime.SkinKeystore
import com.krysta1ll.meowpuut.android.ime.SkinRef
import com.krysta1ll.meowpuut.android.ime.SkinRequest
import com.krysta1ll.meowpuut.android.ime.SkinVault
import java.io.ByteArrayOutputStream
import java.io.IOException

/** One entry of the installed list: verified by the core as the keyboard would, or the code it failed with. */
class InstalledSkin(val ref: SkinRef?, val names: Map<String, String>, val error: Int, val bundled: Boolean = false) {
    val ok get() = error == 0 && ref != null
    fun name(simplified: Boolean): String =
        names[if (simplified) "hans" else "hant"] ?: names["en"] ?: ref?.id ?: "?"
}

/**
 * Redeems, lists and removes custom skins for the settings page (not the keyboard).
 * The secrets stay in [SkinVault]; the byte arrays that carry them are wiped after each call.
 */
class SkinRedeemer(context: Context) {
    private val skins = SkinKeystore.skinsDir(context)
    private val vaultOrNull: SkinVault? = try { SkinKeystore.vault(context) } catch (_: Exception) { null }
    private val vault get() = vaultOrNull ?: throw IOException("no keystore")

    sealed interface Outcome {
        class Installed(val ref: SkinRef, val names: Map<String, String>, val warnings: Int) : Outcome
        class Revocations(val added: Int) : Outcome
        class Failed(val code: Int) : Outcome
    }

    /** False when the Keystore cannot be used on this phone: nothing can be requested or redeemed. */
    val available: Boolean get() = vaultOrNull != null

    /** The redemption ID of the newest request nobody has redeemed yet, or null. */
    fun openRequestId(): String? = try {
        val list = vault.requests()
        try {
            list.filter { !it.redeemed }.maxByOrNull { it.created }?.let { SkinNative.redemptionId(it.publicKey) }
        } finally {
            list.forEach { it.wipe() }
        }
    } catch (_: Exception) {
        null
    }

    /** Makes a new request key pair and returns its redemption ID. */
    fun newRequestId(): String? {
        var pair: ByteArray? = null
        var secret: ByteArray? = null
        return try {
            pair = SkinNative.requestKeyGenerate()
            if (pair == null || pair.size != 64) null else {
                secret = pair.copyOfRange(0, 32)
                val public = pair.copyOfRange(32, 64)
                vault.addRequest(secret, public).wipe()
                SkinNative.redemptionId(public)
            }
        } catch (_: Exception) {
            null
        } finally {
            // The key pair and its copy never outlive this call, whichever way it ends.
            pair?.let { java.util.Arrays.fill(it, 0) }
            secret?.let { java.util.Arrays.fill(it, 0) }
        }
    }

    /** Reads at most the largest package the core accepts; null when the file is over it (checked before and while reading). */
    fun readBounded(resolver: ContentResolver, uri: Uri): ByteArray? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) throw IOException("scheme")
        val max = SkinNative.maxPackageLen()
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0) && it.getLong(0) > max) return null
        }
        val stream = resolver.openInputStream(uri) ?: throw IOException("no stream")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > max) return null
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }
    }

    /** Installs a `.mpskin`, or merges a `.mprevoke`. */
    fun redeem(bytes: ByteArray): Outcome {
        if (!available) return Outcome.Failed(SkinCore.E_STORAGE)
        if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.ISO_8859_1) == "MPREVK") return mergeRevocations(bytes)
        var requests: List<SkinRequest> = emptyList()
        var secrets: ByteArray? = null
        return try {
            requests = vault.requests()
            secrets = RequestBook.secrets(requests)
            try {
                val result = SkinCore.parse(SkinNative.redeem(bytes, secrets!!, vault.state(), skins.path, SkinCore.CLIENT))
                if (result.code != 0) return Outcome.Failed(result.code)
                val skin = SkinRef.parse(result.json.optString("skin")) ?: return Outcome.Failed(SkinCore.E_INTERNAL)
                // The monotonic state first (highest revision per skin), then which request this skin used up.
                result.json.optJSONObject("state")?.let { vault.saveState(it.toString()) }
                for (request in requests) {
                    val bound = SkinCore.parse(SkinNative.verifyInstalled(skins.path, skin.toString(), vault.state(), request.publicKey))
                    if (bound.code == 0) vault.markRedeemed(request.publicKey)
                }
                val names = result.json.optJSONObject("names")?.let { n -> listOf("hant", "hans", "ja", "en").associateWith { n.optString(it) } }.orEmpty()
                Outcome.Installed(skin, names, result.json.optJSONArray("warnings")?.length() ?: 0)
            } finally {
                secrets?.let { java.util.Arrays.fill(it, 0) }
                requests.forEach { it.wipe() }
            }
        } catch (_: IOException) {
            Outcome.Failed(SkinCore.E_STORAGE)
        }
    }

    private fun mergeRevocations(bytes: ByteArray): Outcome = try {
        val result = SkinCore.parse(SkinNative.mergeRevocation(bytes, vault.state()))
        if (result.code != 0) Outcome.Failed(result.code) else {
            result.json.optJSONObject("state")?.let { vault.saveState(it.toString()) }
            Outcome.Revocations(result.json.optInt("added"))
        }
    } catch (_: IOException) {
        Outcome.Failed(SkinCore.E_STORAGE)
    }

    /** Every installed skin, each verified by the core. */
    fun list(): List<InstalledSkin> = try {
        val result = SkinCore.parse(SkinNative.listInstalled(skins.path, vault.state(), vault.publicKeys()))
        val array = if (result.code == 0) result.json.optJSONArray("skins") else null
        (0 until (array?.length() ?: 0)).mapNotNull { i ->
            val entry = array!!.optJSONObject(i) ?: return@mapNotNull null
            val ref = SkinRef.parse(entry.optString("skin"))
            if (entry.optBoolean("ok")) {
                val n = entry.optJSONObject("names")
                InstalledSkin(ref, listOf("hant", "hans", "ja", "en").associateWith { n?.optString(it).orEmpty() }, 0, entry.optString("kind") == "bundled")
            } else {
                InstalledSkin(ref, emptyMap(), entry.optInt("error", SkinCore.E_INTERNAL), entry.optString("kind") == "bundled")
            }
        }.sortedBy { it.ref?.id }
    } catch (_: Exception) {
        emptyList()
    }

    fun remove(ref: SkinRef): Boolean = SkinNative.remove(skins.path, ref.toString()) == 0
}

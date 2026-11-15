package com.krysta1ll.meowpuut.android.app

import android.content.Context
import android.util.Log
import com.krysta1ll.meowpuut.android.engine.SkinCore
import com.krysta1ll.meowpuut.android.engine.SkinNative
import com.krysta1ll.meowpuut.android.ime.Prefs
import com.krysta1ll.meowpuut.android.ime.SkinKeystore
import com.krysta1ll.meowpuut.android.ime.SkinRef
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Where the packages come from, the core that installs them and the two small stores: all replaceable in JVM tests. */
interface BundledSource {
    /** File names under assets/skins. */
    fun names(): List<String>
    fun open(name: String): InputStream
}

interface BundledCore {
    fun maxPackageLen(): Long
    fun install(pkg: ByteArray, stateJson: String, skinsDir: File, client: String): String
}

interface BundledState {
    fun state(): String
    fun saveState(json: String)
}

/** Which package (by SHA-256) was installed as which skin, so an unchanged APK does not reinstall every start. */
interface BundledStamps {
    fun get(sha256: String): String?
    fun put(sha256: String, skin: String)
}

/**
 * Developer-signed skins shipped in the APK (the .mpskinb files in assets/skins; a `.mpskin` name is also read, and the core
 * refuses it with E_KIND unless it is a bundled package). They go through the same Rust core as a redeemed skin:
 * signature, revocation, test-kid refusal in release, and the rollback guard (a revision below the one on the device
 * is skipped, not an error). Nothing here is a secret; a bundled skin has no request key and no remove action,
 * since it comes back with the next start.
 */
class BundledInstaller(
    private val source: BundledSource,
    private val core: BundledCore,
    private val state: BundledState,
    private val stamps: BundledStamps,
    private val skinsDir: File,
    private val client: String = SkinCore.CLIENT,
) {
    class Report(val installed: List<String>, val current: List<String>, val refused: Map<String, Int>, val changed: Boolean)

    fun installAll(): Report {
        val installed = ArrayList<String>()
        val current = ArrayList<String>()
        val refused = LinkedHashMap<String, Int>()
        var changed = false
        val max = core.maxPackageLen()
        val names = source.names().filter { it.endsWith(EXT) || it.endsWith(EXT_ALT) }.sorted()
        for (name in names) {
            val bytes = try { read(name, max) } catch (_: IOException) { null }
            if (bytes == null) {
                refused[name] = SkinCore.E_TOO_LARGE
                continue
            }
            val sha = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
            val stamped = stamps.get(sha)
            val ref = SkinRef.parse(stamped)
            // Already installed from these very bytes and still on disk: nothing to do (verify-on-load keeps checking it).
            if (ref != null && File(skinsDir, "${ref.id}/${ref.rev}/manifest.json").isFile) {
                current += stamped!!
                continue
            }
            val result = try {
                SkinCore.parse(core.install(bytes, state.state(), skinsDir, client))
            } catch (_: IOException) {
                refused[name] = SkinCore.E_STORAGE
                continue
            }
            if (result.code == SkinCore.E_DOWNGRADE) {
                // The device already has a newer revision of this skin (an earlier update or a redemption).
                refused[name] = result.code
                continue
            }
            if (result.code != 0) {
                refused[name] = result.code
                continue
            }
            val skin = result.json.optString("skin")
            if (SkinRef.parse(skin) == null) {
                refused[name] = SkinCore.E_INTERNAL
                continue
            }
            // The monotonic state first, as for a redemption; then the stamp.
            result.json.optJSONObject("state")?.let { state.saveState(it.toString()) }
            stamps.put(sha, skin)
            installed += skin
            changed = true
        }
        return Report(installed, current, refused, changed)
    }

    /** At most [max] bytes of the asset; null when it is larger (the core would refuse it). */
    private fun read(name: String, max: Long): ByteArray? {
        source.open(name).use { input ->
            val out = java.io.ByteArrayOutputStream()
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

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val DIR = "skins"
        const val EXT = ".mpskinb"
        const val EXT_ALT = ".mpskin"
    }
}

/** App start (MeowpuutApp) and the skins page: installs the bundled skins once, off the main thread. */
object BundledSkins {
    private const val TAG = "BundledSkins"
    private val lock = Any()

    /** Runs on a daemon thread; [done] is called on that thread with whether anything was installed. */
    fun installAsync(context: Context, done: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        Thread({
            val changed = try { install(app) } catch (failure: Throwable) {
                // Only the class of failure, never a skin's name or id.
                Log.w(TAG, "bundled skins not installed (${failure.javaClass.simpleName})")
                false
            }
            done?.invoke(changed)
        }, "meowpuut-bundled-skins").apply { isDaemon = true }.start()
    }

    fun install(context: Context): Boolean = synchronized(lock) {
        val assets = context.assets
        if (assets.list(BundledInstaller.DIR).isNullOrEmpty()) return false
        val vault = SkinKeystore.vault(context)
        val prefs = context.getSharedPreferences("meowpuut_bundled_skins", Context.MODE_PRIVATE)
        val report = BundledInstaller(
            source = object : BundledSource {
                override fun names() = assets.list(BundledInstaller.DIR)?.toList().orEmpty()
                override fun open(name: String) = assets.open("${BundledInstaller.DIR}/$name")
            },
            core = object : BundledCore {
                override fun maxPackageLen() = SkinNative.maxPackageLen()
                override fun install(pkg: ByteArray, stateJson: String, skinsDir: File, client: String) =
                    SkinNative.installBundled(pkg, stateJson, skinsDir.path, client)
            },
            state = object : BundledState {
                override fun state() = vault.state()
                override fun saveState(json: String) { vault.saveState(json) }
            },
            stamps = object : BundledStamps {
                override fun get(sha256: String) = prefs.getString(sha256, null)
                override fun put(sha256: String, skin: String) { prefs.edit().putString(sha256, skin).apply() }
            },
            skinsDir = SkinKeystore.skinsDir(context),
        ).installAll()
        for ((name, code) in report.refused) Log.w(TAG, "bundled package $name refused (code $code)")
        // New or changed skins and state: the keyboard verifies again.
        if (report.changed) Prefs(context).touchCustomSkins()
        report.changed
    }
}

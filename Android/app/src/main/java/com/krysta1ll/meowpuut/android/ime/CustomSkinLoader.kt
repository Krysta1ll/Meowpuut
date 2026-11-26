package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.krysta1ll.meowpuut.android.engine.SkinCore
import com.krysta1ll.meowpuut.android.engine.SkinNative
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/** A skin that passed verify-on-load and whose pictures were read: ready to hand to the views (main thread). */
class LoadedSkin(
    val overlay: SkinOverlay,
    val cats: Map<String, CatSprites.Art>,
    val end: Pair<SkinEnd, Bitmap>?,
    val sounds: SkinSoundSet?,
    val leftEnd: Pair<SkinEnd, Bitmap>? = null,
    val spaceMark: Pair<SkinEnd, Bitmap>? = null,
)

/**
 * Verify-on-load (plans/custom-skins.md §5.4) and the one place a custom skin's
 * files are read. It runs on its own thread, when a skin is applied or the
 * keyboard starts; the key-press path never calls it (SkinPathTest checks the
 * source). Any failure gives null and the keyboard keeps its built-in skin.
 * Nothing about the skin (name, id, serial, keys) is logged, only a result code.
 *
 * Each file is read once, right after the core's verify, and checked against the signed
 * list that verify returns (length and SHA-256); only those bytes are used (manifest,
 * pictures and sounds), never the path again, as on iOS (SEAL.md: use what was hashed).
 */
class CustomSkinLoader(context: Context) {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "meowpuut-skin").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    /** Loads [customSkin] ("<id>@<rev>") off the main thread and calls [done] on it; a newer call cancels an older one's answer. */
    fun load(customSkin: String?, done: (LoadedSkin?) -> Unit) {
        val mine = ++generation
        if (customSkin.isNullOrEmpty()) {
            done(null)
            return
        }
        executor.execute {
            val loaded = try { loadNow(context, customSkin) } catch (failure: Throwable) {
                Log.w(TAG, "custom skin not used (${failure.javaClass.simpleName})")
                null
            }
            main.post { if (mine == generation) done(loaded) }
        }
    }

    fun cancel() {
        generation++
    }

    fun shutdown() {
        generation++
        executor.shutdown()
    }

    companion object {
        private const val TAG = "MeowpuutSkin"
        private const val MAX_MANIFEST = 64 * 1024
        // The core's limits (meowpuut-skin lib.rs `limits`): FILE_COUNT, FILE, FILES_TOTAL.
        private const val MAX_FILES = 48
        private const val MAX_FILE = 1024 * 1024L
        private const val MAX_TOTAL = 4 * 1024 * 1024L
        private val FILE_NAME = Regex("[a-z0-9_-]{1,64}\\.(png|wav|json)")

        fun debuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

        fun supported(context: Context): Boolean = SkinNative.hasTrustedRoots() &&
            SkinOverlay.trustAllowed(debuggable(context), SkinNative.acceptsTestKeys())

        /** Blocking: verifies, then reads. Null on any failure. */
        fun loadNow(context: Context, customSkin: String): LoadedSkin? {
            val ref = SkinRef.parse(customSkin) ?: return reject("name")
            // A release build never trusts a skin signed by a test key, even if the library were built to.
            if (!supported(context)) return reject("trust")
            val vault = SkinKeystore.vault(context)
            val skins = SkinKeystore.skinsDir(context)
            val state = vault.state()
            val verified = SkinCore.parse(SkinNative.verifyInstalled(skins.path, ref.toString(), state, vault.publicKeys()))
            if (verified.code != 0) return reject("code ${verified.code}")
            // From here the folder is the one the core just verified; the names come from the re-parsed reference only.
            val dir = File(File(skins, ref.id), ref.rev.toString())
            // The bytes, read once and checked against the signed list the verify returned.
            val files = readVerified(dir, verified.json.optJSONArray("files")) ?: return reject("files")
            val manifest = files["manifest.json"]?.takeIf { it.size <= MAX_MANIFEST } ?: return reject("manifest")
            val overlay = SkinOverlay.parse(String(manifest, Charsets.UTF_8), ref) ?: return reject("manifest")

            val arts = LinkedHashMap<String, CatSprites.Art>()
            for ((name, entry) in overlay.sprites) {
                val bitmap = decode(files, entry.image, entry.sprite.width, entry.sprite.height) ?: return reject("image")
                arts[name] = CatSprites.Art(entry.sprite, bitmap)
            }
            for ((name, entry) in overlay.extraSprites) {
                val bitmap = decode(files, entry.image, entry.sprite.width, entry.sprite.height) ?: return reject("image")
                arts[name] = CatSprites.Art(entry.sprite, bitmap)
            }
            val end = overlay.rightEnd?.let { piece ->
                (piece to (decode(files, piece.image, piece.width, piece.height) ?: return reject("image")))
            }
            val leftEnd = overlay.leftEnd?.let { piece ->
                (piece to (decode(files, piece.image, piece.width, piece.height) ?: return reject("image")))
            }
            val spaceMark = overlay.spaceMark?.let { piece ->
                (piece to (decode(files, piece.image, piece.width, piece.height) ?: return reject("image")))
            }
            val sounds = overlay.sounds?.let { set ->
                val clips = set.files.values.flatten().associateWith { files["$it.wav"] ?: return reject("sound") }
                SkinSoundSet(dir.path, set, clips)
            }
            return LoadedSkin(overlay, arts, end, sounds, leftEnd, spaceMark)
        }

        /**
         * Reads every file of [list] (the core's `files`: `{"n","len","sha256"}`, from the signed header) from [dir]
         * once: a plain name, at most `len` bytes (checked before and while reading), and exactly that length and
         * SHA-256. Null when any file differs, so a file swapped after the core's verify is never used.
         */
        fun readVerified(dir: File, list: JSONArray?): Map<String, ByteArray>? {
            if (list == null || list.length() == 0 || list.length() > MAX_FILES) return null
            val out = HashMap<String, ByteArray>()
            var total = 0L
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: return null
                val name = entry.optString("n")
                val length = entry.optLong("len", -1)
                val sha = entry.optString("sha256")
                if (!FILE_NAME.matches(name) || name in out || length < 0 || length > MAX_FILE || sha.length != 64) return null
                total += length
                if (total > MAX_TOTAL) return null
                val bytes = readCapped(File(dir, name), length.toInt()) ?: return null
                if (hex(MessageDigest.getInstance("SHA-256").digest(bytes)) != sha) return null
                out[name] = bytes
            }
            return out
        }

        private fun readCapped(file: File, length: Int): ByteArray? = try {
            FileInputStream(file).use { input ->
                if (input.channel.size() != length.toLong()) return null
                val bytes = ByteArray(length)
                var got = 0
                while (got < length) {
                    val n = input.read(bytes, got, length - got)
                    if (n < 0) return null
                    got += n
                }
                if (input.read() != -1) return null // grew while being read
                bytes
            }
        } catch (_: Exception) {
            null
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        /** An image at 3 px per dp: its size must be exactly what the manifest says, as the installer made it. */
        private fun decode(files: Map<String, ByteArray>, stem: String, width: Float, height: Float): Bitmap? {
            if (!SkinRef.isName(stem)) return null
            val bytes = files["$stem.png"] ?: return null
            val options = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            if (bitmap.width != (width * 3).toInt() || bitmap.height != (height * 3).toInt()) return null
            bitmap.setHasMipMap(true)
            return bitmap
        }

        private fun reject(why: String): LoadedSkin? {
            Log.i(TAG, "custom skin not used ($why)")
            return null
        }
    }
}

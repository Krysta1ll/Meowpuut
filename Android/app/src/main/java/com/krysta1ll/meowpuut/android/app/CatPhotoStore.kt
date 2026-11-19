package com.krysta1ll.meowpuut.android.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

data class CatPhoto(
    val file: String,
    val width: Int,
    val height: Int,
    val cats: List<String>,
    val month: String? = null,
    val caption: String? = null,
) {
    val aspect: Float get() = if (height > 0) width.toFloat() / height.toFloat() else 1f
    val remoteUrl: String get() = "${CatPhotoStore.REMOTE_BASE_URL}/photos/$file"

    fun title(): String {
        if (!caption.isNullOrEmpty()) return caption
        val mimimo = "mimimo" in cats
        val momo = "momo" in cats
        return when {
            mimimo && momo -> "米米莫和莫莫"
            mimimo -> "米米莫"
            momo -> "莫莫"
            else -> "貓"
        }
    }

    val monthText: String?
        get() {
            val m = month ?: return null
            if (m.length != 7 || m[4] != '-') return null
            val year = m.substring(0, 4).toIntOrNull() ?: return null
            val num = m.substring(5, 7).toIntOrNull() ?: return null
            return "$year 年 $num 月"
        }

    fun subtitle(): String = listOfNotNull(title(), monthText).joinToString(" · ")
}

/**
 * Deterministic daily random photo rotation matching iOS `DailyPhotoOrder` bit-for-bit,
 * plus safe manifest validation preventing path traversal.
 */
object DailyPhotoOrder {
    private const val SEED_MASK = 0x4D45_4F57_5055_5554UL
    private const val GAMMA = 0x9E37_79B9_7F4A_7C15UL
    private const val MIX1 = 0xBF58_476D_1CE4_E5B9UL
    private const val MIX2 = 0x94D0_49BB_1331_11EBUL
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,128}$")

    fun isSafeFilename(name: String): Boolean {
        if (name.isEmpty() || name.contains("..") || !SAFE_NAME.matches(name)) return false
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    fun dayKey(year: Int, month: Int, day: Int): ULong {
        val y = max(0, year).toULong()
        val m = max(0, month).toULong()
        val d = max(0, day).toULong()
        return y * 10_000UL + m * 100UL + d
    }

    fun civilDay(year: Int, month: Int, day: Int): Int {
        val y = year - if (month <= 2) 1 else 0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    private fun nextMix(state: ULong): Pair<ULong, ULong> {
        val next = state + GAMMA
        var z = next
        z = (z xor (z shr 30)) * MIX1
        z = (z xor (z shr 27)) * MIX2
        z = z xor (z shr 31)
        return next to z
    }

    fun <T> shuffle(items: List<T>, key: (T) -> String, year: Int, month: Int, day: Int): List<T> {
        val result = items.sortedBy(key).toMutableList()
        val n = result.size
        if (n <= 1) return result
        val dayNum = civilDay(year, month, day)
        var state = dayNum.toLong().toULong() xor SEED_MASK
        for (i in n - 1 downTo 1) {
            val (nextState, rnd) = nextMix(state)
            state = nextState
            val j = (rnd % (i + 1).toULong()).toInt()
            if (i != j) {
                val tmp = result[i]
                result[i] = result[j]
                result[j] = tmp
            }
        }
        if (n == 2) {
            if ((dayNum and 1) != 0) {
                val tmp = result[0]
                result[0] = result[1]
                result[1] = tmp
            }
            return result
        }
        if ((dayNum and 1) != 0) {
            fun evenLead(evenDay: Int): String {
                var s = evenDay.toLong().toULong() xor SEED_MASK
                val arr = items.sortedBy(key).toMutableList()
                for (i in arr.size - 1 downTo 1) {
                    val (ns, r) = nextMix(s)
                    s = ns
                    val j = (r % (i + 1).toULong()).toInt()
                    if (i != j) {
                        val tmp = arr[i]
                        arr[i] = arr[j]
                        arr[j] = tmp
                    }
                }
                return key(arr[0])
            }
            val prevLead = evenLead(dayNum - 1)
            val nextLead = evenLead(dayNum + 1)
            val curLead = key(result[0])
            if (curLead == prevLead || curLead == nextLead) {
                val rep = (1 until n).firstOrNull {
                    val k = key(result[it])
                    k != prevLead && k != nextLead
                }
                if (rep != null) {
                    val tmp = result[0]
                    result[0] = result[rep]
                    result[rep] = tmp
                }
            }
        }
        return result
    }

    fun shuffleToday(photos: List<CatPhoto>, calendar: Calendar = Calendar.getInstance()): List<CatPhoto> =
        shuffle(
            photos,
            CatPhoto::file,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )

    fun parseManifest(raw: String): List<CatPhoto>? = runCatching {
        val root = JSONObject(raw)
        if (root.optInt("version", -1) != 1) return null
        val arr = root.optJSONArray("photos") ?: return null
        val seen = HashSet<String>()
        val list = ArrayList<CatPhoto>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val file = obj.optString("file", "")
            val w = obj.optInt("width", 0)
            val h = obj.optInt("height", 0)
            if (!isSafeFilename(file) || w <= 0 || h <= 0 || !seen.add(file)) continue
            val catsArr = obj.optJSONArray("cats")
            val cats = ArrayList<String>(2)
            if (catsArr != null) {
                for (j in 0 until catsArr.length()) {
                    val c = catsArr.optString(j, "")
                    if (c.isNotEmpty()) cats += c
                }
            }
            val month = obj.optString("month", "").ifEmpty { null }
            val caption = obj.optString("caption", "").ifEmpty { null }
            list += CatPhoto(file, w, h, cats, month, caption)
        }
        list
    }.getOrNull()
}

class CatPhotoStore(context: Context) {
    private val cacheDir = File(context.applicationContext.cacheDir, "cat_photos")
    private val manifestFile = File(cacheDir, "manifest.json")
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "meowpuut-photo-loader").apply { isDaemon = true }
    }
    private val inFlight = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val bitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Volatile
    private var shutDown = false

    fun loadCachedDailyPhotos(): List<CatPhoto> {
        if (!manifestFile.isFile) return emptyList()
        val raw = runCatching { manifestFile.readText(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        val parsed = DailyPhotoOrder.parseManifest(raw) ?: return emptyList()
        return DailyPhotoOrder.shuffleToday(parsed)
    }

    fun refreshRemote(onResult: (photos: List<CatPhoto>, fromNetwork: Boolean) -> Unit) {
        if (shutDown) return
        executor.execute {
            val remoteJson = fetchUtf8(REMOTE_MANIFEST_URL)
            val remoteList = remoteJson?.let(DailyPhotoOrder::parseManifest)
            if (remoteJson != null && !remoteList.isNullOrEmpty()) {
                writeAtomic(manifestFile, remoteJson.toByteArray(Charsets.UTF_8))
                val ordered = DailyPhotoOrder.shuffleToday(remoteList)
                postUi { onResult(ordered, true) }
            } else {
                val cached = loadCachedDailyPhotos()
                postUi { onResult(cached, false) }
            }
        }
    }

    fun loadBitmap(photo: CatPhoto, targetMaxPx: Int, onLoaded: (Bitmap?) -> Unit) {
        if (shutDown || !DailyPhotoOrder.isSafeFilename(photo.file)) {
            onLoaded(null)
            return
        }
        val key = "${photo.file}|$targetMaxPx"
        bitmaps.get(key)?.let { cached ->
            onLoaded(cached)
            return
        }
        val listeners = inFlight.computeIfAbsent(key) { mutableListOf() }
        synchronized(listeners) {
            listeners += onLoaded
            if (listeners.size > 1) return
        }
        executor.execute {
            val file = ensureDiskFile(photo)
            val bmp = file?.let { decodeSampled(it, targetMaxPx) }
            if (bmp != null) bitmaps.put(key, bmp)
            val pending = inFlight.remove(key)
            val callbacks = if (pending != null) synchronized(pending) { pending.toList() } else emptyList()
            postUi {
                for (cb in callbacks) cb(bmp)
            }
        }
    }

    private fun ensureDiskFile(photo: CatPhoto): File? {
        val target = File(cacheDir, photo.file)
        if (target.isFile && target.length() > 4L) return target
        val bytes = fetchBytes(photo.remoteUrl, MAX_IMAGE_BYTES) ?: return null
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        writeAtomic(target, bytes)
        return target.takeIf { it.isFile }
    }

    private fun decodeSampled(file: File, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        val longSide = max(w, h)
        while (longSide / sample > maxPx * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeFile(file.absolutePath, opts)
    }.getOrNull()

    private fun fetchUtf8(urlStr: String): String? =
        fetchBytes(urlStr, MAX_MANIFEST_BYTES)?.toString(Charsets.UTF_8)

    private fun fetchBytes(urlStr: String, limit: Int): ByteArray? = runCatching {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 12_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", "*/*")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                var total = 0
                val out = java.io.ByteArrayOutputStream()
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    total += read
                    if (total > limit) return null
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun writeAtomic(dest: File, bytes: ByteArray) {
        runCatching {
            cacheDir.mkdirs()
            val tmp = File(cacheDir, ".tmp-${java.util.UUID.randomUUID()}-${dest.name}")
            FileOutputStream(tmp).use { it.write(bytes) }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        }
    }

    private inline fun postUi(crossinline block: () -> Unit) {
        if (!shutDown) mainHandler.post { if (!shutDown) block() }
    }

    fun shutdown() {
        shutDown = true
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    companion object {
        const val REMOTE_BASE_URL = "https://storage.googleapis.com/meowpuut-hk-photos-0285872710"
        const val REMOTE_MANIFEST_URL = "$REMOTE_BASE_URL/photos.json"
        private const val MAX_MANIFEST_BYTES = 512 * 1024
        private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
    }
}

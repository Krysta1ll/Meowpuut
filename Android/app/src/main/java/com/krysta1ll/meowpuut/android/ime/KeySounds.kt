package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import com.krysta1ll.meowpuut.android.R
import java.io.File

enum class KeySound(val label: Int) {
    OFF(R.string.sound_off), SYSTEM(R.string.sound_system),
    WATER(R.string.sound_water), MARIMBA(R.string.sound_marimba), GLASS(R.string.sound_glass),
    FELT(R.string.sound_felt), PENTA(R.string.sound_penta), HARMONIC(R.string.sound_harmonic),
    TAP(R.string.sound_tap);

    val key: String get() = name.lowercase()
}

/**
 * A custom skin's sounds: by role ([sounds]), and the WAV bytes the loader read and checked against the
 * signed list ([clips], by stem). They are loaded from these bytes, never from the folder again.
 */
class SkinSoundSet(val folder: String, val sounds: SkinSounds, val clips: Map<String, ByteArray>)

class KeySounds(private val context: Context) {
    private enum class Kind { OFF, SYSTEM, PRESET, SKIN }

    private val audio = context.getSystemService(AudioManager::class.java)
    /** The person's choice; a verified skin's sounds replace any choice except off. */
    private var selected = KeySound.OFF
    private var skin: SkinSoundSet? = null
    private var kind = Kind.OFF
    private var loadedKey: String? = null
    private var pool: SoundPool? = null
    private val samples = mutableMapOf<String, MutableList<Int>>()
    private val ready = mutableSetOf<Int>()
    private var variation = 0
    private var volume = 1f
    private var gain = 1f
    private var cachedRingerMode = AudioManager.RINGER_MODE_NORMAL
    private var lastRingerCheckMs = -2_000L

    fun refreshSystemState() {
        cachedRingerMode = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        lastRingerCheckMs = SystemClock.uptimeMillis()
    }

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f
    }

    fun update(value: KeySound) {
        selected = value
        reload()
    }

    /** Called when a custom skin is applied or dropped (never from a key press). */
    fun useSkin(set: SkinSoundSet?) {
        if (set == skin) return
        skin = set
        reload()
    }

    private fun reload() {
        val set = skin
        val want = when {
            selected == KeySound.OFF -> Kind.OFF
            set != null -> Kind.SKIN
            selected == KeySound.SYSTEM -> Kind.SYSTEM
            else -> Kind.PRESET
        }
        val key = when (want) {
            Kind.SKIN -> "skin:${set?.folder}"
            Kind.PRESET -> "preset:${selected.key}"
            else -> want.name
        }
        if (key == loadedKey) return
        release()
        loadedKey = key
        kind = want
        gain = if (want == Kind.SKIN) set?.sounds?.gain ?: 1f else 1f
        when (want) {
            Kind.PRESET -> {
                val folder = "key-sounds/${selected.key}"
                val files = context.assets.list(folder).orEmpty().sorted()
                open(files) { file, player -> context.assets.openFd("$folder/$file").use { player.load(it, 1) } }
            }
            Kind.SKIN -> if (set != null) {
                val roles = set.sounds.files
                open(roles.values.flatten().map { "$it.wav" }) { file, player -> loadBytes(player, set.clips[file.removeSuffix(".wav")]) }
            }
            else -> Unit
        }
    }

    /**
     * SoundPool has no load from memory: the verified bytes go into a file in the app's cache that is unlinked
     * before it is loaded, so only this open descriptor (which SoundPool duplicates) ever reaches them.
     */
    private fun loadBytes(player: SoundPool, bytes: ByteArray?): Int {
        if (bytes == null) return 0
        val temp = File.createTempFile("skin-sound", ".wav", context.cacheDir)
        try {
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_WRITE).use { pfd ->
                temp.delete()
                var written = 0
                while (written < bytes.size) written += Os.write(pfd.fileDescriptor, bytes, written, bytes.size - written)
                return player.load(pfd.fileDescriptor, 0, bytes.size.toLong(), 1)
            }
        } finally {
            temp.delete()
        }
    }

    private fun open(files: List<String>, load: (String, SoundPool) -> Int) {
        val player = SoundPool.Builder().setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        pool = player
        player.setOnLoadCompleteListener { source, id, status ->
            if (source === pool && status == 0) ready.add(id)
        }
        for (file in files) {
            val id = runCatching { load(file, player) }.getOrDefault(0)
            if (id != 0) samples.getOrPut(file.substringBefore('-').removeSuffix(".wav")) { mutableListOf() }.add(id)
        }
    }

    /** Memory only: the samples were loaded when the sound setting or the skin changed. */
    fun play(effect: Int, fraction: Float = 0.5f, pick: Boolean = false) {
        if (kind == Kind.OFF || volume == 0f) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRingerCheckMs >= 1_500L) refreshSystemState()
        if (cachedRingerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (kind == Kind.SYSTEM) {
            // The keyboard has its own sound setting, separate from Android's touch sounds.
            audio.playSoundEffect(effect, volume)
            return
        }
        val tones = kind == Kind.PRESET && selected == KeySound.PENTA || kind == Kind.SKIN && samples.containsKey("key0")
        val role = when {
            pick -> "pick"
            effect == AudioManager.FX_KEYPRESS_SPACEBAR -> "space"
            effect == AudioManager.FX_KEYPRESS_DELETE -> "delete"
            effect == AudioManager.FX_KEYPRESS_RETURN -> "return"
            tones -> "key${(fraction * 6).toInt().coerceIn(0, 5)}"
            else -> "key"
        }
        // A skin may leave out the special keys' own sounds: they then use its ordinary key sound.
        val ids = samples[role] ?: (if (kind == Kind.SKIN) samples["key"] ?: samples["key0"] else null) ?: return
        val id = ids[variation % ids.size]
        variation = (variation + 1) and Int.MAX_VALUE
        val level = volume * gain
        if (id in ready) pool?.play(id, level, level, 0, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
        samples.clear()
        ready.clear()
        loadedKey = null
        kind = Kind.OFF
    }
}

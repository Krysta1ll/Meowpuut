package com.krysta1ll.meowpuut.android.ime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.krysta1ll.meowpuut.android.R

enum class KeySound(val label: Int) {
    OFF(R.string.sound_off), SYSTEM(R.string.sound_system),
    WATER(R.string.sound_water), MARIMBA(R.string.sound_marimba), GLASS(R.string.sound_glass),
    FELT(R.string.sound_felt), PENTA(R.string.sound_penta), HARMONIC(R.string.sound_harmonic),
    TAP(R.string.sound_tap);

    val key: String get() = name.lowercase()
}

class KeySounds(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var preset = KeySound.OFF
    private var pool: SoundPool? = null
    private val samples = mutableMapOf<String, MutableList<Int>>()
    private val ready = mutableSetOf<Int>()
    private var variation = 0
    private var volume = 1f

    fun setVolume(percent: Int) {
        volume = percent.coerceIn(0, 100) / 100f
    }

    fun update(value: KeySound) {
        if (value == preset) return
        release()
        preset = value
        if (value == KeySound.OFF || value == KeySound.SYSTEM) return
        val folder = "key-sounds/${value.key}"
        val files = context.assets.list(folder).orEmpty().sorted()
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
            val id = runCatching {
                context.assets.openFd("$folder/$file").use { player.load(it, 1) }
            }.getOrDefault(0)
            if (id != 0) samples.getOrPut(file.substringBefore('-')) { mutableListOf() }.add(id)
        }
    }

    fun play(effect: Int, fraction: Float = 0.5f, pick: Boolean = false) {
        if (preset == KeySound.OFF || volume == 0f || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (preset == KeySound.SYSTEM) {
            // The keyboard has its own sound setting, separate from Android's touch sounds.
            audio.playSoundEffect(effect, volume)
            return
        }
        val role = when {
            pick -> "pick"
            effect == AudioManager.FX_KEYPRESS_SPACEBAR -> "space"
            effect == AudioManager.FX_KEYPRESS_DELETE -> "delete"
            effect == AudioManager.FX_KEYPRESS_RETURN -> "return"
            preset == KeySound.PENTA -> "key${(fraction * 6).toInt().coerceIn(0, 5)}"
            else -> "key"
        }
        val ids = samples[role] ?: return
        val id = ids[variation % ids.size]
        variation = (variation + 1) and Int.MAX_VALUE
        if (id in ready) pool?.play(id, volume, volume, 0, 0, 1f)
    }

    fun release() {
        pool?.release()
        pool = null
        samples.clear()
        ready.clear()
        preset = KeySound.OFF
    }
}

package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.KeySound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10

class KeySoundTest {
    private fun soundDir(setId: String): File = listOf("../../assets/key-sounds", "../assets/key-sounds", "assets/key-sounds")
        .map { File(it, setId) }
        .first { it.isDirectory }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)

    private fun s16(b: ByteArray, off: Int): Int = u16(b, off).toShort().toInt()

    @Test
    fun thockEnumAndPositionalRoutingAreConfigured() {
        assertEquals("thock", KeySound.THOCK.key)
        assertTrue(KeySound.THOCK.usesKeyPosition)
        assertTrue(KeySound.PENTA.usesKeyPosition)
        assertFalse(KeySound.TAP.usesKeyPosition)
    }

    @Test
    fun thockWavsMeetAcousticAndFormatSpecs() {
        val dir = soundDir("thock")
        val expected = buildList {
            for (c in 0..5) {
                add("key$c-1.wav")
                add("key$c-2.wav")
            }
            addAll(listOf("space-1.wav", "space-2.wav", "space-3.wav"))
            addAll(listOf("delete-1.wav", "delete-2.wav", "delete-3.wav"))
            add("return-1.wav")
            addAll(listOf("pick-1.wav", "pick-2.wav", "pick-3.wav"))
        }
        for (name in expected) {
            val bytes = File(dir, name).readBytes()
            assertTrue("$name header", bytes.size > 44)
            assertEquals("RIFF", String(bytes, 0, 4))
            assertEquals("WAVE", String(bytes, 8, 4))
            assertEquals(1, u16(bytes, 20))
            assertEquals(1, u16(bytes, 22))
            assertEquals(44100L, u32(bytes, 24))
            assertEquals(16, u16(bytes, 34))
            val dataBytes = u32(bytes, 40).toInt()
            val samples = dataBytes / 2
            assertEquals(44 + dataBytes, bytes.size)
            assertEquals("$name start zero", 0, s16(bytes, 44))
            assertEquals("$name end zero", 0, s16(bytes, 44 + (samples - 1) * 2))
            var sum = 0.0
            var peak = 0
            for (i in 0 until samples) {
                val v = s16(bytes, 44 + i * 2)
                sum += v / 32767.0
                val av = abs(v)
                if (av > peak) peak = av
            }
            val dc = abs(sum / samples)
            val peakDb = 20.0 * log10(peak / 32767.0)
            assertTrue("$name DC offset $dc", dc < 1e-4)
            assertTrue("$name peak $peakDb dBFS", peakDb <= -10.0)
        }
    }
}

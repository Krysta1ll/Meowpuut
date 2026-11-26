package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.KeyParticleKind
import com.krysta1ll.meowpuut.android.ime.KeyParticlePool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyParticlesTest {
    @Test
    fun spawnsStepsAndExpiresWithoutAllocation() {
        val pool = KeyParticlePool()
        val palette = intArrayOf(0xFFFFB7C5.toInt(), 0xFFFFD4DF.toInt())
        pool.configure(KeyParticleKind.PETAL, palette, fullEffects = true)
        assertFalse(pool.isRunning)

        pool.spawn(120f, 80f, density = 1f)
        assertEquals(2, pool.activeCount())
        assertTrue(pool.isRunning)

        // Advance simulation past max lifetime (~1.25s).
        repeat(80) { pool.step(0.02f, density = 1f) }
        assertEquals(0, pool.activeCount())
        assertFalse(pool.isRunning)
    }

    @Test
    fun disabledEffectsNeverSpawnAndClearingEmptiesPool() {
        val pool = KeyParticlePool()
        val palette = intArrayOf(0xFFFFFFFF.toInt())
        pool.configure(KeyParticleKind.SNOW, palette, fullEffects = false)
        pool.spawn(50f, 50f, density = 1f)
        assertEquals(0, pool.activeCount())

        pool.configure(KeyParticleKind.SPARKLE, palette, fullEffects = true)
        pool.spawn(50f, 50f, density = 1f)
        assertEquals(2, pool.activeCount())
        pool.clear()
        assertEquals(0, pool.activeCount())
    }

    @Test
    fun guardsNonFiniteAndRespectsPoolCapacity() {
        val pool = KeyParticlePool()
        val palette = intArrayOf(0xFFFFB7C5.toInt())
        pool.configure(KeyParticleKind.PETAL, palette, fullEffects = true)
        pool.spawn(Float.NaN, 10f, density = 1f)
        pool.spawn(10f, 10f, density = 0f)
        assertEquals(0, pool.activeCount())

        repeat(30) { pool.spawn(100f, 100f, density = 2f) }
        assertEquals(44, pool.activeCount())
        assertTrue(pool.step(Float.NaN, 1f))
        assertTrue(pool.step(-0.1f, 1f))
        assertEquals(44, pool.activeCount())
    }
}

package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.ime.AlternatesStrip
import com.krysta1ll.meowpuut.android.ime.Box
import com.krysta1ll.meowpuut.android.ime.Idiom
import com.krysta1ll.meowpuut.android.ime.Key
import com.krysta1ll.meowpuut.android.ime.KeyAction
import com.krysta1ll.meowpuut.android.ime.KeyBuilder
import com.krysta1ll.meowpuut.android.ime.KeyContext
import com.krysta1ll.meowpuut.android.ime.KeyGrid
import com.krysta1ll.meowpuut.android.ime.KeyListener
import com.krysta1ll.meowpuut.android.ime.KeyMetrics
import com.krysta1ll.meowpuut.android.ime.KeyPlacement
import com.krysta1ll.meowpuut.android.ime.KeyTouches
import com.krysta1ll.meowpuut.android.ime.KeyTouchFlags
import com.krysta1ll.meowpuut.android.ime.KeyTouchGridSoA
import com.krysta1ll.meowpuut.android.ime.RowAlignment
import com.krysta1ll.meowpuut.android.ime.RowSpec
import com.krysta1ll.meowpuut.android.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyTouchModelTest {
    private class SplitMix64(private var state: Long) {
        fun nextLong(): Long {
            state += -0x61c8864680b583ebL
            var z = state
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        fun unit(): Float = ((nextLong() ushr 40).toDouble() / (1L shl 24).toDouble()).toFloat()
    }

    private fun context(
        mode: InputMode = InputMode.PINYIN, english: Boolean = false, page: Int = 0,
        globe: Boolean = false, composing: Boolean = false,
    ) = KeyContext(mode, english, page, ShiftState.OFF, false, globe, "空格", "確認", true, true, composing = composing)

    @Test
    fun emptyBoundsAndCapacityGrowthAreHandledCleanly() {
        val grid = KeyTouchGridSoA()
        assertEquals(0, grid.count)
        assertEquals(0, grid.rowCount)
        assertEquals(-1, grid.resolveHit(10f, 10f))
        assertEquals(-1, grid.resolveDown(10f, 10f))
        assertEquals(-1, grid.rowOf(-1))
        assertEquals(-1, grid.columnOf(0))
        assertEquals(-1, grid.indexOf(-1, 0))
        assertEquals(-1, grid.indexOf(0, -1))
        assertEquals(-1, grid.resolveCharacterSlide(-1, 0f, 0f, 5f, 5f, true))
        assertFalse(grid.isWithinReleaseSlop(-1, 0f, 0f))
        assertFalse(grid.isValidToneSwipe(-1, 0f, 30f, 0f, 0f))

        grid.rebuild(listOf(emptyList())) { _, _ -> 0 }
        assertEquals(1, grid.rowCount)
        assertEquals(-1, grid.resolveHit(5f, 5f))

        // Exercise >8 rows and >64 keys so SoA array growth is covered.
        val denseSpecs = List(10) { RowSpec(List(8) { 1f }, RowAlignment.CENTERED) }
        val densePlaced = KeyGrid.place(denseSpecs, 393f, 8, KeyMetrics.of(Idiom.PHONE, 393f, 10))
        grid.rebuild(densePlaced) { _, _ -> KeyTouchFlags.pack(true, false, true) }
        assertEquals(80, grid.count)
        assertEquals(10, grid.rowCount)
        assertEquals(79, grid.indexOf(9, 7))
        assertEquals(-1, grid.indexOf(9, 8))
    }

    @Test
    fun defensiveUtilityMarginsRescueCharacterEdgesWithoutShrinkingUtilityCaps() {
        val width = 393f
        val set = KeyBuilder.build(context())
        val metrics = KeyMetrics.of(Idiom.PHONE, width, set.rows.size)
        val placed = KeyGrid.place(set.specs, width, set.referenceColumns, metrics)
        val grid = KeyTouchGridSoA().also { it.rebuild(placed, set) }

        val shift = placed[2][0].frame
        val z = placed[2][1].frame
        val m = placed[2][7].frame
        val del = placed[2][8].frame
        val symbols = placed[3][0].frame

        // Visible utility keycaps + 3 dp cushion stay utility.
        assertEquals(0, grid.columnOf(grid.resolveDown(shift.centerX, shift.centerY)))
        assertEquals(0, grid.columnOf(grid.resolveDown(shift.right + 2.5f, shift.centerY)))
        assertEquals(8, grid.columnOf(grid.resolveDown(del.left - 2.5f, del.centerY)))
        assertEquals(8, grid.columnOf(grid.resolveDown(width + 15f, del.centerY)))
        assertEquals(0, grid.columnOf(grid.resolveDown(-15f, shift.centerY)))

        // Oversized split-edge gutter near Z and M triggers Z and M instead of Shift / Delete.
        val hitZ = grid.resolveDown(z.left - 7.5f, z.centerY)
        assertEquals(2 to 1, grid.rowOf(hitZ) to grid.columnOf(hitZ))
        val hitM = grid.resolveDown(m.right + 7.5f, m.centerY)
        assertEquals(2 to 7, grid.rowOf(hitM) to grid.columnOf(hitM))

        // Vertical 11 dp rowGap below Z triggers Z; top rim of 符號 triggers 符號.
        val belowZ = grid.resolveDown(z.centerX, z.bottom + 6.5f)
        assertEquals(2 to 1, grid.rowOf(belowZ) to grid.columnOf(belowZ))
        val rimSymbols = grid.resolveDown(symbols.centerX, symbols.top - 2f)
        assertEquals(3 to 0, grid.rowOf(rimSymbols) to grid.columnOf(rimSymbols))

        // Top seam dead-strip extension above Row 0, and bottom clamping below last row.
        assertEquals(-1, grid.resolveDown(placed[0][0].frame.centerX, -3f, allowTopMargin = 0f))
        assertEquals(0, grid.rowOf(grid.resolveDown(placed[0][0].frame.centerX, -3f, allowTopMargin = 4f)))
        assertEquals(3, grid.rowOf(grid.resolveDown(placed[3][2].frame.centerX, metrics.areaHeight(4) + 12f)))
    }

    @Test
    fun hysteresisReleaseSlopModifierSlideAndToneGuardWorkEndToEnd() {
        val events = ArrayList<Key>()
        var pageChanges = 0
        val host = object : KeyTouches.Host {
            override fun post(delayMs: Long, action: Runnable) = Unit
            override fun cancel(action: Runnable) = Unit
            override fun pulseKeyPress(row: Int, column: Int) = Unit
            override fun showBalloon(id: Int, key: Key, frame: Box) = Unit
            override fun hideBalloon(id: Int) = Unit
            override fun showAlternates(strip: AlternatesStrip, options: List<String>, selected: Int) = Unit
            override fun selectAlternate(index: Int) = Unit
            override fun hideAlternates() = Unit
            override fun clearVisuals() = Unit
            override fun redraw() = Unit
        }
        val touches = KeyTouches(host).apply {
            topTouchExtension = 4f
            listener = object : KeyListener {
                override fun onKeyDown(key: Key, fraction: Float) = Unit
                override fun onKey(key: Key, text: String?) { events += key }
                override fun onDelete(word: Boolean) = Unit
                override fun onShiftDown() = Unit
                override fun onShiftUp(typed: Boolean) = Unit
                override fun onPage(page: Int) { pageChanges++ }
                override fun onCursorStart() = Unit
                override fun onCursorMove(steps: Int) = Unit
                override fun onCursorEnd() = Unit
                override fun onTick() = Unit
            }
        }
        val set = KeyBuilder.build(context(globe = true))
        val metrics = KeyMetrics.of(Idiom.PHONE, 393f, set.rows.size)
        val rows = KeyGrid.place(set.specs, 393f, set.referenceColumns, metrics)
        touches.layout(set, rows, 393f, sameShape = false)

        // 1) Pressing Return near its left edge and rolling 9 dp onto Space on lift STILL commits Return!
        val enterHit = rows[3][4].hitFrame
        touches.down(0, enterHit.left + 0.5f, enterHit.centerY)
        touches.up(0, enterHit.left - 9f, enterHit.centerY)
        assertEquals(KeyAction.Return, events.last().action)

        // 2) Pressing ToggleEnglish near its bottom edge and lifting 8 dp below the keyboard STILL commits!
        val toggleHit = rows[3][1].hitFrame
        touches.down(0, toggleHit.centerX, toggleHit.bottom - 1f)
        touches.up(0, toggleHit.centerX, toggleHit.bottom + 8f)
        assertEquals(KeyAction.ToggleEnglish, events.last().action)

        // 3) Pressing Shift near its right edge and rolling 6 dp (< 10 dp) into Z does NOT type Z!
        val countBeforeShift = events.size
        val shiftFrame = rows[2][0].frame
        touches.down(0, shiftFrame.right + 1f, shiftFrame.centerY)
        touches.move(0, shiftFrame.right + 6f, shiftFrame.centerY)
        touches.up(0, shiftFrame.right + 6f, shiftFrame.centerY)
        assertEquals(countBeforeShift, events.size)

        // 4) Pressing bottom edge of 'i' (row 0, col 7) and rolling up 21 dp inside the keycap types 'i', NOT tone!
        val iFrame = rows[0][7].frame
        touches.down(0, iFrame.centerX, iFrame.bottom + 4f)
        touches.up(0, iFrame.centerX, iFrame.bottom + 4f - 21f)
        assertEquals(KeyAction.Engine('i'.code), events.last().action)

        // 5) Genuine upward flick from 'i' center triggers the Pinyin tone action.
        touches.down(0, iFrame.centerX, iFrame.centerY)
        touches.up(0, iFrame.centerX, iFrame.centerY - 22f)
        val tone = events.last().action as KeyAction.PinyinTone
        assertEquals('5', tone.digit)

        // 6) Top seam touch at y = -3 dp (below candidate glass) routes to Row 0 ('q').
        touches.down(0, rows[0][0].frame.centerX, -3f)
        touches.up(0, rows[0][0].frame.centerX, -3f)
        assertEquals(KeyAction.Engine('q'.code), events.last().action)
        assertEquals(0, pageChanges)
    }

    @Test
    fun parityAcross100RandomSeedsWithGeometricGrid() {
        val configs = listOf(
            context(InputMode.PINYIN, english = false, page = 0),
            context(InputMode.PINYIN, english = true, page = 0),
            context(InputMode.ZHUYIN, english = false, page = 0),
            context(InputMode.NINE_KEY, english = false, page = 0, composing = true),
            context(InputMode.PINYIN, english = false, page = 1),
        )
        val widths = listOf(320f, 360f, 393f, 430f, 768f)
        for (seed in 1L..100L) {
            val rng = SplitMix64(seed)
            val cfg = configs[(seed % configs.size).toInt()]
            val width = widths[((seed / 5) % widths.size).toInt()]
            val idiom = if (width >= 600f) Idiom.TABLET else Idiom.PHONE
            val set = KeyBuilder.build(cfg.copy(needsGlobe = seed % 2L == 0L))
            val metrics = KeyMetrics.of(idiom, width, set.rows.size)
            val placed = KeyGrid.place(set.specs, width, set.referenceColumns, metrics)
            val totalH = metrics.areaHeight(set.rows.size)
            val grid = KeyTouchGridSoA().also { it.rebuild(placed, set) }

            repeat(250) {
                val x = -20f + rng.unit() * (width + 40f)
                val y = -5f + rng.unit() * (totalH + 15f)
                val expected = KeyGrid.keyAt(x, y, placed)
                val hit = grid.resolveHit(x, y)
                val actual = if (hit >= 0) grid.rowOf(hit) to grid.columnOf(hit) else null
                assertEquals("seed $seed at ($x, $y)", expected, actual)
                if (expected != null) {
                    val down = grid.resolveDown(x, y)
                    val downPair = grid.rowOf(down) to grid.columnOf(down)
                    val baseKey = set.rows[expected.first][expected.second]
                    if (baseKey.isCharacter || baseKey.action == KeyAction.Space || baseKey.action == KeyAction.Segment) {
                        assertEquals("seed $seed character/space parity at ($x, $y)", expected, downPair)
                    }
                }
            }
        }
    }
}

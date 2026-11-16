package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.Candidate
import com.krysta1ll.meowpuut.android.engine.CandidateKind
import com.krysta1ll.meowpuut.android.engine.ChoiceMemory
import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.engine.NineKeySyllables
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
import com.krysta1ll.meowpuut.android.ime.KeyTouches
import com.krysta1ll.meowpuut.android.ime.ShiftState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NineKeyOverhaulTest {
    @Test
    fun progressiveSyllableLockingAndBackspaceUnlock() {
        val raw = "9264468482"
        val first = NineKeySyllables.leading(raw)
        assertTrue(first.take(3).containsAll(listOf("wang", "yang", "zang")))

        val step1 = NineKeySyllables.lock("wang", raw)
        assertEquals("WANG'468482", step1)
        val second = NineKeySyllables.leading(step1!!)
        assertEquals("hou", second.first())
        assertTrue("gou" in second)

        val step2 = NineKeySyllables.lock("hou", step1)
        assertEquals("WANG'HOU'482", step2)
        val third = NineKeySyllables.leading(step2!!)
        assertEquals(listOf("hua", "gua"), third.take(2))

        // While trailing digits remain, Backspace deletes digits first; once at a locked boundary, it unlocks.
        assertNull(NineKeySyllables.unlockLastOnBackspace(step2))
        val unlocked1 = NineKeySyllables.unlockLastOnBackspace("WANG'HOU'")
        assertEquals("WANG'468", unlocked1)
        val unlocked2 = NineKeySyllables.unlockLastOnBackspace("WANG'")
        assertEquals("9264", unlocked2)
        assertNull(NineKeySyllables.unlockLastOnBackspace("9264"))

        assertEquals(listOf("s", "q", "r", "p"), NineKeySyllables.leading("7"))
    }

    @Test
    fun nineKeyMarksAndDigitLetterAlternates() {
        val set = KeyBuilder.build(
            KeyContext(InputMode.NINE_KEY, false, 0, ShiftState.OFF, false, false, "空格", "換行", false, true)
        )
        assertEquals(listOf("，", "。", "？"), set.rows.take(3).map { it.first().label })
        assertTrue("！" in set.rows[0][0].alternates)
        assertTrue("！" in set.rows[2][0].alternates)

        val pqrs = set.rows.take(3).flatten().single { it.label == "PQRS" }
        assertEquals(listOf("7", "p", "q", "r", "s"), pqrs.alternates)
        val wxyz = set.rows.take(3).flatten().single { it.label == "WXYZ" }
        assertEquals(listOf("9", "w", "x", "y", "z"), wxyz.alternates)
    }

    @Test
    fun holdingNineKeyDigitKeepsNumberAtZeroAndSlidesToLetters() {
        var activeStrip: AlternatesStrip? = null
        var activeOptions: List<String> = emptyList()
        var activeSelected = -1
        val timers = ArrayList<Pair<Long, Runnable>>()
        var now = 0L
        val host = object : KeyTouches.Host {
            override fun post(delayMs: Long, action: Runnable) { timers += (now + delayMs) to action }
            override fun cancel(action: Runnable) { timers.removeAll { it.second === action } }
            override fun pulseKeyPress(row: Int, column: Int) = Unit
            override fun showBalloon(id: Int, key: Key, frame: Box) = Unit
            override fun hideBalloon(id: Int) = Unit
            override fun showAlternates(strip: AlternatesStrip, options: List<String>, selected: Int) {
                activeStrip = strip; activeOptions = options; activeSelected = selected
            }
            override fun selectAlternate(index: Int) { activeSelected = index }
            override fun hideAlternates() { activeStrip = null }
            override fun clearVisuals() { activeStrip = null }
            override fun redraw() = Unit
        }
        val committed = ArrayList<String>()
        val touches = KeyTouches(host)
        touches.listener = object : KeyListener {
            override fun onKeyDown(key: Key, fraction: Float) = Unit
            override fun onKey(key: Key, text: String?) { committed += text ?: key.label }
            override fun onDelete(word: Boolean) = Unit
            override fun onShiftDown() = Unit
            override fun onShiftUp(typed: Boolean) = Unit
            override fun onPage(page: Int) = Unit
            override fun onCursorStart() = Unit
            override fun onCursorMove(steps: Int) = Unit
            override fun onCursorEnd() = Unit
            override fun onTick() = Unit
            override fun onKeyHold(key: Key) = Unit
        }
        val set = KeyBuilder.build(
            KeyContext(InputMode.NINE_KEY, false, 0, ShiftState.OFF, false, false, "空格", "換行", false, true)
        )
        val rows = KeyGrid.place(set.specs, 393f, set.referenceColumns, KeyMetrics.of(Idiom.PHONE, 393f, set.rows.size))
        touches.layout(set, rows, 393f, sameShape = false)

        val pqrs = rows[2][1].frame // Row 2 col 1 = 7 PQRS
        touches.down(0, pqrs.centerX, pqrs.centerY)
        val end = now + KeyTouches.ALTERNATES_DELAY
        while (true) {
            val next = timers.filter { it.first <= end }.minByOrNull { it.first } ?: break
            timers.remove(next); now = next.first; next.second.run()
        }
        now = end
        assertNotNull(activeStrip)
        assertEquals(listOf("7", "p", "q", "r", "s"), activeOptions)
        assertEquals(0, activeSelected)

        val dir = if (activeStrip!!.mirrored) -1f else 1f
        touches.move(0, pqrs.centerX + dir * 4f * activeStrip!!.cell, pqrs.centerY)
        assertEquals(4, activeSelected)
        touches.up(0, pqrs.centerX + dir * 4f * activeStrip!!.cell, pqrs.centerY)
        assertEquals(listOf("s"), committed)
    }

    @Test
    fun choiceMemoryNormalizesLockedTrailingSeparator() {
        val partial = Candidate("往", CandidateKind.CHINESE, coreIndex = 0, consumed = 5)
        assertEquals("wang", ChoiceMemory.selectionReading("WANG'468482", partial))
    }
}

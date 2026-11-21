package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.engine.InputMode
import com.krysta1ll.meowpuut.android.engine.InputMode.JAPANESE
import com.krysta1ll.meowpuut.android.engine.InputMode.NINE_KEY
import com.krysta1ll.meowpuut.android.engine.InputMode.PINYIN
import com.krysta1ll.meowpuut.android.engine.InputMode.ZHUYIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「摸米米莫五下」: which way of typing the five touches go to. */
class MimimoModesTest {
    @Test
    fun theTouchesGoRoundTheTickedWaysInFixedOrder() {
        val all = setOf(ZHUYIN, PINYIN, NINE_KEY, JAPANESE)
        assertEquals(PINYIN, InputMode.nextAmong(ZHUYIN, all))
        assertEquals(NINE_KEY, InputMode.nextAmong(PINYIN, all))
        assertEquals(JAPANESE, InputMode.nextAmong(NINE_KEY, all))
        assertEquals(ZHUYIN, InputMode.nextAmong(JAPANESE, all))
        assertEquals(JAPANESE, InputMode.nextAmong(PINYIN, setOf(PINYIN, JAPANESE)))
        assertEquals(PINYIN, InputMode.nextAmong(JAPANESE, setOf(PINYIN, JAPANESE)))
    }

    @Test
    fun aWayThatIsNotTickedGoesToTheFirstThatIs() {
        assertEquals(ZHUYIN, InputMode.nextAmong(NINE_KEY, InputMode.SWITCH_DEFAULT))
        assertEquals(PINYIN, InputMode.nextAmong(JAPANESE, setOf(PINYIN, NINE_KEY)))
    }

    @Test
    fun fewerThanTwoTickedSwitchesTheGestureOff() {
        assertNull(InputMode.nextAmong(ZHUYIN, emptySet()))
        assertNull(InputMode.nextAmong(ZHUYIN, setOf(ZHUYIN)))
    }

    @Test
    fun theDefaultIsTheWayItAlwaysWent() {
        for (m in listOf(ZHUYIN, PINYIN, JAPANESE)) assertEquals(m.next(), InputMode.nextAmong(m, InputMode.SWITCH_DEFAULT))
    }
}

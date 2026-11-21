package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.onboarding.ONBOARDING_KEYS
import com.krysta1ll.meowpuut.android.app.onboarding.Onboarding
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingGeo
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingState
import com.krysta1ll.meowpuut.android.app.onboarding.OnboardingTyping
import com.krysta1ll.meowpuut.android.app.onboarding.Scheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The first-run guide (第一行字): when it comes up, what it keeps, and that it can be walked from the blank page to the home screen. */
class OnboardingTest {
    private val copy = OnboardingStrings.copy(simplified = false)
    private fun guide(host: RecordingHost = RecordingHost(), still: Boolean = false, big: Boolean = false) =
        OnboardingState(copy, host, OnboardingGeo(852.0, 40.0, big)) { ch, _ -> if (ch[0].code < 0x80) 6 else 12 }.also { it.still = still }
    private fun OnboardingState.run(ticks: Int) = repeat(ticks) { tick() }
    private fun RecordingHost.has(id: String) = hots.any { it.id == id }

    // ---- when it comes up ---------------------------------------------------------------------------------
    @Test
    fun everyDeviceIsShownTheGuideOnceWhoeverItIsFor() {
        assertEquals(true to Onboarding.DUE, Onboarding.decide(null))                      // no record: shown, new or not, and noted as due
        assertEquals(true to null, Onboarding.decide(Onboarding.DUE))                      // shown and not finished: shown again
        assertEquals(false to null, Onboarding.decide(Onboarding.DONE))                    // seen or skipped: never again by itself
        assertEquals(true to Onboarding.DUE, Onboarding.decide("something else"))
    }

    @Test
    fun aGuideTheSystemEndedIsTakenUpWhereItWasOnlyForAWhile() {
        val at = 5_000_000L
        val p = Onboarding.readProgress("3,-1,$at", at + 60_000)!!
        assertEquals(3, p.act); assertEquals(-1, p.pick)
        assertEquals(2, Onboarding.readProgress("5,2,$at", at + Onboarding.RESUME_MS)!!.pick)
        assertNull(Onboarding.readProgress("3,-1,$at", at + Onboarding.RESUME_MS + 1))     // too long ago: from the beginning
        assertNull(Onboarding.readProgress("3,-1,$at", at - 1))                            // the clock was set back
        assertNull(Onboarding.readProgress("1,-1,$at", at)); assertNull(Onboarding.readProgress("7,-1,$at", at))
        assertNull(Onboarding.readProgress("3,5,$at", at)); assertNull(Onboarding.readProgress("3,-1", at)); assertNull(Onboarding.readProgress("x,y,z", at))
        assertNull(Onboarding.readProgress(null, at))
    }

    @Test
    fun aNewInstallIsOneThatWasNeverUpdatedAndWhoseKeyboardHasLeftNoFiles() {
        assertTrue(Onboarding.fresh(updatedAfterMs = 0, hasKeyboardFiles = false))
        assertTrue(Onboarding.fresh(updatedAfterMs = 60_000, hasKeyboardFiles = false))
        assertFalse(Onboarding.fresh(updatedAfterMs = 60_001, hasKeyboardFiles = false))
        assertFalse(Onboarding.fresh(updatedAfterMs = 0, hasKeyboardFiles = true))
        assertFalse(Onboarding.fresh(updatedAfterMs = Long.MAX_VALUE, hasKeyboardFiles = false))   // the system would not say: do not interrupt
    }

    @Test
    fun theKeyboardWearsTheGuidesSkinOnlyForAWhile() {
        val now = 1_000_000_000L
        assertTrue(Onboarding.wearing(now + Onboarding.WEAR_MS, now))
        assertTrue(Onboarding.wearing(now + 1, now))
        assertFalse(Onboarding.wearing(now, now))                                           // run out
        assertFalse(Onboarding.wearing(0, now))                                             // never set
        assertFalse(Onboarding.wearing(now + Onboarding.WEAR_MS + 1, now))                  // the clock was set back: not for ever
    }

    // ---- someone already using the app ----------------------------------------------------------------------
    @Test
    fun somebodyAlreadyUsingTheKeyboardIsNotAskedToDoWhatIsDone() {
        val host = RecordingHost().apply { inUse = Scheme.PINYIN }
        val g = guide(host)
        g.enabled(true); g.keyboardIsOurs(true); g.start(); g.tap("paper"); g.run(90)
        g.run(60)                                                           // enabled already: the sun comes up by itself, no trip to the settings
        assertTrue(g.sunrise); assertEquals(0, host.settings); assertEquals(3, g.act)
        assertEquals(1, g.mimiStage); assertFalse(host.has("tag"))          // already the keyboard in use: eyes at once, nothing asked
        repeat(3) { g.typed() }; g.committed(); g.run(120)
        assertEquals(4, g.act)
        assertEquals(copy.order.indexOf(Scheme.PINYIN), g.youAre)           // the road in use is marked
        assertEquals(copy.schemes.getValue(Scheme.PINYIN) + OnboardingState.IN_USE, host.hots.first { it.id == "road${g.youAre}" }.label)
        assertEquals(copy.schemes.getValue(copy.order[(g.youAre + 1) % 3]), host.hots.first { it.id == "road${(g.youAre + 1) % 3}" }.label)   // the others say nothing more
        g.tap("road${g.youAre}")
        assertNull(host.chosen); assertEquals(0, host.chosenCount)          // the road already in use writes nothing
        g.run(200); assertTrue(g.atHome); assertFalse(host.kept)            // and the skin is what it was unless asked
    }

    @Test
    fun anotherRoadIsWrittenAndAWayThatIsNoneOfTheThreeMarksNothing() {
        val host = RecordingHost().apply { inUse = Scheme.ZHUYIN }
        val g = guide(host)
        g.resume(4, -1)
        assertEquals(copy.order.indexOf(Scheme.ZHUYIN), g.youAre)
        val other = (0..2).first { it != g.youAre }
        g.tap("road$other"); assertEquals(copy.order[other], host.chosen); assertEquals(1, host.chosenCount)
        val none = RecordingHost().apply { inUse = null }                  // Japanese, or a new install
        val h = guide(none); h.resume(4, -1)
        assertEquals(-1, h.youAre)
        h.tap("road0"); assertEquals(copy.order[0], none.chosen)           // nothing is marked, so any road is written
    }

    @Test
    fun theGuideSaysWhereItHasGotToAndASkipIsSeenAtOnce() {
        val host = RecordingHost()
        val g = guide(host)
        g.start(); g.tap("paper"); g.run(90)
        assertEquals(listOf(1 to -1, 2 to -1), host.reachedLog)
        g.enabled(true); g.run(90); g.keyboardIsOurs(true); repeat(3) { g.typed() }; g.committed(); g.run(90)
        assertEquals(4, g.act); assertEquals(listOf(1 to -1, 2 to -1, 3 to -1, 4 to -1), host.reachedLog)
        g.tap("road2"); g.run(60)
        assertEquals(5 to 2, host.reachedLog.last())
        val h = RecordingHost(); val s = guide(h)
        s.start(); s.tap("skip"); assertTrue(h.done)                        // before the last page has been typed
    }

    @Test
    fun aGuideTheSystemEndedIsTakenUpAsIfJustComeBack() {
        for (act in 2..5) {
            val host = RecordingHost()
            val g = guide(host)
            g.resume(act, if (act == 5) 1 else -1)
            assertEquals("act $act", act, g.act); assertEquals(1.0, g.pile, 0.0)
            assertEquals(false, g.atHome); assertTrue(host.has("skip"))
            if (act >= 3) assertTrue(g.sunrise)
            if (act == 3) assertTrue(host.keyboardUp)
            if (act == 4) assertTrue(host.has("road0"))
            if (act == 5) assertEquals(copy.order[1], g.scheme)
        }
        val host = RecordingHost(); val g = guide(host)
        g.resume(2, -1); g.run(5)
        assertTrue(host.has("post")); assertFalse(g.sunrise)                // still waiting for the keyboard to be enabled
        g.enabled(true); g.run(120); assertEquals(3, g.act)                 // and goes on when it is
        val last = RecordingHost(); val e = guide(last)
        e.resume(5, 0); e.run(200)
        assertTrue(e.atHome); assertTrue(last.done)
    }

    // ---- what is typed is not kept ------------------------------------------------------------------------
    @Test
    fun theLineIsReadFromItsLengthsAlone() {
        val t = OnboardingTyping()
        assertTrue(t.changed(1, 1))           // n
        assertTrue(t.changed(5, 5))           // nihao, still a reading
        assertFalse(t.changed(5, 5))          // nothing happened
        assertTrue(t.changed(2, 0))           // 你好
        assertTrue(t.changed(1, 0))           // a backspace
        t.reset()
        assertTrue(t.changed(1, 0))
        // A word chosen is text committed while a reading was being composed, and nothing else.
        assertTrue(OnboardingTyping.isCommit(wasComposing = true, committedLength = 2))
        assertFalse(OnboardingTyping.isCommit(wasComposing = true, committedLength = 0))    // the reading was thrown away
        assertFalse(OnboardingTyping.isCommit(wasComposing = false, committedLength = 1))   // a keyboard that composes nothing: a key, never a commit
    }

    @Test
    fun nothingThatTakesTheTypedTextExists() {
        val text = setOf<Class<*>>(String::class.java, CharSequence::class.java, CharArray::class.java)
        for (name in listOf("typed", "committed")) {
            val methods = OnboardingState::class.java.declaredMethods.filter { it.name == name }
            assertTrue(methods.isNotEmpty())
            assertTrue(methods.all { it.parameterCount == 0 })
        }
        assertTrue(OnboardingTyping::class.java.declaredMethods.none { m -> m.parameterTypes.any { it in text } })
        assertTrue(OnboardingTyping::class.java.declaredFields.none { it.type in text })
    }

    // ---- the walk -----------------------------------------------------------------------------------------
    @Test
    fun theGuideCanBeWalkedFromTheBlankPageToTheHomeScreen() {
        val host = RecordingHost()
        val g = guide(host)
        g.start(); g.run(40)
        assertEquals(1, g.act); assertTrue(host.has("paper")); assertTrue(host.has("skip"))
        g.tap("paper"); g.run(90)
        assertEquals(2, g.act); assertEquals(1.0, g.pile, 0.0); assertTrue(host.has("post"))
        g.tap("post"); assertEquals(1, host.settings)
        g.returned(); g.run(30)
        assertTrue(copy.a2Back in host.said); assertFalse(g.sunrise)       // back without enabling it: said once, calmly, and nothing else happens
        g.enabled(true); g.run(90)
        assertTrue(g.sunrise); assertTrue(copy.a2Announce in host.said); assertEquals(1.0, g.sun, 0.0)
        assertEquals(3, g.act); assertTrue(host.keyboardUp); assertTrue(host.has("tag"))
        g.tap("tag"); assertEquals(1, host.picker)
        g.keyboardIsOurs(true)
        assertEquals(1, g.mimiStage); assertFalse(host.has("tag"))        // a pair of eyes; nothing left to ask for
        repeat(3) { g.typed() }; g.run(20)
        assertEquals(2, g.mimiStage); assertEquals(0, g.momo.on)
        g.committed(); g.run(20)
        assertEquals(2, g.momo.on); assertEquals(1.0, g.momo.form, 0.0); assertTrue(copy.a3AnnounceMomo in host.said)
        g.run(70)
        assertEquals(4, g.act); assertFalse(host.keyboardUp); assertTrue(host.has("road1"))   // the line is put away and emptied
        g.tap("road1")
        assertEquals(copy.order[1], host.chosen); assertEquals(copy.order[1], g.scheme)
        g.run(60); assertEquals(5, g.act)
        g.run(140)
        assertEquals(6, g.act); assertTrue(g.atHome)
        g.run(120)
        assertTrue(g.done); assertTrue(host.done); assertFalse(host.kept); assertTrue(host.has("wear")); assertFalse(host.has("skip"))
        g.tap("wear")
        assertTrue(host.kept); assertTrue(g.worn); assertFalse(host.has("wear"))
        g.run(80); assertTrue(host.left)
    }

    @Test
    fun theSkinIsNotChangedUnlessAskedAndTheGuideLeavesByItself() {
        val host = RecordingHost()
        val g = guide(host)
        g.start(); g.tap("skip"); g.run(80)
        assertTrue(g.atHome); assertTrue(g.done); assertTrue(host.done)
        assertFalse(host.has("wear"))                                      // someone who skipped is not offered the skin
        assertNull(host.chosen); assertFalse(host.kept)
        g.tap("wear"); assertFalse(host.kept)
        assertFalse(host.left); g.tap("home"); assertTrue(host.left)
    }

    @Test
    fun skippingFromTheTypingActPutsTheLineAway() {
        val host = RecordingHost()
        val g = guide(host)
        g.start(); g.tap("paper"); g.run(90); g.enabled(true); g.run(90)
        assertEquals(3, g.act); assertTrue(host.keyboardUp)
        g.skip(); assertFalse(host.keyboardUp); assertTrue(g.atHome)
    }

    @Test
    fun aKeyboardEnabledBeforeTheAppWasOpenedNeedsNoTripToTheSettings() {
        val host = RecordingHost()
        val g = guide(host)
        g.enabled(true); g.start(); g.tap("paper"); g.run(90)
        assertEquals(2, g.act); g.run(120)
        assertTrue(g.sunrise); assertEquals(0, host.settings); assertEquals(3, g.act)
    }

    @Test
    fun anotherKeyboardIsToldOnceAndThenOfferedTheWayOn() {
        val host = RecordingHost()
        val g = guide(host)
        g.start(); g.tap("paper"); g.run(90); g.enabled(true); g.run(90)
        repeat(3) { g.typed() }
        assertEquals(1, host.said.count { it == copy.a3Other }); assertEquals(0, g.mimiStage)
        g.committed(); assertFalse(g.committed)                           // nothing this guide knows of was committed
        repeat(5) { g.typed() }
        assertEquals(1, host.said.count { it == copy.a3Other }); assertTrue(host.has("onward"))
        g.tap("onward"); g.run(30)
        assertEquals(4, g.act); assertEquals(0, g.mimi.on); assertEquals(0, g.momo.on)   // the cats are met at the end instead
    }

    @Test
    fun withAnimationsOffEveryActStillArrives() {
        val host = RecordingHost()
        val g = guide(host, still = true)
        g.start(); g.run(30); g.tap("paper")
        assertEquals(1.0, g.pile, 0.0); assertTrue(g.falls.isEmpty()); assertTrue(g.rain.isEmpty())   // nothing falls: the ground is simply there
        g.run(60); g.enabled(true); g.run(70)
        assertEquals(3, g.act); g.keyboardIsOurs(true); repeat(3) { g.typed() }; g.committed(); g.run(70)
        g.tap("road0"); g.run(250)
        assertTrue(g.atHome); assertTrue(g.done); assertEquals(1.0, g.fog, 0.0)
    }

    // ---- the words ----------------------------------------------------------------------------------------
    @Test
    fun bothScriptsHaveEveryLineAndTheRoadsFollowTheScript() {
        val hant = OnboardingStrings.read(simplified = false)
        val hans = OnboardingStrings.read(simplified = true)
        for (key in ONBOARDING_KEYS + listOf("replay", "title")) { assertNotNull(key, hant[key]); assertNotNull(key, hans[key]); assertTrue(key, hant.getValue(key).isNotBlank() && hans.getValue(key).isNotBlank()) }
        assertEquals(listOf(Scheme.ZHUYIN, Scheme.PINYIN, Scheme.NINE_KEY), copy.order)
        assertEquals(listOf(Scheme.PINYIN, Scheme.NINE_KEY, Scheme.ZHUYIN), OnboardingStrings.copy(simplified = true).order)
        for (all in listOf(hant, hans)) for ((key, text) in all) { assertFalse(key, '!' in text || '！' in text); assertFalse(key, "歡迎" in text || "欢迎" in text) }
    }

    @Test
    fun largeTypeTakesMorePagesNotMoreRoom() {
        val long = listOf(OnboardingState.Line("開了，多四樣：按鍵聲、震動、爪印本記得打字的日子、iCloud 同步。鍵盤只寫數量，不寫內容。不開，也什麼都能打。"))
        val normal = guide().layout(long)
        val big = guide(big = true).layout(long)
        assertTrue(big.size > normal.size)
        for (pages in listOf(normal, big)) for (page in pages) for (row in page) assertTrue(row.y + 11 * row.s <= 40.0 + 286)
        assertTrue(big.flatten().any { r -> r.chars.joinToString("") { it.ch }.contains("iCloud") })   // a Latin word is not broken
    }
}

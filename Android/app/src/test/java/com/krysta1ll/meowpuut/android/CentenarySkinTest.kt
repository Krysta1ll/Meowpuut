package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.EffectsLevel
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.GlassStrength
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.Palette
import com.krysta1ll.meowpuut.android.ime.Cat
import com.krysta1ll.meowpuut.android.ime.CatBodyPose
import com.krysta1ll.meowpuut.android.ime.CatMotion
import com.krysta1ll.meowpuut.android.ime.CatPose
import com.krysta1ll.meowpuut.android.ime.CatSeats
import com.krysta1ll.meowpuut.android.ime.CandidateRegionLayout
import com.krysta1ll.meowpuut.android.ime.CentenaryCats
import com.krysta1ll.meowpuut.android.ime.Idiom
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class CentenarySkinTest {
    private fun look(dark: Boolean, palette: Palette = Palette.ROSE) = Look(dark, GlassShape.CAPSULE,
        palette, GlassStrength.BALANCED, true, skin = KeyboardSkin.CENTENARY)

    @Test fun commemorativeSkinIsFreeAndHasItsOwnStableIdentity() {
        val skin = KeyboardSkin.of("centenary")
        assertEquals(KeyboardSkin.CENTENARY, skin)
        assertFalse(skin.requiresQuiz)
        assertFalse(skin.supportsFestivals)
        for (shape in GlassShape.entries) assertEquals(GlassShape.CAPSULE, skin.shape(shape))
        for (dark in listOf(false, true)) {
            val original = look(dark)
            for (palette in Palette.entries) {
                val other = look(dark, palette)
                assertEquals(original.keyboardTop, other.keyboardTop)
                assertEquals(original.keyFaceTop, other.keyFaceTop)
                assertEquals(original.selectedPill, other.selectedPill)
            }
        }
    }

    @Test fun lettersCandidatesBadgeAndActionKeysStayReadableInBothAppearances() {
        for (dark in listOf(false, true)) for (effects in EffectsLevel.entries) for (opaque in listOf(false, true)) {
            val look = look(dark).copy(effects = effects, opaque = opaque)
            for (face in listOf(look.keyFaceTop, look.keyFaceBottom, look.keyUtilityTop, look.keyUtilityBottom)) {
                readable(look.keyText, face)
                readable(look.keyText, Look.darken(face, 0.12f))
            }
            for (face in listOf(look.keyUtilityTop, look.keyUtilityBottom)) {
                readable(if (dark) look.tint else 0xFF76542A.toInt(), face) // The small 100 seal.
            }
            for (deck in listOf(look.keyboardTop, look.keyboardMiddle, look.keyboardBottom)) {
                readable(look.candidateText, deck)
                readable(look.candidateSecondary, deck)
            }
            readable(look.pillText, look.selectedPill.first)
            readable(look.pillText, look.selectedPill.second)
            readable(0xFFFFFFFF.toInt(), look.accent)
            readable(0xFFFFFFFF.toInt(), Look.darken(look.accent, 0.12f))
            assertTrue(look.keyRim)
            assertNull(look.overlay)
        }
    }

    @Test fun everyPoseAndBodyReactionUsesCostumedArtworkAndFitsTheExistingBar() {
        for (cat in Cat.entries) {
            val frames = CatBodyPose.entries.map { CentenaryCats.sprite(cat, it) }
            assertEquals(4, frames.map { it.name }.distinct().size)
            for (frame in frames + CatPose.entries.map { CentenaryCats.sprite(cat, it) }) {
                assertTrue(frame.name.startsWith("centenary_${cat.key}_"))
                assertTrue(frame.paws.isEmpty()) // Whole figures never use an old paw cut.
            }
        }
        for ((width, idiom) in listOf(320f to Idiom.PHONE, 393f to Idiom.PHONE, 820f to Idiom.TABLET)) {
            val stage = CandidateRegionLayout(width, idiom).stage(GlassShape.CAPSULE)
            val seats = CatSeats.ends(stage, CentenaryCats.sprite(Cat.MIMIMO, CatPose.PEEK),
                CentenaryCats.sprite(Cat.MOMO, CatPose.PEEK))
            assertNotNull("costumed figures must fit at $width", seats)
            assertNotNull(stage.fitting(seats!!))
        }
    }

    @Test fun commemorativeBowAndPairedCheerEndAndReduceMotionUsesOnlyFades() {
        for (cat in Cat.entries) for ((event, duration, body) in listOf(
            Triple(CatMotion.Event.CENTENARY_BOW, 560L, CatBodyPose.GREET),
            Triple(CatMotion.Event.CENTENARY_CHEER, 640L, CatBodyPose.HAPPY),
        )) for (fade in listOf(false, true)) {
            val motion = CatMotion(cat, 0L, event = event, fade = fade)
            val middle = motion.pose(duration / 4)!!
            assertEquals(body, middle.body)
            assertTrue(middle.bodyAlpha > 0f)
            if (fade) {
                assertEquals(1f, middle.tall, 0f)
                assertEquals(0f, middle.tilt, 0f)
            } else assertTrue(middle.tall < 1f)
            assertNull(motion.pose(duration + 1))
        }
    }

    private fun readable(foreground: Int, background: Int) {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val value = ((color ushr shift) and 255) / 255.0
                return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
            }
            return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
        }
        val a = luminance(foreground)
        val b = luminance(background)
        val contrast = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("text contrast $contrast must be at least 4.5:1", contrast >= 4.5)
    }
}

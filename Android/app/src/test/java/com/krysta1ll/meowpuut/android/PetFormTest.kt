package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.Cat
import com.krysta1ll.meowpuut.android.ime.CatBodyPose
import com.krysta1ll.meowpuut.android.ime.CatPose
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.GlassStrength
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.Palette
import com.krysta1ll.meowpuut.android.ime.PetAssetResolver
import com.krysta1ll.meowpuut.android.ime.PetForm
import com.krysta1ll.meowpuut.android.ime.PetFormPreference
import com.krysta1ll.meowpuut.android.ime.SkinCatArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetFormTest {
    @Test
    fun preferenceDecouplesFormAcrossAllSkins() {
        assertEquals(PetForm.THREE_D, PetFormPreference.AUTO.resolved(KeyboardSkin.CURRENT))
        assertEquals(PetForm.THREE_D, PetFormPreference.AUTO.resolved(KeyboardSkin.CLASSIC_CLOUD))
        assertEquals(PetForm.THREE_D, PetFormPreference.AUTO.resolved(KeyboardSkin.CENTENARY))
        assertEquals(PetForm.PIXEL, PetFormPreference.AUTO.resolved(KeyboardSkin.PIXEL))
        assertEquals(PetForm.PIXEL, PetFormPreference.AUTO.resolved(KeyboardSkin.GLYPH))

        for (skin in KeyboardSkin.entries) {
            assertEquals(PetForm.THREE_D, PetFormPreference.THREE_D.resolved(skin))
            assertEquals(PetForm.PIXEL, PetFormPreference.PIXEL.resolved(skin))
            assertEquals(SkinCatArtwork.PIXEL, PetFormPreference.PIXEL.resolvedArtwork(skin))
            assertNotEquals(SkinCatArtwork.PIXEL, PetFormPreference.THREE_D.resolvedArtwork(skin))
        }
        assertEquals(PetFormPreference.AUTO, PetFormPreference.of("unknown_future_value"))
    }

    @Test
    fun assetResolverFollowsThreeTierPriorityAndFallbacks() {
        val pixelCandidates = PetAssetResolver.candidateAssetNames(PetForm.PIXEL, KeyboardSkin.CURRENT, Cat.MIMIMO, "edge_peek")
        assertEquals("pixel_daily_mimimo_edge_peek", pixelCandidates[0])
        assertEquals("pixel_mimimo_edge_peek", pixelCandidates[1])
        assertTrue(pixelCandidates.contains("pixel_mimimo"))

        val threeDCandidates = PetAssetResolver.candidateAssetNames(PetForm.THREE_D, KeyboardSkin.CENTENARY, Cat.MOMO, "edge_peek")
        assertEquals("3d_centenary_momo_edge_peek", threeDCandidates[0])
        assertEquals("3d_momo_edge_peek", threeDCandidates[1])
        assertTrue(threeDCandidates.contains("centenary_momo_idle"))

        val tier1 = PetAssetResolver.resolveAssetName(PetForm.PIXEL, KeyboardSkin.CENTENARY, Cat.MOMO, "edge_rest") {
            it == "pixel_centenary_momo_edge_rest" || it == "pixel_momo_edge_rest" || it == "pixel_momo"
        }
        assertEquals("pixel_centenary_momo_edge_rest", tier1)

        val tier2 = PetAssetResolver.resolveAssetName(PetForm.PIXEL, KeyboardSkin.CENTENARY, Cat.MOMO, "edge_rest") {
            it == "pixel_momo_edge_rest" || it == "pixel_momo"
        }
        assertEquals("pixel_momo_edge_rest", tier2)

        val tier3 = PetAssetResolver.resolveAssetName(PetForm.PIXEL, KeyboardSkin.CURRENT, Cat.MIMIMO, "edge_lounge") {
            it == "pixel_mimimo"
        }
        assertEquals("pixel_mimimo", tier3)
    }

    @Test
    fun seatedAndReactionResolutionRespectsLookOverride() {
        val pixelOnDaily = PetAssetResolver.resolveSeatedSprite(PetForm.PIXEL, KeyboardSkin.CURRENT, Cat.MOMO, CatPose.PEEK) {
            it == "pixel_momo"
        }
        assertEquals("pixel_momo", pixelOnDaily.name)
        assertEquals(44f, pixelOnDaily.width, 0.01f)

        val threeDOnPixel = PetAssetResolver.resolveSeatedSprite(PetForm.THREE_D, KeyboardSkin.PIXEL, Cat.MIMIMO, CatPose.PEEK) {
            it == "mimimo_edge_peek"
        }
        assertEquals("mimimo_edge_peek", threeDOnPixel.name)

        val skinOverride = PetAssetResolver.resolveSeatedSprite(PetForm.THREE_D, KeyboardSkin.PIXEL, Cat.MIMIMO, CatPose.PEEK) {
            it == "3d_pixel_mimimo_edge_peek"
        }
        assertEquals("3d_pixel_mimimo_edge_peek", skinOverride.name)

        val reaction = PetAssetResolver.resolveReactionAsset(PetForm.PIXEL, KeyboardSkin.CURRENT, Cat.MOMO, CatBodyPose.HAPPY) {
            it == "pixel_daily_momo_happy"
        }
        assertEquals("pixel_daily_momo_happy", reaction)

        val lookForcedPixel = Look(
            dark = false, shape = GlassShape.CLOUD, palette = Palette.ROSE, strength = GlassStrength.BALANCED,
            cats = true, skin = KeyboardSkin.CLASSIC_CLOUD, petForm = PetFormPreference.PIXEL,
        )
        assertEquals(PetForm.PIXEL, lookForcedPixel.resolvedPetForm)
        assertEquals(SkinCatArtwork.PIXEL, lookForcedPixel.resolvedCatArtwork)
        assertFalse(lookForcedPixel.edgeHuggingCats)
    }
}

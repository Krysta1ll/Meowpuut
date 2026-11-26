package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.EffectsLevel
import com.krysta1ll.meowpuut.android.ime.GlassShape
import com.krysta1ll.meowpuut.android.ime.GlassStrength
import com.krysta1ll.meowpuut.android.ime.KeyParticleKind
import com.krysta1ll.meowpuut.android.ime.KeyboardSkin
import com.krysta1ll.meowpuut.android.ime.Look
import com.krysta1ll.meowpuut.android.ime.Palette
import com.krysta1ll.meowpuut.android.ime.PetForm
import com.krysta1ll.meowpuut.android.ime.SkinCatArtwork
import com.krysta1ll.meowpuut.android.ime.SkinOverlay
import com.krysta1ll.meowpuut.android.ime.SkinRef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The manifest-to-Look mapping of a verified custom skin (CustomSkin.kt, Theme.kt). No files, no android. */
class CustomSkinTest {
    private val ref = SkinRef("sakura_tea", 3)
    private val manifestText = javaClass.getResource("/skins/sakura_tea-3.manifest.json")!!.readText()

    private fun overlay(edit: (JSONObject) -> Unit = {}): SkinOverlay? {
        val json = JSONObject(manifestText)
        edit(json)
        return SkinOverlay.parse(json.toString(), ref)
    }

    private fun look(overlay: SkinOverlay?, dark: Boolean = false, opaque: Boolean = false, pillOverride: Int? = null) =
        Look(dark, GlassShape.CAPSULE, Palette.ROSE, GlassStrength.BALANCED, true, pillOverride, opaque,
            skin = overlay?.base ?: KeyboardSkin.CURRENT, overlay = overlay)

    private fun rgb(text: String) = Look.parseColor(text)!!

    @Test
    fun referenceIsALowerCaseNameAndAU32Revision() {
        assertEquals(SkinRef("sakura_tea", 3), SkinRef.parse("sakura_tea@3"))
        assertEquals("a-b_9@4294967295", SkinRef.parse("a-b_9@4294967295").toString())
        assertEquals(SkinRef("x", 0), SkinRef.parse("x@0"))
        for (bad in listOf("../x@1", "x@01", "x@4294967296", "X@1", "x@-1", "x@", "@1", "x@1 ", "x@1\n", "x/y@1", "x@1@2", "con.@1",
            "a".repeat(65) + "@1", "", "x", "x@١")) {
            assertNull(bad, SkinRef.parse(bad))
        }
        assertNull(SkinRef.parse(null))
    }

    @Test
    fun parsesTheInstalledManifest() {
        val o = overlay()!!
        assertEquals(KeyboardSkin.CURRENT, o.base)
        assertEquals("櫻花奶茶", o.name(simplified = false))
        assertEquals("樱花奶茶", o.name(simplified = true))
        assertEquals(GlassShape.CLOUD, o.glassShape)
        assertFalse(o.lockShape)
        assertEquals(GlassStrength.BALANCED, o.strength)
        assertEquals(0.06f, o.deckTint, 1e-6f)
        assertEquals(0.12f, o.keyTint, 1e-6f)
        assertTrue(o.keyRim)
        assertFalse(o.festivalsAllowed)
        assertFalse(o.seatingEdges)
        assertEquals(rgb("#7A4E52"), o.contrastPill)
        assertEquals(rgb("#FFF8F0"), o.light.paper)
        assertEquals(rgb("#F4D6D9"), o.light.pillTop)
        assertEquals(rgb("#EBC3C8"), o.light.pillBottom)
        assertEquals(rgb("#E9A3B0"), o.dark.stamp)
        assertEquals(rgb("#4A3A3D"), o.keysDark.character)
        assertEquals("right", o.rightEnd!!.image)
        assertEquals(20f, o.rightEnd!!.width, 0f)
    }

    @Test
    fun catSpritesCarryTheMeasuredGeometryOfThePerchedPosesOnly() {
        val o = overlay()!!
        // momo_side_hold is in the manifest, but the lifted cat's mesh has fixed paw landmarks: not taken.
        assertEquals(setOf("mimimo_edge_peek"), o.sprites.keys)
        val entry = o.sprites.getValue("mimimo_edge_peek")
        assertEquals("mimimo_peek", entry.image)
        val s = entry.sprite
        assertEquals("mimimo_edge_peek", s.name)
        assertEquals(32f, s.width, 0f)
        assertEquals(40f, s.height, 0f)
        assertEquals(0.5f, s.anchorX, 1e-6f)
        assertEquals(0.75f, s.anchorY, 1e-6f)
        assertEquals(0.2083f, s.left, 1e-6f)
        assertEquals(0.1667f, s.top, 1e-6f)
        assertEquals(0.7917f, s.right, 1e-6f)
        assertEquals(0.8333f, s.bottom, 1e-6f)
        assertEquals(0.2083f, s.cutLeft, 1e-6f)
        assertEquals(0.7917f, s.cutRight, 1e-6f)
        assertEquals(2, s.paws.size)
        assertEquals(listOf(0.3333f, 0.0417f, 0.0833f), s.paws[0].toList())
        assertEquals(listOf("mimimo_peek", "right"), o.images)
    }

    @Test
    fun soundsMapRolesAndGain() {
        val sounds = overlay()!!.sounds!!
        assertEquals(setOf("key", "return", "space"), sounds.files.keys)
        assertEquals(listOf("key-1"), sounds.files.getValue("key"))
        assertEquals(1f, sounds.gain, 0f)
        assertEquals(0.7079f, overlay { it.getJSONObject("sounds").put("gainDb", -3) }!!.sounds!!.gain, 0.001f)
        // SoundPool has no headroom: +3 dB plays at the person's own volume, not louder.
        val loud = overlay { it.getJSONObject("sounds").put("gainDb", 3) }!!.sounds!!
        assertEquals(1f, loud.gain, 0f)
        assertNull(overlay { it.remove("sounds") }!!.sounds)
    }

    @Test
    fun lookTakesTheSkinsColours() {
        val o = overlay()!!
        val light = look(o)
        assertEquals(rgb("#3A2A2A"), light.candidateText)
        assertEquals(rgb("#6B5555"), light.candidateSecondary)
        assertEquals(rgb("#B4475A"), light.tint)
        assertEquals(rgb("#F4D6D9") to rgb("#EBC3C8"), light.pill)
        // The capsule is drawn as validated (pillText is checked against it), not darkened like a built-in one.
        assertEquals(light.pill, light.selectedPill)
        assertEquals(rgb("#3A2A2A"), light.pillText)
        assertEquals(rgb("#3A2A2A"), light.keyText)
        assertEquals(rgb("#A23B4F"), light.accent)
        assertTrue(light.keyRim)

        val dark = look(o, dark = true)
        assertEquals(rgb("#F5ECEC"), dark.candidateText)
        assertEquals(rgb("#E9A3B0"), dark.tint)
        assertEquals(rgb("#5A3D43") to rgb("#4A3237"), dark.pill)
        assertEquals(rgb("#F5ECEC"), dark.keyText)
    }

    @Test
    fun deckGoesFromPaperToPaper2() {
        val light = look(overlay()!!)
        // deckTint 0.06 toward the stamp keeps it close to the paper colours.
        fun near(a: Int, b: Int) = (0..2).all { kotlin.math.abs(((a shr (it * 8)) and 0xFF) - ((b shr (it * 8)) and 0xFF)) <= 12 }
        assertTrue(near(light.keyboardTop, rgb("#FFF8F0")))
        assertTrue(near(light.keyboardBottom, rgb("#F7EDE4")))
        val plain = overlay { it.getJSONObject("glass").put("deckTint", 0) }!!
        assertEquals(rgb("#FFF8F0"), look(plain).keyboardTop)
        assertEquals(rgb("#F7EDE4"), look(plain).keyboardBottom)
    }

    @Test
    fun keysAreTheValidatedColoursLeanedByKeysTint() {
        val still = overlay { it.getJSONObject("keys").put("tint", 0) }!!
        val l = look(still)
        assertEquals(rgb("#FFFFFF"), l.keyFaceTop)
        assertEquals(rgb("#E8DADA"), l.keyUtility)
        assertEquals(rgb("#E8DADA"), l.keyUtilityTop)
        val tinted = look(overlay()!!)
        assertTrue(tinted.keyFaceTop != rgb("#FFFFFF"))
    }

    @Test
    fun highContrastUsesContrastPillWithWhiteText() {
        val l = look(overlay()!!, opaque = true)
        assertEquals(rgb("#7A4E52") to rgb("#7A4E52"), l.pill)
        assertEquals(rgb("#FFFFFF"), l.pillText)
        assertEquals(rgb("#FFF8F0"), l.glassFill)
    }

    @Test
    fun aFestivalColourStillWinsWhereFestivalsAreAllowed() {
        val allowed = overlay { it.put("festivals", "allow") }!!
        assertTrue(allowed.festivalsAllowed)
        val festive = look(allowed, pillOverride = 0xFF336699.toInt())
        assertEquals(0xFF336699.toInt(), festive.pill.second)
        assertEquals(0xFFFFFFFF.toInt(), festive.pillText)
        assertTrue(festive.supportsFestivals)
        assertFalse(look(overlay()!!).supportsFestivals)
    }

    @Test
    fun festivalsAreOnlyForTheCurrentBase() {
        assertNotNull(overlay { it.put("base", "classic").put("festivals", "suppress") })
        assertFalse(overlay { it.put("base", "classic").put("festivals", "allow") }!!.festivalsAllowed)
        assertFalse(overlay { it.put("base", "pixel").put("festivals", "allow") }!!.festivalsAllowed)
    }

    @Test
    fun baseSelectsTheBuiltInMaterial() {
        assertEquals(KeyboardSkin.CLASSIC_CLOUD, overlay { it.put("base", "classic") }!!.base)
        val pixel = overlay { it.put("base", "pixel") }!!
        assertEquals(KeyboardSkin.PIXEL, pixel.base)
        val l = look(pixel)
        assertTrue(l.usesSteppedMaterial)
        assertEquals(rgb("#FFFFFF"), l.pixelSurface)
        assertEquals(rgb("#E8DADA"), l.pixelUtility)
        assertEquals(rgb("#A23B4F"), l.pixelAccent)
    }

    @Test
    fun edgeHuggingCatsOnlyWhereTheBaseHasThatArtwork() {
        val edges = { base: String -> overlay { it.put("base", base).getJSONObject("cats").put("seating", "edges") }!! }
        assertTrue(look(edges("classic")).edgeHuggingCats)
        val classicUltra = look(edges("classic")).copy(effects = EffectsLevel.ULTRA)
        assertEquals(PetForm.THREE_D, classicUltra.resolvedPetForm)
        assertEquals(SkinCatArtwork.SCULPTED, classicUltra.resolvedCatArtwork)
        assertFalse(classicUltra.edgeHuggingCats)
        // current and pixel have no edge-hugging pictures: perched, never a crash for a missing sprite.
        assertFalse(look(edges("current")).edgeHuggingCats)
        assertFalse(look(edges("pixel")).edgeHuggingCats)
        assertFalse(look(overlay()!!).edgeHuggingCats)
    }

    @Test
    fun shapeFollowsThePersonUnlessTheSkinLocksIt() {
        val free = overlay()!!
        assertEquals(GlassShape.CLOUD, free.shape(GlassShape.WAVE, chosen = false))
        assertEquals(GlassShape.WAVE, free.shape(GlassShape.WAVE, chosen = true))
        val locked = overlay { it.getJSONObject("glass").put("lockShape", true) }!!
        assertEquals(GlassShape.CLOUD, locked.shape(GlassShape.WAVE, chosen = true))
    }

    @Test
    fun aBuiltInLookIsUnchangedWithoutAnOverlay() {
        val plain = look(null)
        assertEquals(Palette.ROSE.pill(false), plain.pill)
        assertEquals(0xFF3A3334.toInt(), plain.candidateText)
        assertEquals(0xFFFFFFFF.toInt(), plain.pillText)
        assertFalse(plain.keyRim)
        assertEquals(Palette.ROSE.color(false), plain.tint)
        assertTrue(plain.supportsFestivals)
    }

    @Test
    fun manifestThatDoesNotFitIsRejected() {
        assertNotNull(overlay())
        assertNull(SkinOverlay.parse(manifestText, SkinRef("sakura_tea", 4)))
        assertNull(SkinOverlay.parse(manifestText, SkinRef("other", 3)))
        assertNull(SkinOverlay.parse("", ref))
        assertNull(SkinOverlay.parse("{}", ref))
        assertNull(SkinOverlay.parse("[1]", ref))
        assertNull(overlay { it.put("schema", 2) })
        assertNull(overlay { it.put("base", "glass") })
        assertNull(overlay { it.getJSONObject("palette").put("contrastPill", "7A4E52") })
        assertNull(overlay { it.getJSONObject("palette").put("contrastPill", "#7A4E5G") })
        assertNull(overlay { it.getJSONObject("palette").put("contrastPill", "#-A4E52") })
        assertNull(overlay { it.getJSONObject("palette").getJSONObject("light").put("pill", org.json.JSONArray(listOf("#000000"))) })
        assertNull(overlay { it.getJSONObject("glass").put("shape", "star") })
        assertNull(overlay { it.getJSONObject("glass").put("deckTint", 0.5) })
        assertNull(overlay { it.getJSONObject("keys").put("tint", 1e308) })
        assertNull(overlay { it.getJSONObject("keys").remove("rim") })
        assertNull(overlay { it.getJSONObject("cats").put("seating", "floating") })
        assertNull(overlay { it.getJSONObject("cats").getJSONObject("sprites").getJSONObject("mimimo_edge_peek").getJSONArray("anchor").put(0, 2) })
        assertNull(overlay { it.getJSONObject("cats").getJSONObject("sprites").getJSONObject("mimimo_edge_peek").put("image", "../x") })
        assertNull(overlay { it.getJSONObject("cats").getJSONObject("sprites").getJSONObject("mimimo_edge_peek").getJSONArray("size").put(0, 0) })
        assertNull(overlay { it.getJSONObject("cats").getJSONObject("ends").getJSONObject("right").put("image", "a/b") })
        assertNull(overlay { it.getJSONObject("sounds").put("gainDb", 12) })
        assertNull(overlay { it.getJSONObject("sounds").getJSONObject("files").put("key", org.json.JSONArray(listOf("space-1"))) })
        assertNull(overlay { it.getJSONObject("sounds").getJSONObject("files").put("ping", org.json.JSONArray(listOf("ping-1"))) })
        assertNull(overlay { it.getJSONObject("names").put("hant", "") })
    }

    @Test
    fun releaseBuildsRefuseALibraryThatTrustsTestKeys() {
        // debuggable, accepts test keys: a debug build
        assertTrue(SkinOverlay.trustAllowed(debuggable = true, acceptsTestKeys = true))
        // debuggable, no test keys
        assertTrue(SkinOverlay.trustAllowed(debuggable = true, acceptsTestKeys = false))
        // a release build with a release library
        assertTrue(SkinOverlay.trustAllowed(debuggable = false, acceptsTestKeys = false))
        // a release build that somehow links the test-keys library: no skin is loaded
        assertFalse(SkinOverlay.trustAllowed(debuggable = false, acceptsTestKeys = true))
    }

    @Test
    fun parsesExtendedSpaceMarkParticlesLeftEndAndWholeBodySprites() {
        val o = overlay { root ->
            root.getJSONObject("keys")
                .put("spaceMark", JSONObject().put("image", "space_mark").put("size", org.json.JSONArray(listOf(22, 22))))
                .put("particles", JSONObject()
                    .put("kind", "petal")
                    .put("light", org.json.JSONArray(listOf("#FFB7C5", "#FFD4DF")))
                    .put("dark", org.json.JSONArray(listOf("#E88BA0", "#C86D84"))))
            val cats = root.getJSONObject("cats")
            cats.getJSONObject("ends")
                .put("left", JSONObject().put("image", "left").put("size", org.json.JSONArray(listOf(24, 24))))
            cats.getJSONObject("sprites")
                .put("momo_grab", JSONObject().put("image", "momo_grab").put("size", org.json.JSONArray(listOf(64, 64))).put("anchor", org.json.JSONArray(listOf(0.5, 0.24)))
                    .put("bounds", org.json.JSONArray(listOf(0.1, 0.2, 0.9, 0.8))))
                .put("momo_happy", JSONObject().put("image", "momo_happy").put("size", org.json.JSONArray(listOf(64, 64))).put("anchor", org.json.JSONArray(listOf(0.5, 0.88))))
        }!!
        assertEquals("space_mark", o.spaceMark?.image)
        assertEquals(22f, o.spaceMark?.width)
        assertEquals(KeyParticleKind.PETAL, o.keyParticles?.kind)
        assertEquals(2, o.keyParticles?.palette(dark = false)?.size)
        assertEquals(2, o.keyParticles?.palette(dark = true)?.size)
        assertEquals("left", o.leftEnd?.image)
        assertEquals(setOf("momo_grab", "momo_happy"), o.extraSprites.keys)
        val grabSprite = o.extraSprites.getValue("momo_grab").sprite
        val happySprite = o.extraSprites.getValue("momo_happy").sprite
        assertEquals(0.24f, grabSprite.anchorY, 1e-6f)
        assertEquals(0.1f, grabSprite.left, 1e-6f)
        assertEquals(0.8f, grabSprite.bottom, 1e-6f)
        assertEquals(0f, happySprite.left, 1e-6f)
        assertEquals(1f, happySprite.bottom, 1e-6f)
        assertTrue(o.images.containsAll(listOf("space_mark", "left", "momo_grab", "momo_happy")))
        assertNull(overlay { it.getJSONObject("keys").put("particles", JSONObject().put("kind", "confetti").put("light", org.json.JSONArray(listOf("#FFB7C5"))).put("dark", org.json.JSONArray(listOf("#E88BA0")))) })
    }
}

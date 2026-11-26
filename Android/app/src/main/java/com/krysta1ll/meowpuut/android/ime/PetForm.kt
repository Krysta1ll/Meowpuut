package com.krysta1ll.meowpuut.android.ime

/**
 * Decouples pet visual form (`3d` vs `pixel`) from [KeyboardSkin] so every skin shares
 * a unified cat system while allowing separate 3D and pixel artwork sets per skin.
 * Pure Kotlin (no Android classes), fully covered by JVM unit tests.
 */
enum class PetForm(val key: String, val assetPrefix: String) {
    THREE_D("3d", "3d"),
    PIXEL("pixel", "pixel");

    companion object {
        fun of(key: String?): PetForm = entries.firstOrNull { it.key == key } ?: THREE_D
    }
}

/**
 * User preference persisted in [Prefs.PET_FORM]:
 * - [AUTO]: follow the skin's default art style (pixel for Pixel/Glyph skins, 3D otherwise).
 * - [THREE_D]: always render the 3D form across all skins.
 * - [PIXEL]: always render the pixel form across all skins.
 */
enum class PetFormPreference(val key: String) {
    AUTO("auto"),
    THREE_D("3d"),
    PIXEL("pixel");

    fun resolved(skin: KeyboardSkin, effects: EffectsLevel = EffectsLevel.STANDARD): PetForm =
        if (effects == EffectsLevel.ULTRA && skin != KeyboardSkin.GLYPH) PetForm.THREE_D
        else when (this) {
            THREE_D -> PetForm.THREE_D
            PIXEL -> PetForm.PIXEL
            AUTO -> if (skin.catArtwork == SkinCatArtwork.PIXEL) PetForm.PIXEL else PetForm.THREE_D
        }

    fun resolvedArtwork(skin: KeyboardSkin, effects: EffectsLevel = EffectsLevel.STANDARD): SkinCatArtwork =
        if (effects == EffectsLevel.ULTRA && skin != KeyboardSkin.GLYPH) SkinCatArtwork.SCULPTED
        else when (this) {
            AUTO -> skin.catArtwork
            PIXEL -> SkinCatArtwork.PIXEL
            THREE_D -> if (skin.catArtwork == SkinCatArtwork.PIXEL) SkinCatArtwork.SCULPTED else skin.catArtwork
        }

    companion object {
        fun of(key: String?): PetFormPreference = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

/**
 * Unified skin-shared asset resolver with deterministic 3-tier priority:
 * 1. `<form>_<skin>_<cat>_<pose>` (skin-specific artwork for this form)
 * 2. `<form>_<cat>_<pose>`        (shared default artwork for this form)
 * 3. Existing built-in fallback asset for that form and skin
 */
object PetAssetResolver {
    fun skinKey(skin: KeyboardSkin): String = when (skin) {
        KeyboardSkin.CURRENT -> "daily"
        KeyboardSkin.CLASSIC_CLOUD -> "classic"
        KeyboardSkin.PIXEL -> "pixel"
        KeyboardSkin.CENTENARY -> "centenary"
        KeyboardSkin.GLYPH -> "glyph"
    }

    fun candidateAssetNames(form: PetForm, skin: KeyboardSkin, cat: Cat, pose: String): List<String> {
        val names = ArrayList<String>(6)
        fun addUnique(name: String) {
            if (name !in names) names += name
        }
        val prefix = form.assetPrefix
        val sk = skinKey(skin)
        val ck = cat.key

        // Tier 1: <form>_<skin>_<cat>_<pose>
        addUnique("${prefix}_${sk}_${ck}_$pose")
        // Tier 2: <form>_<cat>_<pose>
        addUnique("${prefix}_${ck}_$pose")

        // Tier 3: Existing built-in fallbacks
        when (form) {
            PetForm.PIXEL -> {
                addUnique("pixel_${sk}_$ck")
                addUnique("pixel_$ck")
            }
            PetForm.THREE_D -> {
                when (skin.catArtwork) {
                    SkinCatArtwork.CENTENARY -> addUnique("centenary_${ck}_${centenaryPose(pose)}")
                    SkinCatArtwork.LEGACY -> addUnique(classicFallback(cat, pose))
                    SkinCatArtwork.SCULPTED, SkinCatArtwork.PIXEL -> Unit
                }
                addUnique("${ck}_$pose")
                addUnique("${ck}_edge_peek")
            }
        }
        return names
    }

    fun resolveAssetName(
        form: PetForm,
        skin: KeyboardSkin,
        cat: Cat,
        pose: String,
        exists: (String) -> Boolean,
    ): String {
        val candidates = candidateAssetNames(form, skin, cat, pose)
        return candidates.firstOrNull(exists) ?: candidates.last()
    }

    fun pixelSprite(name: String, cat: Cat): CatSprite = CatSprite(
        name = name,
        width = 44f,
        height = 44f,
        anchorX = 0.5f,
        anchorY = 0.92f,
        left = 0.07f,
        top = if (cat == Cat.MIMIMO) 0.09f else 0.035f,
        right = 0.965f,
        bottom = 1f,
        cutLeft = 0.18f,
        cutRight = 0.82f,
        paws = listOf(floatArrayOf(0.32f, 0.12f, 0.075f), floatArrayOf(0.68f, 0.12f, 0.075f)),
    )

    fun resolveSeatedSprite(
        form: PetForm,
        skin: KeyboardSkin,
        cat: Cat,
        pose: CatPose,
        customSprite: CatSprite? = null,
        exists: (String) -> Boolean,
    ): CatSprite {
        val poseName = "edge_${pose.key}"
        return when (form) {
            PetForm.PIXEL -> {
                val resolved = resolveAssetName(PetForm.PIXEL, skin, cat, poseName, exists)
                pixelSprite(resolved, cat)
            }
            PetForm.THREE_D -> {
                if (customSprite != null) return customSprite
                val base = when (skin.catArtwork) {
                    SkinCatArtwork.CENTENARY -> CentenaryCats.sprite(cat, pose)
                    SkinCatArtwork.LEGACY -> cat.classicSprite()
                    SkinCatArtwork.SCULPTED, SkinCatArtwork.PIXEL -> pose.sprite(cat)
                }
                val preferred = candidateAssetNames(PetForm.THREE_D, skin, cat, poseName).take(2)
                val override = preferred.firstOrNull(exists)
                if (override != null) base.renamed(override) else base
            }
        }
    }

    fun resolveReactionAsset(
        form: PetForm,
        skin: KeyboardSkin,
        cat: Cat,
        pose: CatBodyPose,
        exists: (String) -> Boolean,
    ): String {
        val suffix = reactionSuffix(cat, pose)
        return resolveAssetName(form, skin, cat, suffix, exists)
    }

    private fun reactionSuffix(cat: Cat, pose: CatBodyPose): String = when (pose) {
        CatBodyPose.IDLE -> "idle"
        CatBodyPose.GREET -> if (cat == Cat.MIMIMO) "press" else "wave"
        CatBodyPose.HAPPY -> "happy"
        CatBodyPose.JUMP -> if (cat == Cat.MOMO) "jump" else "happy"
        CatBodyPose.SLEEP -> "sleep"
    }

    private fun centenaryPose(pose: String): String = when (pose) {
        "edge_lounge", "lounge", "sleep", "doze" -> "doze"
        "press", "wave", "bow" -> "bow"
        "happy", "jump", "cheer" -> "cheer"
        else -> "idle"
    }

    private fun classicFallback(cat: Cat, pose: String): String = when (pose) {
        "sleep", "doze" -> "classic_${cat.key}_blink"
        "happy", "jump", "press", "wave", "cheer", "bow" -> "classic_${cat.key}_happy"
        else -> "classic_${cat.key}_open"
    }

    private fun CatSprite.renamed(newName: String): CatSprite = CatSprite(
        newName, width, height, anchorX, anchorY, left, top, right, bottom, cutLeft, cutRight, paws,
    )
}

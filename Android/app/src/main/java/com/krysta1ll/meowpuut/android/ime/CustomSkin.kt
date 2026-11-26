package com.krysta1ll.meowpuut.android.ime

import org.json.JSONArray
import org.json.JSONObject

// Custom skins (plans/custom-skins.md §3.5, §4): what a verified, installed
// skin tells the keyboard, as plain values laid over a built-in skin's
// definition. Nothing here reads a file or touches android.*, so the JVM unit
// tests cover the whole mapping. The files are read, after verification, by
// CustomSkinLoader; the keyboard's key-press path never gets here.

/** "<id>@<rev>": how the settings name an installed skin. Re-parsed everywhere it is used (§4.1). */
data class SkinRef(val id: String, val rev: Long) {
    override fun toString() = "$id@$rev"

    companion object {
        private val PATTERN = Regex("^([a-z0-9_-]{1,64})@(0|[1-9][0-9]{0,9})$")

        /** Null unless [text] is a lower-case name and a decimal u32 revision: never a path. */
        fun parse(text: String?): SkinRef? {
            val match = PATTERN.matchEntire(text ?: return null) ?: return null
            val rev = match.groupValues[2].toLongOrNull()?.takeIf { it in 0..0xFFFF_FFFFL } ?: return null
            return SkinRef(match.groupValues[1], rev)
        }

        /** A file stem or skin id as the validator allows it. */
        fun isName(text: String) = text.length in 1..64 && text.all { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-' }
    }
}

/** One appearance's colours (`palette.light` / `palette.dark`). */
data class SkinPalette(
    val paper: Int, val paper2: Int, val ink: Int, val inkSecondary: Int,
    val pillTop: Int, val pillBottom: Int, val pillText: Int, val stamp: Int, val accent: Int,
)

/** One appearance's key colours (`keys.light` / `keys.dark`). */
data class SkinKeys(val character: Int, val utility: Int, val text: Int)

/** A cat picture the skin replaces: its image stem and the geometry the installer measured. */
data class SkinCatSprite(val image: String, val sprite: CatSprite)

/** An optional spacebar sticker or corner ornament: image stem and size in dp. */
data class SkinEnd(val image: String, val width: Float, val height: Float) {
    fun piece() = FestivalPiece(image, width, height)
}

/** Optional keystroke particle emitter (`keys.particles`): shape kind + light/dark ARGB palettes. */
data class SkinKeyParticles(val kind: KeyParticleKind, val light: IntArray, val dark: IntArray) {
    fun palette(dark: Boolean): IntArray = if (dark) this.dark else light

    override fun equals(other: Any?): Boolean =
        other is SkinKeyParticles && kind == other.kind && light.contentEquals(other.light) && dark.contentEquals(other.dark)

    override fun hashCode(): Int = 31 * (31 * kind.hashCode() + light.contentHashCode()) + dark.contentHashCode()
}

/** Sound roles to stems of `<stem>.wav`, and the skin's gain. */
data class SkinSounds(val files: Map<String, List<String>>, val gainDb: Float) {
    /** Linear gain, never above 1: SoundPool has no headroom, so a +3 dB skin just plays at the user's volume. */
    val gain: Float get() = Math.pow(10.0, gainDb / 20.0).toFloat().coerceAtMost(1f)
}

/**
 * A verified skin laid over its base. The manifest read here is the installer's
 * rewritten one (canonical JSON, measurements included); its values were
 * validated at install and are signed, and are checked for sanity once more.
 */
data class SkinOverlay(
    val ref: SkinRef,
    val names: Map<String, String>,
    val base: KeyboardSkin,
    val festivalsAllowed: Boolean,
    val light: SkinPalette,
    val dark: SkinPalette,
    val contrastPill: Int,
    val glassShape: GlassShape,
    val lockShape: Boolean,
    val strength: GlassStrength,
    val deckTint: Float,
    val keyTint: Float,
    val keyRim: Boolean,
    val keysLight: SkinKeys,
    val keysDark: SkinKeys,
    val seatingEdges: Boolean,
    /** Replacement cat pictures by sprite name (`mimimo_edge_peek` …): the three perched poses of each cat. */
    val sprites: Map<String, SkinCatSprite>,
    val rightEnd: SkinEnd?,
    val sounds: SkinSounds?,
    val spaceMark: SkinEnd? = null,
    val keyParticles: SkinKeyParticles? = null,
    /** Optional whole-body interaction poses (`mimimo_grab`, `momo_grab`, `*_happy`, `*_sleep`). */
    val extraSprites: Map<String, SkinCatSprite> = emptyMap(),
    val leftEnd: SkinEnd? = null,
) {
    fun palette(dark: Boolean) = if (dark) this.dark else light
    fun keys(dark: Boolean) = if (dark) keysDark else keysLight

    /** The skin's shape: its own when locked or when the person has not chosen one. */
    fun shape(preferred: GlassShape, chosen: Boolean) = if (lockShape || !chosen) glassShape else preferred

    fun name(simplified: Boolean) = names[if (simplified) "hans" else "hant"] ?: names["en"] ?: ref.id

    /** Every image stem the skin uses, for the loader. */
    val images: List<String> get() =
        sprites.values.map { it.image } +
        extraSprites.values.map { it.image } +
        listOfNotNull(leftEnd?.image, rightEnd?.image, spaceMark?.image)

    companion object {
        /** The perched poses a skin may replace; `*_side_hold` is held by the lifted cat's mesh, which has fixed paw landmarks. */
        val SPRITE_NAMES = listOf("mimimo", "momo").flatMap { cat -> listOf("peek", "rest", "lounge").map { "${cat}_edge_$it" } }

        /** Whole-body interactive poses a skin may optionally supply (`*_grab`, `*_happy`, `*_sleep`). */
        val EXTRA_SPRITE_NAMES = listOf("mimimo", "momo").flatMap { cat -> listOf("grab", "happy", "sleep").map { "${cat}_$it" } }

        /** The test-keys policy of §9.5: a release build never trusts a skin signed by a test key. */
        fun trustAllowed(debuggable: Boolean, acceptsTestKeys: Boolean) = debuggable || !acceptsTestKeys

        /** Parses an installed manifest.json; null when anything is missing or out of range. */
        fun parse(text: String, ref: SkinRef): SkinOverlay? = try {
            read(JSONObject(text), ref)
        } catch (_: Exception) {
            null
        }

        private fun read(m: JSONObject, ref: SkinRef): SkinOverlay? {
            if (m.getInt("schema") != 1 || m.getString("id") != ref.id || m.getLong("rev") != ref.rev) return null
            val base = when (m.getString("base")) {
                "current" -> KeyboardSkin.CURRENT
                "classic" -> KeyboardSkin.CLASSIC_CLOUD
                "pixel" -> KeyboardSkin.PIXEL
                else -> return null
            }
            val festivalsAllowed = m.optString("festivals", "suppress") == "allow" && base == KeyboardSkin.CURRENT
            val names = m.getJSONObject("names").let { n -> listOf("hant", "hans", "ja", "en").associateWith { n.getString(it) } }
            if (names.values.any { it.isEmpty() || it.length > 96 }) return null
            val p = m.getJSONObject("palette")
            val glass = m.getJSONObject("glass")
            val keys = m.getJSONObject("keys")
            val cats = m.getJSONObject("cats")
            return SkinOverlay(
                ref = ref,
                names = names,
                base = base,
                festivalsAllowed = festivalsAllowed,
                light = palette(p.getJSONObject("light")),
                dark = palette(p.getJSONObject("dark")),
                contrastPill = color(p.getString("contrastPill")),
                glassShape = GlassShape.entries.first { it.key == glass.getString("shape") },
                lockShape = glass.getBoolean("lockShape"),
                strength = GlassStrength.entries.first { it.key == glass.getString("strength") },
                deckTint = fraction(glass.getDouble("deckTint"), 0.12f),
                keyTint = fraction(keys.getDouble("tint"), 0.22f),
                keyRim = keys.getBoolean("rim"),
                keysLight = keys(keys.getJSONObject("light")),
                keysDark = keys(keys.getJSONObject("dark")),
                seatingEdges = when (cats.getString("seating")) {
                    "edges" -> true
                    "perched" -> false
                    else -> return null
                },
                sprites = sprites(cats.optJSONObject("sprites")),
                rightEnd = cats.optJSONObject("ends")?.optJSONObject("right")?.let { end(it) },
                sounds = m.optJSONObject("sounds")?.let { sounds(it) },
                spaceMark = keys.optJSONObject("spaceMark")?.let { end(it) },
                keyParticles = keys.optJSONObject("particles")?.let { particles(it) },
                extraSprites = extraSprites(cats.optJSONObject("sprites")),
                leftEnd = cats.optJSONObject("ends")?.optJSONObject("left")?.let { end(it) },
            )
        }

        private fun color(text: String): Int {
            require(text.length == 7 && text[0] == '#' && text.substring(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            return Look.parseColor(text)!!
        }

        private fun fraction(value: Double, max: Float): Float {
            require(value.isFinite() && value >= 0.0 && value <= max + 1e-6)
            return value.toFloat()
        }

        private fun palette(o: JSONObject): SkinPalette {
            val pill = o.getJSONArray("pill")
            require(pill.length() == 2)
            return SkinPalette(
                paper = color(o.getString("paper")), paper2 = color(o.getString("paper2")),
                ink = color(o.getString("ink")), inkSecondary = color(o.getString("inkSecondary")),
                pillTop = color(pill.getString(0)), pillBottom = color(pill.getString(1)),
                pillText = color(o.getString("pillText")), stamp = color(o.getString("stamp")), accent = color(o.getString("accent")),
            )
        }

        private fun keys(o: JSONObject) = SkinKeys(color(o.getString("character")), color(o.getString("utility")), color(o.getString("text")))

        private fun numbers(a: JSONArray, count: Int, lo: Float, hi: Float): List<Float> {
            require(a.length() == count)
            return (0 until count).map {
                val v = a.getDouble(it)
                require(v.isFinite() && v >= lo && v <= hi)
                v.toFloat()
            }
        }

        private fun sprites(o: JSONObject?): Map<String, SkinCatSprite> {
            o ?: return emptyMap()
            val out = LinkedHashMap<String, SkinCatSprite>()
            for (name in SPRITE_NAMES) {
                val s = o.optJSONObject(name) ?: continue
                val image = s.getString("image")
                require(SkinRef.isName(image))
                val size = numbers(s.getJSONArray("size"), 2, 8f, 128f)
                val anchor = numbers(s.getJSONArray("anchor"), 2, 0f, 1f)
                val bounds = numbers(s.getJSONArray("bounds"), 4, 0f, 1f)
                val cut = numbers(s.getJSONArray("cut"), 2, 0f, 1f)
                val paws = s.getJSONArray("paws").let { list ->
                    require(list.length() in 1..4)
                    (0 until list.length()).map { i ->
                        val paw = numbers(list.getJSONArray(i), 3, 0f, 1f)
                        floatArrayOf(paw[0], paw[1], paw[2])
                    }
                }
                require(bounds[0] < bounds[2] && bounds[1] < bounds[3] && cut[0] < cut[1])
                out[name] = SkinCatSprite(image, CatSprite(name, size[0], size[1], anchor[0], anchor[1],
                    bounds[0], bounds[1], bounds[2], bounds[3], cut[0], cut[1], paws))
            }
            return out
        }

        private fun end(o: JSONObject): SkinEnd {
            val image = o.getString("image")
            require(SkinRef.isName(image))
            val size = numbers(o.getJSONArray("size"), 2, 8f, 64f)
            return SkinEnd(image, size[0], size[1])
        }

        private fun extraSprites(o: JSONObject?): Map<String, SkinCatSprite> {
            o ?: return emptyMap()
            val out = LinkedHashMap<String, SkinCatSprite>()
            for (name in EXTRA_SPRITE_NAMES) {
                val s = o.optJSONObject(name) ?: continue
                val image = s.getString("image")
                require(SkinRef.isName(image))
                val size = numbers(s.getJSONArray("size"), 2, 8f, 128f)
                val anchor = numbers(s.getJSONArray("anchor"), 2, 0f, 1f)
                val bounds = s.optJSONArray("bounds")?.let {
                    numbers(it, 4, 0f, 1f).also { b -> require(b[0] < b[2] && b[1] < b[3]) }
                } ?: listOf(0f, 0f, 1f, 1f)
                out[name] = SkinCatSprite(image, CatSprite(name, size[0], size[1], anchor[0], anchor[1],
                    bounds[0], bounds[1], bounds[2], bounds[3], 0f, 1f, emptyList()))
            }
            return out
        }

        private fun particles(o: JSONObject): SkinKeyParticles {
            val kind = when (o.getString("kind")) {
                "petal" -> KeyParticleKind.PETAL
                "sparkle" -> KeyParticleKind.SPARKLE
                "snow" -> KeyParticleKind.SNOW
                else -> throw IllegalArgumentException("particle kind")
            }
            fun paletteArray(key: String): IntArray {
                val arr = o.getJSONArray(key)
                require(arr.length() in 1..4)
                return IntArray(arr.length()) { color(arr.getString(it)) }
            }
            return SkinKeyParticles(kind, paletteArray("light"), paletteArray("dark"))
        }

        private fun sounds(o: JSONObject): SkinSounds {
            val roles = o.getJSONObject("files")
            val files = LinkedHashMap<String, List<String>>()
            for (role in roles.keys()) {
                val list = roles.getJSONArray(role)
                require(role in SOUND_ROLES && list.length() in 1..20)
                files[role] = (0 until list.length()).map {
                    list.getString(it).also { stem -> require(SkinRef.isName(stem) && stem.substringBefore('-') == role) }
                }
            }
            require(files.isNotEmpty())
            val gain = o.getDouble("gainDb")
            require(gain.isFinite() && gain in -12.0..3.0)
            return SkinSounds(files, gain.toFloat())
        }

        val SOUND_ROLES = setOf("delete", "key", "key0", "key1", "key2", "key3", "key4", "key5", "pick", "return", "space")
    }
}

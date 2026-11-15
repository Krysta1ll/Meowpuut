package com.krysta1ll.meowpuut.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source check: the key-press path never touches skin I/O (plans/custom-skins.md §4.3). A custom skin is
 * verified and read once, off the main thread, when it is applied (CustomSkinLoader); a key press, a draw
 * and a sound only use what is already in memory.
 */
class SkinPathTest {
    private val ime: File = listOf("src/main/java", "app/src/main/java").map { File(it, "com/krysta1ll/meowpuut/android") }
        .first { it.isDirectory }.resolve("ime")
    private val main: File = ime.parentFile!!

    private fun source(name: String) = File(ime, name).readText()

    /**
     * The block body of every `fun` in [text], by name and position (brace matching; the braces in this code
     * base's string templates are balanced). Expression-bodied functions are skipped.
     */
    private fun functions(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        val header = Regex("""(?m)^[ \t]*(?:(?:private|internal|override|protected|public|inline)\s+)*fun\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)\s*\(""")
        for (match in header.findAll(text)) {
            // Past the parameter list.
            var i = match.range.last + 1
            var parens = 1
            while (i < text.length && parens > 0) {
                when (text[i]) { '(' -> parens++; ')' -> parens-- }
                i++
            }
            val rest = text.substring(i, text.indexOf('\n', i).let { if (it < 0) text.length else it })
            val brace = rest.indexOf('{')
            if (brace < 0 || rest.substring(0, brace).contains('=')) continue
            var k = i + brace
            var depth = 0
            val start = k
            while (k < text.length) {
                when (text[k]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) break } }
                k++
            }
            out[match.groupValues[1] + "@" + match.range.first] = text.substring(start, minOf(k + 1, text.length))
        }
        return out
    }

    private val skinIo = listOf("SkinNative", "SkinVault", "SkinKeystore", "CustomSkinLoader", "SkinRedeemer", "loadNow",
        "BitmapFactory", "FileInputStream", "FileOutputStream", "readBytes", "readText", "openFd", "decodeFile", "decodeStream",
        "java.io.File", "Files.", "assets")

    @Test
    fun theKeyHandlingFilesHaveNoFileOrSkinStorageAccess() {
        for (name in listOf("Keys.kt", "KeyTouches.kt", "KeyStates.kt", "KeysView.kt", "KeyHaptics.kt")) {
            val text = source(name)
            for (token in skinIo) assertTrue("$name must not use $token", !text.contains(token))
        }
    }

    @Test
    fun playingASoundOnlyUsesWhatIsLoaded() {
        val sounds = source("KeySounds.kt")
        val play = functions(sounds).filterKeys { it.startsWith("play@") }.values.single()
        for (token in listOf("load(", "openFd", "File", "assets", "Native", "Vault", "Loader", "SoundPool.Builder", "reload", "release", "readBytes")) {
            assertTrue("KeySounds.play must not use $token", !play.contains(token))
        }
    }

    @Test
    fun onlyTheLoaderAndTheVaultTouchSkinStorage() {
        val allowed = setOf("CustomSkinLoader.kt", "SkinVault.kt", "SkinKeystore.kt", "SkinNative.kt", "SkinRedeemer.kt",
            "RedeemActivity.kt", "SkinDebugHooks.kt", "BundledSkins.kt")
        val offenders = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { f -> listOf("SkinNative", "SkinVault", "SkinKeystore", "SkinRedeemer").any { f.readText().contains(it) } }
            .map { it.name }.toList()
        assertEquals("files that reach skin storage or the core", emptyList<String>(), offenders)
    }

    @Test
    fun theKeyboardServiceLoadsASkinOnlyFromItsOwnSetupAndPreferenceChange() {
        val text = source("MeowpuutIME.kt")
        val functions = functions(text).mapKeys { it.key.substringBefore('@') }
        val users = functions.filter { (_, body) -> body.contains("skinLoader") || body.contains("CustomSkinLoader") }.keys
        // onCreate creates it and starts the first load, onDestroy stops it, reloadCustomSkin is the one that loads.
        assertEquals(setOf("onCreate", "onDestroy", "reloadCustomSkin"), users)
        // Reloading is reached from onCreate and from a preference change only, never from a key or a candidate.
        val callers = functions.filter { (name, body) -> name != "reloadCustomSkin" && body.contains("reloadCustomSkin()") }.keys
        assertEquals(setOf("onCreate", "onSharedPreferenceChanged"), callers)
        // What a key press may touch of a skin is in memory: look and sounds are set from the loaded value.
        val use = functions.getValue("useCustomSkin")
        assertTrue(use.contains("sprites.useSkin") && use.contains("keySounds.useSkin"))
        assertTrue(!use.contains("File") && !use.contains("Native"))
    }

    @Test
    fun theDrawingCodeNeverReadsASkinFile() {
        for (name in listOf("CandidateBarView.kt", "ExpandedPanelView.kt", "KeyboardView.kt", "Painting.kt", "PreviewOverlay.kt", "Theme.kt")) {
            val text = source(name)
            for (token in listOf("File(", "decodeFile", "FileInputStream", "readBytes", "readText", "SkinNative", "SkinVault")) {
                assertTrue("$name must not use $token", !text.contains(token))
            }
        }
        // The pictures a skin brings reach the cats as decoded bitmaps, set by the loader's owner.
        assertTrue(source("Cats.kt").contains("fun useSkin"))
    }
}

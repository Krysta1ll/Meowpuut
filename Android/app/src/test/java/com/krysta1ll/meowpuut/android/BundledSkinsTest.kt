package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.app.BundledCore
import com.krysta1ll.meowpuut.android.app.BundledInstaller
import com.krysta1ll.meowpuut.android.app.BundledSource
import com.krysta1ll.meowpuut.android.app.BundledStamps
import com.krysta1ll.meowpuut.android.app.BundledState
import com.krysta1ll.meowpuut.android.engine.SkinCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The install-at-start logic of skins shipped in the APK (BundledSkins.kt), with a stand-in for the Rust core.
 * The core's own checks (signature, kinds, test kids, revocation, rollback) are tested in the core; here the
 * answers it gives are acted on: state saved only after success, idempotent, downgrade and refusals skipped.
 */
class BundledSkinsTest {
    @get:Rule val temp = TemporaryFolder()

    private val files = LinkedHashMap<String, ByteArray>()
    private val calls = ArrayList<Triple<String, String, String>>() // package text, state in, client
    private var failOpen: String? = null
    private val source = object : BundledSource {
        override fun names() = files.keys.toList()
        override fun open(name: String): InputStream {
            if (name == failOpen) throw IOException("gone")
            return ByteArrayInputStream(files.getValue(name))
        }
    }

    /** A package here is its own answer: "ok:<id>:<rev>", "error:<code>". [newest] plays the revision the device already has. */
    private val newest = HashMap<String, Long>()
    private val core = object : BundledCore {
        override fun maxPackageLen() = 64L
        override fun install(pkg: ByteArray, stateJson: String, skinsDir: File, client: String): String {
            val text = String(pkg)
            calls += Triple(text, stateJson, client)
            val parts = text.split(":")
            if (parts[0] == "error") return """{"error":${parts[1]}}"""
            val id = parts[1]
            val rev = parts[2].toLong()
            if ((newest[id] ?: 0) > rev) return """{"error":${SkinCore.E_DOWNGRADE}}"""
            File(skinsDir, "$id/$rev").mkdirs()
            File(skinsDir, "$id/$rev/manifest.json").writeText("{}")
            newest[id] = rev
            return """{"skin":"$id@$rev","kind":"bundled","state":{"highest":{"$id":$rev}}}"""
        }
    }

    private var state = ""
    private var saves = 0
    private val store = object : BundledState {
        override fun state() = state
        override fun saveState(json: String) { state = json; saves++ }
    }
    private val stamped = HashMap<String, String>()
    private val stamps = object : BundledStamps {
        override fun get(sha256: String) = stamped[sha256]
        override fun put(sha256: String, skin: String) { stamped[sha256] = skin }
    }

    private fun installer() = BundledInstaller(source, core, store, stamps, File(temp.root, "skins"))
    private fun add(name: String, body: String) { files[name] = body.toByteArray() }

    @Test
    fun installsEveryBundledPackageAndSavesTheStateAfterEach() {
        add("a.mpskinb", "ok:aaa:1")
        add("b.mpskinb", "ok:bbb:2")
        add("readme.txt", "not a package")
        val report = installer().installAll()
        assertEquals(listOf("aaa@1", "bbb@2"), report.installed)
        assertTrue(report.changed)
        assertTrue(report.refused.isEmpty())
        assertEquals(2, calls.size)
        // The second install saw the state the first one produced.
        assertEquals("", calls[0].second)
        assertTrue(calls[1].second.contains("aaa"))
        assertEquals(SkinCore.CLIENT, calls[0].third)
        assertEquals(2, saves)
    }

    @Test
    fun startingAgainDoesNothingWhileTheSkinIsOnDisk() {
        add("a.mpskinb", "ok:aaa:1")
        installer().installAll()
        calls.clear()
        val again = installer().installAll()
        assertTrue(calls.isEmpty())
        assertFalse(again.changed)
        assertEquals(listOf("aaa@1"), again.current)
        // A skin folder that went missing is installed again.
        File(temp.root, "skins/aaa/1/manifest.json").delete()
        val repaired = installer().installAll()
        assertEquals(1, calls.size)
        assertEquals(listOf("aaa@1"), repaired.installed)
    }

    @Test
    fun aNewerPackageInANewAppVersionReplacesTheOld() {
        add("a.mpskinb", "ok:aaa:1")
        installer().installAll()
        files.clear()
        add("a.mpskinb", "ok:aaa:2")
        val report = installer().installAll()
        assertEquals(listOf("aaa@2"), report.installed)
    }

    @Test
    fun aRevisionBelowTheDevicesIsSkippedAndNeverSavesState() {
        newest["aaa"] = 5
        add("a.mpskinb", "ok:aaa:3")
        val report = installer().installAll()
        assertTrue(report.installed.isEmpty())
        assertEquals(SkinCore.E_DOWNGRADE, report.refused["a.mpskinb"])
        assertFalse(report.changed)
        assertEquals(0, saves)
        assertTrue(stamped.isEmpty())
    }

    @Test
    fun aRefusedPackageDoesNotStopTheOthersOrChangeAnything() {
        // A release build refuses a test kid; a recipient package is the wrong kind; a revoked skin; a damaged one.
        add("1-test.mpskinb", "error:${SkinCore.E_TEST_KEY}")
        add("2-kind.mpskin", "error:${SkinCore.E_KIND}")
        add("3-revoked.mpskinb", "error:${SkinCore.E_REVOKED}")
        add("4-bad.mpskinb", "error:9")
        add("5-good.mpskinb", "ok:good:1")
        val report = installer().installAll()
        assertEquals(listOf("good@1"), report.installed)
        assertEquals(SkinCore.E_TEST_KEY, report.refused["1-test.mpskinb"])
        assertEquals(SkinCore.E_KIND, report.refused["2-kind.mpskin"])
        assertEquals(SkinCore.E_REVOKED, report.refused["3-revoked.mpskinb"])
        assertEquals(9, report.refused["4-bad.mpskinb"])
        assertEquals(1, saves)
        assertEquals(1, stamped.size)
        // A refused package is tried again next time (a later build may accept it), never stamped as installed.
        calls.clear()
        installer().installAll()
        assertEquals(4, calls.size)
    }

    @Test
    fun anOversizedOrUnreadableAssetIsRefusedWithoutAskingTheCore() {
        files["big.mpskinb"] = ByteArray(65)
        add("gone.mpskinb", "ok:gone:1")
        failOpen = "gone.mpskinb"
        val report = installer().installAll()
        assertTrue(calls.isEmpty())
        assertEquals(SkinCore.E_TOO_LARGE, report.refused["big.mpskinb"])
        assertEquals(SkinCore.E_TOO_LARGE, report.refused["gone.mpskinb"])
    }

    @Test
    fun anAnswerWithoutAValidSkinNameInstallsNothing() {
        add("a.mpskinb", "ok:Bad Name:1")
        val report = installer().installAll()
        assertTrue(report.installed.isEmpty())
        assertEquals(SkinCore.E_INTERNAL, report.refused["a.mpskinb"])
        assertEquals(0, saves)
    }

    @Test
    fun aTamperedStampCannotPointOutsideTheSkinsFolder() {
        add("a.mpskinb", "ok:aaa:1")
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(files.getValue("a.mpskinb"))
            .joinToString("") { "%02x".format(it) }
        File(temp.root, "manifest.json").writeText("{}")
        stamped[sha] = "../..@1"
        val report = installer().installAll()
        // The stamp is not a skin name, so the package is installed from the core as usual.
        assertEquals(listOf("aaa@1"), report.installed)
    }
}

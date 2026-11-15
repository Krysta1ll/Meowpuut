package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.RequestBook
import com.krysta1ll.meowpuut.android.ime.Sealer
import com.krysta1ll.meowpuut.android.ime.SkinRequest
import com.krysta1ll.meowpuut.android.ime.SkinVault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The device-side storage of request keys and the monotonic state (SkinVault.kt), with a software key in place of the Keystore. */
class VaultTest {
    @get:Rule val temp = TemporaryFolder()

    private fun key(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun bytes(fill: Int, n: Int = 32) = ByteArray(n) { (fill + it).toByte() }

    @Test
    fun sealerRoundTripsAndBindsTheFileName() {
        val k = key()
        val sealer = Sealer { k }
        val plain = "state".toByteArray()
        val sealed = sealer.seal("state.bin", plain)
        assertFalse(sealed.toList().containsAll(plain.toList()) && sealed.size == plain.size)
        assertArrayEquals(plain, sealer.open("state.bin", sealed))
        // Another file's name: the same bytes do not open (a sealed file cannot be moved over another).
        assertNull(sealer.open("requests.bin", sealed))
        // A flipped bit anywhere fails.
        for (i in sealed.indices step 3) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertNull("bit in byte $i", sealer.open("state.bin", bad))
        }
        assertNull(sealer.open("state.bin", sealed.copyOf(sealed.size - 1)))
        assertNull(sealer.open("state.bin", ByteArray(0)))
        // Another key (a restored app whose Keystore key is gone).
        assertNull(Sealer { key() }.open("state.bin", sealed))
        // A fresh IV each time.
        assertFalse(sealed.contentEquals(sealer.seal("state.bin", plain)))
    }

    @Test
    fun requestBookEncodesAndDecodes() {
        val list = listOf(SkinRequest(1_700_000_000L, bytes(1), bytes(50), false), SkinRequest(1_700_000_100L, bytes(2), bytes(60), true))
        val back = RequestBook.decode(RequestBook.encode(list))!!
        assertEquals(2, back.size)
        assertEquals(1_700_000_100L, back[1].created)
        assertTrue(back[1].redeemed)
        assertFalse(back[0].redeemed)
        assertArrayEquals(bytes(1), back[0].secret)
        assertArrayEquals(bytes(60), back[1].publicKey)
        assertEquals(64, RequestBook.publics(back).size)
        assertArrayEquals(bytes(50), RequestBook.publics(back).copyOfRange(0, 32))
        assertArrayEquals(bytes(2), RequestBook.secrets(back).copyOfRange(32, 64))
        val bytes = RequestBook.encode(list)
        assertNull(RequestBook.decode(bytes.copyOf(bytes.size - 1)))
        assertNull(RequestBook.decode(ByteArray(3)))
        assertNull(RequestBook.decode(bytes.copyOf().also { it[0] = 0 }))
        assertNull(RequestBook.decode(bytes.copyOf().also { it[4] = 9 }))
    }

    @Test
    fun anUnredeemedRequestExpiresAfterThirtyDaysButARedeemedOneStays() {
        val now = 1_800_000_000L
        val day = 24 * 3600L
        val list = mutableListOf(
            SkinRequest(now - 31 * day, bytes(1), bytes(2), false),
            SkinRequest(now - 29 * day, bytes(3), bytes(4), false),
            SkinRequest(now - 400 * day, bytes(5), bytes(6), true),
        )
        assertTrue(RequestBook.prune(list, now))
        assertEquals(listOf(now - 29 * day, now - 400 * day), list.map { it.created })
        assertFalse(RequestBook.prune(list, now))
    }

    @Test
    fun onlyTheNewestEightOpenRequestsAreKept() {
        val now = 1_800_000_000L
        val list = (0 until 12).map { SkinRequest(now - it, bytes(it), bytes(100 + it), false) }.toMutableList()
        assertTrue(RequestBook.prune(list, now))
        assertEquals(RequestBook.MAX_OPEN, list.size)
        assertEquals((0 until 8).map { now - it }.toSet(), list.map { it.created }.toSet())
    }

    @Test
    fun vaultKeepsRequestsAcrossInstancesAndMarksOneRedeemed() {
        val k = key()
        var now = 1_800_000_000L
        val dir = File(temp.root, "vault")
        fun open() = SkinVault(dir, Sealer { k }) { now }
        val first = open()
        assertEquals(0, first.requests().size)
        assertEquals(0, first.publicKeys().size)
        assertEquals("", first.state())
        first.addRequest(bytes(1), bytes(11)).wipe()
        now += 10
        first.addRequest(bytes(2), bytes(12)).wipe()

        val second = open()
        val list = second.requests()
        assertEquals(2, list.size)
        assertEquals(64, second.publicKeys().size)
        assertArrayEquals(bytes(11), second.publicKeys().copyOfRange(0, 32))
        second.markRedeemed(bytes(11))
        val after = open().requests()
        assertTrue(after.first { it.publicKey.contentEquals(bytes(11)) }.redeemed)
        assertFalse(after.first { it.publicKey.contentEquals(bytes(12)) }.redeemed)
        // 31 days later the unredeemed one is gone, the redeemed one (kept so the same phone can reinstall) stays.
        now += 31 * 24 * 3600L
        val later = open()
        assertEquals(1, later.requests().size)
        assertArrayEquals(bytes(11), later.publicKeys())
    }

    @Test
    fun stateIsSealedAndSurvives() {
        val k = key()
        val dir = File(temp.root, "vault")
        val vault = SkinVault(dir, Sealer { k })
        vault.saveState("""{"highest":{"sakura_tea":3},"revoked":{"kid":[],"serial":[],"skin":[]},"seq":0,"v":1}""")
        assertEquals("""{"highest":{"sakura_tea":3},"revoked":{"kid":[],"serial":[],"skin":[]},"seq":0,"v":1}""", SkinVault(dir, Sealer { k }).state())
        val raw = File(dir, "state.bin").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("sakura_tea"))
        // No plaintext secrets either, and no temp file left behind.
        vault.addRequest(bytes(7), bytes(8)).wipe()
        assertFalse(File(dir, "requests.bin").readBytes().toList().windowed(32).any { it == bytes(7).toList() })
        assertEquals(setOf("state.bin", "requests.bin", "publics.bin"), dir.list()!!.toSet())
        // A key that is gone makes the files unreadable: an empty device, never a crash or a stale state.
        val lost = SkinVault(dir, Sealer { key() })
        assertEquals("", lost.state())
        assertEquals(0, lost.requests().size)
        assertEquals(0, lost.publicKeys().size)
        // Damage to a sealed file reads as missing too.
        File(dir, "state.bin").writeBytes(ByteArray(40))
        assertEquals("", SkinVault(dir, Sealer { k }).state())
        assertNotNull(SkinVault(dir, Sealer { k }).requests())
    }

    /** A sealer that keeps every plain buffer it was given or gave out, to look at them after the vault is done. */
    private class Recording(k: SecretKey) : Sealer({ k }) {
        val given = ArrayList<ByteArray>()
        val taken = ArrayList<ByteArray>()
        override fun seal(name: String, plain: ByteArray): ByteArray { if (name == "requests.bin") given += plain; return super.seal(name, plain) }
        override fun open(name: String, sealed: ByteArray): ByteArray? = super.open(name, sealed)?.also { if (name == "requests.bin") taken += it }
    }

    @Test
    fun decryptedRequestSecretsAreWipedFromTheBuffersTheVaultUses() {
        val sealer = Recording(key())
        val vault = SkinVault(temp.newFolder(), sealer) { 1_700_000_000L }
        vault.addRequest(bytes(1), bytes(50)).wipe()
        vault.addRequest(bytes(2), bytes(60)).wipe()
        vault.requests().forEach { it.wipe() }
        vault.markRedeemed(bytes(60))
        assertTrue(sealer.given.size >= 3 && sealer.taken.size >= 3)
        for (buffer in sealer.given + sealer.taken) assertTrue("a plain buffer was left readable", buffer.all { it == 0.toByte() })
    }

    @Test
    fun addRequestWipesTheOtherRequestsItDecryptedAndReturnsOnlyTheNewOne() {
        val vault = SkinVault(temp.newFolder(), Sealer(key().let { k -> { k } })) { 1_700_000_000L }
        vault.addRequest(bytes(1), bytes(50)).wipe()
        val callerSecret = bytes(7)
        val added = vault.addRequest(callerSecret, bytes(70))
        // The vault keeps its own copy; the caller's array is the caller's to wipe.
        assertArrayEquals(bytes(7), added.secret)
        assertTrue(added.secret !== callerSecret)
        added.wipe()
        assertTrue(added.secret.all { it == 0.toByte() })
        // What is stored is intact.
        val stored = vault.requests()
        assertEquals(2, stored.size)
        assertArrayEquals(bytes(7), stored.single { it.publicKey.contentEquals(bytes(70)) }.secret)
        stored.forEach { it.wipe() }
    }

    @Test
    fun secretsAreNeverPutInStringsOrLeftUnwipedInTheAppCode() {
        val root = listOf("src/main/java", "app/src/main/java").map { File(it, "com/krysta1ll/meowpuut/android") }.first { it.isDirectory }
        val redeemer = File(root, "app/SkinRedeemer.kt").readText()
        val vault = File(root, "ime/SkinVault.kt").readText()
        // Both entry points that hold secrets wipe in a finally block.
        for (name in listOf("fun newRequestId", "fun redeem(")) {
            val body = redeemer.substring(redeemer.indexOf(name))
            val end = body.indexOf("\n    }\n")
            assertTrue("$name wipes in finally", body.substring(0, end).contains("finally") && body.substring(0, end).contains("Arrays.fill"))
        }
        assertTrue(vault.contains("Arrays.fill(plain, 0)") && vault.contains("plain?.let { java.util.Arrays.fill"))
        // No secret is turned into text.
        for (text in listOf(redeemer, vault)) {
            for (line in text.lines().filter { Regex("""\b(secret|secrets|seed|pair)\b""").containsMatchIn(it) }) {
                assertFalse("secret in a String: $line", Regex("""toString\(|String\(|joinToString|toHex|Base64""").containsMatchIn(line) && !line.trim().startsWith("*") && !line.trim().startsWith("//"))
            }
        }
    }
}

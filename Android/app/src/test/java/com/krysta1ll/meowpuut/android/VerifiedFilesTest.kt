package com.krysta1ll.meowpuut.android

import com.krysta1ll.meowpuut.android.ime.CustomSkinLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * The loader reads each file once, right after the core's verify, and checks it against the signed list
 * that verify returns (`files`: name, length, SHA-256). A file changed after the verify, of any size, or a
 * list that names something other than a plain skin file, gives null: only bytes that match are used.
 */
class VerifiedFilesTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun entry(name: String, bytes: ByteArray) = JSONObject().put("n", name).put("len", bytes.size).put("sha256", sha(bytes))

    private fun folder(files: Map<String, ByteArray>): File {
        val dir = temp.newFolder()
        for ((name, bytes) in files) File(dir, name).writeBytes(bytes)
        return dir
    }

    private val files = linkedMapOf(
        "manifest.json" to "{\"schema\":1}".toByteArray(),
        "frame.png" to ByteArray(300) { it.toByte() },
        "key-1.wav" to ByteArray(500) { (it * 7).toByte() },
    )

    private fun list(edit: (String, ByteArray) -> JSONObject = ::entry) = JSONArray(files.map { (n, b) -> edit(n, b) })

    @Test
    fun theVerifiedBytesAreReturned() {
        val read = CustomSkinLoader.readVerified(folder(files), list())!!
        assertEquals(files.keys, read.keys)
        for ((name, bytes) in files) assertArrayEquals(name, bytes, read[name])
    }

    @Test
    fun aFileChangedAfterTheVerifyIsNotUsed() {
        val dir = folder(files)
        // Same length, one byte different (swapped after the core hashed it).
        File(dir, "key-1.wav").writeBytes(files.getValue("key-1.wav").copyOf().also { it[10] = (it[10] + 1).toByte() })
        assertNull(CustomSkinLoader.readVerified(dir, list()))
        // Longer or shorter.
        File(dir, "key-1.wav").writeBytes(files.getValue("key-1.wav") + 0)
        assertNull(CustomSkinLoader.readVerified(dir, list()))
        File(dir, "key-1.wav").writeBytes(files.getValue("key-1.wav").copyOf(499))
        assertNull(CustomSkinLoader.readVerified(dir, list()))
        // Gone.
        File(dir, "key-1.wav").delete()
        assertNull(CustomSkinLoader.readVerified(dir, list()))
    }

    @Test
    fun onlyPlainSkinFileNamesAreRead() {
        val dir = folder(files)
        File(dir.parentFile, "outside.png").writeBytes(files.getValue("frame.png"))
        for (bad in listOf("../outside.png", "a/b.png", "Frame.png", "frame.gif", ".png", "seal.bin", "")) {
            val l = list { n, b -> if (n == "frame.png") entry(bad, b) else entry(n, b) }
            assertNull(bad, CustomSkinLoader.readVerified(dir, l))
        }
        assertNull(CustomSkinLoader.readVerified(dir, null))
        assertNull(CustomSkinLoader.readVerified(dir, JSONArray()))
        // The same name twice.
        assertNull(CustomSkinLoader.readVerified(dir, JSONArray(list().toList() + entry("frame.png", files.getValue("frame.png")))))
    }

    private fun JSONArray.toList() = (0 until length()).map { getJSONObject(it) }
}

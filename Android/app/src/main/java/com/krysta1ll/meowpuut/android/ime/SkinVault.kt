package com.krysta1ll.meowpuut.android.ime

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Where the custom-skin secrets live on the device (plans/custom-skins.md §5.3, §6.2):
//   requests.bin  every request key pair (secret and public), sealed
//   publics.bin   the public keys alone, for the keyboard's binding check, sealed
//   state.bin     the monotonic state JSON (highest revision per skin, revocations), sealed
// "Sealed" = AES-256-GCM under a Keystore key that never leaves the secure hardware
// (SkinKeystore.kt). The files live in the app's private storage, which is not backed up.
// This file is plain Java I/O and javax.crypto, so the JVM tests run it with a software key.

/** AES-GCM sealing of one file under a key, the file's name as associated data. */
open class Sealer(private val key: () -> SecretKey) {
    open fun seal(name: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(plain)
        val iv = cipher.iv
        require(iv.size == IV_BYTES)
        return ByteBuffer.allocate(1 + IV_BYTES + body.size).put(VERSION).put(iv).put(body).array()
    }

    /** The plain bytes, or null when the file is not ours, was changed, or the key is gone. */
    open fun open(name: String, sealed: ByteArray): ByteArray? {
        if (sealed.size < 1 + IV_BYTES + TAG_BYTES || sealed[0] != VERSION) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, sealed, 1, IV_BYTES))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: java.security.GeneralSecurityException) {
            null
        }
    }

    private companion object {
        const val VERSION: Byte = 1
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/** One "ask the developer for a skin": a fresh X25519 key pair (§3.4). */
class SkinRequest(val created: Long, val secret: ByteArray, val publicKey: ByteArray, var redeemed: Boolean = false) {
    fun wipe() = java.util.Arrays.fill(secret, 0)
}

/** The list of requests and its sealed layout: `MPQ1`, u32 count, then per request u64 created (Unix s), u8 flags, 32 secret, 32 public. */
object RequestBook {
    const val EXPIRY_SECONDS = 30L * 24 * 3600
    /** Open (unredeemed) requests kept at once; a new one beyond this replaces the oldest. */
    const val MAX_OPEN = 8
    private const val MAGIC = 0x3151504D // "MPQ1" little endian
    private const val ENTRY = 8 + 1 + 32 + 32

    fun encode(list: List<SkinRequest>): ByteArray {
        val out = ByteBuffer.allocate(8 + list.size * ENTRY).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putInt(MAGIC).putInt(list.size)
        for (request in list) {
            out.putLong(request.created).put(if (request.redeemed) 1 else 0).put(request.secret).put(request.publicKey)
        }
        return out.array()
    }

    fun decode(bytes: ByteArray): MutableList<SkinRequest>? {
        if (bytes.size < 8) return null
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt() != MAGIC) return null
        val count = buffer.getInt()
        if (count < 0 || count > 1024 || bytes.size != 8 + count * ENTRY) return null
        val list = ArrayList<SkinRequest>(count)
        repeat(count) {
            val created = buffer.getLong()
            val flags = buffer.get().toInt()
            val secret = ByteArray(32).also { buffer.get(it) }
            val public = ByteArray(32).also { buffer.get(it) }
            list += SkinRequest(created, secret, public, flags and 1 != 0)
        }
        return list
    }

    /** Drops requests nobody redeemed within 30 days (this is the code's practical expiry), and the oldest open ones past [MAX_OPEN]. */
    fun prune(list: MutableList<SkinRequest>, nowSeconds: Long): Boolean {
        val before = list.size
        list.removeAll { r -> (!r.redeemed && nowSeconds - r.created > EXPIRY_SECONDS).also { if (it) r.wipe() } }
        val open = list.filter { !it.redeemed }.sortedBy { it.created }
        for (extra in open.take((open.size - MAX_OPEN).coerceAtLeast(0))) {
            extra.wipe()
            list.remove(extra)
        }
        return list.size != before
    }

    fun publics(list: List<SkinRequest>): ByteArray {
        val out = ByteArray(list.size * 32)
        list.forEachIndexed { i, r -> r.publicKey.copyInto(out, i * 32) }
        return out
    }

    /** Every secret, redeemed or not: a redemption tries them all, and the same device redeems the same package again. */
    fun secrets(list: List<SkinRequest>): ByteArray {
        val out = ByteArray(list.size * 32)
        list.forEachIndexed { i, r -> r.secret.copyInto(out, i * 32) }
        return out
    }
}

/** The sealed files in [dir]. Every call is serialised: the keyboard and the settings share the process. */
class SkinVault(private val dir: File, private val sealer: Sealer, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private fun read(name: String): ByteArray? = synchronized(LOCK) {
        val file = File(dir, name)
        if (!file.isFile || file.length() > MAX_FILE) return null
        try { sealer.open(name, file.readBytes()) } catch (_: IOException) { null }
    }

    private fun write(name: String, plain: ByteArray) = synchronized(LOCK) {
        dir.mkdirs()
        val temp = File(dir, "$name.tmp")
        temp.writeBytes(sealer.seal(name, plain))
        Files.move(temp.toPath(), File(dir, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** The requests, expired ones dropped; empty when the file is missing or its key is gone (then nothing it bound can open either). */
    fun requests(): MutableList<SkinRequest> = synchronized(LOCK) {
        val plain = read(REQUESTS)
        val list = try { plain?.let { RequestBook.decode(it) } ?: mutableListOf() } finally {
            // The decrypted file holds every secret: wipe it as soon as the list is built.
            plain?.let { java.util.Arrays.fill(it, 0) }
        }
        if (RequestBook.prune(list, clock())) saveRequests(list)
        list
    }

    private fun saveRequests(list: List<SkinRequest>) = synchronized(LOCK) {
        val plain = RequestBook.encode(list)
        try {
            write(REQUESTS, plain)
        } finally {
            java.util.Arrays.fill(plain, 0)
        }
        write(PUBLICS, RequestBook.publics(list))
    }

    /** Keeps a new request key pair (from SkinNative.requestKeyGenerate). */
    fun addRequest(secret: ByteArray, publicKey: ByteArray): SkinRequest = synchronized(LOCK) {
        val list = requests()
        val request = SkinRequest(clock(), secret.copyOf(), publicKey.copyOf())
        try {
            list += request
            RequestBook.prune(list, clock())
            saveRequests(list)
        } catch (failure: Throwable) {
            request.wipe()
            throw failure
        } finally {
            // The other requests' secrets were decrypted just to be written back; only the new one is returned.
            list.forEach { if (it !== request) it.wipe() }
        }
        request
    }

    fun markRedeemed(publicKey: ByteArray) = synchronized(LOCK) {
        val list = requests()
        try {
            var changed = false
            for (request in list) if (!request.redeemed && request.publicKey.contentEquals(publicKey)) {
                request.redeemed = true
                changed = true
            }
            if (changed) saveRequests(list)
        } finally {
            list.forEach { it.wipe() }
        }
    }

    /** Public request keys, 32 bytes each, for the binding check; empty when there are none. */
    fun publicKeys(): ByteArray = synchronized(LOCK) { read(PUBLICS) ?: ByteArray(0) }

    /** The monotonic state JSON ("" for a new device, which the core reads as empty). */
    fun state(): String = synchronized(LOCK) { read(STATE)?.toString(Charsets.UTF_8).orEmpty() }

    fun saveState(json: String) = synchronized(LOCK) { write(STATE, json.toByteArray(Charsets.UTF_8)) }

    private companion object {
        const val REQUESTS = "requests.bin"
        const val PUBLICS = "publics.bin"
        const val STATE = "state.bin"
        const val MAX_FILE = 256L * 1024
        val LOCK = Any()
    }
}

package com.krysta1ll.meowpuut.android.engine

import org.json.JSONObject

/**
 * JNI entry points over the custom-skin C ABI (`mps_*` in meowpuut.h, the Rust
 * feature `skin`; see src/main/cpp/meowpuut_jni.c). Only the settings app and
 * the keyboard's skin loader call these, never a key press.
 *
 * Calls that return JSON give the core's result; a failure carries an `error`
 * code (see [SkinCore.errorOf]).
 */
object SkinNative {
    init {
        System.loadLibrary("meowpuut_jni")
    }

    /** The largest `.mpskin` accepted: check a file's size against it before reading. */
    @JvmStatic external fun maxPackageLen(): Long

    /** True in a library that trusts the published test keys (debug builds); a release build must see false. */
    @JvmStatic external fun acceptsTestKeys(): Boolean

    /** False when this build has no trusted issuer for skin packages. */
    @JvmStatic external fun hasTrustedRoots(): Boolean

    /** 64 bytes, the secret then the public key; null when the system's random source fails. */
    @JvmStatic external fun requestKeyGenerate(): ByteArray?
    @JvmStatic external fun requestPublicKey(secret: ByteArray): ByteArray?

    /** The "MPR2-…" text to send to the developer (74 characters), or null. */
    @JvmStatic external fun redemptionId(publicKey: ByteArray): String?

    /** [secrets] is 32 bytes per request key; [client] is "android/<major>.<minor>". */
    @JvmStatic external fun redeem(pkg: ByteArray, secrets: ByteArray, stateJson: String, skinsDir: String, client: String): String

    /**
     * Installs a developer-signed bundled package (`.mpskinb`, shipped in the APK). Same-revision installs are
     * idempotent; a revision below the one on this device gives [SkinCore.E_DOWNGRADE]; a per-recipient package gives
     * [SkinCore.E_KIND]. [client] is "android/<major>.<minor>".
     */
    @JvmStatic external fun installBundled(pkg: ByteArray, stateJson: String, skinsDir: String, client: String): String

    /** [publics] null skips only the device-binding check; an empty array means this device has no request keys. */
    @JvmStatic external fun verifyInstalled(skinsDir: String, customSkin: String, stateJson: String, publics: ByteArray?): String
    @JvmStatic external fun listInstalled(skinsDir: String, stateJson: String, publics: ByteArray?): String
    @JvmStatic external fun remove(skinsDir: String, customSkin: String): Int
    @JvmStatic external fun mergeRevocation(file: ByteArray, stateJson: String): String
    @JvmStatic external fun errorText(code: Int): String
}

/** The core's answer: [code] 0 for success, else one of the MPS_E_* values. */
class SkinResult(val code: Int, val json: JSONObject)

object SkinCore {
    /** Result codes the settings page words for the person (the rest read as a general failure). */
    const val E_TOO_LARGE = 1
    const val E_TEST_KEY = 6
    const val E_REVOKED = 7
    const val E_NOT_FOR_THIS_DEVICE = 10
    const val E_DOWNGRADE = 18
    const val E_TOO_MANY = 19
    const val E_CLIENT = 20
    const val E_STORAGE = 22
    const val E_NOT_INSTALLED = 23
    const val E_TAMPERED = 24
    const val E_BINDING = 25
    const val E_ARGUMENT = 26
    const val E_INTERNAL = 29
    /** A bundled package where a per-recipient one is expected, or the reverse. */
    const val E_KIND = 32

    /** The client string the packages' `minClient` is checked against: the custom-skin level of this app. */
    const val CLIENT = "android/1.2"

    fun parse(text: String): SkinResult = try {
        val json = JSONObject(text)
        SkinResult(if (json.has("error")) json.optInt("error", E_INTERNAL).takeIf { it != 0 } ?: E_INTERNAL else 0, json)
    } catch (_: Exception) {
        SkinResult(E_INTERNAL, JSONObject())
    }
}

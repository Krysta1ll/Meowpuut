package com.krysta1ll.meowpuut.android.engine

/** JNI entry points over mpn_english_* (see src/main/cpp/meowpuut_jni.c); [EnglishWords] is the typed way in. */
internal object NativeEnglish {
    init {
        System.loadLibrary("meowpuut_jni")
    }

    const val COMPLETE = 0
    const val CORRECT = 1
    const val PREDICT = 2
    const val NINE_KEY = 3

    /** Maps mimo_en.bin out of the APK and opens it in place; 0 for a damaged file. */
    @JvmStatic external fun openFromFd(fd: Int, offset: Long, length: Long): Long
    @JvmStatic external fun close(handle: Long)
    @JvmStatic external fun wordCount(handle: Long): Int
    @JvmStatic external fun query(handle: Long, mode: Int, word: String, before: String, flags: Int): Array<String>
}

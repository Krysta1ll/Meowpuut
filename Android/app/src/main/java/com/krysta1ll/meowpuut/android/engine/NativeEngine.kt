package com.krysta1ll.meowpuut.android.engine

/** JNI entry points over crates/meowpuut-ffi (see src/main/cpp/meowpuut_jni.c). */
object NativeEngine {
    init {
        System.loadLibrary("meowpuut_jni")
    }

    // Engine key codes (the X11 editing keys the core understands).
    const val KEY_BACKSPACE = 0xff08
    const val KEY_RETURN = 0xff0d
    const val KEY_ESCAPE = 0xff1b

    // MPNCandidateKind
    const val KIND_EXACT = 1
    const val KIND_JAPANESE = 5

    @JvmStatic external fun lexiconFromFd(fd: Int, offset: Long, length: Long, pinyin: Boolean): Long
    @JvmStatic external fun attachPairsFromFd(lexicon: Long, fd: Int, offset: Long, length: Long): Boolean
    @JvmStatic external fun lexiconDestroy(lexicon: Long)
    @JvmStatic external fun lexiconHeapBytes(lexicon: Long): Long
    @JvmStatic external fun sessionCreate(lexicon: Long): Long
    @JvmStatic external fun nineKeySessionCreate(lexicon: Long): Long
    @JvmStatic external fun sessionDestroy(session: Long)
    @JvmStatic external fun maxInputBytes(): Int
    @JvmStatic external fun key(session: Long, key: Int): Boolean
    @JvmStatic external fun setSimplified(session: Long, simplified: Boolean)
    @JvmStatic external fun clear(session: Long)
    @JvmStatic external fun choose(session: Long, index: Int): Boolean
    @JvmStatic external fun input(session: Long): String
    @JvmStatic external fun preedit(session: Long): String
    @JvmStatic external fun takeCommitted(session: Long): String
    @JvmStatic external fun candidateCount(session: Long): Int
    @JvmStatic external fun candidates(session: Long, start: Int, count: Int): Array<String>

    /** Two ints per candidate: consumed input bytes, then MPNCandidateKind. */
    @JvmStatic external fun candidateDetails(session: Long, start: Int, count: Int): IntArray
    @JvmStatic external fun addJapaneseWord(session: Long, reading: String, text: String, weight: Int): Boolean
    @JvmStatic external fun mixedPinyin(session: Long, reading: String, simplified: Boolean): String

    /** {text, consumed bytes} of a complete leading segment, or null. */
    @JvmStatic external fun mixedPinyinPrefix(session: Long, reading: String, simplified: Boolean): Array<String>?
    @JvmStatic external fun predict(session: Long, context: String, limit: Int): Array<String>
}

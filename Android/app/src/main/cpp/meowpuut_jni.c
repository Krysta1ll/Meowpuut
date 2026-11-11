// JNI bridge from the Android client to the Rust engine's C ABI
// (crates/meowpuut-ffi/include/meowpuut.h). Strings cross the boundary as
// UTF-16 rather than JNI's modified UTF-8, so characters outside the BMP
// (CJK Extension B and later) arrive intact.
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#include "meowpuut.h"

#define FN(name) Java_com_krysta1ll_meowpuut_android_engine_NativeEngine_##name
#define SESSION(handle) ((MPNSession *)(intptr_t)(handle))

static jstring to_java(JNIEnv *env, const char *text) {
    if (!text) text = "";
    size_t bytes = strlen(text);
    jchar small[256];
    // A UTF-8 sequence never yields more UTF-16 units than bytes.
    jchar *out = bytes < 256 ? small : malloc((bytes + 1) * sizeof(jchar));
    if (!out) return (*env)->NewString(env, small, 0);
    size_t n = 0;
    const unsigned char *p = (const unsigned char *)text;
    while (*p) {
        uint32_t c = *p++;
        int extra = 0;
        if (c < 0x80) {
            extra = 0;
        } else if ((c & 0xE0) == 0xC0) {
            c &= 0x1F; extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            c &= 0x0F; extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            c &= 0x07; extra = 3;
        } else {
            c = 0xFFFD;
        }
        for (int i = 0; i < extra; i++) {
            if ((*p & 0xC0) != 0x80) { c = 0xFFFD; break; }
            c = (c << 6) | (*p++ & 0x3F);
        }
        if (c >= 0x10000 && c <= 0x10FFFF) {
            c -= 0x10000;
            out[n++] = (jchar)(0xD800 | (c >> 10));
            out[n++] = (jchar)(0xDC00 | (c & 0x3FF));
        } else {
            out[n++] = (jchar)(c > 0xFFFF ? 0xFFFD : c);
        }
    }
    jstring result = (*env)->NewString(env, out, (jsize)n);
    if (out != small) free(out);
    return result;
}

// Caller frees the result.
static char *from_java(JNIEnv *env, jstring text) {
    if (!text) {
        char *empty = malloc(1);
        if (empty) empty[0] = 0;
        return empty;
    }
    jsize length = (*env)->GetStringLength(env, text);
    const jchar *units = (*env)->GetStringChars(env, text, NULL);
    char *out = malloc((size_t)length * 3 + 1);
    if (!out || !units) {
        if (units) (*env)->ReleaseStringChars(env, text, units);
        free(out);
        return NULL;
    }
    size_t n = 0;
    for (jsize i = 0; i < length; i++) {
        uint32_t c = units[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length && units[i + 1] >= 0xDC00 && units[i + 1] <= 0xDFFF) {
            c = 0x10000 + ((c - 0xD800) << 10) + (units[i + 1] - 0xDC00);
            i++;
        } else if (c >= 0xD800 && c <= 0xDFFF) {
            c = 0xFFFD;
        }
        if (c < 0x80) {
            out[n++] = (char)c;
        } else if (c < 0x800) {
            out[n++] = (char)(0xC0 | (c >> 6));
            out[n++] = (char)(0x80 | (c & 0x3F));
        } else if (c < 0x10000) {
            out[n++] = (char)(0xE0 | (c >> 12));
            out[n++] = (char)(0x80 | ((c >> 6) & 0x3F));
            out[n++] = (char)(0x80 | (c & 0x3F));
        } else {
            out[n++] = (char)(0xF0 | (c >> 18));
            out[n++] = (char)(0x80 | ((c >> 12) & 0x3F));
            out[n++] = (char)(0x80 | ((c >> 6) & 0x3F));
            out[n++] = (char)(0x80 | (c & 0x3F));
        }
    }
    out[n] = 0;
    (*env)->ReleaseStringChars(env, text, units);
    return out;
}

static jobjectArray string_array(JNIEnv *env, jsize count) {
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    return (*env)->NewObjectArray(env, count, string_class, NULL);
}

struct asset_mapping {
    void *base;
    size_t size;
};

// Release callback of mpn_lexicon_create_from_base_in_place and mpn_lexicon_attach_pairs.
static void unmap_asset(void *context) {
    struct asset_mapping *mapping = context;
    munmap(mapping->base, mapping->size);
    free(mapping);
}

// Maps an asset straight out of the APK (stored uncompressed); returns its
// first byte, or NULL. The mapping stays valid after the caller closes fd.
static const char *map_asset(jint fd, jlong offset, jlong length, struct asset_mapping **out) {
    if (fd < 0 || offset < 0 || length <= 0) return NULL;
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) page = 4096;
    off_t aligned = (off_t)(offset - (offset % page));
    size_t delta = (size_t)(offset - aligned);
    size_t mapped = (size_t)length + delta;
    void *base = mmap(NULL, mapped, PROT_READ, MAP_PRIVATE, fd, aligned);
    if (base == MAP_FAILED) return NULL;
    struct asset_mapping *mapping = malloc(sizeof *mapping);
    if (!mapping) {
        munmap(base, mapped);
        return NULL;
    }
    mapping->base = base;
    mapping->size = mapped;
    *out = mapping;
    return (const char *)base + delta;
}

// The engine reads the mapped lexicon in place and unmaps it through
// unmap_asset once the lexicon and all its sessions are destroyed (or the load fails).
JNIEXPORT jlong JNICALL FN(lexiconFromFd)(JNIEnv *env, jclass cls, jint fd, jlong offset, jlong length, jboolean pinyin) {
    (void)env; (void)cls;
    struct asset_mapping *mapping = NULL;
    const char *data = map_asset(fd, offset, length, &mapping);
    if (!data) return 0;
    MPNLexicon *lexicon = mpn_lexicon_create_from_base_in_place(data, (size_t)length, pinyin ? 1 : 0, unmap_asset, mapping);
    return (jlong)(intptr_t)lexicon;
}

// Attaches the word-pair table (mimo_pairs.bin) before any session is made;
// unmapped like the lexicon. False when it does not belong to this lexicon.
JNIEXPORT jboolean JNICALL FN(attachPairsFromFd)(JNIEnv *env, jclass cls, jlong lexicon, jint fd, jlong offset, jlong length) {
    (void)env; (void)cls;
    struct asset_mapping *mapping = NULL;
    const char *data = map_asset(fd, offset, length, &mapping);
    if (!data) return JNI_FALSE;
    return mpn_lexicon_attach_pairs((MPNLexicon *)(intptr_t)lexicon, data, (size_t)length, unmap_asset, mapping) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL FN(lexiconDestroy)(JNIEnv *env, jclass cls, jlong lexicon) {
    (void)env; (void)cls;
    mpn_lexicon_destroy((MPNLexicon *)(intptr_t)lexicon);
}

JNIEXPORT jlong JNICALL FN(lexiconHeapBytes)(JNIEnv *env, jclass cls, jlong lexicon) {
    (void)env; (void)cls;
    return (jlong)mpn_lexicon_heap_bytes((const MPNLexicon *)(intptr_t)lexicon);
}

JNIEXPORT jlong JNICALL FN(sessionCreate)(JNIEnv *env, jclass cls, jlong lexicon) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)mpn_session_create((const MPNLexicon *)(intptr_t)lexicon);
}

JNIEXPORT void JNICALL FN(sessionDestroy)(JNIEnv *env, jclass cls, jlong session) {
    (void)env; (void)cls;
    mpn_session_destroy(SESSION(session));
}

JNIEXPORT jint JNICALL FN(maxInputBytes)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jint)mpn_max_input_bytes();
}

JNIEXPORT jboolean JNICALL FN(key)(JNIEnv *env, jclass cls, jlong session, jint key) {
    (void)env; (void)cls;
    return mpn_key(SESSION(session), key) != 0;
}

JNIEXPORT void JNICALL FN(setSimplified)(JNIEnv *env, jclass cls, jlong session, jboolean simplified) {
    (void)env; (void)cls;
    mpn_set_simplified(SESSION(session), simplified ? 1 : 0);
}

JNIEXPORT void JNICALL FN(clear)(JNIEnv *env, jclass cls, jlong session) {
    (void)env; (void)cls;
    mpn_clear(SESSION(session));
}

JNIEXPORT jboolean JNICALL FN(choose)(JNIEnv *env, jclass cls, jlong session, jint index) {
    (void)env; (void)cls;
    if (index < 0) return JNI_FALSE;
    return mpn_choose(SESSION(session), (size_t)index) != 0;
}

JNIEXPORT jstring JNICALL FN(input)(JNIEnv *env, jclass cls, jlong session) {
    (void)cls;
    return to_java(env, mpn_input(SESSION(session)));
}

JNIEXPORT jstring JNICALL FN(preedit)(JNIEnv *env, jclass cls, jlong session) {
    (void)cls;
    return to_java(env, mpn_preedit(SESSION(session)));
}

// Returns the text the engine committed and clears it.
JNIEXPORT jstring JNICALL FN(takeCommitted)(JNIEnv *env, jclass cls, jlong session) {
    (void)cls;
    jstring text = to_java(env, mpn_committed(SESSION(session)));
    mpn_clear_commit(SESSION(session));
    return text;
}

JNIEXPORT jint JNICALL FN(candidateCount)(JNIEnv *env, jclass cls, jlong session) {
    (void)env; (void)cls;
    size_t count = mpn_candidate_count(SESSION(session));
    return count > 0x7fffffff ? 0x7fffffff : (jint)count;
}

JNIEXPORT jobjectArray JNICALL FN(candidates)(JNIEnv *env, jclass cls, jlong session, jint start, jint count) {
    (void)cls;
    size_t total = mpn_candidate_count(SESSION(session));
    size_t from = start < 0 ? 0 : (size_t)start;
    size_t to = count <= 0 ? from : from + (size_t)count;
    if (to > total) to = total;
    jsize n = to > from ? (jsize)(to - from) : 0;
    jobjectArray result = string_array(env, n);
    for (jsize i = 0; i < n && result; i++) {
        jstring text = to_java(env, mpn_candidate(SESSION(session), from + (size_t)i));
        (*env)->SetObjectArrayElement(env, result, i, text);
        (*env)->DeleteLocalRef(env, text);
    }
    return result;
}

// Two ints per candidate: input bytes consumed, then MPNCandidateKind.
JNIEXPORT jintArray JNICALL FN(candidateDetails)(JNIEnv *env, jclass cls, jlong session, jint start, jint count) {
    (void)cls;
    size_t total = mpn_candidate_count(SESSION(session));
    size_t from = start < 0 ? 0 : (size_t)start;
    size_t to = count <= 0 ? from : from + (size_t)count;
    if (to > total) to = total;
    jsize n = to > from ? (jsize)(to - from) : 0;
    jint *values = malloc(sizeof(jint) * (size_t)(n > 0 ? n * 2 : 1));
    if (!values) return (*env)->NewIntArray(env, 0);
    for (jsize i = 0; i < n; i++) {
        MPNCandidateInfo info;
        memset(&info, 0, sizeof info);
        size_t index = from + (size_t)i;
        if (mpn_candidate_info(SESSION(session), index, &info, sizeof info)) {
            values[i * 2] = (jint)info.consumed_bytes;
            values[i * 2 + 1] = (jint)info.kind;
        } else {
            values[i * 2] = (jint)mpn_candidate_consumed(SESSION(session), index);
            values[i * 2 + 1] = 0;
        }
    }
    jintArray result = (*env)->NewIntArray(env, n * 2);
    if (result && n > 0) (*env)->SetIntArrayRegion(env, result, 0, n * 2, values);
    free(values);
    return result;
}

JNIEXPORT jboolean JNICALL FN(addJapaneseWord)(JNIEnv *env, jclass cls, jlong session, jstring reading, jstring text, jint weight) {
    (void)cls;
    char *r = from_java(env, reading);
    char *t = from_java(env, text);
    int added = (r && t) ? mpn_add_japanese_word(SESSION(session), r, t, weight) : 0;
    free(r);
    free(t);
    return added != 0;
}

JNIEXPORT jstring JNICALL FN(mixedPinyin)(JNIEnv *env, jclass cls, jlong session, jstring reading, jboolean simplified) {
    (void)cls;
    char *r = from_java(env, reading);
    const char *result = r ? mpn_mixed_pinyin(SESSION(session), r, simplified ? 1 : 0) : "";
    jstring text = to_java(env, result);
    free(r);
    return text;
}

// {text, consumed bytes} for a complete leading segment, or null.
JNIEXPORT jobjectArray JNICALL FN(mixedPinyinPrefix)(JNIEnv *env, jclass cls, jlong session, jstring reading, jboolean simplified) {
    (void)cls;
    char *r = from_java(env, reading);
    if (!r) return NULL;
    size_t consumed = 0;
    const char *result = mpn_mixed_pinyin_prefix(SESSION(session), r, simplified ? 1 : 0, &consumed);
    jobjectArray pair = NULL;
    if (result && result[0] && consumed > 0) {
        char digits[24];
        snprintf(digits, sizeof digits, "%zu", consumed);
        pair = string_array(env, 2);
        if (pair) {
            jstring text = to_java(env, result);
            jstring count = to_java(env, digits);
            (*env)->SetObjectArrayElement(env, pair, 0, text);
            (*env)->SetObjectArrayElement(env, pair, 1, count);
            (*env)->DeleteLocalRef(env, text);
            (*env)->DeleteLocalRef(env, count);
        }
    }
    free(r);
    return pair;
}

JNIEXPORT jobjectArray JNICALL FN(predict)(JNIEnv *env, jclass cls, jlong session, jstring context, jint limit) {
    (void)cls;
    char *c = from_java(env, context);
    size_t count = (c && limit > 0) ? mpn_predict(SESSION(session), c, (size_t)limit) : 0;
    jobjectArray result = string_array(env, (jsize)count);
    for (size_t i = 0; i < count && result; i++) {
        jstring text = to_java(env, mpn_prediction(SESSION(session), i));
        (*env)->SetObjectArrayElement(env, result, (jsize)i, text);
        (*env)->DeleteLocalRef(env, text);
    }
    free(c);
    return result;
}

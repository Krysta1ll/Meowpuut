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
#define SKIN_FN(name) Java_com_krysta1ll_meowpuut_android_engine_SkinNative_##name

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

// Attaches the English word list (mimo_en.bin) to a pinyin lexicon before any
// session is made: English words beyond mimo-latin in pinyin and nine-key
// Chinese. Unmapped like the lexicon. False for a Zhuyin lexicon or another file.
JNIEXPORT jboolean JNICALL FN(attachEnglishFromFd)(JNIEnv *env, jclass cls, jlong lexicon, jint fd, jlong offset, jlong length) {
    (void)env; (void)cls;
    struct asset_mapping *mapping = NULL;
    const char *data = map_asset(fd, offset, length, &mapping);
    if (!data) return JNI_FALSE;
    return mpn_lexicon_attach_english((MPNLexicon *)(intptr_t)lexicon, data, (size_t)length, unmap_asset, mapping) ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT jlong JNICALL FN(nineKeySessionCreate)(JNIEnv *env, jclass cls, jlong lexicon) {
    (void)env; (void)cls;
    return (jlong)(intptr_t)mpn_nine_key_session_create((const MPNLexicon *)(intptr_t)lexicon);
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

// The input as written: Zhuyin symbols with tone marks (no ˉ), or the letters.
JNIEXPORT jstring JNICALL FN(inputText)(JNIEnv *env, jclass cls, jlong session) {
    (void)cls;
    return to_java(env, mpn_input_text(SESSION(session)));
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

// ---------------------------------------------------------------------------
// The English keyboard (engine/NativeEnglish.kt over mpn_english_*). mimo_en.bin is mapped out of the
// APK and read in place; the handle owns the mapping, the lexicon and one query. Calls come from one
// thread (the keyboard's main thread). The text before the cursor is read during a call only.

#define ENGLISH_FN(name) Java_com_krysta1ll_meowpuut_android_engine_NativeEnglish_##name

struct english_handle {
    MPNEnglish *english;
    MPNEnglishQuery *query;
    struct asset_mapping *mapping;
};

JNIEXPORT jlong JNICALL ENGLISH_FN(openFromFd)(JNIEnv *env, jclass cls, jint fd, jlong offset, jlong length) {
    (void)env; (void)cls;
    struct asset_mapping *mapping = NULL;
    const char *data = map_asset(fd, offset, length, &mapping);
    if (!data) return 0;
    struct english_handle *handle = calloc(1, sizeof *handle);
    if (handle) {
        handle->english = mpn_english_open(data, (size_t)length, 0);
        handle->query = handle->english ? mpn_english_query_create() : NULL;
    }
    if (!handle || !handle->english || !handle->query) {
        if (handle && handle->english) mpn_english_close(handle->english);
        free(handle);
        unmap_asset(mapping);
        return 0;
    }
    handle->mapping = mapping;
    return (jlong)(intptr_t)handle;
}

JNIEXPORT void JNICALL ENGLISH_FN(close)(JNIEnv *env, jclass cls, jlong value) {
    (void)env; (void)cls;
    struct english_handle *handle = (struct english_handle *)(intptr_t)value;
    if (!handle) return;
    mpn_english_query_destroy(handle->query);
    mpn_english_close(handle->english);
    unmap_asset(handle->mapping);
    free(handle);
}

JNIEXPORT jint JNICALL ENGLISH_FN(wordCount)(JNIEnv *env, jclass cls, jlong value) {
    (void)env; (void)cls;
    struct english_handle *handle = (struct english_handle *)(intptr_t)value;
    return handle ? (jint)mpn_english_word_count(handle->english) : 0;
}

static jobjectArray english_result(JNIEnv *env, struct english_handle *handle, size_t count) {
    jobjectArray result = string_array(env, (jsize)count);
    for (size_t i = 0; i < count && result; i++) {
        jstring text = to_java(env, mpn_english_text(handle->query, i));
        (*env)->SetObjectArrayElement(env, result, (jsize)i, text);
        (*env)->DeleteLocalRef(env, text);
    }
    return result;
}

// mode 0 completions of `word`, 1 corrections of `word`, 2 next words (`word` unused), 3 keypad digits in `word`.
JNIEXPORT jobjectArray JNICALL ENGLISH_FN(query)(JNIEnv *env, jclass cls, jlong value, jint mode, jstring word, jstring before, jint flags) {
    (void)cls;
    struct english_handle *handle = (struct english_handle *)(intptr_t)value;
    char *w = from_java(env, word);
    char *b = from_java(env, before);
    size_t count = 0;
    if (handle && w && b) {
        uint32_t f = (uint32_t)flags;
        switch (mode) {
            case 0: count = mpn_english_complete(handle->query, handle->english, w, b, f); break;
            case 1: count = mpn_english_correct(handle->query, handle->english, w, b, f); break;
            case 2: count = mpn_english_predict(handle->query, handle->english, b, f); break;
            case 3: count = mpn_english_nine_key(handle->query, handle->english, w, b, f); break;
            default: break;
        }
    }
    jobjectArray result = english_result(env, handle, handle ? count : 0);
    free(w);
    free(b);
    return result;
}

// ---------------------------------------------------------------------------
// Pets (engine/NativePets.kt over mp_pets_*). Structs cross as primitives; no
// call allocates on the Java heap, so the cats' moments cost nothing per frame.

#define PETS(name) Java_com_krysta1ll_meowpuut_android_engine_NativePets_##name
#define PETS_HANDLE(handle) ((MPPets *)(intptr_t)(handle))

static void pets_clock(MPPetsClock *clock, jlong mono_ms, jint day, jint minute) {
    clock->mono_ms = mono_ms < 0 ? 0 : (uint64_t)mono_ms;
    clock->day = day < 0 ? 0 : (uint32_t)day;
    clock->minute = minute < 0 ? 0 : (uint32_t)minute;
}

static void pets_ambience(MPPetsAmbience *ambience, jint flags, jint quiet_ms, jint mode, jint cats) {
    ambience->flags = (uint32_t)flags;
    ambience->quiet_ms = (uint32_t)quiet_ms;  // -1 is UINT32_MAX: no key yet
    ambience->mode = mode < 0 ? 99u : (uint32_t)mode;
    ambience->cats = cats < 0 ? 0u : (uint32_t)cats;
}

// Copies a Java byte array out; the caller frees the result. *size is 0 for null.
static uint8_t *pets_bytes(JNIEnv *env, jbyteArray array, size_t *size) {
    *size = 0;
    if (!array) return NULL;
    jsize length = (*env)->GetArrayLength(env, array);
    if (length <= 0) return NULL;
    uint8_t *bytes = malloc((size_t)length);
    if (!bytes) return NULL;
    (*env)->GetByteArrayRegion(env, array, 0, length, (jbyte *)bytes);
    *size = (size_t)length;
    return bytes;
}

JNIEXPORT jlong JNICALL PETS(fromBytes)(JNIEnv *env, jclass cls, jbyteArray data, jintArray status) {
    (void)cls;
    size_t size = 0;
    uint8_t *bytes = pets_bytes(env, data, &size);
    int result = 0;
    MPPets *pets = mp_pets_from_bytes(bytes, size, &result);
    free(bytes);
    if (status && (*env)->GetArrayLength(env, status) > 0) {
        jint value = result;
        (*env)->SetIntArrayRegion(env, status, 0, 1, &value);
    }
    return (jlong)(intptr_t)pets;
}

JNIEXPORT void JNICALL PETS(free)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    mp_pets_free(PETS_HANDLE(handle));
}

JNIEXPORT jboolean JNICALL PETS(decide)(JNIEnv *env, jclass cls, jlong handle, jint trigger, jint arg,
                                        jint flags, jint quiet_ms, jint mode, jint cats,
                                        jlong mono_ms, jint day, jint minute, jlong seed, jintArray out) {
    (void)cls;
    if (!handle || !out || (*env)->GetArrayLength(env, out) < 7 || trigger < 0) return JNI_FALSE;
    MPPetsAmbience ambience;
    MPPetsClock clock;
    MPPetsPlan plan;
    memset(&plan, 0, sizeof plan);
    pets_ambience(&ambience, flags, quiet_ms, mode, cats);
    pets_clock(&clock, mono_ms, day, minute);
    if (!mp_pets_decide(PETS_HANDLE(handle), (uint32_t)trigger, arg < 0 ? 0u : (uint32_t)arg, &ambience, &clock,
                        (uint64_t)seed, &plan)) return JNI_FALSE;
    jint values[7] = {
        (jint)plan.token, (jint)plan.moment, (jint)plan.who, (jint)plan.style, (jint)plan.ripple,
        (jint)plan.duration_ms, (jint)plan.delay_ms,
    };
    (*env)->SetIntArrayRegion(env, out, 0, 7, values);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL PETS(resolve)(JNIEnv *env, jclass cls, jlong handle, jint token, jint outcome,
                                     jlong mono_ms, jint day, jint minute) {
    (void)env; (void)cls;
    MPPetsClock clock;
    pets_clock(&clock, mono_ms, day, minute);
    return (jint)mp_pets_resolve(PETS_HANDLE(handle), (uint32_t)token, outcome < 0 ? 99u : (uint32_t)outcome, &clock);
}

// uint32 result as a long: UINT32_MAX (MP_PETS_NEVER) must not turn negative.
JNIEXPORT jlong JNICALL PETS(idleDelayMs)(JNIEnv *env, jclass cls, jlong handle, jint flags, jint quiet_ms,
                                          jint mode, jint cats, jlong mono_ms, jint day, jint minute) {
    (void)env; (void)cls;
    MPPetsAmbience ambience;
    MPPetsClock clock;
    pets_ambience(&ambience, flags, quiet_ms, mode, cats);
    pets_clock(&clock, mono_ms, day, minute);
    return (jlong)mp_pets_idle_delay_ms(PETS_HANDLE(handle), &ambience, &clock);
}

JNIEXPORT jint JNICALL PETS(restPose)(JNIEnv *env, jclass cls, jint pet, jint flags, jint mode, jint cats, jint minute) {
    (void)env; (void)cls;
    MPPetsAmbience ambience;
    pets_ambience(&ambience, flags, -1, mode, cats);
    return (jint)mp_pets_rest_pose(pet < 0 ? 0u : (uint32_t)pet, &ambience, minute < 0 ? 9999u : (uint32_t)minute);
}

JNIEXPORT jint JNICALL PETS(toBytes)(JNIEnv *env, jclass cls, jlong handle, jbyteArray out) {
    (void)cls;
    if (!handle || !out) return 0;
    uint8_t buffer[MP_PETS_STATE_BYTES];
    size_t written = mp_pets_to_bytes(PETS_HANDLE(handle), buffer, sizeof buffer);
    if (written == 0 || written > sizeof buffer || (*env)->GetArrayLength(env, out) < (jsize)written) return 0;
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)written, (const jbyte *)buffer);
    return (jint)written;
}

JNIEXPORT jint JNICALL PETS(takeDelta)(JNIEnv *env, jclass cls, jlong handle, jbyteArray out) {
    (void)cls;
    if (!handle || !out) return 0;
    uint8_t buffer[MP_PETS_DELTA_BYTES];
    size_t written = mp_pets_take_delta(PETS_HANDLE(handle), buffer, sizeof buffer);
    if (written == 0 || written > sizeof buffer || (*env)->GetArrayLength(env, out) < (jsize)written) return 0;
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)written, (const jbyte *)buffer);
    return (jint)written;
}

JNIEXPORT jboolean JNICALL PETS(applyDelta)(JNIEnv *env, jclass cls, jlong handle, jbyteArray data) {
    (void)cls;
    size_t size = 0;
    uint8_t *bytes = pets_bytes(env, data, &size);
    int applied = bytes ? mp_pets_apply_delta(PETS_HANDLE(handle), bytes, size) : 0;
    free(bytes);
    return applied ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL PETS(merge)(JNIEnv *env, jclass cls, jlong handle, jbyteArray data) {
    (void)cls;
    size_t size = 0;
    uint8_t *bytes = pets_bytes(env, data, &size);
    int merged = bytes ? mp_pets_merge(PETS_HANDLE(handle), bytes, size) : 0;
    free(bytes);
    return merged ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL PETS(importLegacy)(JNIEnv *env, jclass cls, jlong handle, jlong graphemes,
                                              jlong mimimo_pets, jlong mimimo_falls, jlong momo_pets, jlong momo_falls) {
    (void)env; (void)cls;
    #define NONNEGATIVE(value) ((value) < 0 ? 0u : (uint64_t)(value))
    return mp_pets_import_legacy(PETS_HANDLE(handle), NONNEGATIVE(graphemes), NONNEGATIVE(mimimo_pets),
                                 NONNEGATIVE(mimimo_falls), NONNEGATIVE(momo_pets), NONNEGATIVE(momo_falls)) ? JNI_TRUE : JNI_FALSE;
    #undef NONNEGATIVE
}

JNIEXPORT void JNICALL PETS(clear)(JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    mp_pets_clear(PETS_HANDLE(handle));
}

#ifdef MEOWPUUT_SKIN
// ---------------------------------------------------------------------------
// Custom skins (plans/custom-skins.md): the app's SkinNative class over the
// mps_* functions of meowpuut.h. Every call is made off the key-press path.
//
// JSON-returning calls give the core's result as it is; on an error the core
// leaves {"detail":…,"error":<code>} in the buffer, and if it left nothing
// this adds {"error":<code>}. A caller tells success from failure by the
// "error" key alone.

// Overwrites memory in a way the compiler may not drop.
static void wipe(void *data, size_t length) {
    volatile unsigned char *p = data;
    while (length--) *p++ = 0;
}

// Copies a Java byte[] into malloc'ed memory (NULL for null or on failure);
// *length is set to its size. The caller frees (and wipes secrets).
static uint8_t *copy_bytes(JNIEnv *env, jbyteArray array, size_t *length) {
    *length = 0;
    if (!array) return NULL;
    jsize n = (*env)->GetArrayLength(env, array);
    uint8_t *out = malloc((size_t)(n > 0 ? n : 1));
    if (!out) return NULL;
    if (n > 0) (*env)->GetByteArrayRegion(env, array, 0, n, (jbyte *)out);
    if ((*env)->ExceptionCheck(env)) {
        free(out);
        return NULL;
    }
    *length = (size_t)n;
    return out;
}

static jbyteArray new_bytes(JNIEnv *env, const uint8_t *data, size_t length) {
    jbyteArray out = (*env)->NewByteArray(env, (jsize)length);
    if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize)length, (const jbyte *)data);
    return out;
}

static jstring skin_json(JNIEnv *env, int32_t rc, char *buffer) {
    if (rc != 0 && buffer[0] == 0) snprintf(buffer, 64, "{\"error\":%d}", (int)rc);
    jstring text = to_java(env, buffer);
    free(buffer);
    return text;
}

JNIEXPORT jlong JNICALL SKIN_FN(maxPackageLen)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)mps_skin_max_package_len();
}

JNIEXPORT jboolean JNICALL SKIN_FN(acceptsTestKeys)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return mps_skin_accepts_test_keys() != 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL SKIN_FN(hasTrustedRoots)(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return mps_skin_has_trusted_roots() != 0 ? JNI_TRUE : JNI_FALSE;
}

// 64 bytes, the secret then the public key; null on failure.
JNIEXPORT jbyteArray JNICALL SKIN_FN(requestKeyGenerate)(JNIEnv *env, jclass cls) {
    (void)cls;
    uint8_t pair[64];
    jbyteArray result = NULL;
    if (mps_request_key_generate(pair, pair + 32) == MPS_OK) result = new_bytes(env, pair, sizeof pair);
    wipe(pair, sizeof pair);
    return result;
}

JNIEXPORT jbyteArray JNICALL SKIN_FN(requestPublicKey)(JNIEnv *env, jclass cls, jbyteArray secret) {
    (void)cls;
    size_t length = 0;
    uint8_t *copy = copy_bytes(env, secret, &length);
    jbyteArray result = NULL;
    if (copy && length == 32) {
        uint8_t pub[32];
        if (mps_request_public_key(copy, pub) == MPS_OK) result = new_bytes(env, pub, sizeof pub);
    }
    if (copy) {
        wipe(copy, length);
        free(copy);
    }
    return result;
}

JNIEXPORT jstring JNICALL SKIN_FN(redemptionId)(JNIEnv *env, jclass cls, jbyteArray publicKey) {
    (void)cls;
    size_t length = 0;
    uint8_t *copy = copy_bytes(env, publicKey, &length);
    jstring result = NULL;
    if (copy && length == 32) {
        char text[128];
        size_t n = mps_redemption_id(copy, text, sizeof text);
        if (n > 0 && n < sizeof text) result = to_java(env, text);
    }
    free(copy);
    return result;
}

JNIEXPORT jstring JNICALL SKIN_FN(redeem)(JNIEnv *env, jclass cls, jbyteArray package, jbyteArray secrets,
                                          jstring state, jstring skinsDir, jstring client) {
    (void)cls;
    size_t package_len = 0, secrets_len = 0;
    uint8_t *pkg = copy_bytes(env, package, &package_len);
    uint8_t *keys = copy_bytes(env, secrets, &secrets_len);
    char *state_c = from_java(env, state);
    char *dir = from_java(env, skinsDir);
    char *client_c = from_java(env, client);
    char *buffer = calloc(1, MPS_RESULT_CAP);
    jstring result = NULL;
    if (pkg && keys && state_c && dir && client_c && buffer && secrets_len % 32 == 0) {
        int32_t rc = mps_skin_redeem(pkg, package_len, keys, secrets_len / 32, state_c, dir, client_c, buffer, MPS_RESULT_CAP);
        result = skin_json(env, rc, buffer);
        buffer = NULL;
    } else {
        free(buffer);
        buffer = NULL;
        result = to_java(env, "{\"error\":26}");
    }
    if (keys) wipe(keys, secrets_len);
    free(keys);
    free(pkg);
    free(state_c);
    free(dir);
    free(client_c);
    return result;
}

// A developer-signed package shipped inside the app (assets/skins). No secrets are involved.
JNIEXPORT jstring JNICALL SKIN_FN(installBundled)(JNIEnv *env, jclass cls, jbyteArray package, jstring state,
                                                  jstring skinsDir, jstring client) {
    (void)cls;
    size_t package_len = 0;
    uint8_t *pkg = copy_bytes(env, package, &package_len);
    char *state_c = from_java(env, state);
    char *dir = from_java(env, skinsDir);
    char *client_c = from_java(env, client);
    char *buffer = calloc(1, MPS_RESULT_CAP);
    jstring result;
    if (pkg && state_c && dir && client_c && buffer) {
        int32_t rc = mps_skin_install_bundled(pkg, package_len, state_c, dir, client_c, buffer, MPS_RESULT_CAP);
        result = skin_json(env, rc, buffer);
    } else {
        free(buffer);
        result = to_java(env, "{\"error\":26}");
    }
    free(pkg);
    free(state_c);
    free(dir);
    free(client_c);
    return result;
}

// A null publics array skips the binding check; an empty one means the device has no request keys.
static jstring verify_or_list(JNIEnv *env, int list, jstring skinsDir, jstring skin, jstring state, jbyteArray publics) {
    size_t length = 0;
    uint8_t *keys = copy_bytes(env, publics, &length);
    uint8_t none = 0;
    char *dir = from_java(env, skinsDir);
    char *name = list ? NULL : from_java(env, skin);
    char *state_c = from_java(env, state);
    char *buffer = calloc(1, MPS_RESULT_CAP);
    jstring result;
    if (dir && (list || name) && state_c && buffer && (publics == NULL || (keys && length % 32 == 0))) {
        const uint8_t *key_pointer = publics == NULL ? NULL : (length == 0 ? &none : keys);
        size_t count = publics == NULL ? 0 : length / 32;
        int32_t rc = list
            ? mps_skin_list_installed(dir, state_c, key_pointer, count, buffer, MPS_RESULT_CAP)
            : mps_skin_verify_installed(dir, name, state_c, key_pointer, count, buffer, MPS_RESULT_CAP);
        result = skin_json(env, rc, buffer);
    } else {
        free(buffer);
        result = to_java(env, "{\"error\":26}");
    }
    free(keys);
    free(dir);
    free(name);
    free(state_c);
    return result;
}

JNIEXPORT jstring JNICALL SKIN_FN(verifyInstalled)(JNIEnv *env, jclass cls, jstring skinsDir, jstring skin,
                                                   jstring state, jbyteArray publics) {
    (void)cls;
    return verify_or_list(env, 0, skinsDir, skin, state, publics);
}

JNIEXPORT jstring JNICALL SKIN_FN(listInstalled)(JNIEnv *env, jclass cls, jstring skinsDir, jstring state, jbyteArray publics) {
    (void)cls;
    return verify_or_list(env, 1, skinsDir, NULL, state, publics);
}

JNIEXPORT jint JNICALL SKIN_FN(remove)(JNIEnv *env, jclass cls, jstring skinsDir, jstring skin) {
    (void)cls;
    char *dir = from_java(env, skinsDir);
    char *name = from_java(env, skin);
    int32_t rc = (dir && name) ? mps_skin_remove(dir, name) : MPS_E_ARGUMENT;
    free(dir);
    free(name);
    return (jint)rc;
}

JNIEXPORT jstring JNICALL SKIN_FN(mergeRevocation)(JNIEnv *env, jclass cls, jbyteArray file, jstring state) {
    (void)cls;
    size_t length = 0;
    uint8_t *bytes = copy_bytes(env, file, &length);
    char *state_c = from_java(env, state);
    char *buffer = calloc(1, MPS_RESULT_CAP);
    jstring result;
    if (bytes && state_c && buffer) {
        int32_t rc = mps_revocation_merge(bytes, length, state_c, buffer, MPS_RESULT_CAP);
        result = skin_json(env, rc, buffer);
    } else {
        free(buffer);
        result = to_java(env, "{\"error\":26}");
    }
    free(bytes);
    free(state_c);
    return result;
}

JNIEXPORT jstring JNICALL SKIN_FN(errorText)(JNIEnv *env, jclass cls, jint code) {
    (void)cls;
    return to_java(env, mps_skin_error((int32_t)code));
}
#endif // MEOWPUUT_SKIN

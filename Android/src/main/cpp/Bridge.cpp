#include "MeowpuutCore.h"
#include "CandidateEvidence.h"

#include <android/asset_manager_jni.h>
#include <jni.h>
#include <cstring>
#include <memory>

namespace {
MPNSession *session(jlong handle) {
    return reinterpret_cast<MPNSession *>(handle);
}

jbyteArray utf8(JNIEnv *env, const char *value) {
    const auto size = static_cast<jsize>(std::strlen(value));
    auto bytes = env->NewByteArray(size);
    if (bytes && size) env->SetByteArrayRegion(bytes, 0, size, reinterpret_cast<const jbyte *>(value));
    return bytes;
}
}

#define JNI_METHOD(name) Java_app_meowpuut_android_MeowpuutKeyboard_##name

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(open)(
    JNIEnv *env, jclass, jobject assets, jboolean pinyin
) {
    auto *manager = AAssetManager_fromJava(env, assets);
    if (!manager) return 0;
    const auto *name = pinyin ? "native-pinyin.tsv" : "native-zhuyin.tsv";
    std::unique_ptr<AAsset, decltype(&AAsset_close)> asset(
        AAssetManager_open(manager, name, AASSET_MODE_BUFFER), AAsset_close);
    if (!asset) return 0;
    auto *lexicon = mpn_lexicon_create(static_cast<const char *>(AAsset_getBuffer(asset.get())),
                                      AAsset_getLength(asset.get()));
    auto *result = mpn_session_create(lexicon);
    mpn_lexicon_destroy(lexicon);
    if (result) mpn_require_full_candidate(result);
    return reinterpret_cast<jlong>(result);
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv *, jclass, jlong handle) {
    mpn_session_destroy(session(handle));
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(reset)(JNIEnv *, jclass, jlong handle, jboolean simplified) {
    mpn_clear(session(handle));
    mpn_set_simplified(session(handle), simplified);
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(key)(JNIEnv *, jclass, jlong handle, jint value) {
    return mpn_key(session(handle), value) != 0;
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(choose)(JNIEnv *, jclass, jlong handle, jint index) {
    try {
        mpn_choose(session(handle), index);
    } catch (...) {
        mpn_clear(session(handle));
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(takeCommit)(JNIEnv *env, jclass, jlong handle) {
    auto *value = session(handle);
    auto result = utf8(env, mpn_committed(value));
    mpn_clear_commit(value);
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(preedit)(JNIEnv *env, jclass, jlong handle) {
    return utf8(env, mpn_preedit(session(handle)));
}

extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(raw)(JNIEnv *env, jclass, jlong handle) {
    return env->NewStringUTF(mpn_input(session(handle)));
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(count)(JNIEnv *, jclass, jlong handle) {
    return static_cast<jint>(mpn_candidate_count(session(handle)));
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(candidate)(JNIEnv *env, jclass, jlong handle, jint index) {
    return utf8(env, mpn_candidate(session(handle), index));
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(setScript)(
    JNIEnv *, jclass, jlong handle, jboolean simplified
) {
    mpn_set_simplified(session(handle), simplified);
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(page)(JNIEnv *, jclass, jlong handle) {
    return static_cast<jint>(mpn_page(session(handle)));
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(sentenceCount)(JNIEnv *, jclass, jlong handle) {
    return static_cast<jint>(mpn_sentence_count(session(handle)));
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(hasExactChinese)(JNIEnv *, jclass, jlong handle) {
    return session(handle) && pinyinHasExactChinese(session(handle));
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(literalTail)(JNIEnv *, jclass, jlong handle) {
    return static_cast<jint>(mpn_literal_tail(session(handle)));
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(consumed)(JNIEnv *, jclass, jlong handle, jint index) {
    return static_cast<jint>(mpn_candidate_consumed(session(handle), index));
}

extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(exactPinyin)(
    JNIEnv *env, jclass, jlong handle, jstring reading
) {
    const char *keys = env->GetStringUTFChars(reading, nullptr);
    if (!keys) return nullptr;
    auto result = utf8(env, mpn_exact_pinyin(session(handle), keys));
    env->ReleaseStringUTFChars(reading, keys);
    return result;
}

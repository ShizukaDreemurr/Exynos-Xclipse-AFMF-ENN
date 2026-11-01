#include <android/asset_manager_jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "pipeline.h"

namespace {

std::mutex gErrMu;
std::string gCreateErr;

ff::Pipeline* P(jlong h) { return reinterpret_cast<ff::Pipeline*>(h); }

}  // namespace

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_fluidframes_vulkan_NativeBridge_##name

FN(jlong, nativeCreate)(JNIEnv* env, jclass, jobject assetManager) {
    AAssetManager* am = AAssetManager_fromJava(env, assetManager);
    std::string err;
    auto p = ff::Pipeline::Create(am, &err);
    if (!p) {
        std::lock_guard<std::mutex> l(gErrMu);
        gCreateErr = err;
        return 0;
    }
    return reinterpret_cast<jlong>(p.release());
}

FN(jstring, nativeCreateError)(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> l(gErrMu);
    return env->NewStringUTF(gCreateErr.c_str());
}

FN(void, nativeDestroy)(JNIEnv*, jclass, jlong h) { delete P(h); }

FN(jboolean, nativeSetSurface)(JNIEnv* env, jclass, jlong h, jobject surface) {
    ANativeWindow* w = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    // Pipeline nhận quyền sở hữu 1 tham chiếu và tự release.
    return P(h)->SetSurface(w) ? JNI_TRUE : JNI_FALSE;
}

FN(jboolean, nativeConfigure)(JNIEnv*, jclass, jlong h, jint w, jint hh) {
    return P(h)->Configure(w, hh) ? JNI_TRUE : JNI_FALSE;
}

FN(jint, nativeBegin)(JNIEnv* env, jclass, jlong h, jobject prev, jobject cur, jboolean nn, jboolean bm) {
    AHardwareBuffer* a = AHardwareBuffer_fromHardwareBuffer(env, prev);
    AHardwareBuffer* b = AHardwareBuffer_fromHardwareBuffer(env, cur);
    if (!a || !b) return ff::kBeginError;
    return P(h)->Begin(a, b, nn == JNI_TRUE, bm == JNI_TRUE);
}

FN(jobject, nativeNnInput)(JNIEnv* env, jclass, jlong h) {
    size_t n = 0;
    float* p = P(h)->NnInput(&n);
    return p ? env->NewDirectByteBuffer(p, static_cast<jlong>(n)) : nullptr;
}

FN(jobject, nativeNnFlow)(JNIEnv* env, jclass, jlong h) {
    size_t n = 0;
    float* p = P(h)->NnFlow(&n);
    return p ? env->NewDirectByteBuffer(p, static_cast<jlong>(n)) : nullptr;
}

FN(jlong, nativeGpuAsync)(JNIEnv* env, jclass, jlong h, jobject prev, jobject cur) {
    AHardwareBuffer* a = AHardwareBuffer_fromHardwareBuffer(env, prev);
    AHardwareBuffer* b = AHardwareBuffer_fromHardwareBuffer(env, cur);
    if (!a || !b) return -1;
    return static_cast<jlong>(P(h)->GpuAsync(a, b));
}

FN(jlong, nativeDoneSeq)(JNIEnv*, jclass, jlong h) {
    return static_cast<jlong>(P(h)->DoneSeq());
}

FN(jboolean, nativeFinish)(JNIEnv*, jclass, jlong h, jboolean interp, jboolean nnFlowReady, jfloat gapMs) {
    return P(h)->Finish(interp == JNI_TRUE, nnFlowReady == JNI_TRUE, gapMs) ? JNI_TRUE : JNI_FALSE;
}

FN(jboolean, nativePresentReal)(JNIEnv* env, jclass, jlong h, jobject cur) {
    AHardwareBuffer* b = AHardwareBuffer_fromHardwareBuffer(env, cur);
    if (!b) return JNI_FALSE;
    return P(h)->PresentReal(b) ? JNI_TRUE : JNI_FALSE;
}

FN(void, nativeStats)(JNIEnv* env, jclass, jlong h, jfloatArray out) {
    float tmp[16] = {};
    P(h)->GetStats(tmp, 16);
    const jsize n = env->GetArrayLength(out);
    env->SetFloatArrayRegion(out, 0, n < 16 ? n : 16, tmp);
}

FN(jstring, nativeLastError)(JNIEnv* env, jclass, jlong h) {
    return env->NewStringUTF(P(h)->LastError().c_str());
}

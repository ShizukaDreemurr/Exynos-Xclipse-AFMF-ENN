#include <android/asset_manager_jni.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <dlfcn.h>
#include <cstdint>

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

// Đặt chế độ hiệu năng/điện của NPU Samsung (ENN): 0 LOW_POWER, 4 BALANCED, 7 PERFORMANCE.
// LiteRT dispatch tự ép PERFORMANCE cho Exynos 2600 (9965); gọi lại sau khi nạp model để hạ xung.
FN(jstring, nativeEnnPerf)(JNIEnv* env, jclass, jint mode) {
    void* lib = dlopen("libenn_public_api_cpp.so", RTLD_NOW);
    if (!lib) { std::string m = std::string("không mở được libenn: ") + (dlerror() ? dlerror() : ""); return env->NewStringUTF(m.c_str()); }
    using Fn = int (*)(uint32_t);
    auto fn = reinterpret_cast<Fn>(dlsym(lib, "EnnSetPreferencePerfMode"));
    if (!fn) return env->NewStringUTF("không có EnnSetPreferencePerfMode");
    int ret = fn(static_cast<uint32_t>(mode));
    std::string m = "mode=" + std::to_string(mode) + " ret=" + std::to_string(ret);
    return env->NewStringUTF(m.c_str());
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

FN(void, nativeSetTune)(JNIEnv*, jclass, jlong h, jfloat st, jfloat oc, jint flags) {
    P(h)->SetTune(st, oc, flags);
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

FN(jint, nativeNnPre)(JNIEnv* env, jclass, jlong h, jobject prev, jobject cur) {
    AHardwareBuffer* a = AHardwareBuffer_fromHardwareBuffer(env, prev);
    AHardwareBuffer* b = AHardwareBuffer_fromHardwareBuffer(env, cur);
    if (!a || !b) return -1;
    return P(h)->NnPre(a, b);
}

FN(jboolean, nativeNnWait)(JNIEnv*, jclass, jlong h, jint slot) {
    return P(h)->NnWait(slot) ? JNI_TRUE : JNI_FALSE;
}

FN(jobject, nativeNnInputOf)(JNIEnv* env, jclass, jlong h, jint slot) {
    size_t n = 0;
    float* p = P(h)->NnInputOf(slot, &n);
    return p ? env->NewDirectByteBuffer(p, static_cast<jlong>(n)) : nullptr;
}

FN(jobject, nativeNnFlowOf)(JNIEnv* env, jclass, jlong h, jint slot) {
    size_t n = 0;
    float* p = P(h)->NnFlowOf(slot, &n);
    return p ? env->NewDirectByteBuffer(p, static_cast<jlong>(n)) : nullptr;
}

FN(jlong, nativeNnPost)(JNIEnv*, jclass, jlong h, jint slot) {
    return static_cast<jlong>(P(h)->NnPost(slot));
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

FN(jboolean, nativeSrInit)(JNIEnv* env, jclass, jlong h, jobject assetManager, jint w, jint hh,
                           jfloat inScale, jint inZp, jfloat outScale, jint outZp) {
    AAssetManager* am = AAssetManager_fromJava(env, assetManager);
    return P(h)->SrInit(am, w, hh, inScale, inZp, outScale, outZp) ? JNI_TRUE : JNI_FALSE;
}

FN(jobject, nativeSrIn)(JNIEnv* env, jclass, jlong h) {
    size_t n = 0;
    uint8_t* p = P(h)->SrIn(&n);
    return p ? env->NewDirectByteBuffer(p, static_cast<jlong>(n)) : nullptr;
}

FN(jobject, nativeSrOut)(JNIEnv* env, jclass, jlong h) {
    size_t n = 0;
    uint8_t* p = P(h)->SrOut(&n);
    return p ? env->NewDirectByteBuffer(p, static_cast<jlong>(n)) : nullptr;
}

FN(jboolean, nativeSrPre)(JNIEnv* env, jclass, jlong h, jobject lr) {
    AHardwareBuffer* a = AHardwareBuffer_fromHardwareBuffer(env, lr);
    return (a && P(h)->SrPre(a)) ? JNI_TRUE : JNI_FALSE;
}

FN(jboolean, nativeSrPost)(JNIEnv* env, jclass, jlong h, jobject lr, jobject token, jint slot) {
    AHardwareBuffer* a = AHardwareBuffer_fromHardwareBuffer(env, lr);
    AHardwareBuffer* t = AHardwareBuffer_fromHardwareBuffer(env, token);
    return (a && t && P(h)->SrPost(a, t, slot)) ? JNI_TRUE : JNI_FALSE;
}

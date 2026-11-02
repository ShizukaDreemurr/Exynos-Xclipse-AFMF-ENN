package com.fluidframes.vulkan

import android.content.res.AssetManager
import android.hardware.HardwareBuffer
import android.view.Surface
import java.nio.ByteBuffer

/** Khai báo JNI thô. Dùng qua [NativePipeline]. */
internal object NativeBridge {
    init {
        System.loadLibrary("fluidframes_native")
    }

    @JvmStatic external fun nativeCreate(assets: AssetManager): Long
    @JvmStatic external fun nativeCreateError(): String
    @JvmStatic external fun nativeEnnPerf(mode: Int): String
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeSetSurface(handle: Long, surface: Surface?): Boolean
    @JvmStatic external fun nativeSetTune(handle: Long, staticThr: Float, occThr: Float, flags: Int)
    @JvmStatic external fun nativeConfigure(handle: Long, procW: Int, procH: Int): Boolean
    @JvmStatic external fun nativeBegin(
        handle: Long, prev: HardwareBuffer, cur: HardwareBuffer, nn: Boolean, blockMatch: Boolean,
    ): Int
    @JvmStatic external fun nativeNnInput(handle: Long): ByteBuffer?
    @JvmStatic external fun nativeNnFlow(handle: Long): ByteBuffer?
    @JvmStatic external fun nativeFinish(handle: Long, interp: Boolean, nnFlowReady: Boolean, gapMs: Float): Boolean
    @JvmStatic external fun nativeNnPre(handle: Long, prev: HardwareBuffer, cur: HardwareBuffer): Int
    @JvmStatic external fun nativeNnWait(handle: Long, slot: Int): Boolean
    @JvmStatic external fun nativeNnInputOf(handle: Long, slot: Int): ByteBuffer?
    @JvmStatic external fun nativeNnFlowOf(handle: Long, slot: Int): ByteBuffer?
    @JvmStatic external fun nativeNnPost(handle: Long, slot: Int): Long
    @JvmStatic external fun nativeGpuAsync(handle: Long, prev: HardwareBuffer, cur: HardwareBuffer): Long
    @JvmStatic external fun nativeDoneSeq(handle: Long): Long
    @JvmStatic external fun nativePresentReal(handle: Long, cur: HardwareBuffer): Boolean
    @JvmStatic external fun nativeStats(handle: Long, out: FloatArray)
    @JvmStatic external fun nativeLastError(handle: Long): String
}

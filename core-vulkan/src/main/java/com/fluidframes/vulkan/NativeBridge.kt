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
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeSetSurface(handle: Long, surface: Surface?): Boolean
    @JvmStatic external fun nativeConfigure(handle: Long, procW: Int, procH: Int): Boolean
    @JvmStatic external fun nativeBegin(
        handle: Long, prev: HardwareBuffer, cur: HardwareBuffer, nn: Boolean, blockMatch: Boolean,
    ): Int
    @JvmStatic external fun nativeNnInput(handle: Long): ByteBuffer?
    @JvmStatic external fun nativeNnFlow(handle: Long): ByteBuffer?
    @JvmStatic external fun nativeFinish(handle: Long, interp: Boolean, nnFlowReady: Boolean, gapMs: Float): Boolean
    @JvmStatic external fun nativePresentReal(handle: Long, cur: HardwareBuffer): Boolean
    @JvmStatic external fun nativeStats(handle: Long, out: FloatArray)
    @JvmStatic external fun nativeLastError(handle: Long): String
}

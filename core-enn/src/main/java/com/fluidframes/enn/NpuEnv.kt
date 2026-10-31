package com.fluidframes.enn

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Environment

/** Environment trỏ tới libLiteRtDispatch_Samsung.so (đóng gói trong APK, nativeLibraryDir). */
object NpuEnv {
    private const val TAG = "NpuEnv"

    fun create(context: Context): Environment? = try {
        val dir = context.applicationInfo.nativeLibraryDir
        Environment.create(
            context,
            mapOf(
                Environment.Option.DispatchLibraryDir to dir,
                Environment.Option.CompilerPluginLibraryDir to dir,
            ),
        )
    } catch (t: Throwable) {
        Log.w(TAG, "Không tạo được Environment NPU: ${t.message}")
        null
    }

    /** Mô tả: các accelerator môi trường báo có (NPU có xuất hiện không). */
    fun describe(context: Context): String {
        val dir = context.applicationInfo.nativeLibraryDir
        val files = java.io.File(dir).list()?.filter { it.contains("LiteRt") || it.contains("samsung", true) } ?: emptyList()
        val env = try { create(context) } catch (t: Throwable) { null }
        val accs = try { env?.getAvailableAccelerators()?.joinToString { it.toString() } ?: "env=null" }
        catch (t: Throwable) { "lỗi: ${t.message}" }
        return "libs in APK: ${files.joinToString()}\nAccelerators: $accs"
    }
}

package com.fluidframes.enn

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File

/**
 * Môi trường LiteRT trỏ tới libLiteRtDispatch_Samsung.so (nằm trong nativeLibraryDir của APK).
 *
 * Model đã biên dịch trước (AOT) KHÔNG cần compiler plugin trên máy (plugin đòi thư viện LiteCore
 * không có trên điện thoại) nên chiến lược mặc định chỉ khai DispatchLibraryDir.
 */
object NpuEnv {
    private const val TAG = "NpuEnv"

    enum class EnvMode { NONE, DISPATCH_ONLY, DISPATCH_AND_PLUGIN }

    /** Một cách nạp model lên NPU. Thử lần lượt, cách nào chạy được thì dùng. */
    data class Strategy(
        val id: String,
        val label: String,
        val env: EnvMode,
        val fromFile: Boolean,
        val withGpuFallback: Boolean,
    )

    val strategies: List<Strategy> = listOf(
        Strategy("A", "Dispatch riêng · nạp từ assets", EnvMode.DISPATCH_ONLY, false, false),
        Strategy("B", "Dispatch riêng · nạp từ file", EnvMode.DISPATCH_ONLY, true, false),
        Strategy("C", "Dispatch + plugin · nạp từ assets", EnvMode.DISPATCH_AND_PLUGIN, false, false),
        Strategy("D", "Dispatch riêng · NPU+GPU", EnvMode.DISPATCH_ONLY, false, true),
        Strategy("E", "Môi trường mặc định · NPU", EnvMode.NONE, false, false),
    )

    fun create(context: Context, mode: EnvMode = EnvMode.DISPATCH_ONLY): Environment? = try {
        val dir = context.applicationInfo.nativeLibraryDir
        when (mode) {
            EnvMode.NONE -> Environment.create(context)
            EnvMode.DISPATCH_ONLY -> Environment.create(
                context, mapOf(Environment.Option.DispatchLibraryDir to dir))
            EnvMode.DISPATCH_AND_PLUGIN -> Environment.create(
                context, mapOf(
                    Environment.Option.DispatchLibraryDir to dir,
                    Environment.Option.CompilerPluginLibraryDir to dir))
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Không tạo được Environment ($mode): ${t.message}")
        null
    }

    fun accelerators(env: Environment?): String = try {
        env?.getAvailableAccelerators()?.joinToString { it.toString() } ?: "env=null"
    } catch (t: Throwable) { "lỗi: ${t.message}" }

    /** Tên thư viện Samsung/LiteRT có trong APK. */
    fun bundledLibs(context: Context): List<String> =
        File(context.applicationInfo.nativeLibraryDir).list()
            ?.filter { it.contains("LiteRt") || it.contains("samsung", true) }?.sorted() ?: emptyList()

    /** Nạp model theo một chiến lược. Ném ngoại lệ nếu lỗi. */
    fun load(context: Context, assetName: String, s: Strategy, env: Environment?): CompiledModel {
        val opts = if (s.withGpuFallback) CompiledModel.Options(Accelerator.NPU, Accelerator.GPU)
        else CompiledModel.Options(Accelerator.NPU)
        return if (s.fromFile) {
            val f = File(context.cacheDir, assetName.substringAfterLast('/'))
            context.assets.open(assetName).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            CompiledModel.create(f.absolutePath, opts, env)
        } else {
            CompiledModel.create(context.assets, assetName, opts, env)
        }
    }

    fun cpuLoadTest(context: Context, assetName: String): String = try {
        CompiledModel.create(context.assets, assetName, CompiledModel.Options(Accelerator.CPU)).close()
        "OK"
    } catch (t: Throwable) { "lỗi: ${t.javaClass.simpleName}: ${(t.message ?: "").take(200)}" }

    /** Chạy thử model nhỏ trên NPU; trả về chuỗi kết quả (OK hoặc giai đoạn lỗi). */
    fun probeRun(context: Context, assetName: String, s: Strategy): String {
        NativeLog.start(); NativeLog.clear()
        val e = create(context, s.env)
        var stage = "nạp"
        return try {
            val m = load(context, assetName, s, e)
            try {
                stage = "tạo buffer"
                val ins = m.createInputBuffers()
                val outs = m.createOutputBuffers()
                stage = "ghi input"
                ins[0].writeFloat(FloatArray(6 * 64 * 64) { (it % 7) / 7f })
                stage = "chạy"
                m.run(ins, outs)
                stage = "đọc output"
                val o = outs[0].readFloat()
                ins.forEach { it.close() }; outs.forEach { it.close() }
                "OK (${o.size} giá trị)"
            } finally { try { m.close() } catch (_: Throwable) {} }
        } catch (t: Throwable) {
            "[$stage] ${t.javaClass.simpleName}: ${(t.message ?: "").take(160)}\n--- log native ---\n${NativeLog.tail(14, 1100)}"
        } finally { try { e?.close() } catch (_: Throwable) {} }
    }

    /** Đo tốc độ model warp (gather) ở kích thước thật: ms/khung gồm cả chép vào/ra. */
    fun warpBench(context: Context, assetName: String, w: Int, h: Int, s: Strategy): String {
        val e = create(context, s.env)
        return try {
            val m = load(context, assetName, s, e)
            try {
                val ins = m.createInputBuffers(); val outs = m.createOutputBuffers()
                val inArr = FloatArray(6 * w * h) { ((it * 31) % 255) / 255f - 0.5f }
                repeat(4) { ins[0].writeFloat(inArr); m.run(ins, outs); outs[0].readFloat() }
                var run = 0L; var tot = 0L; val n = 30
                repeat(n) {
                    val t0 = System.nanoTime(); ins[0].writeFloat(inArr)
                    val t1 = System.nanoTime(); m.run(ins, outs)
                    val t2 = System.nanoTime(); outs[0].readFloat(); val t3 = System.nanoTime()
                    run += t2 - t1; tot += t3 - t0
                }
                ins.forEach { it.close() }; outs.forEach { it.close() }
                "OK %.2f ms chạy NPU · %.2f ms cả chép vào/ra".format(run / n / 1e6, tot / n / 1e6)
            } finally { try { m.close() } catch (_: Throwable) {} }
        } catch (t: Throwable) { "${t.javaClass.simpleName}: ${(t.message ?: "").take(120)}" }
        finally { try { e?.close() } catch (_: Throwable) {} }
    }
}

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

    /** Nhật ký (mức W/E trở lên) của chính tiến trình app, dòng cuối. */
    fun ownLog(maxChars: Int = 3500): String = try {
        val pid = android.os.Process.myPid()
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid", "-v", "brief", "*:I"))
        val lines = p.inputStream.bufferedReader().readLines()
            .filter { !it.contains("Choreographer") && !it.contains("OpenGLRenderer") && !it.contains("ViewRootImpl") }
        lines.joinToString("\n").takeLast(maxChars)
    } catch (t: Throwable) { "logcat lỗi: ${t.message}" }

    /** Chạy thử model đã AOT trên NPU: nạp, chạy 40 lần, trả về mô tả + ms trung bình. */
    fun benchmark(context: Context): String {
        val sizes = listOf(640 to (640 to 288), 960 to (960 to 416), 1280 to (1280 to 576))
        val sb = StringBuilder("NPU benchmark:\n")
        for ((ls, wh) in sizes) {
            val (w, h) = wh
            val be = LiteRtBackend(context, preferNpu = true)
            val ok = try { be.load(ls, w, h) } catch (t: Throwable) { sb.append("$ls: lỗi ${t.message}\n"); continue }
            if (!ok) { val sz = try { context.assets.open("models/flow_$ls.tflite").use { it.available() } } catch (t: Throwable) { -1 }
                sb.append("$ls: không nạp được (asset=${sz}B)\n${be.lastError}\n"); continue }
            val inp = java.nio.ByteBuffer.allocateDirect(6 * w * h * 4).order(java.nio.ByteOrder.nativeOrder())
            val out = java.nio.ByteBuffer.allocateDirect(5 * (w / 2) * (h / 2) * 4).order(java.nio.ByteOrder.nativeOrder())
            for (i in 0 until 6 * w * h) inp.putFloat(((i * 31) % 255) / 255f)
            repeat(5) { be.run(inp, out) }
            var tot = 0f; var n = 0
            repeat(40) { val t = be.run(inp, out); if (t > 0) { tot += t; n++ } }
            sb.append("$ls ${w}x$h: ${be.name} ${if (n > 0) "%.2f ms".format(tot / n) else "chạy lỗi"} onNpu=${be.onNpu}\n")
            be.close()
        }
        if (sb.contains("không nạp được")) sb.append("\n--- log ---\n").append(ownLog())
        return sb.toString()
    }
}

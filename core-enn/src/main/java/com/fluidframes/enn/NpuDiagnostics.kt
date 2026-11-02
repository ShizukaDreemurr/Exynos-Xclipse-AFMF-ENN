package com.fluidframes.enn

import android.content.Context
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Báo cáo chẩn đoán NPU có cấu trúc (để UI hiển thị và để chia sẻ dạng văn bản). */
object NpuDiagnostics {

    enum class Level { OK, WARN, FAIL, INFO }

    data class Row(val title: String, val level: Level, val detail: String = "")

    data class Report(val rows: List<Row>, val npuMsAt640: Float?, val npuWorks: Boolean) {
        fun toText(): String = buildString {
            appendLine("Xclipse ENN-SS — báo cáo NPU")
            for (r in rows) {
                appendLine("[${r.level}] ${r.title}${if (r.detail.isNotBlank()) ": " + r.detail else ""}")
            }
        }
    }

    private val ennLibs = listOf(
        "enn_public_api_cpp", "enn_public_api_cpp_lib", "enn_user_lib", "enn_user.samsung_slsi",
    )

    fun run(context: Context, benchmark: Boolean = true): Report {
        val rows = mutableListOf<Row>()
        rows += Row("Thiết bị", Level.INFO, "${Build.MANUFACTURER} ${Build.MODEL} · SoC ${Build.SOC_MODEL} · Android ${Build.VERSION.RELEASE}")

        // 1. Thư viện ENN hệ thống
        var ennOk = true
        for (l in ennLibs) {
            val ok = try { System.loadLibrary(l); true } catch (t: Throwable) { false }
            if (!ok) ennOk = false
            rows += Row("lib$l.so", if (ok) Level.OK else Level.FAIL, if (ok) "nạp được" else "không nạp được")
        }

        // 2. Thư viện đi kèm APK
        val bundled = NpuEnv.bundledLibs(context)
        val hasDispatch = bundled.any { it.contains("Dispatch_Samsung") }
        rows += Row("Dispatch Samsung trong APK", if (hasDispatch) Level.OK else Level.FAIL, bundled.joinToString())

        // 2b. API TensorBuffer thật của LiteRT trong APK (để biết có ghi/đọc thẳng ByteBuffer, khóa bộ nhớ, AHardwareBuffer…)
        try {
            val ms = Class.forName("com.google.ai.edge.litert.TensorBuffer").methods
                .filter { it.declaringClass.name.startsWith("com.google") }
                .map { it.name + "(" + it.parameterTypes.joinToString(",") { p -> p.simpleName } + ")" }.distinct().sorted()
            rows += Row("API TensorBuffer", Level.INFO, ms.joinToString("; "))
        } catch (t: Throwable) { rows += Row("API TensorBuffer", Level.WARN, t.javaClass.simpleName) }

        // 3. Accelerator
        val env = NpuEnv.create(context, NpuEnv.EnvMode.DISPATCH_ONLY)
        val accs = NpuEnv.accelerators(env)
        rows += Row("Accelerator LiteRT thấy", if (accs.contains("NPU")) Level.OK else Level.FAIL, accs)
        try { env?.close() } catch (_: Throwable) {}

        // 4. Model
        val sizes = listOf(640 to (640 to 288), 960 to (960 to 416), 1280 to (1280 to 576))
        val modelSizes = sizes.map { (ls, _) ->
            ls to (try { context.assets.open("models/flow_$ls.tflite").use { it.available() } } catch (t: Throwable) { -1 })
        }
        rows += Row("Model trong APK", if (modelSizes.all { it.second > 1000 }) Level.OK else Level.FAIL,
            modelSizes.joinToString { "${it.first}: ${it.second}B" })
        rows += Row("Đọc model bằng CPU", Level.INFO, NpuEnv.cpuLoadTest(context, "models/flow_640.tflite"))

        // 5. Thử từng cách nạp lên NPU
        var works = false
        var runs = false
        for (s in NpuEnv.strategies) {
            val e = NpuEnv.create(context, s.env)
            try {
                val m = NpuEnv.load(context, "models/flow_640.tflite", s, e)
                m.close()
                rows += Row("Cách ${s.id}: ${s.label}", Level.OK, "nạp được")
                works = true
            } catch (t: Throwable) {
                rows += Row("Cách ${s.id}: ${s.label}", Level.FAIL, "${t.javaClass.simpleName}: ${(t.message ?: "").take(140)}")
            } finally { try { e?.close() } catch (_: Throwable) {} }
        }

        // 5a. Bộ nhớ DMA-BUF (NPU Samsung chỉ nhận loại này)
        val heapInfo = listOf("/dev/dma_heap", "/dev/dma_heap/system", "/dev/dma_heap/system-uncached", "/dev/ion").joinToString("; ") { p ->
            val f = java.io.File(p)
            "$p: " + (if (!f.exists()) "không thấy" else "có, đọc=${f.canRead()} ghi=${f.canWrite()}" + (f.list()?.let { " [${it.take(12).joinToString(",")}]" } ?: ""))
        }
        rows += Row("Heap DMA-BUF", Level.INFO, heapInfo)

        // 5b. Chạy thử model siêu nhỏ (tách lỗi model hay lỗi cách chạy)
        for (probe in listOf("probe_nchw", "probe_nhwc", "op_resize", "op_gather", "op_gather_warp", "op_gather_nd", "op_shift25")) {
            val has = try { context.assets.open("models/flow_$probe.tflite").close(); true } catch (_: Throwable) { false }
            if (!has) { rows += Row("Chạy thử $probe", Level.INFO, "chưa có model"); continue }
            for (st in NpuEnv.strategies.take(3)) {
                val r = NpuEnv.probeRun(context, "models/flow_$probe.tflite", st)
                if (r.startsWith("OK")) runs = true
                rows += Row("Chạy thử $probe · cách ${st.id}", if (r.startsWith("OK")) Level.OK else Level.FAIL, r)
            }
        }

        // 5c. Đo tốc độ warp bằng gather ở kích thước thật
        for ((ls, wh) in sizes) {
            val a = "models/flow_op_gwarp_$ls.tflite"
            val has = try { context.assets.open(a).close(); true } catch (_: Throwable) { false }
            if (!has) { rows += Row("Warp gather $ls", Level.INFO, "chưa có model"); continue }
            val r = NpuEnv.warpBench(context, a, wh.first, wh.second, NpuEnv.strategies.first())
            rows += Row("Warp gather $ls (${wh.first}x${wh.second})", if (r.startsWith("OK")) Level.OK else Level.FAIL, r)
        }

        // 6. Đo tốc độ
        var ms640: Float? = null
        if (benchmark && works) {
            for ((ls, wh) in sizes) {
                val (w, h) = wh
                val be = LiteRtBackend(context, preferNpu = true)
                if (!be.load(ls, w, h)) {
                    rows += Row("Đo $ls (${w}x$h)", Level.FAIL, be.lastError.trim()); continue
                }
                val inp = ByteBuffer.allocateDirect(6 * w * h * 4).order(ByteOrder.nativeOrder())
                val out = ByteBuffer.allocateDirect(5 * (w / 2) * (h / 2) * 4).order(ByteOrder.nativeOrder())
                for (i in 0 until 6 * w * h) inp.putFloat(((i * 31) % 255) / 255f)
                repeat(5) { be.run(inp, out) }
                var tot = 0f; var n = 0
                repeat(40) { val t = be.run(inp, out); if (t > 0) { tot += t; n++ } }
                if (n > 0) {
                    val avg = tot / n
                    if (ls == 640) ms640 = avg
                    rows += Row("Đo $ls (${w}x$h)", Level.OK, "%.2f ms/khung · ${be.name} · cách ${be.usedStrategy?.id}".format(avg))
                } else rows += Row("Đo $ls (${w}x$h)", Level.FAIL, be.lastRunError.ifBlank { "chạy lỗi" })
                be.close()
            }
        }
        if (!works) rows += Row("Kết luận", Level.WARN,
            "NPU chưa chạy được model. Ứng dụng sẽ dùng GPU (block-matching) cho tới khi có cách nạp thành công.")
        else if (!runs) rows += Row("Kết luận", Level.WARN, "NPU nạp được model nhưng chạy thì lỗi (kể cả model 1 lớp) — lỗi ở đường chạy/bộ nhớ, không phải model.")
        else rows += Row("Kết luận", Level.OK, "NPU Exynos chạy được model IFRNet-S (đã huấn luyện).")
        return Report(rows, ms640, works)
    }
}

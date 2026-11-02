package com.fluidframes.enn

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.nio.ByteBuffer

/**
 * LiteRT CompiledModel chạy model đã AOT cho Exynos NPU (qua libLiteRtDispatch_Samsung.so).
 * Thử lần lượt các [NpuEnv.strategies]; nhớ cách thành công để lần sau dùng ngay.
 * Đường này sao chép CPU float[] <-> buffer (chưa zero-copy).
 */
class LiteRtBackend(private val context: Context, private val preferNpu: Boolean = true) : InferenceBackend {

    private var model: CompiledModel? = null
    private var env: Environment? = null
    private var inBufs: List<TensorBuffer> = emptyList()
    private var outBufs: List<TensorBuffer> = emptyList()
    private var inArr = FloatArray(0)
    private var outArr = FloatArray(0)
    private var usedNpu = false

    /** Thời gian từng đoạn trong run() (EMA, ms) để biết chi phí chép dữ liệu so với NPU chạy thật. */
    // Đường nhanh (nếu thư viện có): ghi/đọc thẳng Direct ByteBuffer, bỏ qua FloatArray của JVM. Dò bằng reflection nên không làm hỏng build.
    private var fastW: java.lang.reflect.Method? = null
    private var fastR: java.lang.reflect.Method? = null
    private var fastChecked = false
    @Volatile var fastNote = ""; private set
    private fun probeFast() {
        val ms = inBufs.firstOrNull()?.javaClass?.methods ?: return
        fun find(n: String) = ms.firstOrNull { it.name == n && it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == ByteBuffer::class.java }
        fastW = find("writeBuffer"); fastR = find("readBuffer"); fastChecked = false
        fastNote = "writeBuffer=${fastW != null} readBuffer=${fastR != null}"
    }
    private fun callFast(m: java.lang.reflect.Method, tb: Any, bb: ByteBuffer) {
        bb.position(0)
        if (m.parameterTypes.size >= 2) m.invoke(tb, bb, 0) else m.invoke(tb, bb)
    }

    @Volatile var copyInMs = 0f; private set
    @Volatile var runMs = 0f; private set
    @Volatile var copyOutMs = 0f; private set

    var lastError: String = ""
        private set
    var lastRunError: String = ""
        private set
    var usedStrategy: NpuEnv.Strategy? = null
        private set

    @Volatile private var loadedPrec = "FP32"
    override var precision: Int = 0
    override val name: String get() = if (usedNpu) "NPU Exynos $loadedPrec" + (if (fastW != null) " direct" else "") else "—"
    override val onNpu: Boolean get() = usedNpu

    override fun load(longSide: Int, procW: Int, procH: Int): Boolean {
        close()
        lastError = ""
        if (!preferNpu) return false
        // Thứ tự thử: theo lựa chọn người dùng; luôn có đường lui xuống độ chính xác cao hơn.
        val order0 = when (precision) {
            1 -> listOf("int8", "fp16", "")
            2 -> listOf("fp16", "")
            3 -> listOf("")
            else -> listOf("int8", "fp16", "")
        }
        val files = order0.mapNotNull { suf ->
            val p = if (suf.isEmpty()) "models/flow_$longSide.tflite" else "models/flow_${longSide}_$suf.tflite"
            if (try { context.assets.open(p).use { it.available() > 2048 } } catch (_: Throwable) { false }) suf to p else null
        }
        if (files.isEmpty()) { lastError = "không có model nào cho $longSide\n"; return false }
        val prefs = context.getSharedPreferences("npu", Context.MODE_PRIVATE)
        val lastOk = prefs.getString("strategy", null)
        val order = NpuEnv.strategies.sortedBy { if (it.id == lastOk) 0 else 1 }
        for ((suf, path) in files) for (s in order) {
            val e = NpuEnv.create(context, s.env)
            try {
                val m = NpuEnv.load(context, path, s, e)
                val ib = m.createInputBuffers()
                val ob = m.createOutputBuffers()
                env = e; model = m; inBufs = ib; outBufs = ob
                inArr = FloatArray(6 * procW * procH)
                outArr = FloatArray(5 * (procW / 2) * (procH / 2))
                usedNpu = true
                probeFast()
                usedStrategy = s
                loadedPrec = when (suf) { "int8" -> "INT8"; "fp16" -> "FP16"; else -> "FP32" }
                prefs.edit().putString("strategy", s.id).apply()
                Log.i(TAG, "Nạp $path trên NPU bằng cách ${s.id}: ${s.label}")
                return true
            } catch (t: Throwable) {
                lastError += "[${s.id}] ${t.javaClass.simpleName}: ${(t.message ?: "").take(160)}\n"
                Log.w(TAG, "Cách ${s.id} lỗi: ${t.message}")
                try { e?.close() } catch (_: Throwable) {}
            }
        }
        return false
    }

    override fun run(input: ByteBuffer, output: ByteBuffer): Float {
        val m = model ?: return -1f
        var stage = "chuẩn bị"
        return try {
            val t0 = System.nanoTime()
            stage = "đọc input"
            var t1 = 0L
            var t2 = 0L
            val fw = fastW; val fr = fastR
            var fastDone = false
            if (fw != null && fr != null) try {
                stage = "ghi buffer (direct)"
                callFast(fw, inBufs[0], input)
                if (!fastChecked) {   // lần đầu: kiểm chứng kết quả giống đường FloatArray, sai thì tắt đường nhanh
                    input.position(0); val fb = input.asFloatBuffer(); val probe = FloatArray(minOf(256, fb.remaining())); fb.get(probe)
                    val back = inBufs[0].readFloat()
                    var ok = back.size >= probe.size
                    if (ok) for (i in probe.indices) if (kotlin.math.abs(back[i] - probe[i]) > 1e-4f) { ok = false; break }
                    fastChecked = true
                    if (!ok) { fastW = null; fastR = null; fastNote += " (kiểm chứng sai → tắt)"; lastRunError = ""; return run(input, output) }
                    fastNote += " (đã kiểm chứng)"
                }
                t1 = System.nanoTime()
                stage = "chạy model"
                m.run(inBufs, outBufs)
                t2 = System.nanoTime()
                stage = "đọc buffer (direct)"
                callFast(fr, outBufs[0], output)
                fastDone = true
            } catch (e: Throwable) {
                fastW = null; fastR = null; fastNote += " (lỗi đường nhanh → tắt: ${e.javaClass.simpleName} ${(e.cause?.message ?: e.message ?: "").take(80)})"
                return run(input, output)
            }
            if (!fastDone) {
                input.position(0)
                input.asFloatBuffer().get(inArr)
                stage = "ghi vào buffer NPU"
                inBufs[0].writeFloat(inArr)
                t1 = System.nanoTime()
                stage = "chạy model"
                m.run(inBufs, outBufs)
                t2 = System.nanoTime()
                stage = "đọc output"
                outArr = outBufs[0].readFloat()
                stage = "chép output"
                output.position(0)
                output.asFloatBuffer().put(outArr)
            }
            val t3 = System.nanoTime()
            copyInMs = copyInMs * 0.85f + (t1 - t0) / 1e6f * 0.15f
            runMs = runMs * 0.85f + (t2 - t1) / 1e6f * 0.15f
            copyOutMs = copyOutMs * 0.85f + (t3 - t2) / 1e6f * 0.15f
            (t3 - t0) / 1e6f
        } catch (t: Throwable) {
            lastRunError = "[$stage] ${t.javaClass.simpleName}: ${(t.message ?: "").take(220)} (in=${inArr.size}f, out=${outArr.size}f)"
            Log.e(TAG, "Suy luận lỗi: $lastRunError", t)
            -1f
        }
    }

    override fun close() {
        try { inBufs.forEach { it.close() } } catch (_: Throwable) {}
        try { outBufs.forEach { it.close() } } catch (_: Throwable) {}
        try { model?.close() } catch (_: Throwable) {}
        try { env?.close() } catch (_: Throwable) {}
        model = null; env = null
        inBufs = emptyList(); outBufs = emptyList()
        usedNpu = false
    }

    private companion object { const val TAG = "LiteRtBackend" }
}

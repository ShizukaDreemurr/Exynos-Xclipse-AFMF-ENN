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
    override val name: String get() = if (usedNpu) "NPU Exynos $loadedPrec" else "—"
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
            if (try { context.assets.open(p).close(); true } catch (_: Throwable) { false }) suf to p else null
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
            input.position(0)
            input.asFloatBuffer().get(inArr)
            stage = "ghi vào buffer NPU"
            inBufs[0].writeFloat(inArr)
            val t1 = System.nanoTime()
            stage = "chạy model"
            m.run(inBufs, outBufs)
            val t2 = System.nanoTime()
            stage = "đọc output"
            outArr = outBufs[0].readFloat()
            stage = "chép output"
            output.position(0)
            output.asFloatBuffer().put(outArr)
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

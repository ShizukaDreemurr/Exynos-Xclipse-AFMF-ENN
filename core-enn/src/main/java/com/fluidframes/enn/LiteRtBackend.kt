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

    var lastError: String = ""
        private set
    var lastRunError: String = ""
        private set
    var usedStrategy: NpuEnv.Strategy? = null
        private set

    override val name: String get() = if (usedNpu) "NPU Exynos" else "—"
    override val onNpu: Boolean get() = usedNpu

    override fun load(longSide: Int, procW: Int, procH: Int): Boolean {
        close()
        lastError = ""
        if (!preferNpu) return false
        val path = "models/flow_$longSide.tflite"
        val prefs = context.getSharedPreferences("npu", Context.MODE_PRIVATE)
        val lastOk = prefs.getString("strategy", null)
        val order = NpuEnv.strategies.sortedBy { if (it.id == lastOk) 0 else 1 }
        for (s in order) {
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
            stage = "chạy model"
            m.run(inBufs, outBufs)
            stage = "đọc output"
            outArr = outBufs[0].readFloat()
            stage = "chép output"
            output.position(0)
            output.asFloatBuffer().put(outArr)
            (System.nanoTime() - t0) / 1e6f
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

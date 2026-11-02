package com.fluidframes.enn

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import java.nio.ByteBuffer

/**
 * Chạy model super-resolution (vào/ra int8) trên NPU Exynos. Vào: int8 NCHW [1,3,H,W]; ra: int8 NCHW [1,12,H,W].
 * Giao diện int8 được ghi/đọc bằng writeInt8/readInt8 (dò bằng reflection như trong NpuEnv.srBench vì API không công khai ổn định).
 */
class SrBackend(private val context: Context) {
    private var model: CompiledModel? = null
    private var env: Environment? = null
    private var ins: List<TensorBuffer> = emptyList()
    private var outs: List<TensorBuffer> = emptyList()
    private var wI8: java.lang.reflect.Method? = null
    private var rI8: java.lang.reflect.Method? = null
    private var inArr = ByteArray(0)
    @Volatile var lastError = ""; private set
    @Volatile var copyInMs = 0f; private set
    @Volatile var runMs = 0f; private set
    @Volatile var copyOutMs = 0f; private set
    val loaded: Boolean get() = model != null

    fun load(asset: String, inBytes: Int): Boolean {
        close(); lastError = ""
        if (try { context.assets.open(asset).use { it.available() < 2048 } } catch (_: Throwable) { true }) {
            lastError = "không có $asset trong APK (CI chưa biên dịch xong?)"; return false
        }
        val prefs = context.getSharedPreferences("npu", Context.MODE_PRIVATE)
        val lastOk = prefs.getString("strategy", null)
        for (s in NpuEnv.strategies.sortedBy { if (it.id == lastOk) 0 else 1 }) {
            val e = NpuEnv.create(context, s.env)
            try {
                val m = NpuEnv.load(context, asset, s, e)
                val i = m.createInputBuffers(); val o = m.createOutputBuffers()
                val w = i[0].javaClass.methods.firstOrNull { it.name == "writeInt8" && it.parameterTypes.size == 1 && it.parameterTypes[0] == ByteArray::class.java }
                val r = o[0].javaClass.methods.firstOrNull { it.name == "readInt8" && it.parameterTypes.isEmpty() }
                if (w == null || r == null) { m.close(); lastError += "[${s.id}] thiếu writeInt8/readInt8\n"; continue }
                env = e; model = m; ins = i; outs = o; wI8 = w; rI8 = r
                inArr = ByteArray(inBytes)
                Log.i(TAG, "Nạp $asset trên NPU bằng cách ${s.id}")
                return true
            } catch (t: Throwable) {
                lastError += "[${s.id}] ${t.javaClass.simpleName}: ${(t.message ?: "").take(120)}\n"
                try { e?.close() } catch (_: Throwable) {}
            }
        }
        return false
    }

    /** input/output: direct buffer int8 NCHW (bộ nhớ chung với GPU). Trả thời gian ms, hoặc -1 nếu lỗi. */
    fun run(input: ByteBuffer, output: ByteBuffer): Float {
        val m = model ?: return -1f
        return try {
            val t0 = System.nanoTime()
            input.position(0); input.get(inArr, 0, minOf(inArr.size, input.remaining()))
            wI8!!.invoke(ins[0], inArr)
            val t1 = System.nanoTime()
            synchronized(NpuEnv.npuLock) { m.run(ins, outs) }
            val t2 = System.nanoTime()
            val out = rI8!!.invoke(outs[0]) as ByteArray
            output.position(0); output.put(out, 0, minOf(out.size, output.remaining()))
            val t3 = System.nanoTime()
            copyInMs = copyInMs * 0.85f + (t1 - t0) / 1e6f * 0.15f
            runMs = runMs * 0.85f + (t2 - t1) / 1e6f * 0.15f
            copyOutMs = copyOutMs * 0.85f + (t3 - t2) / 1e6f * 0.15f
            (t3 - t0) / 1e6f
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${(t.cause?.message ?: t.message ?: "").take(160)}"
            Log.e(TAG, "SR suy luận lỗi", t)
            -1f
        }
    }

    fun close() {
        try { ins.forEach { it.close() } } catch (_: Throwable) {}
        try { outs.forEach { it.close() } } catch (_: Throwable) {}
        try { model?.close() } catch (_: Throwable) {}
        try { env?.close() } catch (_: Throwable) {}
        model = null; env = null; ins = emptyList(); outs = emptyList()
    }

    private companion object { const val TAG = "SrBackend" }
}

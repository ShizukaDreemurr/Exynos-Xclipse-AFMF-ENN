package com.fluidframes.enn

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.nio.ByteBuffer

/**
 * LiteRT CompiledModel, ưu tiên NPU (Samsung LiteCore nếu runtime có mặt) rồi GPU.
 * Lưu ý: đường này sao chép CPU float[] <-> buffer (không zero-copy). Zero-copy qua AHardwareBuffer
 * cần API C++ của LiteRT/ENN — xem [EnnNativeBackend].
 */
class LiteRtBackend(private val context: Context, private val preferNpu: Boolean = true) : InferenceBackend {

    private var model: CompiledModel? = null
    private var inBufs: List<com.google.ai.edge.litert.TensorBuffer> = emptyList()
    private var outBufs: List<com.google.ai.edge.litert.TensorBuffer> = emptyList()
    private var inArr = FloatArray(0)
    private var outArr = FloatArray(0)
    private var usedNpu = false
    private var env: Environment? = null
    var lastError: String = ""

    override val name: String
        get() = if (usedNpu) "LiteRT/NPU" else "LiteRT/GPU"
    override val onNpu: Boolean get() = usedNpu

    override fun load(longSide: Int, procW: Int, procH: Int): Boolean {
        close()
        lastError = ""
        if (preferNpu && env == null) env = NpuEnv.create(context)
        val path = "models/flow_$longSide.tflite"
        val attempts = buildList {
            if (preferNpu) add(true to CompiledModel.Options(Accelerator.NPU, Accelerator.GPU))
            add(false to CompiledModel.Options(Accelerator.GPU))
        }
        for ((npu, opts) in attempts) {
            try {
                val m = CompiledModel.create(context.assets, path, opts, if (npu) env else null)
                val ib = m.createInputBuffers()
                val ob = m.createOutputBuffers()
                model = m
                inBufs = ib
                outBufs = ob
                inArr = FloatArray(6 * procW * procH)
                outArr = FloatArray(5 * (procW / 2) * (procH / 2))
                usedNpu = npu
                Log.i(TAG, "Nạp $path, accelerator=${if (npu) "NPU" else "GPU"}")
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "Không biên dịch được $path (npu=$npu): ${t.message}")
                lastError += "[${if (npu) "NPU" else "GPU"}] ${t.javaClass.simpleName}: ${(t.message ?: "").take(400)}\n"
            }
        }
        return false
    }

    override fun run(input: ByteBuffer, output: ByteBuffer): Float {
        val m = model ?: return -1f
        return try {
            val t0 = System.nanoTime()
            input.position(0)
            input.asFloatBuffer().get(inArr)
            inBufs[0].writeFloat(inArr)
            m.run(inBufs, outBufs)
            outArr = outBufs[0].readFloat()
            output.position(0)
            output.asFloatBuffer().put(outArr)
            (System.nanoTime() - t0) / 1e6f
        } catch (t: Throwable) {
            Log.e(TAG, "Suy luận lỗi", t)
            -1f
        }
    }

    override fun close() {
        try { inBufs.forEach { it.close() } } catch (_: Throwable) {}
        try { outBufs.forEach { it.close() } } catch (_: Throwable) {}
        try { model?.close() } catch (_: Throwable) {}
        model = null
        inBufs = emptyList()
        outBufs = emptyList()
    }

    private companion object { const val TAG = "LiteRtBackend" }
}

package com.fluidframes.enn

import android.content.Context
import com.fluidframes.telemetry.FlowBackend

/** Chọn backend theo lựa chọn người dùng. null = không có NN (dùng block-match GPU). */
object BackendFactory {
    fun create(context: Context, choice: FlowBackend): InferenceBackend? = when (choice) {
        FlowBackend.OFF, FlowBackend.GPU -> null
        FlowBackend.NPU -> LiteRtBackend(context, preferNpu = true)
        FlowBackend.AUTO -> LiteRtBackend(context, preferNpu = true)
    }
}

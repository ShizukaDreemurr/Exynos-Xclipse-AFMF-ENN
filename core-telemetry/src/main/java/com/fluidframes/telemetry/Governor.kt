package com.fluidframes.telemetry

/**
 * Chọn chế độ + độ phân giải xử lý cho từng frame — HOÀN TOÀN theo thiết lập của người dùng.
 * Không còn tự hạ độ phân giải, tự đổi NPU sang GPU hay tự tắt nội suy theo tải.
 * Ngoại lệ duy nhất: nhiệt độ CRITICAL trở lên (tự bảo vệ máy) -> chạy trực tiếp.
 *
 * - Độ phân giải xử lý: đúng mức đã chọn ("Mặc định" = 540p).
 * - Công cụ flow: "NPU"/"Tự động" -> NPU nếu nạp được (nạp lỗi mới rơi về GPU), "GPU" -> GPU, "Tắt" -> passthrough.
 */
class Governor {

    data class Input(
        val enabled: Boolean,
        val prefTier: ProcTier,
        val prefBackend: FlowBackend,
        val npuAvailable: Boolean,
        val srcFps: Float,
        val workMs: Float,
        val dropRate: Float,
        val thermalStatus: Int,
    )

    data class Decision(val mode: Mode, val tier: ProcTier)

    fun reset() {}

    fun decide(i: Input): Decision {
        if (!i.enabled || i.prefBackend == FlowBackend.OFF) return pass()
        if (i.thermalStatus >= 4) return pass() // CRITICAL trở lên: bảo vệ phần cứng
        val tier = if (i.prefTier == ProcTier.AUTO) ProcTier.P540 else i.prefTier
        val nn = i.prefBackend != FlowBackend.GPU && i.npuAvailable
        return Decision(if (nn) Mode.FLOW_NN else Mode.FLOW_GPU, tier)
    }

    private fun pass() = Decision(Mode.PASSTHROUGH, ProcTier.P540)
}

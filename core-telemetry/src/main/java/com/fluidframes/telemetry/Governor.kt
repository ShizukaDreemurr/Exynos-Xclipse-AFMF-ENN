package com.fluidframes.telemetry

/**
 * Chọn chế độ + độ phân giải xử lý cho từng frame, dựa trên chi phí thật và thermal.
 *
 * Chi phí = [Input.workMs] (từ lúc nhận frame tới khi frame nội suy sẵn sàng, KHÔNG gồm chờ vsync).
 * Ngân sách = 0.5 × chu kỳ frame nguồn: frame nội suy phải sẵn sàng trong nửa chu kỳ để
 * lịch present [I, N] cách nhau 1/60 s không bị trễ dần.
 * Vượt ngân sách liên tục => hạ độ phân giải, rồi hạ NPU xuống GPU, rồi passthrough.
 * Dưới ngân sách đủ lâu => nâng ngược lại (có cooldown để không dao động).
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

    private val tiers = listOf(ProcTier.P720, ProcTier.P540, ProcTier.P360)
    private var tierIdx = 0
    private var gpuOnly = false
    private var passthroughHold = 0 // số frame còn bị ép passthrough sau khi quá tải
    private var over = 0
    private var under = 0
    private var cooldown = 0

    fun reset() {
        tierIdx = 0; gpuOnly = false; passthroughHold = 0; over = 0; under = 0; cooldown = 0
    }

    fun decide(i: Input): Decision {
        val prefIdx = tiers.indexOf(i.prefTier).let { if (it < 0) 0 else it }

        if (!i.enabled || i.prefBackend == FlowBackend.OFF) return pass()
        if (i.thermalStatus >= 4) return pass() // CRITICAL trở lên
        // Luôn nội suy, không phụ thuộc fps nguồn (theo yêu cầu người dùng).
        if (passthroughHold > 0) { passthroughHold--; return pass() }

        if (cooldown > 0) cooldown--
        val budgetMs = 0.5f * 1000f / i.srcFps.coerceIn(10f, 60f)
        val overBudget = i.workMs > budgetMs
        val underBudget = i.workMs < 0.6f * budgetMs
        if (overBudget) { over++; under = 0 } else if (underBudget) { under++; over = 0 } else { over = 0; under = 0 }

        if (over >= 20 && cooldown == 0) {
            over = 0
            when {
                tierIdx < tiers.lastIndex -> tierIdx++
                !gpuOnly && i.npuAvailable -> gpuOnly = true
                else -> {} // đã ở mức thấp nhất (GPU 360p): vẫn nội suy, không rơi về passthrough
            }
            cooldown = 60
        } else if (under >= 300 && cooldown == 0) {
            under = 0
            when {
                gpuOnly && i.npuAvailable -> gpuOnly = false
                tierIdx > 0 -> tierIdx--
            }
            cooldown = 120
        }

        // Thermal: MODERATE chặn tier cao nhất; SEVERE ép tier thấp nhất + GPU.
        var idx = maxOf(tierIdx, prefIdx)
        var forceGpu = gpuOnly
        if (i.thermalStatus == 2) idx = maxOf(idx, 1)
        if (i.thermalStatus == 3) { idx = tiers.lastIndex; forceGpu = true }

        val nn = when (i.prefBackend) {
            FlowBackend.GPU -> false
            FlowBackend.NPU -> i.npuAvailable && !forceGpu
            else -> i.npuAvailable && !forceGpu
        }
        return Decision(if (nn) Mode.FLOW_NN else Mode.FLOW_GPU, tiers[idx])
    }

    private fun pass() = Decision(Mode.PASSTHROUGH, ProcTier.P540)
}

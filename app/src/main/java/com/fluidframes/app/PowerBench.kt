package com.fluidframes.app

import com.fluidframes.telemetry.FlowBackend

/**
 * Đo điện A/B: lần lượt Tắt -> GPU -> NPU -> GPU -> NPU (xen kẽ để triệt tiêu việc pin nóng dần).
 * Mỗi pha bỏ WARM giây đầu (chờ ổn định), rồi lấy trung bình MEAS giây.
 * Gọi tick() mỗi 500 ms từ main thread.
 */
class PowerBench {
    private class Acc { var n = 0; var w = 0.0; var t = 0.0; var fps = 0.0; var ma = 0.0 }

    private val order = listOf(FlowBackend.OFF, FlowBackend.GPU, FlowBackend.NPU, FlowBackend.GPU, FlowBackend.NPU)
    private val acc = FlowBackend.values().associateWith { Acc() }
    private var idx = -1
    private var phaseStart = 0L
    private var temp0 = 0f

    @Volatile var active = false; private set
    /** Backend ép trong lúc đo (null = dùng cài đặt người dùng). */
    @Volatile var forced: FlowBackend? = null; private set
    @Volatile var status = ""; private set
    @Volatile var result = ""; private set

    fun start(nowMs: Long, tempC: Float) {
        for (a in acc.values) { a.n = 0; a.w = 0.0; a.t = 0.0; a.fps = 0.0; a.ma = 0.0 }
        idx = -1; temp0 = tempC; result = ""; active = true
        next(nowMs)
    }

    fun cancel() { active = false; forced = null; status = "" }

    private fun next(now: Long) {
        idx++
        if (idx >= order.size) { finish(); return }
        forced = order[idx]; phaseStart = now
    }

    fun tick(nowMs: Long, watt: Float, tempC: Float, ma: Float, outFps: Float) {
        if (!active) return
        val el = (nowMs - phaseStart) / 1000f
        val name = label(order[idx])
        status = if (el < WARM) "Đo điện ${idx + 1}/${order.size}: $name (chờ ổn định ${(WARM - el).toInt() + 1}s)"
                 else "Đo điện ${idx + 1}/${order.size}: $name (${(MEAS - (el - WARM)).toInt() + 1}s)"
        if (el >= WARM) {
            val a = acc.getValue(order[idx])
            a.n++; a.w += watt; a.t += tempC; a.fps += outFps; a.ma += ma
        }
        if (el >= WARM + MEAS) next(nowMs)
    }

    private fun finish() {
        active = false; forced = null; status = ""
        val sb = StringBuilder()
        var base = 0.0
        for (b in listOf(FlowBackend.OFF, FlowBackend.GPU, FlowBackend.NPU)) {
            val a = acc.getValue(b); if (a.n == 0) continue
            val w = a.w / a.n
            if (b == FlowBackend.OFF) base = w
            sb.append("${label(b)}: %.2f W | %.0f mA | %.1f°C | %.0f fps".format(w, a.ma / a.n, a.t / a.n, a.fps / a.n))
            if (b != FlowBackend.OFF && base > 0) sb.append(" | +%.2f W so với Tắt".format(w - base))
            sb.append('\n')
        }
        sb.append("Pin nóng thêm %.1f°C trong lúc đo".format(lastTemp - temp0))
        result = sb.toString()
    }

    private var lastTemp = 0f
    fun noteTemp(t: Float) { lastTemp = t }

    private fun label(b: FlowBackend) = when (b) { FlowBackend.OFF -> "Tắt"; FlowBackend.GPU -> "GPU"; FlowBackend.NPU -> "NPU"; else -> "Tự động" }

    companion object { const val WARM = 6; const val MEAS = 20 }
}

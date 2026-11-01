package com.fluidframes.app

import android.content.Context
import android.util.Log
import com.fluidframes.capture.FrameRingBuffer
import com.fluidframes.capture.FrameSlot
import com.fluidframes.enn.BackendFactory
import com.fluidframes.enn.InferenceBackend
import com.fluidframes.overlay.FluidSettings
import com.fluidframes.telemetry.FlowBackend
import com.fluidframes.telemetry.Governor
import com.fluidframes.telemetry.Mode
import com.fluidframes.telemetry.PipelineStats
import com.fluidframes.telemetry.ProcTier
import com.fluidframes.telemetry.ThermalMonitor
import com.fluidframes.vulkan.BeginResult
import com.fluidframes.vulkan.NativePipeline
import kotlin.concurrent.thread

/**
 * Vòng lặp xử lý: chờ frame mới -> governor quyết định -> (NN | block-match) -> warp -> present.
 *
 * Lịch present: khi frame N tới, present nội suy I(N-1,N) rồi N; mỗi lần present chặn theo vsync (FIFO),
 * nên N được hiển thị trễ ≈ 1 chu kỳ 60 Hz so với lúc nhận (độ trễ cố định ~16.7 ms + thời gian xử lý).
 */
class FramePipeline(
    private val context: Context,
    private val ring: FrameRingBuffer,
    private val native: NativePipeline,
    private val settings: () -> FluidSettings,
    private val thermal: ThermalMonitor,
    private val onOverlayVisible: (Boolean) -> Unit,
) {
    val stats = PipelineStats()
    private val governor = Governor()

    @Volatile private var running = false
    private var worker: Thread? = null

    @Volatile private var lastMode = Mode.PASSTHROUGH
    @Volatile private var lastTier = ProcTier.P540
    @Volatile private var backendLabel = "-"
    @Volatile private var lastError = ""
    @Volatile var hostLabel = "?"
    @Volatile var captureInfo: () -> String = { "" }
    @Volatile private var stalls = 0
    private var lastFrameNs = 0L
    private var holdOffUntilNs = 0L

    private var backend: InferenceBackend? = null
    private var backendChoice: FlowBackend? = null
    private var backendLoadedFor = 0     // longSide đã nạp
    private var backendFailed = false
    private var overlayShown = true
    private var passthroughStreak = 0
    private var lastWarpMs = 0f

    fun start() {
        if (running) return
        running = true
        thermal.start()
        worker = thread(name = "ff-pipeline", isDaemon = true, priority = Thread.MAX_PRIORITY) { loop() }
    }

    fun stop() {
        running = false
        worker?.join(1500)
        worker = null
        thermal.stop()
        backend?.close()
        backend = null
    }

    fun hudText(): String {
        val s = stats
        val out = s.outFps.fps()
        return buildString {
            append("src %.0f → out %.0f fps  [%s %s]\n".format(s.srcFps.fps(), out, lastMode, tierLabel(lastTier)))
            append("NN: %s | host: %s | overlay: %s\n".format(backendLabel, hostLabel, if (overlayShown) "on" else "off"))
            append("pre %.1f | flow %.1f | warp %.1f | pres %.1f ms\n".format(
                s.preMs.value, s.flowMs.value, s.warpMs.value, s.presentMs.value))
            append("work %.1f ms | drop %.1f%% | skip %d | therm %d".format(
                s.workMs.value, s.dropRate() * 100f, s.framesSkipped.get(), thermal.status))
            append("\n").append(captureInfo()).append(" stalls=").append(stalls)
            if (lastError.isNotEmpty()) append("\n! ").append(lastError)
        }
    }

    /** Ảnh chụp số liệu cho giao diện. */
    fun live(): LiveStats {
        val s = stats
        return LiveStats(
            srcFps = s.srcFps.fps().toFloat(), outFps = s.outFps.fps().toFloat(),
            mode = lastMode.name, tier = tierLabel(lastTier), backend = backendLabel,
            workMs = s.workMs.value.toFloat(), dropPct = s.dropRate() * 100f, thermal = thermal.status,
        )
    }

    private fun tierLabel(t: ProcTier) = t.label

    private fun loop() {
        var lastSeq = 0L
        var prev: FrameSlot? = null
        try {
            while (running) {
                if (!ring.awaitNewerThan(lastSeq, 200)) {
                    // Capture đứng (ví dụ app bị overlay che nên hệ thống ngừng cấp frame):
                    // ẩn overlay để game không bị đơ hình, và đợi 3 s trước khi thử hiện lại.
                    val idleMs = (System.nanoTime() - lastFrameNs) / 1e6
                    if (overlayShown && lastFrameNs != 0L && idleMs > 500) {
                        stalls++
                        holdOffUntilNs = System.nanoTime() + 3_000_000_000L
                        setOverlay(false)
                    }
                    continue
                }
                lastFrameNs = System.nanoTime()
                val cur = ring.acquireLatest(1).firstOrNull() ?: continue
                lastSeq = cur.seq
                stats.srcFps.tick()
                stats.framesIn.incrementAndGet()
                // Frame bị bỏ = frame đã push nhưng chưa từng được xử lý (evicted luôn ~100% vì ring luôn đầy).
                val pushed = ring.pushed.get()
                stats.ringPushed.set(pushed)
                stats.ringDropped.set((pushed - stats.framesIn.get()).coerceAtLeast(0))
                try {
                    process(prev, cur)
                } catch (t: Throwable) {
                    lastError = t.javaClass.simpleName
                    Log.e(TAG, "Lỗi xử lý frame", t)
                }
                prev?.release()
                prev = cur
            }
        } finally {
            prev?.release()
        }
    }

    private fun setOverlay(visible: Boolean) {
        if (overlayShown != visible) {
            overlayShown = visible
            onOverlayVisible(visible)
        }
    }

    private fun passthrough(cur: FrameSlot) {
        lastMode = Mode.PASSTHROUGH
        stats.framesPassthrough.incrementAndGet()
        passthroughStreak++
        // Passthrough kéo dài: ẩn overlay để game hiển thị trực tiếp, đỡ tốn GPU và tránh trễ thêm.
        if (passthroughStreak > HIDE_AFTER_FRAMES) {
            setOverlay(false)
            return
        }
        if (overlayShown) {
            if (native.procW == 0) {
                val (w, h) = NativePipeline.procSize(ProcTier.P540.longSide, cur.width, cur.height)
                native.configure(w, h)
            }
            if (native.presentReal(cur.hardwareBuffer)) stats.outFps.tick()
            else lastError = native.lastError()
        }
    }

    private fun ensureBackend(choice: FlowBackend, longSide: Int, procW: Int, procH: Int): Boolean {
        if (choice != backendChoice) {
            backend?.close()
            backend = BackendFactory.create(context, choice)
            backendChoice = choice
            backendLoadedFor = 0
            backendFailed = false
        }
        val b = backend ?: return false
        if (backendFailed) return false
        if (backendLoadedFor != longSide) {
            if (!b.load(longSide, procW, procH)) {
                backendFailed = true
                backendLabel = "không nạp được models/flow_$longSide.tflite → GPU"
                return false
            }
            backendLoadedFor = longSide
        }
        backendLabel = b.name
        return true
    }

    private fun process(prev: FrameSlot?, cur: FrameSlot) {
        val st = settings()
        if (prev == null) {
            passthrough(cur)
            return
        }
        // Khoảng cách timestamp bất thường (pause, nguồn 60 fps, lag) -> không nội suy.
        val dtMs = (cur.timestampNs - prev.timestampNs) / 1e6
        val gapOk = dtMs in 14.0..70.0

        // npuAvailable chỉ true khi đã nạp được (hoặc chưa thử và có backend).
        val npuCandidate = st.backend != FlowBackend.GPU && st.backend != FlowBackend.OFF && !backendFailed
        val d = governor.decide(
            Governor.Input(
                enabled = st.enabled,
                prefTier = st.tier,
                prefBackend = st.backend,
                npuAvailable = npuCandidate,
                srcFps = stats.srcFps.fps(),
                workMs = stats.workMs.value,
                dropRate = stats.dropRate(),
                thermalStatus = thermal.status,
            ),
        )
        lastTier = d.tier
        if (d.mode == Mode.PASSTHROUGH) { passthrough(cur); return }
        if (!gapOk) {
            stats.framesSkipped.incrementAndGet()
            passthrough(cur)
            return
        }

        if (System.nanoTime() < holdOffUntilNs) return
        passthroughStreak = 0
        setOverlay(true)

        val (pw, ph) = NativePipeline.procSize(d.tier.longSide, cur.width, cur.height)
        if (pw != native.procW || ph != native.procH) {
            if (!native.configure(pw, ph)) { lastError = native.lastError(); passthrough(cur); return }
        }

        val t0 = System.nanoTime()
        var useNn = d.mode == Mode.FLOW_NN && ensureBackend(st.backend, d.tier.longSide, pw, ph)
        var res = native.begin(prev.hardwareBuffer, cur.hardwareBuffer, nn = useNn, blockMatch = !useNn)
        if (res == BeginResult.ERROR) { lastError = native.lastError(); passthrough(cur); return }

        var inferMs = 0f
        var interp = res == BeginResult.OK
        if (interp && useNn) {
            val input = native.nnInput()
            val output = native.nnFlow()
            inferMs = if (input != null && output != null) backend!!.run(input, output) else -1f
            if (inferMs < 0f) {
                // NN lỗi giữa chừng: tính lại bằng block-match GPU cho frame này và tắt NN.
                backendFailed = true
                backendLabel = "lỗi suy luận → GPU"
                useNn = false
                inferMs = 0f
                res = native.begin(prev.hardwareBuffer, cur.hardwareBuffer, nn = false, blockMatch = true)
                if (res == BeginResult.ERROR) { lastError = native.lastError(); passthrough(cur); return }
                interp = res == BeginResult.OK
            }
        }
        val queueMs = (t0 - cur.arrivedNs) / 1e6f
        val workMs = (System.nanoTime() - t0) / 1e6f + lastWarpMs + queueMs.coerceAtLeast(0f)
        stats.workMs.add(workMs)

        val gapMs = (dtMs.toFloat() / 2f - 1f).coerceIn(0f, 25f)
        val ok = native.finish(interp, nnFlowReady = interp && useNn, gapMs = gapMs)
        if (!ok) lastError = native.lastError() else lastError = ""

        val ns = native.stats()
        lastWarpMs = ns.warpMs
        stats.preMs.add(ns.preMs + ns.blockMs)
        stats.flowMs.add(inferMs)
        stats.warpMs.add(ns.warpMs)
        stats.presentMs.add(ns.presentMs)
        stats.captureMs.add(queueMs.coerceAtLeast(0f))

        lastMode = if (interp) d.mode else Mode.PASSTHROUGH
        if (interp) {
            stats.framesInterp.incrementAndGet()
            stats.interpFps.tick()
            stats.outFps.tick()
        } else {
            stats.framesSkipped.incrementAndGet() // scene cut / chuyển động quá lớn
        }
        stats.outFps.tick()
    }

    private companion object {
        const val TAG = "FramePipeline"
        const val HIDE_AFTER_FRAMES = 20
    }
}

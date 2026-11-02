package com.fluidframes.app

import com.fluidframes.overlay.HudOverlay
import android.content.Context
import com.fluidframes.app.BuildConfig
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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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
    @Volatile private var lastTier = ProcTier.P180
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
    // Lỗi NPU nhớ THEO TỪNG model (longSide): đổi tier/độ phân giải là thử lại ngay; lỗi suy luận tự thử lại sau 6 giây.
    private var failLong = 0
    private var failUntilMs = 0L          // 0 = lỗi nạp model (giữ tới khi đổi tier/backend)
    private fun failedFor(long: Int) = failLong == long && (failUntilMs == 0L || android.os.SystemClock.elapsedRealtime() < failUntilMs)
    private fun markFail(long: Int, permanent: Boolean, label: String) {
        if (long == 0) { backendLabel = label; return }   // đã đang chờ thử lại
        failLong = long
        failUntilMs = if (permanent) 0L else android.os.SystemClock.elapsedRealtime() + 6000L
        backendFailed = true; backendLabel = label
        if (!permanent) backendLoadedFor = 0   // thử lại sẽ nạp lại model
    }
    private fun wantLong(st: com.fluidframes.overlay.FluidSettings) = if (st.tier == ProcTier.AUTO) ProcTier.P180.longSide else st.tier.longSide
    private var overlayShown = true
    private var passthroughStreak = 0
    private var lastWarpMs = 0f

    fun start() {
        if (running) return
        running = true
        thermal.start()
        nnThread = thread(name = "ff-npu", isDaemon = true, priority = Thread.MAX_PRIORITY) { nnLoop() }
        worker = thread(name = "ff-pipeline", isDaemon = true, priority = Thread.MAX_PRIORITY) { loop() }
    }

    fun stop() {
        running = false
        worker?.join(1500)
        worker = null
        nnThread?.join(1500)
        nnThread = null
        thermal.stop()
        backend?.close()
        backend = null
    }

    fun hudText(mask: Int = -1): String {
        val s = stats
        val out = s.outFps.fps()
        val src = s.srcFps.fps()
        fun on(bit: Int) = mask and bit != 0
        val G = HudOverlay.G; val Y = HudOverlay.Y; val R = HudOverlay.R
        fun r(a: String, b: String, c: String) = HudOverlay.row(a, b, c)
        val lines = ArrayList<String>()
        if (on(1)) {
            lines += "Xclipse ENN-SS"
            lines += r("Bản", BuildConfig.BUILD_TAG, "")
            lines += r("Chế độ", lastMode.name + " " + tierLabel(lastTier), if (lastMode.name.contains("PASS", true)) Y else G)
        }
        if (on(2)) {
            lines += r("FPS gốc", "%.0f".format(src), "")
            lines += r("FPS xuất", "%.0f".format(out), if (out >= curHz() * 0.9f) G else if (out >= curHz() * 0.6f) Y else R)
        }
        if (on(4)) lines += r("Tần số quét", "%.0fHz".format(curHz()), "")
        if (on(8)) lines += r("Backend", backendLabel + if (overlayShown) "" else " (overlay tắt)", if (backendLabel.contains("NPU", true)) G else "")
        if (on(16) && lastMode == Mode.FLOW_NN) (backend as? com.fluidframes.enn.LiteRtBackend)?.let {
            lines += r("NPU vào/chạy/ra ms", "%.1f/%.1f/%.1f".format(it.copyInMs, it.runMs, it.copyOutMs), if (it.runMs + it.copyInMs + it.copyOutMs < 1000f / src.coerceAtLeast(20f)) G else Y)
        }
        if (on(16)) lines += r("pre/flow/warp ms", "%.1f/%.1f/%.1f".format(s.preMs.value, s.flowMs.value, s.warpMs.value), "")
        if (on(32)) lines += r("Chờ/xử lý ms", "%.1f/%.1f".format(idleEma.value, loopProcEma.value), "")
        if (on(64)) {
            val w = s.workMs.value
            val budget = 1000f / curHz().coerceAtLeast(30f)
            lines += r("Frame time", "%.1fms".format(w), if (w < budget * 0.7f) G else if (w < budget) Y else R)
            val d = s.dropRate() * 100f
            lines += r("Rớt frame", "%.1f%%".format(d), if (d < 2f) G else if (d < 10f) Y else R)
            lines += r("Nhiệt", when { thermal.status <= 1 -> "Nominal"; thermal.status == 2 -> "Fair"; thermal.status == 3 -> "Serious"; else -> "Critical" },
                when { thermal.status <= 1 -> G; thermal.status == 2 -> Y; else -> R })
        }
        if (on(128)) lines += r("Capture", captureInfo() + " stalls=" + stalls, "")
        if (on(512) && lastError.isNotEmpty()) lines += r("Lỗi", lastError, R)
        return lines.joinToString("\n")
    }

    /** Số liệu cho trình đo đạc (mỗi giây một lần). */
    fun telemetry(): Map<String, Any?> {
        val s = stats
        return linkedMapOf(
            "src_fps" to s.srcFps.fps().toDouble(),
            "out_fps" to s.outFps.fps().toDouble(),
            "noi_suy_fps" to s.interpFps.fps().toDouble(),
            "work_ms" to s.workMs.value.toDouble(),
            "pre_ms" to s.preMs.value.toDouble(),
            "flow_ms" to s.flowMs.value.toDouble(),
            "warp_ms" to s.warpMs.value.toDouble(),
            "present_ms" to s.presentMs.value.toDouble(),
            "capture_queue_ms" to s.captureMs.value.toDouble(),
            "cho_frame_ms" to idleEma.value.toDouble(),
            "process_ms" to loopProcEma.value.toDouble(),
            "gpuasync_ms" to asyncCallEma.value.toDouble(),
            "npu_chep_vao_ms" to ((backend as? com.fluidframes.enn.LiteRtBackend)?.copyInMs ?: 0f).toDouble(),
            "npu_chay_ms" to ((backend as? com.fluidframes.enn.LiteRtBackend)?.runMs ?: 0f).toDouble(),
            "npu_chep_ra_ms" to ((backend as? com.fluidframes.enn.LiteRtBackend)?.copyOutMs ?: 0f).toDouble(),
            "drop_pct" to (s.dropRate() * 100f).toDouble(),
            "frames_in" to s.framesIn.get(),
            "frames_noi_suy" to s.framesInterp.get(),
            "frames_bo_qua" to s.framesSkipped.get(),
            "mode" to lastMode.name,
            "tier" to tierLabel(lastTier),
            "backend" to backendLabel,
            "overlay_hien" to if (overlayShown) 1 else 0,
            "man_hz_hien_tai" to curHz().toDouble(),
            "stalls" to stalls,
            "loi" to lastError,
            "capture" to captureInfo(),
        )
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

    private val idleEma = com.fluidframes.telemetry.Ema(0.15f); private val loopProcEma = com.fluidframes.telemetry.Ema(0.15f); private val asyncCallEma = com.fluidframes.telemetry.Ema(0.15f)

    private fun tierLabel(t: ProcTier) = t.label

    private fun loop() {
        var lastSeq = 0L
        var prev: FrameSlot? = null
        try {
            while (running) {
                val tWait = System.nanoTime()
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
                idleEma.add((lastFrameNs - tWait) / 1e6f)
                val cur = ring.acquireLatest(1).firstOrNull() ?: continue
                lastSeq = cur.seq
                stats.srcFps.tick()
                stats.framesIn.incrementAndGet()
                // Frame bị bỏ = frame đã push nhưng chưa từng được xử lý (evicted luôn ~100% vì ring luôn đầy).
                val pushed = ring.pushed.get()
                stats.ringPushed.set(pushed)
                stats.ringDropped.set((pushed - stats.framesIn.get()).coerceAtLeast(0))
                val tProc = System.nanoTime()
                try {
                    process(prev, cur)
                } catch (t: Throwable) {
                    lastError = t.javaClass.simpleName
                    Log.e(TAG, "Lỗi xử lý frame", t)
                }
                loopProcEma.add((System.nanoTime() - tProc) / 1e6f)
                // Frame chỉ được trả lại producer khi GPU đã dùng xong (đường bất đồng bộ không chờ).
                prev?.let { pendingRelease.addLast(Rel(usedJob, usedSeq, it)) }
                usedSeq = 0L
                usedJob = null
                prev = cur
                drainReleases()
            }
        } finally {
            prev?.release()
            pendingRelease.forEach { it.slot.release() }
            pendingRelease.clear()
        }
    }

    // --- "Không làm tệ hơn chơi trần": nếu pipeline không theo kịp nhịp game thì trả game về trực tiếp ---
    private val procEma = com.fluidframes.telemetry.Ema(0.15f)
    private var capFps = 30f
    private var capSampleAtNs = 0L
    private var capSamplePushed = 0L
    private var overloadUntilNs = 0L     // đang "nghỉ" nội suy tới thời điểm này
    private var overloadProbeUntilNs = 0L // đang thử lại nội suy tới thời điểm này
    private var overloadStrikes = 0

    private fun updateCapFps() {
        val now = System.nanoTime()
        if (capSampleAtNs == 0L) { capSampleAtNs = now; capSamplePushed = ring.pushed.get(); return }
        if (now - capSampleAtNs >= 1_000_000_000L) {
            val pushed = ring.pushed.get()
            capFps = ((pushed - capSamplePushed) * 1e9f / (now - capSampleAtNs)).coerceIn(10f, 120f)
            capSampleAtNs = now; capSamplePushed = pushed
        }
    }

    private var dtEma = 0.0
    private var nextProbeNs = 0L
    private var probeUntilNs = 0L
    private var hzCached = 60f
    private var hzAtNs = 0L
    private fun curHz(): Float {
        val now = System.nanoTime()
        if (now - hzAtNs > 300_000_000L) {
            hzAtNs = now
            try {
                hzCached = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY).refreshRate
            } catch (_: Throwable) {}
        }
        return hzCached
    }

    private var vsyncCached = 16.6f
    private var vsyncAtNs = 0L
    private fun vsyncMs(): Float {
        val now = System.nanoTime()
        if (now - vsyncAtNs > 1_000_000_000L) {
            vsyncAtNs = now
            try {
                // Tần số quét CAO NHẤT máy hỗ trợ ở độ phân giải hiện tại (overlay sẽ xin mode này).
                val d = context.getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val cur = d.mode
                val hz = d.supportedModes
                    .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                    .maxOfOrNull { it.refreshRate } ?: d.refreshRate
                if (hz in 24f..240f) vsyncCached = 1000f / hz
            } catch (_: Throwable) {}
        }
        return vsyncCached
    }

    private class NnJob(val prev: FrameSlot, val cur: FrameSlot, val slot: Int) {
        /** 0 = NPU/GPU chưa gửi xong, > 0 = số thứ tự GPU, -1 = hỏng/bỏ. */
        @Volatile var seq = 0L
    }
    private class Rel(val job: NnJob?, val seq: Long, val slot: FrameSlot)

    private val pendingRelease = java.util.ArrayDeque<Rel>()
    private var usedSeq = 0L
    private var usedJob: NnJob? = null

    private fun drainReleases() {
        if (pendingRelease.isEmpty()) return
        val done = try { native.doneSeq() } catch (_: Throwable) { Long.MAX_VALUE }
        while (pendingRelease.isNotEmpty()) {
            val r = pendingRelease.first
            val j = r.job
            val s = if (j != null) j.seq else r.seq
            if (j != null && s == 0L) break      // NPU chưa chạy xong khung này
            if (s > 0L && s > done) break        // GPU chưa dùng xong
            pendingRelease.removeFirst().slot.release()
        }
        // An toàn: nếu ứng dụng giữ quá nhiều frame (GPU kẹt) thì giải phóng frame cũ nhất để capture không bị đói.
        while (pendingRelease.size > 10) pendingRelease.removeFirst().slot.release()
    }

    private fun setOverlay(visible: Boolean) {
        if (overlayShown != visible) {
            overlayShown = visible
            onOverlayVisible(visible)
        }
    }

    /** Chỉ present frame thật, giữ overlay (dùng khi pipeline đang chậm/thiếu frame liền kề). */
    private fun presentOnly(cur: FrameSlot) {
        passthroughStreak = 0
        setOverlay(true)
        if (native.procW == 0) {
            val (w, h) = NativePipeline.procSize(ProcTier.P180.longSide, cur.width, cur.height)
            native.configure(w, h)
        }
        stats.framesPassthrough.incrementAndGet()
        if (native.presentReal(cur.hardwareBuffer)) stats.outFps.tick() else lastError = native.lastError()
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
                val (w, h) = NativePipeline.procSize(ProcTier.P180.longSide, cur.width, cur.height)
                native.configure(w, h)
            }
            if (native.presentReal(cur.hardwareBuffer)) stats.outFps.tick()
            else lastError = native.lastError()
        }
    }

    // --- NPU dây chuyền: GPU chuẩn bị (loop) -> NPU chạy flow (luồng ff-npu) -> GPU ghép + present ---
    private val nnPermits = Semaphore(2)
    private val nnQueue = LinkedBlockingQueue<NnJob>()
    private var nnThread: Thread? = null
    private var nnOutW = 0
    private var nnOutH = 0

    /** Chờ NPU chạy xong mọi khung đang bay (trước khi đổi model/kích thước/đóng). */
    private fun nnDrain() {
        if (nnPermits.tryAcquire(2, 2, TimeUnit.SECONDS)) nnPermits.release(2)
    }

    private fun submitNn(prev: FrameSlot, cur: FrameSlot, t0: Long, d: Governor.Decision): Boolean {
        if (cur.width != nnOutW || cur.height != nnOutH) { nnDrain(); nnOutW = cur.width; nnOutH = cur.height }
        // NPU đang bận 2 khung: bỏ khung này (không present chen ngang để không đảo thứ tự hiển thị).
        if (!nnPermits.tryAcquire()) { stats.framesSkipped.incrementAndGet(); return true }
        val slot = native.nnPre(prev.hardwareBuffer, cur.hardwareBuffer)
        if (slot < 0) { nnPermits.release(); lastError = native.lastError(); return false }
        val job = NnJob(prev, cur, slot)
        usedJob = job
        nnQueue.put(job)
        stats.captureMs.add(((t0 - cur.arrivedNs) / 1e6f).coerceAtLeast(0f))
        procEma.add((System.nanoTime() - t0) / 1e6f)
        lastMode = d.mode
        lastError = ""
        return true
    }

    private fun nnLoop() {
        while (running) {
            val job = nnQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            val t0 = System.nanoTime()
            var seq = -1L
            try {
                val waited = native.nnWait(job.slot)
                val inp = native.nnInputOf(job.slot)
                val out = native.nnFlowOf(job.slot)
                val b = backend
                var ms = -1f
                if (waited && inp != null && out != null && b != null) ms = b.run(inp, out)
                if (ms < 0f) {
                    if (waited) markFail(backendLoadedFor, false, "lỗi suy luận NPU → GPU (thử lại sau 6s)")
                    if (out != null) { out.clear(); out.put(ByteArray(out.capacity())); out.clear() }  // flow 0: trộn 2 khung, không méo
                } else stats.flowMs.add(ms)
                seq = native.nnPost(job.slot)
            } catch (t: Throwable) {
                lastError = t.javaClass.simpleName + ": " + t.message
                Log.e(TAG, "NPU worker", t)
            }
            job.seq = if (seq > 0) seq else -1L
            nnPermits.release()
            if (seq > 0) {
                val ns = native.stats()
                stats.preMs.add(ns.preMs)
                stats.warpMs.add(ns.warpMs)
                stats.presentMs.add(ns.presentMs)
                // Thông lượng của dây chuyền = thời gian bận của luồng NPU. Governor so với 0.5*chu kỳ nên nhân 0.5.
                stats.workMs.add((System.nanoTime() - t0) / 1e6f * 0.5f)
                stats.framesInterp.incrementAndGet()
                stats.interpFps.tick()
                stats.outFps.tick(); stats.outFps.tick()
            }
        }
    }

    private fun ensureBackend(choice: FlowBackend, longSide: Int, procW: Int, procH: Int): Boolean {
        if (choice != backendChoice || backendLoadedFor != longSide) nnDrain()
        if (choice != backendChoice) {
            backend?.close()
            backend = BackendFactory.create(context, choice)
            backendChoice = choice
            backendLoadedFor = 0
            backendFailed = false; failLong = 0
        }
        val b = backend ?: return false
        if (failedFor(longSide)) return false
        backendFailed = false
        if (backendLoadedFor != longSide) {
            if (!b.load(longSide, procW, procH)) {
                markFail(longSide, true, "không nạp được flow_$longSide.tflite → GPU (đổi tier để thử lại)")
                return false
            }
            backendLoadedFor = longSide
        }
        backendLabel = b.name
        return true
    }

    private var tuneKey = -1
    private fun applyTune(st: com.fluidframes.overlay.FluidSettings) {
        val key = st.sensitivity * 2 + (if (st.hudProtect) 0 else 1)
        if (key == tuneKey) return
        tuneKey = key
        val (sThr, oThr) = when (st.sensitivity) { 0 -> 0.03f to 0.10f; 2 -> 0.01f to 0.20f; else -> 0.02f to 0.14f }
        native.setTune(sThr, oThr, if (st.hudProtect) 0 else 1)
    }

    private fun process(prev: FrameSlot?, cur: FrameSlot) {
        val st = settings()
        applyTune(st)
        // Chỉ nội suy app đã bật. Chưa nhận ra app (chưa bật Trợ năng) thì cứ nội suy như cũ.
        if (st.onlyEnabledApps) {
            val fg = com.fluidframes.app.telemetry.TelemetryRecorder.foreground()
            if (fg.isNotEmpty() && fg !in st.enabledApps) { passthrough(cur); return }
        }
        if (prev == null) {
            passthrough(cur)
            return
        }
        // Khoảng cách timestamp bất thường (pause, nguồn 60 fps, lag) -> không nội suy.
        val dtMs = (cur.timestampNs - prev.timestampNs) / 1e6
        val gapOk = dtMs in 3.0..120.0

        // npuAvailable chỉ true khi đã nạp được (hoặc chưa thử và có backend).
        // Model NN hiện chỉ có trọng số ngẫu nhiên => flow rác. Chỉ bật khi có model đã huấn luyện.
        val npuCandidate = NN_FLOW_TRAINED && st.backend != FlowBackend.GPU && st.backend != FlowBackend.OFF && !failedFor(wantLong(st))
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
        // Màn hình chỉ hiển thị tối đa `refresh` khung/s. Nếu nửa chu kỳ nguồn ngắn hơn 1 vsync thì
        // khung nội suy không còn chỗ để hiện: ta sẽ present 2 lần/khung, chặn theo vsync, làm cả
        // pipeline (và fps ta đo được) tụt về ~1/2. Khi đó để game hiển thị trực tiếp.
        // Dùng tần số quét HIỆN TẠI (không phải tối đa): nếu màn hình chưa lên 120 Hz mà vẫn nội suy thì
        // mỗi khung phải present 2 lần ở 60 Hz => src bị kẹt ở 30 fps (tệ hơn chơi trần).
        // Giữ overlay hiện (presentOnly) vài giây để phiếu 120 Hz của overlay có hiệu lực, hết hạn thì trả game về trực tiếp.
        // Dùng khoảng cách TRUNG BÌNH (không phải từng khung): game 30 fps hay lệch 28–36 ms, dùng từng khung làm
        // nội suy chập chờn bật/tắt dù nhịp thật vẫn đủ chỗ cho khung nội suy.
        if (dtMs in 3.0..120.0) dtEma = if (dtEma == 0.0) dtMs else dtEma * 0.9 + dtMs * 0.1
        val dtRef = if (dtEma > 0.0) dtEma else dtMs
        if (!settings().alwaysInterp && dtRef / 2.0 < 0.95 * 1000.0 / curHz().coerceAtLeast(24f)) {
            val now = System.nanoTime()
            if (now > nextProbeNs) { probeUntilNs = now + 4_000_000_000L; nextProbeNs = now + 40_000_000_000L }
            if (now < probeUntilNs) presentOnly(cur) else passthrough(cur)
            return
        }
        if (!gapOk) {
            stats.framesSkipped.incrementAndGet()
            passthrough(cur)
            return
        }

        if (System.nanoTime() < holdOffUntilNs) return
        updateCapFps()
        run {
            val now = System.nanoTime()
            if (now < overloadUntilNs) { passthrough(cur); return }
            // Đủ thời gian nghỉ: thử lại nội suy 3 s để đo chi phí thật.
            if (overloadUntilNs != 0L && overloadProbeUntilNs == 0L) {
                overloadProbeUntilNs = now + 3_000_000_000L
                procEma.reset()
            }
            if (overloadProbeUntilNs != 0L && now >= overloadProbeUntilNs) {
                // Hết thời gian thử: nếu vẫn quá tải thì nghỉ tiếp (lâu dần), nếu ổn thì tiếp tục bình thường.
                overloadProbeUntilNs = 0L
                val interval = 1000f / capFps
                if (procEma.value > 1.3f * interval) {
                    overloadStrikes++
                    overloadUntilNs = now + minOf(60L, 8L * overloadStrikes) * 1_000_000_000L
                    passthrough(cur); return
                } else { overloadUntilNs = 0L; overloadStrikes = 0 }
            }
        }
        // Đang tụt lại (đã có frame mới hơn chờ sẵn) hoặc frame trước không liền kề (bị bỏ):
        // nội suy lúc này vừa trễ vừa sai chuyển động, và làm pipeline chậm thêm. Chỉ present frame thật.
        passthroughStreak = 0
        setOverlay(true)

        val (pw, ph) = NativePipeline.procSize(d.tier.longSide, cur.width, cur.height)
        if (pw != native.procW || ph != native.procH) {
            nnDrain()
            if (!native.configure(pw, ph)) { lastError = native.lastError(); passthrough(cur); return }
        }

        val t0 = System.nanoTime()
        var useNn = d.mode == Mode.FLOW_NN && ensureBackend(st.backend, d.tier.longSide, pw, ph)
        if (useNn) {
            if (submitNn(prev, cur, t0, d)) return
            useNn = false
        }
        if (!useNn) {
            // Đường GPU bất đồng bộ: 1 lần gửi GPU cho cả khung nội suy + khung thật, không chờ GPU xong.
            val tA = System.nanoTime()
            val sq = native.gpuAsync(prev.hardwareBuffer, cur.hardwareBuffer)
            asyncCallEma.add((System.nanoTime() - tA) / 1e6f)
            if (sq > 0) {
                usedSeq = sq
                val ns = native.stats()
                stats.preMs.add(ns.preMs + ns.blockMs)
                stats.flowMs.add(0f)
                stats.warpMs.add(ns.warpMs)
                stats.presentMs.add(ns.presentMs)
                stats.captureMs.add(((t0 - cur.arrivedNs) / 1e6f).coerceAtLeast(0f))
                val procMs = (System.nanoTime() - t0) / 1e6f
                // Không tính thời gian chờ vsync/swapchain (không phải tải thật) -> governor khỏi tụt tier oan.
                stats.workMs.add((procMs - ns.presentMs).coerceAtLeast(0f) + ns.preMs + ns.blockMs + ns.warpMs)
                procEma.add(procMs)
                lastMode = d.mode
                lastError = ""
                stats.framesInterp.incrementAndGet()
                stats.interpFps.tick()
                stats.outFps.tick(); stats.outFps.tick()
                return
            }
            lastError = native.lastError()
            // -1: chưa có swapchain / lỗi tạm thời -> rơi về đường đồng bộ cũ bên dưới.
        }
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
                markFail(backendLoadedFor, false, "lỗi suy luận NPU → GPU (thử lại sau 6s)")
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
        procEma.add((System.nanoTime() - t0) / 1e6f)
        if (overloadUntilNs == 0L && overloadProbeUntilNs == 0L && procEma.count >= 30 &&
            procEma.value > 1.3f * (1000f / capFps)) {
            overloadStrikes = 1
            overloadUntilNs = System.nanoTime() + 8_000_000_000L
        }

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
        const val NN_FLOW_TRAINED = true
    }
}

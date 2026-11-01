package com.fluidframes.capture

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MediaProjection -> VirtualDisplay -> ImageReader(RGBA_8888, GPU_SAMPLED_IMAGE) -> FrameRingBuffer.
 * Mỗi frame giữ nguyên HardwareBuffer để batch Vulkan import trực tiếp (không copy CPU).
 *
 * Engine dùng một lần (start -> stop). Trên Android 14+ một MediaProjection token chỉ được
 * createVirtualDisplay một lần, nên đổi kích thước dùng [reconfigure] (resize + setSurface),
 * không tạo VirtualDisplay mới.
 */
class ScreenCaptureEngine(
    private val projection: MediaProjection,
    private val ring: FrameRingBuffer,
    initialConfig: CaptureConfig,
    private val onProjectionStopped: () -> Unit,
) {
    private val thread = HandlerThread("ff-capture", Process.THREAD_PRIORITY_URGENT_DISPLAY)
    private lateinit var handler: Handler

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private val seq = AtomicLong()

    @Volatile var config: CaptureConfig = initialConfig
        private set
    @Volatile private var running = false

    // Số liệu cho telemetry
    val framesCaptured = AtomicLong()
    val acquireFailures = AtomicLong()
    /** now - frame.timestamp (ns). Xấp xỉ: timebase của timestamp có thể lệch tùy thiết bị. */
    @Volatile var lastCaptureLatencyNs: Long = 0L
        private set

    /** null = chưa nhận callback (chụp toàn màn hình hoặc Android < 14). */
    @Volatile var contentVisible: Boolean? = null
        private set
    @Volatile var contentW = 0
        private set
    @Volatile var contentH = 0
        private set

    fun info(): String =
        "cap=${framesCaptured.get()} vis=${contentVisible ?: "?"} content=${contentW}x$contentH " +
            "vd=${config.width}x${config.height} fail=${acquireFailures.get()}"

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            contentVisible = isVisible
            Log.i(TAG, "captured content visible=$isVisible")
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            contentW = width
            contentH = height
            Log.i(TAG, "captured content resize ${width}x$height")
        }

        override fun onStop() {
            // Người dùng tắt từ chip hệ thống, hoặc hệ thống thu hồi.
            if (!running) return
            running = false
            teardown()
            thread.quitSafely()
            onProjectionStopped()
        }
    }

    fun start() {
        check(!running) { "Engine đã chạy" }
        thread.start()
        handler = Handler(thread.looper)
        // Android 14+: callback phải đăng ký TRƯỚC createVirtualDisplay.
        projection.registerCallback(projectionCallback, handler)
        running = true
        val r = newReader(config)
        reader = r
        display = projection.createVirtualDisplay(
            "fluidframes-capture",
            config.width,
            config.height,
            config.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            handler,
        )
    }

    /** Đổi kích thước (xoay màn hình, đổi mode độ phân giải, đổi scale). Gọi từ thread nào cũng được. */
    fun reconfigure(newConfig: CaptureConfig) {
        if (!running) return
        handler.post {
            if (!running || newConfig.sameSize(config)) return@post
            val oldReader = reader
            val r = newReader(newConfig)
            ring.clear() // frame cũ khác kích thước
            display?.resize(newConfig.width, newConfig.height, newConfig.densityDpi)
            display?.surface = r.surface
            reader = r
            config = newConfig
            oldReader?.let {
                it.setOnImageAvailableListener(null, null)
                it.close()
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        if (Looper.myLooper() == thread.looper) {
            teardown()
        } else {
            val latch = CountDownLatch(1)
            handler.post {
                try { teardown() } finally { latch.countDown() }
            }
            latch.await(2, TimeUnit.SECONDS)
        }
        thread.quitSafely()
    }

    private fun newReader(c: CaptureConfig): ImageReader =
        ImageReader.newInstance(
            c.width, c.height, PixelFormat.RGBA_8888, c.maxImages,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
        ).also { it.setOnImageAvailableListener({ r -> drain(r) }, handler) }

    /** Lấy mọi image đang chờ (không bỏ frame nào), bọc thành FrameSlot rồi đẩy vào ring. */
    private fun drain(r: ImageReader) {
        while (running) {
            val img = try {
                r.acquireNextImage()
            } catch (e: IllegalStateException) {
                // Hết maxImages (consumer giữ quá nhiều) hoặc reader đã đóng.
                acquireFailures.incrementAndGet()
                null
            } ?: return

            // Chờ producer (SurfaceFlinger) vẽ xong frame này, nếu không GPU của ta đọc buffer đang
            // được ghi dở => xé hình.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                try { img.fence.use { f -> if (f.isValid) f.awaitForever() } } catch (_: Throwable) {}
            }
            val now = System.nanoTime()
            val hb = try { img.hardwareBuffer } catch (e: Exception) { null }
            if (hb == null) {
                Log.w(TAG, "Image không có HardwareBuffer")
                runCatching { img.close() }
                continue
            }
            lastCaptureLatencyNs = now - img.timestamp
            framesCaptured.incrementAndGet()
            ring.push(FrameSlot(seq.incrementAndGet(), img.timestamp, now, img.width, img.height, img, hb))
        }
    }

    private fun teardown() {
        runCatching { projection.unregisterCallback(projectionCallback) }
        runCatching { display?.release() }
        display = null
        runCatching {
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
        }
        reader = null
        ring.clear()
        runCatching { projection.stop() }
    }

    private companion object {
        const val TAG = "ScreenCaptureEngine"
    }
}

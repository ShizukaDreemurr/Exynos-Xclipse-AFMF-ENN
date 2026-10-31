package com.fluidframes.capture

import android.hardware.HardwareBuffer
import android.media.Image
import java.util.concurrent.atomic.AtomicInteger

/**
 * Một frame đã capture, giữ nguyên [HardwareBuffer] (zero-copy, không đụng CPU).
 *
 * Vòng đời đếm tham chiếu: ring giữ 1 ref khi tạo; mỗi consumer gọi [FrameRingBuffer.acquireLatest]
 * nhận thêm 1 ref và PHẢI gọi [release]. Về 0 thì đóng HardwareBuffer rồi Image
 * (trả buffer về ImageReader).
 *
 * [timestampNs] là timestamp của frame từ display (thường cùng timebase CLOCK_MONOTONIC);
 * [arrivedNs] là System.nanoTime() lúc callback nhận frame.
 */
class FrameSlot internal constructor(
    val seq: Long,
    val timestampNs: Long,
    val arrivedNs: Long,
    val width: Int,
    val height: Int,
    private var image: Image?,
    val hardwareBuffer: HardwareBuffer,
) {
    private val refs = AtomicInteger(1)

    internal fun retain(): FrameSlot {
        while (true) {
            val c = refs.get()
            check(c > 0) { "retain() trên frame đã giải phóng" }
            if (refs.compareAndSet(c, c + 1)) return this
        }
    }

    fun release() {
        if (refs.decrementAndGet() == 0) closeNow()
    }

    private fun closeNow() {
        runCatching { hardwareBuffer.close() }
        runCatching { image?.close() }
        image = null
    }
}

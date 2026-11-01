package com.fluidframes.telemetry

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/** Trung bình trượt mũ; ghi từ 1 thread, đọc từ thread khác. */
class Ema(private val alpha: Float = 0.1f) {
    @Volatile var value: Float = 0f
        private set
    private var primed = false
    @Volatile var count: Int = 0
        private set

    fun reset() { primed = false; count = 0; value = 0f }

    fun add(x: Float) {
        count++
        if (!primed) {
            primed = true
            value = x
        } else {
            value += alpha * (x - value)
        }
    }
}

/** Đếm sự kiện trong 1 giây gần nhất. */
class FpsCounter {
    private val stamps = ArrayDeque<Long>()

    @Synchronized
    fun tick(nowNs: Long = System.nanoTime()) {
        stamps.addLast(nowNs)
        trim(nowNs)
    }

    @Synchronized
    fun fps(nowNs: Long = System.nanoTime()): Float {
        trim(nowNs)
        return stamps.size.toFloat()
    }

    private fun trim(nowNs: Long) {
        while (stamps.isNotEmpty() && nowNs - stamps.peekFirst()!! > 1_000_000_000L) stamps.pollFirst()
    }
}

inline fun <T> Ema.measureMs(block: () -> T): T {
    val t0 = System.nanoTime()
    try {
        return block()
    } finally {
        add((System.nanoTime() - t0) / 1_000_000f)
    }
}

/** Số liệu toàn pipeline, mọi thành phần đều thread-safe cho 1 writer + nhiều reader. */
class PipelineStats {
    val srcFps = FpsCounter()
    val outFps = FpsCounter()
    val interpFps = FpsCounter()

    val captureMs = Ema()
    val preMs = Ema()      // GPU preprocess (+ block-match nếu dùng)
    val flowMs = Ema()     // NN inference (CPU wall) hoặc 0
    val warpMs = Ema()     // GPU warp + blend + occlusion
    val presentMs = Ema()  // thời gian chờ present (vsync)
    val workMs = Ema()     // từ lúc bắt đầu frame tới khi frame nội suy sẵn sàng

    val framesIn = AtomicLong()
    val framesInterp = AtomicLong()
    val framesPassthrough = AtomicLong()
    val framesSkipped = AtomicLong() // bỏ nội suy do scene cut / motion lớn / khoảng trống timestamp
    val ringDropped = AtomicLong()
    val ringPushed = AtomicLong()

    fun dropRate(): Float {
        val p = ringPushed.get()
        return if (p <= 0) 0f else ringDropped.get().toFloat() / p
    }
}

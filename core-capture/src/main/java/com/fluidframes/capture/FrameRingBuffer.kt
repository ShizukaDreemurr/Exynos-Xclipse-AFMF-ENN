package com.fluidframes.capture

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Ring buffer thread-safe, giữ tối đa [capacity] frame mới nhất.
 *
 * Dùng mutex ngắn thay vì lock-free: tốc độ push chỉ 30–120 lần/giây nên không có tranh chấp
 * đáng kể, và refcount + evict đúng đắn dễ chứng minh hơn nhiều.
 *
 * - Producer (thread capture): [push]. Đầy thì đẩy frame cũ nhất ra (đếm vào [evicted]).
 * - Consumer (thread pipeline): [awaitNewerThan] rồi [acquireLatest]; xong phải [FrameSlot.release].
 */
class FrameRingBuffer(val capacity: Int = 4) {

    init {
        require(capacity >= 2) { "Cần tối thiểu 2 frame để nội suy" }
    }

    private val lock = ReentrantLock()
    private val newFrame = lock.newCondition()
    private val slots = ArrayDeque<FrameSlot>(capacity)
    private var latestSeq = 0L

    val pushed = AtomicLong()
    val evicted = AtomicLong()

    fun push(slot: FrameSlot) {
        var old: FrameSlot? = null
        lock.withLock {
            if (slots.size == capacity) old = slots.pollFirst()
            slots.addLast(slot)
            latestSeq = slot.seq
            newFrame.signalAll()
        }
        pushed.incrementAndGet()
        old?.let {
            evicted.incrementAndGet()
            it.release() // ref của ring
        }
    }

    /**
     * Trả về tối đa [count] frame mới nhất theo thứ tự cũ -> mới, mỗi frame đã retain.
     * Caller phải release từng frame.
     */
    fun acquireLatest(count: Int = 2): List<FrameSlot> = lock.withLock {
        val n = minOf(count, slots.size)
        val skip = slots.size - n
        slots.asSequence().drop(skip).map { it.retain() }.toList()
    }

    /** Chờ tới khi có frame có seq > [seq]. false nếu hết thời gian. */
    fun awaitNewerThan(seq: Long, timeoutMs: Long): Boolean = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (latestSeq <= seq) {
            if (remaining <= 0L) return false
            remaining = newFrame.awaitNanos(remaining)
        }
        true
    }

    val size: Int get() = lock.withLock { slots.size }

    /** Giải phóng toàn bộ ref của ring (frame consumer đang giữ vẫn sống tới khi họ release). */
    fun clear() {
        val drained = lock.withLock {
            val list = slots.toList()
            slots.clear()
            list
        }
        drained.forEach { it.release() }
    }
}

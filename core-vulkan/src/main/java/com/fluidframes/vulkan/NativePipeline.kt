package com.fluidframes.vulkan

import android.content.Context
import android.hardware.HardwareBuffer
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Kết quả giai đoạn 1. */
enum class BeginResult { ERROR, OK, SCENE_CUT, LARGE_MOTION }

/** Thống kê GPU/ngưỡng của lần Begin/Finish gần nhất. */
data class NativeStats(
    val preMs: Float, val blockMs: Float, val warpMs: Float,
    val meanDiff: Float, val boundaryFrac: Float, val residual: Float,
    val beginWallMs: Float, val presentMs: Float,
)

/** Bao bọc an toàn luồng ở mức gọi (native đã có mutex). */
class NativePipeline private constructor(private var handle: Long) {

    var procW = 0
        private set
    var procH = 0
        private set

    /** Chế độ điện NPU Samsung: 0 tiết kiệm, 4 cân bằng, 7 tối đa. Trả về kết quả để hiện trên HUD. */
    fun ennPerf(mode: Int): String = try { NativeBridge.nativeEnnPerf(mode) } catch (t: Throwable) { "lỗi: ${t.javaClass.simpleName}" }

    fun setSurface(surface: Surface?): Boolean = NativeBridge.nativeSetSurface(handle, surface)

    fun setTune(staticThr: Float, occThr: Float, flags: Int) = NativeBridge.nativeSetTune(handle, staticThr, occThr, flags)

    fun configure(w: Int, h: Int): Boolean {
        val ok = NativeBridge.nativeConfigure(handle, w, h)
        if (ok) { procW = w; procH = h }
        return ok
    }

    fun begin(prev: HardwareBuffer, cur: HardwareBuffer, nn: Boolean, blockMatch: Boolean): BeginResult =
        when (NativeBridge.nativeBegin(handle, prev, cur, nn, blockMatch)) {
            0 -> BeginResult.OK
            1 -> BeginResult.SCENE_CUT
            2 -> BeginResult.LARGE_MOTION
            else -> BeginResult.ERROR
        }

    /** Direct buffer NCHW float32 [1,6,procH,procW]; hợp lệ tới khi Configure đổi cỡ. */
    fun nnInput(): ByteBuffer? = NativeBridge.nativeNnInput(handle)?.order(ByteOrder.nativeOrder())

    /** Direct buffer NCHW float32 [1,5,procH/2,procW/2]. */
    fun nnFlow(): ByteBuffer? = NativeBridge.nativeNnFlow(handle)?.order(ByteOrder.nativeOrder())

    /** [gapMs]: thời gian chờ giữa present frame nội suy và frame thật (≈ nửa chu kỳ nguồn). */
    fun finish(interp: Boolean, nnFlowReady: Boolean, gapMs: Float): Boolean =
        NativeBridge.nativeFinish(handle, interp, nnFlowReady, gapMs)

    /** NPU dây chuyền: slot >= 0 | -1 lỗi | -2 hết slot. */
    fun nnPre(prev: HardwareBuffer, cur: HardwareBuffer): Int = NativeBridge.nativeNnPre(handle, prev, cur)
    fun nnWait(slot: Int): Boolean = NativeBridge.nativeNnWait(handle, slot)
    fun nnInputOf(slot: Int): ByteBuffer? = NativeBridge.nativeNnInputOf(handle, slot)?.order(ByteOrder.nativeOrder())
    fun nnFlowOf(slot: Int): ByteBuffer? = NativeBridge.nativeNnFlowOf(handle, slot)?.order(ByteOrder.nativeOrder())
    fun nnPost(slot: Int): Long = NativeBridge.nativeNnPost(handle, slot)

    /** Gửi 1 khung nguồn lên GPU không chờ (nội suy + khung thật). Trả số thứ tự > 0, hoặc -1 nếu lỗi. */
    fun gpuAsync(prev: HardwareBuffer, cur: HardwareBuffer): Long = NativeBridge.nativeGpuAsync(handle, prev, cur)

    /** Số thứ tự lớn nhất đã chạy xong trên GPU: các HardwareBuffer dùng cho lần gửi ≤ số này mới được trả lại producer. */
    fun doneSeq(): Long = NativeBridge.nativeDoneSeq(handle)

    /** Upscale SR x2: dựng bộ nhớ/pipeline cho model vào lrW x lrH (int8, tham số lượng tử từ JSON). */
    fun srInit(assets: android.content.res.AssetManager, w: Int, h: Int, inScale: Float, inZp: Int, outScale: Float, outZp: Int): Boolean =
        NativeBridge.nativeSrInit(handle, assets, w, h, inScale, inZp, outScale, outZp)
    fun srIn(): ByteBuffer? = NativeBridge.nativeSrIn(handle)?.order(ByteOrder.nativeOrder())
    fun srOut(): ByteBuffer? = NativeBridge.nativeSrOut(handle)?.order(ByteOrder.nativeOrder())
    fun srPre(lr: HardwareBuffer): Boolean = NativeBridge.nativeSrPre(handle, lr)
    fun srPost(lr: HardwareBuffer, token: HardwareBuffer, slot: Int): Boolean = NativeBridge.nativeSrPost(handle, lr, token, slot)

    fun presentReal(cur: HardwareBuffer): Boolean = NativeBridge.nativePresentReal(handle, cur)

    private val statsArr = FloatArray(16)
    fun stats(): NativeStats {
        NativeBridge.nativeStats(handle, statsArr)
        val s = statsArr
        return NativeStats(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7])
    }

    fun lastError(): String = NativeBridge.nativeLastError(handle)

    fun close() {
        if (handle != 0L) {
            NativeBridge.nativeDestroy(handle)
            handle = 0L
        }
    }

    companion object {
        /** Trả về Result để UI hiển thị lỗi Vulkan (thiếu extension, thiếu shader...). */
        fun create(context: Context): Result<NativePipeline> {
            val h = try {
                NativeBridge.nativeCreate(context.assets)
            } catch (t: UnsatisfiedLinkError) {
                return Result.failure(t)
            }
            return if (h == 0L) Result.failure(IllegalStateException(NativeBridge.nativeCreateError()))
            else Result.success(NativePipeline(h))
        }

        /** Kích thước xử lý: cạnh dài ≈ longSide, cả hai cạnh làm tròn xuống bội số 32 (tối thiểu 64). */
        fun procSize(longSide: Int, srcW: Int, srcH: Int): Pair<Int, Int> {
            val scale = longSide.toFloat() / maxOf(srcW, srcH)
            fun r(v: Int) = maxOf(64, ((v * scale).toInt() / 32) * 32)
            return Pair(r(srcW), r(srcH))
        }
    }
}

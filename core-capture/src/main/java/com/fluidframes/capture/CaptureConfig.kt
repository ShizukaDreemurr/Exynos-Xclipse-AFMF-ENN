package com.fluidframes.capture

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface

/**
 * Kích thước VirtualDisplay/ImageReader.
 * [maxImages] phải > capacity của ring + số frame pipeline giữ cùng lúc (mặc định 4 + 2, +1 vì drain acquire TRƯỚC khi ring đẩy frame cũ ra, +1 dự phòng).
 */
data class CaptureConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val maxImages: Int = 14,
) {
    fun sameSize(o: CaptureConfig) = width == o.width && height == o.height

    companion object {
        const val MAX_LONG_SIDE = 1280f

        /**
         * Lấy độ phân giải vật lý hiện tại của màn hình chính (theo mode đang chọn,
         * ví dụ FHD+/WQHD+ trên Samsung) và xoay theo rotation hiện tại.
         * Giả định Display.Mode.physicalWidth/Height là theo hướng tự nhiên của máy.
         * [scale] < 1 để capture ở độ phân giải thấp hơn (VirtualDisplay tự scale).
         */
        fun fromContext(context: Context, scale: Float = 0f): CaptureConfig {
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display.mode
            var w = mode.physicalWidth
            var h = mode.physicalHeight
            if (display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270) {
                val t = w; w = h; h = t
            }
            // scale <= 0: tự chọn cạnh dài ~1280 px (tier cao nhất của pipeline). Capture full-res 2340x1080 làm
            // SurfaceFlinger tốn GPU/băng thông để compose thêm 1 màn hình ảo => game và src cùng tụt.
            val sc = if (scale > 0f) scale else minOf(1f, MAX_LONG_SIDE / maxOf(w, h).toFloat())
            fun align(v: Int) = ((v * sc).toInt() and 1.inv()).coerceAtLeast(2)
            return CaptureConfig(
                width = align(w),
                height = align(h),
                densityDpi = context.resources.displayMetrics.densityDpi,
            )
        }
    }
}

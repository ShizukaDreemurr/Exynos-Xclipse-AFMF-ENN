package com.fluidframes.overlay

import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager

/**
 * Cửa sổ toàn màn hình chứa SurfaceView; Surface được giao cho native (Vulkan swapchain).
 * Không nhận cảm ứng và không lấy focus.
 */
class VideoOverlay(private val host: OverlayHost, private val listener: Listener) {

    interface Listener {
        /** Gọi trên main thread. surface == null nghĩa là Surface đã bị hủy. */
        fun onSurface(surface: Surface?, width: Int, height: Int)
    }

    /** Tần số quét mục tiêu: theo cài đặt (0 = cao nhất), chọn mode gần nhất trong số mode máy hỗ trợ. */
    private fun targetMode(): android.view.Display.Mode? = try {
        val d = host.context.getSystemService(android.hardware.display.DisplayManager::class.java)
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val cur = d.mode
        val modes = d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
        val want = SettingsStore(host.context).load().targetHz
        if (want <= 0) modes.maxByOrNull { it.refreshRate }
        else modes.minByOrNull { kotlin.math.abs(it.refreshRate - want) }
    } catch (_: Throwable) { null }

    private val main = Handler(Looper.getMainLooper())
    private var view: SurfaceView? = null

    val isShown: Boolean get() = view != null

    fun show(secure: Boolean) {
        if (view != null) return
        val sv = SurfaceView(host.context)
        sv.setZOrderOnTop(true)
        sv.holder.setFormat(PixelFormat.RGBA_8888)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
                // Bỏ phiếu cho tần số quét mục tiêu của người dùng.
                runCatching {
                    val hz = targetMode()?.refreshRate ?: 60f
                    holder.surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                        Surface.CHANGE_FRAME_RATE_ALWAYS)
                }
                listener.onSurface(holder.surface, w, h)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) = listener.onSurface(null, 0, 0)
        })
        if (secure) sv.setSecure(true)

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (secure) flags = flags or WindowManager.LayoutParams.FLAG_SECURE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            host.windowType,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = "FluidFramesVideo"
            // Xin màn hình chạy đúng tần số quét mục tiêu.
            targetMode()?.let { m ->
                preferredDisplayModeId = m.modeId
                preferredRefreshRate = m.refreshRate
            }
            // Host thường bị Android 12+ chặn cảm ứng nếu độ mờ > 0.8.
            if (!host.trusted) alpha = 0.8f
        }
        try {
            host.windowManager.addView(sv, lp)
            view = sv
        } catch (e: Exception) {
            Log.e(TAG, "addView thất bại", e)
        }
    }

    fun setVisible(visible: Boolean) {
        main.post { view?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    fun hide() {
        val v = view ?: return
        view = null
        runCatching { host.windowManager.removeViewImmediate(v) }
    }

    private companion object {
        const val TAG = "VideoOverlay"
    }
}

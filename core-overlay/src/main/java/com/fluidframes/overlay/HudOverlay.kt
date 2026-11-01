package com.fluidframes.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Bảng số liệu nhỏ ở góc trái trên; không nhận cảm ứng. */
class HudOverlay(private val host: OverlayHost) {
    private val main = Handler(Looper.getMainLooper())
    private var tv: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var lastStyle = Triple(100, 67, 0)

    fun show() {
        if (tv != null) return
        val dp = host.context.resources.displayMetrics.density
        val t = TextView(host.context).apply {
            setTextColor(Color.WHITE)
            background = OS.rounded(0xAA0A0C12.toInt(), 10 * dp)
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (4 * dp).toInt()
            y = (24 * dp).toInt()
            title = "FluidFramesHud"
            if (!host.trusted) alpha = 0.8f
        }
        try {
            host.windowManager.addView(t, lp)
            tv = t
            this.lp = lp
            style(lastStyle.first, lastStyle.second, lastStyle.third)
        } catch (e: Exception) {
            Log.e("HudOverlay", "addView thất bại", e)
        }
    }

    /** Cỡ chữ (%), độ đậm nền (%), vị trí neo (0 trái | 1 giữa | 2 phải). Áp dụng ngay. */
    fun style(scalePct: Int, alphaPct: Int, anchor: Int) {
        lastStyle = Triple(scalePct, alphaPct, anchor)
        main.post {
            val t = tv ?: return@post
            val dp = host.context.resources.displayMetrics.density
            t.textSize = 10f * scalePct / 100f
            t.background = OS.rounded((((alphaPct.coerceIn(0, 100) * 255) / 100) shl 24) or 0x0A0C12, 10 * dp)
            val l = lp ?: return@post
            l.gravity = Gravity.TOP or when (anchor) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START }
            l.x = if (anchor == 1) 0 else (4 * dp).toInt()
            runCatching { host.windowManager.updateViewLayout(t, l) }
        }
    }

    fun setText(text: String) {
        main.post { tv?.text = text }
    }

    fun setVisible(visible: Boolean) {
        main.post { tv?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    fun hide() {
        val v = tv ?: return
        tv = null
        runCatching { host.windowManager.removeViewImmediate(v) }
    }
}

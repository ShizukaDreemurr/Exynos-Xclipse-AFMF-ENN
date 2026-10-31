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

    fun show() {
        if (tv != null) return
        val dp = host.context.resources.displayMetrics.density
        val t = TextView(host.context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
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
        } catch (e: Exception) {
            Log.e("HudOverlay", "addView thất bại", e)
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

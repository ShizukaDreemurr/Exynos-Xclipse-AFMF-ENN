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
import android.widget.LinearLayout
import android.widget.TextView

/** Bảng số liệu nhỏ ở góc trái trên; không nhận cảm ứng. */
class HudOverlay(private val host: OverlayHost) {
    private val main = Handler(Looper.getMainLooper())
    private var tv: LinearLayout? = null
    private var scale = 100
    private var lastText = ""
    private var lp: WindowManager.LayoutParams? = null
    private var lastStyle = Triple(100, 67, 0)

    fun show() {
        if (tv != null) return
        val dp = host.context.resources.displayMetrics.density
        val t = LinearLayout(host.context).apply {
            orientation = LinearLayout.VERTICAL
            background = OS.rounded(0xAA0A0C12.toInt(), 10 * dp)
            minimumWidth = (190 * dp).toInt()
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
            scale = scalePct
            t.minimumWidth = (190 * dp * scalePct / 100f).toInt()
            render()
            t.background = OS.rounded((((alphaPct.coerceIn(0, 100) * 255) / 100) shl 24) or 0x0A0C12, 10 * dp)
            val l = lp ?: return@post
            l.gravity = Gravity.TOP or when (anchor) { 1 -> Gravity.CENTER_HORIZONTAL; 2 -> Gravity.END; else -> Gravity.START }
            l.x = if (anchor == 1) 0 else (4 * dp).toInt()
            runCatching { host.windowManager.updateViewLayout(t, l) }
        }
    }

    /** Mỗi dòng "nhãn\tgiá trị"; giá trị có thể bắt đầu bằng \u0001 xanh, \u0002 vàng, \u0003 đỏ. Dòng không có tab = tiêu đề. */
    fun setText(text: String) {
        main.post { lastText = text; render() }
    }

    private fun render() {
        val t = tv ?: return
        val dp = host.context.resources.displayMetrics.density
        val size = 10f * scale / 100f
        val lines = if (lastText.isEmpty()) emptyList() else lastText.split("\n")
        while (t.childCount > lines.size) t.removeViewAt(t.childCount - 1)
        while (t.childCount < lines.size) {
            val row = LinearLayout(t.context).apply { orientation = LinearLayout.HORIZONTAL }
            fun tvw(w: Float, g: Int) = TextView(t.context).apply {
                typeface = Typeface.MONOSPACE; setTextColor(Color.WHITE); gravity = g
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)
            }
            row.addView(tvw(1f, Gravity.START)); row.addView(tvw(0f, Gravity.END).also {
                it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                it.setPadding((12 * dp).toInt(), 0, 0, 0)
            })
            t.addView(row)
        }
        for ((i, line) in lines.withIndex()) {
            val row = t.getChildAt(i) as LinearLayout
            val l = row.getChildAt(0) as TextView; val v = row.getChildAt(1) as TextView
            l.textSize = size; v.textSize = size
            val tab = line.indexOf('\t')
            if (tab < 0) { l.text = line; l.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); v.text = ""; continue }
            l.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            l.text = line.substring(0, tab)
            var val_ = line.substring(tab + 1)
            var c = Color.WHITE
            if (val_.isNotEmpty()) when (val_[0]) {
                '\u0001' -> { c = 0xFF7CFF6B.toInt(); val_ = val_.substring(1) }
                '\u0002' -> { c = 0xFFFFD54A.toInt(); val_ = val_.substring(1) }
                '\u0003' -> { c = 0xFFFF5A4D.toInt(); val_ = val_.substring(1) }
            }
            v.setTextColor(c); v.text = val_
        }
    }

    fun setVisible(visible: Boolean) {
        main.post { tv?.visibility = if (visible) View.VISIBLE else View.GONE }
    }

    companion object {
        const val G = "\u0001"; const val Y = "\u0002"; const val R = "\u0003"
        fun row(label: String, value: String, color: String = "") = "$label\t$color$value"
    }

    fun hide() {
        val v = tv ?: return
        tv = null
        runCatching { host.windowManager.removeViewImmediate(v) }
    }
}

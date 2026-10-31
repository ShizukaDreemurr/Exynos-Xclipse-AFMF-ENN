package com.fluidframes.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.fluidframes.telemetry.FlowBackend
import com.fluidframes.telemetry.ProcTier

/**
 * Nút nổi kéo được + bảng cài đặt. Cửa sổ nhận cảm ứng nhưng KHÔNG lấy focus (không bật bàn phím).
 * Dùng nút bấm xoay vòng thay vì Spinner để không phụ thuộc popup window từ overlay.
 */
class MenuOverlay(
    private val host: OverlayHost,
    initial: FluidSettings,
    private val onChange: (FluidSettings) -> Unit,
    private val onStop: () -> Unit,
) {
    private var settings = initial
    private val dp = host.context.resources.displayMetrics.density
    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null

    fun show() {
        if (bubble != null) return
        val ctx = host.context
        val tv = TextView(ctx).apply {
            text = "FF"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC1E88E5.toInt())
            }
        }
        val lp = WindowManager.LayoutParams(
            (48 * dp).toInt(), (48 * dp).toInt(), host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * dp).toInt()
            y = (140 * dp).toInt()
            title = "FluidFramesBubble"
        }
        tv.setOnTouchListener(dragListener(lp))
        try {
            host.windowManager.addView(tv, lp)
            bubble = tv
            bubbleLp = lp
        } catch (e: Exception) {
            Log.e(TAG, "addView bubble thất bại", e)
        }
    }

    fun hide() {
        closePanel()
        bubble?.let { runCatching { host.windowManager.removeViewImmediate(it) } }
        bubble = null
    }

    private fun dragListener(lp: WindowManager.LayoutParams): View.OnTouchListener {
        val slop = ViewConfiguration.get(host.context).scaledTouchSlop
        var startX = 0; var startY = 0; var downX = 0f; var downY = 0f; var moved = false
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y; downX = e.rawX; downY = e.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (dx * dx + dy * dy) > slop * slop) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { host.windowManager.updateViewLayout(v, lp) }
                        if (panel != null) closePanel() // kéo thì đóng bảng, mở lại bằng chạm
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    true
                }
                else -> false
            }
        }
    }

    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    private fun closePanel() {
        panel?.let { runCatching { host.windowManager.removeViewImmediate(it) } }
        panel = null
    }

    private fun openPanel() {
        val ctx = host.context
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 12 * dp
                setColor(0xEE212121.toInt())
            }
        }
        col.addView(label("FluidFrames"))
        col.addView(toggle("Nội suy khung hình", { settings.enabled }) { settings = settings.copy(enabled = it) })
        col.addView(cycler("Độ phân giải xử lý", ProcTier.entries.map { it.label }, settings.tier.ordinal) {
            settings = settings.copy(tier = ProcTier.entries[it])
        })
        col.addView(cycler("Backend flow", FlowBackend.entries.map { it.label }, settings.backend.ordinal) {
            settings = settings.copy(backend = FlowBackend.entries[it])
        })
        col.addView(toggle("Hiện HUD số liệu", { settings.showHud }) { settings = settings.copy(showHud = it) })
        col.addView(toggle("Overlay secure (thử nghiệm)", { settings.secureOverlay }) {
            settings = settings.copy(secureOverlay = it)
        })
        col.addView(button("Dừng FluidFrames") { closePanel(); onStop() })

        val b = bubbleLp
        val lp = WindowManager.LayoutParams(
            (270 * dp).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (b?.x ?: 0) + (56 * dp).toInt()
            y = b?.y ?: 0
            title = "FluidFramesPanel"
        }
        try {
            host.windowManager.addView(col, lp)
            panel = col
        } catch (e: Exception) {
            Log.e(TAG, "addView panel thất bại", e)
        }
    }

    private fun label(t: String) = TextView(host.context).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 15f
        setPadding(0, 0, 0, (6 * dp).toInt())
    }

    private fun button(t: String, onClick: () -> Unit) = Button(host.context).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun toggle(title: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val b = button("") {}
        fun render() { b.text = "$title: ${if (get()) "BẬT" else "TẮT"}" }
        render()
        b.setOnClickListener {
            set(!get())
            render()
            onChange(settings)
        }
        return b
    }

    private fun cycler(title: String, options: List<String>, start: Int, set: (Int) -> Unit): View {
        var i = start
        val b = button("") {}
        fun render() { b.text = "$title: ${options[i]}" }
        render()
        b.setOnClickListener {
            i = (i + 1) % options.size
            set(i)
            render()
            onChange(settings)
        }
        return b
    }

    private companion object {
        const val TAG = "MenuOverlay"
    }
}

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
    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var panel: View? = null

    fun show() {
        if (bubble != null) return
        val ctx = host.context
        val tv = EclipseView(ctx).apply {
            background = OS.rounded(0xCC0A0C12.toInt(), 28 * dp, OS.OUTLINE, (1 * dp).toInt())
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            contentDescription = "FluidFrames: chạm để mở bảng điều khiển"
        }
        val lp = WindowManager.LayoutParams(
            (52 * dp).toInt(), (52 * dp).toInt(), host.windowType,
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
        val ui = OverlayUi(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(16), ui.px(14), ui.px(16), ui.px(16))
            background = OS.rounded(OS.BG, 28 * dp, OS.OUTLINE, ui.px(1))
        }
        col.addView(ui.title("FluidFrames"))
        col.addView(ui.toggleRow("Nội suy khung hình", { settings.enabled }) {
            settings = settings.copy(enabled = it); onChange(settings)
        })
        col.addView(ui.caption("Độ phân giải xử lý"))
        col.addView(ui.segmented(listOf("Tự động", "720p", "540p", "360p"), tierIndex(settings.tier)) {
            settings = settings.copy(tier = TIERS[it]); onChange(settings)
        })
        col.addView(ui.caption("Công cụ tính flow"))
        col.addView(ui.segmented(listOf("Tự động", "NPU", "GPU", "Tắt"), backendIndex(settings.backend)) {
            settings = settings.copy(backend = BACKENDS[it]); onChange(settings)
        })
        col.addView(ui.toggleRow("HUD số liệu", { settings.showHud }) {
            settings = settings.copy(showHud = it); onChange(settings)
        })
        col.addView(ui.toggleRow("Overlay secure (thử nghiệm)", { settings.secureOverlay }) {
            settings = settings.copy(secureOverlay = it); onChange(settings)
        })
        col.addView(View(ctx), LinearLayout.LayoutParams(1, ui.px(8)))
        col.addView(ui.button("Dừng FluidFrames", danger = true) { closePanel(); onStop() })

        val b = bubbleLp
        val lp = WindowManager.LayoutParams(
            (300 * dp).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (b?.x ?: 0) + (60 * dp).toInt()
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

    private fun tierIndex(t: ProcTier) = TIERS.indexOf(t).coerceAtLeast(0)
    private fun backendIndex(b: FlowBackend) = BACKENDS.indexOf(b).coerceAtLeast(0)

    private companion object {
        const val TAG = "MenuOverlay"
        val TIERS = listOf(ProcTier.AUTO, ProcTier.P720, ProcTier.P540, ProcTier.P360)
        val BACKENDS = listOf(FlowBackend.AUTO, FlowBackend.NPU, FlowBackend.GPU, FlowBackend.OFF)
    }
}

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
import android.widget.ScrollView
import android.widget.Toast
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
    private val onRecordToggle: () -> String,
    private val isRecording: () -> Boolean,
    private val foreground: () -> String,
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
            contentDescription = "Xclipse ENN-SS: chạm để mở bảng điều khiển"
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
        col.addView(ui.title("Xclipse ENN-SS"))
        col.addView(ui.toggleRow("Nội suy khung hình (tổng)", { settings.enabled }) {
            settings = settings.copy(enabled = it); onChange(settings)
        })
        // App đang ở trước: bật nội suy riêng cho app này, có hiệu lực ngay (không cần mở app, không cần khởi động lại).
        val fg = foreground()
        if (fg.isEmpty()) {
            col.addView(ui.caption("Chưa nhận ra app đang chạy (bật Trợ năng, rồi chuyển vào game một lần)"))
        } else {
            val pm = ctx.packageManager
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(fg, 0)).toString() } catch (_: Throwable) { fg }
            col.addView(ui.toggleRow("Nội suy cho: $label", { fg in settings.enabledApps }) {
                settings = settings.copy(enabledApps = if (it) settings.enabledApps + fg else settings.enabledApps - fg)
                onChange(settings)
            })
        }
        col.addView(ui.toggleRow("Chỉ nội suy app đã bật", { settings.onlyEnabledApps }) {
            settings = settings.copy(onlyEnabledApps = it); onChange(settings)
        })
        col.addView(ui.caption("Độ phân giải xử lý (nhỏ hơn = NPU nhanh, ít điện, nhưng flow thô hơn)"))
        col.addView(ui.segmented(listOf("Mặc định", "720p", "540p", "360p"), tierIndex(settings.tier).takeIf { it < 4 } ?: -1) {
            settings = settings.copy(tier = TIERS[it]); onChange(settings)
        })
        col.addView(ui.segmented(listOf("200p", "160p", "130p"), (tierIndex(settings.tier) - 4).takeIf { it in 0..2 } ?: -1) {
            settings = settings.copy(tier = TIERS[it + 4]); onChange(settings)
        })
        col.addView(ui.caption("Công cụ tính flow"))
        col.addView(ui.segmented(listOf("Tự động", "NPU", "GPU", "Tắt"), backendIndex(settings.backend)) {
            settings = settings.copy(backend = BACKENDS[it]); onChange(settings)
        })
        col.addView(ui.caption("Độ nhạy chuyển động"))
        col.addView(ui.segmented(listOf("Thấp", "Vừa", "Cao"), settings.sensitivity.coerceIn(0, 2)) {
            settings = settings.copy(sensitivity = it); onChange(settings)
        })
        col.addView(ui.toggleRow("Bảo vệ HUD / chữ đứng yên", { settings.hudProtect }) {
            settings = settings.copy(hudProtect = it); onChange(settings)
        })
        col.addView(ui.toggleRow("Luôn nội suy (không passthrough)", { settings.alwaysInterp }) {
            settings = settings.copy(alwaysInterp = it); onChange(settings)
        })
        col.addView(ui.caption("Ép tần số quét màn hình"))
        col.addView(ui.segmented(listOf("Cao nhất", "60", "90", "120"), HZS.indexOf(settings.targetHz).coerceAtLeast(0)) {
            settings = settings.copy(targetHz = HZS[it]); onChange(settings)
        })
        col.addView(ui.toggleRow("HUD số liệu", { settings.showHud }) {
            settings = settings.copy(showHud = it); onChange(settings)
        })
        col.addView(ui.caption("Tùy chỉnh HUD — dòng hiển thị"))
        for ((bit, name) in HUD_ROWS) {
            col.addView(ui.toggleRow(name, { settings.hudLines and bit != 0 }) {
                settings = settings.copy(hudLines = if (it) settings.hudLines or bit else settings.hudLines and bit.inv()); onChange(settings)
            })
        }
        col.addView(ui.caption("Cỡ chữ HUD"))
        col.addView(ui.segmented(listOf("Nhỏ", "Vừa", "Lớn", "Rất lớn"), HUD_SCALES.indexOf(settings.hudScale).coerceAtLeast(1)) {
            settings = settings.copy(hudScale = HUD_SCALES[it]); onChange(settings)
        })
        col.addView(ui.caption("Độ đậm nền HUD"))
        col.addView(ui.segmented(listOf("Trong", "Mờ", "Vừa", "Đậm"), HUD_ALPHAS.indexOf(settings.hudAlpha).coerceAtLeast(2)) {
            settings = settings.copy(hudAlpha = HUD_ALPHAS[it]); onChange(settings)
        })
        col.addView(ui.caption("Vị trí HUD"))
        col.addView(ui.segmented(listOf("Trái trên", "Giữa trên", "Phải trên"), settings.hudAnchor.coerceIn(0, 2)) {
            settings = settings.copy(hudAnchor = it); onChange(settings)
        })
        col.addView(ui.toggleRow("Overlay secure (thử nghiệm)", { settings.secureOverlay }) {
            settings = settings.copy(secureOverlay = it); onChange(settings)
        })
        col.addView(View(ctx), LinearLayout.LayoutParams(1, ui.px(8)))
        lateinit var recBtn: TextView
        fun recLabel() = if (isRecording()) "■  Dừng đo log" else "●  Đo log app đang chạy"
        recBtn = ui.button(recLabel(), danger = false) {
            val msg = onRecordToggle()
            if (msg.isNotEmpty()) Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            recBtn.text = recLabel()
        } as TextView
        col.addView(recBtn)
        col.addView(View(ctx), LinearLayout.LayoutParams(1, ui.px(8)))
        col.addView(ui.button("Dừng Xclipse ENN-SS", danger = true) { closePanel(); onStop() })

        // Cuộn được: giới hạn chiều cao 85% màn hình, nội dung dài hơn thì vuốt.
        val dm = ctx.resources.displayMetrics
        val maxH = (dm.heightPixels * 0.85f).toInt()
        val w = (300 * dp).toInt()
        col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val h = minOf(col.measuredHeight, maxH)
        val scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = OS.rounded(OS.BG, 28 * dp, OS.OUTLINE, ui.px(1))
            clipToOutline = true
            addView(col)
        }
        col.background = null
        val b = bubbleLp
        val lp = WindowManager.LayoutParams(
            w, h, host.windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (b?.x ?: 0) + (60 * dp).toInt()
            y = (b?.y ?: 0).coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
            x = x.coerceIn(0, (dm.widthPixels - w).coerceAtLeast(0))
            title = "FluidFramesPanel"
        }
        try {
            host.windowManager.addView(scroll, lp)
            panel = scroll
        } catch (e: Exception) {
            Log.e(TAG, "addView panel thất bại", e)
        }
    }

    private fun tierIndex(t: ProcTier) = TIERS.indexOf(t).coerceAtLeast(0)
    private fun backendIndex(b: FlowBackend) = BACKENDS.indexOf(b).coerceAtLeast(0)

    private companion object {
        const val TAG = "MenuOverlay"
        val HZS = listOf(0, 60, 90, 120)
        val HUD_SCALES = listOf(70, 100, 140, 190)
        val HUD_ALPHAS = listOf(0, 35, 67, 90)
        val HUD_ROWS = listOf(
            HudLines.VERSION to "Dòng: phiên bản",
            HudLines.FPS to "Dòng: FPS nguồn → xuất, chế độ",
            HudLines.HZ to "Dòng: tần số quét màn",
            HudLines.BACKEND to "Dòng: NPU/GPU, host, overlay",
            HudLines.STAGES to "Dòng: pre / flow / warp / present (ms)",
            HudLines.LOOP to "Dòng: chờ frame / process / gpuAsync",
            HudLines.WORK to "Dòng: work, drop, skip, nhiệt",
            HudLines.CAPTURE to "Dòng: capture, stalls",
            HudLines.POWER to "Dòng: pin %, nhiệt pin, công suất (W)",
            HudLines.LOAD to "Dòng: CPU / GPU % và MHz",
            HudLines.ERROR to "Dòng: lỗi (nếu có)",
        )
        val TIERS = listOf(ProcTier.AUTO, ProcTier.P720, ProcTier.P540, ProcTier.P360, ProcTier.P270, ProcTier.P216, ProcTier.P180)
        val BACKENDS = listOf(FlowBackend.AUTO, FlowBackend.NPU, FlowBackend.GPU, FlowBackend.OFF)
    }
}

package com.fluidframes.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Màu và vật liệu dùng chung cho các lớp phủ (cùng bảng "Eclipse" với app, xem docs/DESIGN.md). */
internal object OS {
    const val BG = 0xF2141824.toInt()       // nền bảng (mờ nhẹ)
    const val CARD2 = 0xFF1C2232.toInt()
    const val OUTLINE = 0xFF2A3146.toInt()
    const val TEXT = 0xFFF3F5FA.toInt()
    const val MUTED = 0xFF9AA3B8.toInt()
    const val ACCENT = 0xFF7C6CFF.toInt()
    const val ACCENT2 = 0xFF35D8FF.toInt()
    const val BAD = 0xFFFF6B7A.toInt()

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusPx
        if (strokeColor != null) setStroke(strokePx, strokeColor)
    }

    fun pill(color: Int, heightPx: Float) = rounded(color, heightPx / 2f)

    fun gradientPill(heightPx: Float) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT2)).apply {
        cornerRadius = heightPx / 2f
    }
}

/** Biểu tượng nhật thực tự vẽ (đĩa gradient + lưỡi liềm khoét), dùng cho bong bóng và tiêu đề bảng. */
internal class EclipseView(context: Context) : View(context) {
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val cut = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f) return
        val cx = w / 2f
        val cy = height / 2f
        val r = w * 0.36f
        glow.shader = RadialGradient(cx, cy, w / 2f, intArrayOf(0x667C6CFF, 0x00000000), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, w / 2f, glow)
        disc.shader = SweepGradient(cx, cy, intArrayOf(OS.ACCENT, OS.ACCENT2, OS.ACCENT), null)
        path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW)
        cut.reset(); cut.addCircle(cx + r * 0.30f, cy - r * 0.10f, r * 0.86f, Path.Direction.CW)
        path.op(cut, Path.Op.DIFFERENCE)
        canvas.drawPath(path, disc)
    }
}

/** Dựng các điều khiển kiểu One UI bằng View thuần (cửa sổ overlay không dùng Compose). */
internal class OverlayUi(private val context: Context) {
    val dp = context.resources.displayMetrics.density
    fun px(v: Int) = (v * dp).toInt()

    fun text(t: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = t
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    fun title(t: String): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(2), px(4), px(10))
        }
        row.addView(EclipseView(context), LinearLayout.LayoutParams(px(32), px(32)))
        row.addView(text(t, 17f, OS.TEXT, true).apply { setPadding(px(10), 0, 0, 0) })
        return row
    }

    fun caption(t: String) = text(t, 12f, OS.MUTED).apply { setPadding(px(4), px(10), px(4), px(6)) }

    /** Hàng công tắc: nhãn bên trái, viên thuốc BẬT/TẮT bên phải. Cao tối thiểu 48dp. */
    fun toggleRow(label: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(48)
            setPadding(px(4), 0, px(4), 0)
        }
        val tv = text(label, 14f, OS.TEXT)
        val chip = text("", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(px(14), px(6), px(14), px(6))
        }
        fun render() {
            val on = get()
            chip.text = if (on) "BẬT" else "TẮT"
            chip.setTextColor(if (on) Color.WHITE else OS.MUTED)
            chip.background = if (on) OS.gradientPill(px(28).toFloat()) else OS.pill(OS.CARD2, px(28).toFloat())
        }
        render()
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(chip)
        row.setOnClickListener { set(!get()); render() }
        return row
    }

    /** Danh sách thả xuống: bấm để mở/đóng, chọn một mục. */
    fun dropdown(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = text("", 13f, Color.WHITE, true).apply {
            background = OS.pill(OS.CARD2, px(48).toFloat())
            setPadding(px(16), px(10), px(16), px(10))
        }
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        var cur = selected.coerceIn(0, labels.size - 1)
        fun title() { head.text = labels[cur] + (if (list.visibility == View.VISIBLE) "   ▲" else "   ▼") }
        labels.forEachIndexed { i, l ->
            list.addView(text(l, 13f, if (i == cur) Color.WHITE else OS.MUTED, i == cur).apply {
                setPadding(px(16), px(9), px(16), px(9))
                setOnClickListener {
                    cur = i; list.visibility = View.GONE; title(); onSelect(i)
                    for (k in 0 until list.childCount) (list.getChildAt(k) as TextView).apply {
                        setTextColor(if (k == cur) Color.WHITE else OS.MUTED)
                    }
                }
            })
        }
        head.setOnClickListener { list.visibility = if (list.visibility == View.VISIBLE) View.GONE else View.VISIBLE; title() }
        title()
        box.addView(head); box.addView(list)
        return box
    }

    /** Nhóm lựa chọn dạng viên thuốc (thay cho nút xoay vòng). */
    fun segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = OS.pill(OS.CARD2, px(48).toFloat())
            setPadding(px(3), px(3), px(3), px(3))
        }
        val items = labels.map { l ->
            text(l, 12f, OS.MUTED, true).apply {
                gravity = Gravity.CENTER
                minimumHeight = px(42)
                maxLines = 1
            }
        }
        fun render(sel: Int) {
            items.forEachIndexed { i, tv ->
                tv.background = if (i == sel) OS.gradientPill(px(42).toFloat()) else null
                tv.setTextColor(if (i == sel) Color.WHITE else OS.MUTED)
            }
        }
        render(selected)
        items.forEachIndexed { i, tv ->
            tv.setOnClickListener { render(i); onSelect(i) }
            box.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return box
    }

    fun button(label: String, danger: Boolean, onClick: () -> Unit): View = text(label, 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        minimumHeight = px(52)
        background = if (danger) OS.pill(OS.BAD, px(52).toFloat()) else OS.gradientPill(px(52).toFloat())
        setOnClickListener { onClick() }
    }
}

package com.fluidframes.overlay

import android.content.Context
import com.fluidframes.telemetry.FlowBackend
import com.fluidframes.telemetry.ProcTier

data class FluidSettings(
    val enabled: Boolean = true,
    val tier: ProcTier = ProcTier.AUTO,   // AUTO = 130p
    val backend: FlowBackend = FlowBackend.AUTO,
    val showHud: Boolean = true,
    /** Thử nghiệm: đánh dấu cửa sổ video là FLAG_SECURE để (có thể) bị loại khỏi MediaProjection. */
    val secureOverlay: Boolean = false,
    /** Tần số quét mục tiêu (Hz). 0 = cao nhất máy hỗ trợ. */
    val targetHz: Int = 0,
    /** Cạnh dài của hình xuất/chụp (px). 0 = gốc màn hình. Áp dụng khi bấm Bắt đầu lại. */
    val outLongSide: Int = 0,
    /** Luôn nội suy: bỏ qua guard tần số quét (không tự trả game về passthrough). */
    val alwaysInterp: Boolean = false,
    /** Độ nhạy chuyển động: 0 thấp (an toàn) | 1 vừa | 2 cao (tin flow hơn). */
    val sensitivity: Int = 1,
    /** Bảo vệ HUD/chữ đứng yên. */
    val hudProtect: Boolean = true,
    /** Chỉ nội suy app đã bật trong menu (app lạ/launcher/hệ thống chạy trực tiếp, không tốn tài nguyên). */
    val onlyEnabledApps: Boolean = true,
    val enabledApps: Set<String> = emptySet(),
    /** Dòng HUD hiển thị (bitmask, xem HudLines). */
    val hudLines: Int = HudLines.ALL,
    /** Cỡ chữ HUD (%). */
    val hudScale: Int = 100,
    /** Độ đậm nền HUD (%). */
    val hudAlpha: Int = 67,
    /** 0 góc trái | 1 giữa trên | 2 phải trên. */
    val hudAnchor: Int = 0,
)

/** Các dòng của HUD số liệu; mỗi dòng bật/tắt riêng trong menu overlay. */
object HudLines {
    const val VERSION = 1
    const val FPS = 2
    const val HZ = 4
    const val BACKEND = 8
    const val STAGES = 16
    const val LOOP = 32
    const val WORK = 64
    const val CAPTURE = 128
    const val POWER = 256
    const val ERROR = 512
    const val LOAD = 1024
    const val ALL = 2047 and CAPTURE.inv() and LOOP.inv()   // mặc định gọn: bỏ dòng kỹ thuật (chờ/process và capture)
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("fluidframes", Context.MODE_PRIVATE)

    fun load(): FluidSettings = FluidSettings(
        enabled = prefs.getBoolean("enabled", true),
        tier = enumOf("tier", ProcTier.AUTO),
        backend = enumOf("backend", FlowBackend.AUTO),
        showHud = prefs.getBoolean("hud", true),
        secureOverlay = prefs.getBoolean("secure", false),
        targetHz = prefs.getInt("hz", 0),
        outLongSide = prefs.getInt("outres", 0),
        alwaysInterp = prefs.getBoolean("always", false),
        sensitivity = prefs.getInt("sens", 1),
        hudProtect = prefs.getBoolean("hudprot", true),
        onlyEnabledApps = prefs.getBoolean("onlyapps", true),
        enabledApps = prefs.getStringSet("apps_on", emptySet())?.toSet() ?: emptySet(),
        hudLines = prefs.getInt("hud_lines", HudLines.ALL),
        hudScale = prefs.getInt("hud_scale", 100),
        hudAlpha = prefs.getInt("hud_alpha", 67),
        hudAnchor = prefs.getInt("hud_anchor", 0),
    )

    fun save(s: FluidSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putString("tier", s.tier.name)
            .putString("backend", s.backend.name)
            .putBoolean("hud", s.showHud)
            .putBoolean("secure", s.secureOverlay)
            .putInt("hz", s.targetHz)
            .putInt("outres", s.outLongSide)
            .putBoolean("always", s.alwaysInterp)
            .putInt("sens", s.sensitivity)
            .putBoolean("hudprot", s.hudProtect)
            .putBoolean("onlyapps", s.onlyEnabledApps)
            .putStringSet("apps_on", s.enabledApps)
            .putInt("hud_lines", s.hudLines)
            .putInt("hud_scale", s.hudScale)
            .putInt("hud_alpha", s.hudAlpha)
            .putInt("hud_anchor", s.hudAnchor)
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}

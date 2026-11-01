package com.fluidframes.overlay

import android.content.Context
import com.fluidframes.telemetry.FlowBackend
import com.fluidframes.telemetry.ProcTier

data class FluidSettings(
    val enabled: Boolean = true,
    val tier: ProcTier = ProcTier.AUTO,
    val backend: FlowBackend = FlowBackend.AUTO,
    val showHud: Boolean = true,
    /** Thử nghiệm: đánh dấu cửa sổ video là FLAG_SECURE để (có thể) bị loại khỏi MediaProjection. */
    val secureOverlay: Boolean = false,
    /** Tần số quét mục tiêu (Hz). 0 = cao nhất máy hỗ trợ. */
    val targetHz: Int = 0,
    /** Cạnh dài của hình xuất/chụp (px). 0 = gốc màn hình. Áp dụng khi bấm Bắt đầu lại. */
    val outLongSide: Int = 0,
)

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
            .apply()
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}

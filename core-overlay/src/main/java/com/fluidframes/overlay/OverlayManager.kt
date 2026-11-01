package com.fluidframes.overlay

import android.os.Handler
import android.os.Looper

/**
 * Điều phối 3 cửa sổ: video (dưới cùng) -> HUD -> menu (trên cùng).
 * Mọi hàm public gọi trên main thread (hoặc tự post).
 */
class OverlayManager(
    private val host: OverlayHost,
    surfaceListener: VideoOverlay.Listener,
    initial: FluidSettings,
    private val onSettings: (FluidSettings) -> Unit,
    onStopRequested: () -> Unit,
    onRecordToggle: () -> String = { "" },
    isRecording: () -> Boolean = { false },
    foreground: () -> String = { "" },
) {
    private val main = Handler(Looper.getMainLooper())

    @Volatile var settings: FluidSettings = initial
        private set

    private val video = VideoOverlay(host, surfaceListener)
    private val hud = HudOverlay(host)
    private val menu = MenuOverlay(host, initial, ::applySettings, onStopRequested, onRecordToggle, isRecording, foreground)

    val trusted: Boolean get() = host.trusted

    fun show() {
        video.show(settings.secureOverlay)
        hud.show()
        hud.setVisible(settings.showHud)
        menu.show()
    }

    fun hide() {
        menu.hide()
        hud.hide()
        video.hide()
    }

    fun setVideoVisible(visible: Boolean) = video.setVisible(visible)

    fun setHudText(text: String) = hud.setText(text)

    private fun applySettings(new: FluidSettings) {
        val old = settings
        settings = new
        hud.setVisible(new.showHud)
        if (old.targetHz != new.targetHz) SettingsStore(host.context).save(new)
        if (old.secureOverlay != new.secureOverlay || old.targetHz != new.targetHz) {
            // FLAG_SECURE đặt lúc tạo cửa sổ: dựng lại cửa sổ video (menu/HUD vẫn nằm trên).
            video.hide()
            video.show(new.secureOverlay)
            main.post { /* thứ tự z: video mới nằm trên menu cũ nếu cùng type; đưa HUD/menu lên lại */
                hud.hide(); hud.show(); hud.setVisible(new.showHud)
                menu.hide(); menu.show()
            }
        }
        onSettings(new)
    }
}

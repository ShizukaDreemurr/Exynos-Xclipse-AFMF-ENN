package com.fluidframes.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.view.WindowManager

/**
 * Nơi gắn cửa sổ overlay.
 *
 * Android 12+ chặn cảm ứng đi xuyên qua cửa sổ TYPE_APPLICATION_OVERLAY nếu độ mờ tổng hợp > 0.8.
 * Cửa sổ TYPE_ACCESSIBILITY_OVERLAY được coi là "trusted" nên cảm ứng xuyên qua ở độ mờ 100%.
 * Vì vậy host Accessibility là đường chính; host ứng dụng thường chỉ là phương án dự phòng
 * (alpha 0.8, hình bị trộn 20% với game thật).
 */
interface OverlayHost {
    val context: Context
    val windowManager: WindowManager
    val windowType: Int
    /** true nếu cảm ứng xuyên qua được ở độ mờ 100%. */
    val trusted: Boolean
}

class AccessibilityOverlayHost(service: AccessibilityService) : OverlayHost {
    override val context: Context = service
    override val windowManager: WindowManager = service.getSystemService(WindowManager::class.java)
    override val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
    override val trusted: Boolean = true
}

class AppOverlayHost(override val context: Context) : OverlayHost {
    override val windowManager: WindowManager = context.getSystemService(WindowManager::class.java)
    override val windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    override val trusted: Boolean = false
}

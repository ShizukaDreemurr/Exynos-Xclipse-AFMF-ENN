package com.fluidframes.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Chỉ để có cửa sổ TYPE_ACCESSIBILITY_OVERLAY (trusted: cảm ứng xuyên qua ở độ mờ 100%).
 * Không đọc nội dung cửa sổ (canRetrieveWindowContent=false).
 * Phải bật thủ công: Cài đặt > Trợ năng. Với APK cài ngoài Play, Android 13+ có thể yêu cầu
 * "Cho phép cài đặt bị hạn chế" ở trang thông tin ứng dụng trước.
 */
class OverlayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    companion object {
        @Volatile var instance: OverlayAccessibilityService? = null
            private set
    }
}

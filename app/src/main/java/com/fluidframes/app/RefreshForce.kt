package com.fluidframes.app

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Ép tần số quét toàn hệ thống (min/peak_refresh_rate) khi dịch vụ chạy — chỉ làm được nếu người dùng đã
 * cấp WRITE_SECURE_SETTINGS bằng adb một lần. Không có quyền thì bỏ qua (overlay vẫn bỏ phiếu tần số quét).
 * Khôi phục giá trị cũ khi dừng.
 */
object RefreshForce {
    private const val TAG = "RefreshForce"
    private var saved: Pair<Float, Float>? = null

    fun canForce(c: Context) =
        c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun apply(c: Context, hz: Float): Boolean {
        if (!canForce(c)) return false
        return try {
            val r = c.contentResolver
            if (saved == null) saved = Settings.System.getFloat(r, "min_refresh_rate", 0f) to
                Settings.System.getFloat(r, "peak_refresh_rate", 0f)
            Settings.System.putFloat(r, "min_refresh_rate", hz)
            Settings.System.putFloat(r, "peak_refresh_rate", hz)
            true
        } catch (t: Throwable) { Log.w(TAG, "apply", t); false }
    }

    fun restore(c: Context) {
        val s = saved ?: return
        try {
            val r = c.contentResolver
            Settings.System.putFloat(r, "min_refresh_rate", s.first)
            Settings.System.putFloat(r, "peak_refresh_rate", s.second)
        } catch (_: Throwable) {}
        saved = null
    }
}

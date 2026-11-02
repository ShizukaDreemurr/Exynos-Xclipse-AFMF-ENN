package com.fluidframes.app

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Ép tần số quét toàn hệ thống (min/peak_refresh_rate) khi dịch vụ chạy — làm được nếu người dùng cấp
 * “Sửa đổi cài đặt hệ thống” (WRITE_SETTINGS, ngay trong app, không cần máy tính) hoặc WRITE_SECURE_SETTINGS qua adb. Không có quyền thì bỏ qua (overlay vẫn bỏ phiếu tần số quét).
 * Khôi phục giá trị cũ khi dừng.
 */
object RefreshForce {
    private const val TAG = "RefreshForce"
    private var saved: Pair<Float, Float>? = null

    fun canForce(c: Context) =
        Settings.System.canWrite(c) ||
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

    @Volatile var reassertCount = 0; private set      // số lần ép THÀNH CÔNG
    @Volatile var attempts = 0; private set           // số lần đã thử
    @Volatile var lastError = ""; private set
    @Volatile private var lastReassertMs = 0L

    /** Ép lại: đổi giá trị rồi đặt về mục tiêu để hệ thống tính lại (đặt cùng giá trị thì không có thông báo). */
    fun reassert(c: Context, hz: Float): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReassertMs < 250) return false   // tránh dồn dập
        lastReassertMs = now
        attempts++
        val r = c.contentResolver
        // 1) Cài đặt hệ thống (cần quyền WRITE_SETTINGS hoặc WRITE_SECURE_SETTINGS)
        val ok = try {
            Settings.System.putFloat(r, "peak_refresh_rate", hz + 1f)
            Settings.System.putFloat(r, "min_refresh_rate", hz)
            Settings.System.putFloat(r, "peak_refresh_rate", hz)
            true
        } catch (t: Throwable) { lastError = t.javaClass.simpleName + ": " + (t.message ?: "").take(60); Log.w(TAG, "reassert", t); false }
        if (ok) { reassertCount++; lastError = "" }
        return ok
    }

    /** Giá trị cài đặt liên quan tần số quét, để biết cái gì đang ép (hiện trên HUD/chẩn đoán). */
    fun dump(c: Context): String = try {
        val r = c.contentResolver
        "min=%.0f peak=%.0f mode=%s".format(
            Settings.System.getFloat(r, "min_refresh_rate", 0f), Settings.System.getFloat(r, "peak_refresh_rate", 0f),
            Settings.Secure.getString(r, "refresh_rate_mode") ?: Settings.System.getString(r, "refresh_rate_mode") ?: "?")
    } catch (_: Throwable) { "?" }

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

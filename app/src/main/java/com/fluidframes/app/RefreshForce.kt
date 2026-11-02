package com.fluidframes.app

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Ép tần số quét toàn hệ thống (min/peak_refresh_rate). Trên một số máy (Samsung/Android 16) hai khoá này
 * nằm ở bảng Secure (ghi vào System bị từ chối: "You cannot keep your settings in the secure settings"),
 * nên thử lần lượt System -> Secure -> Global và nhớ nơi ghi được.
 * Cần WRITE_SETTINGS (System) hoặc WRITE_SECURE_SETTINGS (Secure/Global, cấp qua adb).
 * Khôi phục giá trị cũ khi dừng.
 */
object RefreshForce {
    private const val TAG = "RefreshForce"
    private const val MIN = "min_refresh_rate"
    private const val PEAK = "peak_refresh_rate"
    private val spaces = listOf("system", "secure", "global")

    private var saved: Triple<String, Float, Float>? = null   // (bảng, min, peak)
    @Volatile private var okSpace: String? = null

    @Volatile var reassertCount = 0; private set      // số lần ép THÀNH CÔNG
    @Volatile var attempts = 0; private set           // số lần đã thử
    @Volatile var lastError = ""; private set
    @Volatile var space = ""; private set             // bảng đang ghi được
    @Volatile private var lastReassertMs = 0L

    fun canForce(c: Context) =
        Settings.System.canWrite(c) ||
            c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun get(r: ContentResolver, sp: String, key: String): Float = when (sp) {
        "secure" -> Settings.Secure.getFloat(r, key, 0f)
        "global" -> Settings.Global.getFloat(r, key, 0f)
        else -> Settings.System.getFloat(r, key, 0f)
    }

    private fun put(r: ContentResolver, sp: String, key: String, v: Float) {
        when (sp) {
            "secure" -> Settings.Secure.putFloat(r, key, v)
            "global" -> Settings.Global.putFloat(r, key, v)
            else -> Settings.System.putFloat(r, key, v)
        }
    }

    /** Đặt min=peak=hz; true nếu ghi được ở một bảng nào đó. [bounce]: đổi giá trị trước để hệ thống chắc chắn tính lại. */
    private fun write(r: ContentResolver, hz: Float, bounce: Boolean): Boolean {
        val errs = StringBuilder()
        val order = okSpace?.let { listOf(it) + spaces.filter { s -> s != it } } ?: spaces
        for (sp in order) {
            try {
                if (saved == null) saved = Triple(sp, get(r, sp, MIN), get(r, sp, PEAK))
                if (bounce) put(r, sp, PEAK, hz + 1f)
                put(r, sp, MIN, hz)
                put(r, sp, PEAK, hz)
                okSpace = sp; space = sp; lastError = ""
                return true
            } catch (t: Throwable) {
                errs.append(sp.take(3)).append(": ").append(t.javaClass.simpleName.removeSuffix("Exception"))
                    .append(' ').append((t.message ?: "").take(40)).append(" | ")
                if (okSpace == sp) okSpace = null
            }
        }
        lastError = errs.toString().trimEnd(' ', '|').take(160)
        Log.w(TAG, "write lỗi: $lastError")
        return false
    }

    fun apply(c: Context, hz: Float): Boolean = write(c.contentResolver, hz, false)

    /** Ép lại (giới hạn tần suất). */
    fun reassert(c: Context, hz: Float): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastReassertMs < 250) return false
        lastReassertMs = now
        attempts++
        val ok = write(c.contentResolver, hz, true)
        if (ok) reassertCount++
        return ok
    }

    /** Giá trị liên quan tần số quét ở cả ba bảng, để biết cái gì đang ép (hiện trong CSV). */
    fun dump(c: Context): String = try {
        val r = c.contentResolver
        fun f(sp: String, k: String) = try { "%.0f".format(get(r, sp, k)) } catch (_: Throwable) { "?" }
        "sys %s/%s sec %s/%s glo %s/%s mode=%s".format(
            f("system", MIN), f("system", PEAK), f("secure", MIN), f("secure", PEAK), f("global", MIN), f("global", PEAK),
            Settings.Secure.getString(r, "refresh_rate_mode") ?: Settings.System.getString(r, "refresh_rate_mode") ?: "?")
    } catch (_: Throwable) { "?" }

    fun restore(c: Context) {
        val s = saved ?: return
        try {
            val r = c.contentResolver
            put(r, s.first, MIN, s.second)
            put(r, s.first, PEAK, s.third)
        } catch (_: Throwable) {}
        saved = null
    }
}

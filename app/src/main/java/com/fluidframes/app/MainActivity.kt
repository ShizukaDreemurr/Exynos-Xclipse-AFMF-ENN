package com.fluidframes.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Luồng xin quyền:
 *  1. SYSTEM_ALERT_WINDOW (overlay)  -> màn hình Settings riêng
 *  2. POST_NOTIFICATIONS (Android 13+) -> không bắt buộc, từ chối vẫn chạy
 *  3. MediaProjection consent -> kết quả chuyển thẳng cho ProjectionService
 */
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Từ chối notification không chặn FGS; đi tiếp bước ghi màn hình.
            requestProjection()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    ProjectionService.startIntent(this, result.resultCode, data),
                )
                status.text = "Đang chạy. Mở game; HUD hiện chữ là OK."
            } else {
                status.text = "Bạn đã từ chối quyền ghi màn hình"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Sẵn sàng"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply {
            text = "Bắt đầu"
            setOnClickListener { onStartClicked() }
        }
        val a11y = Button(this).apply {
            text = "Bật dịch vụ Trợ năng (overlay 100% mờ, cảm ứng xuyên qua)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val stop = Button(this).apply {
            text = "Dừng"
            setOnClickListener { startService(ProjectionService.stopIntent(this@MainActivity)) ; status.text = "Đã dừng" }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(48, 48, 48, 48)
                addView(status)
                addView(a11y)
                addView(start)
                addView(stop)
            },
        )
    }

    private fun onStartClicked() {
        // Cần dịch vụ Trợ năng đang chạy HOẶC quyền SYSTEM_ALERT_WINDOW (phương án dự phòng, mờ 80%).
        if (OverlayAccessibilityService.instance == null && !Settings.canDrawOverlays(this)) {
            status.text = "Hãy bật \"Hiển thị trên ứng dụng khác\" rồi bấm Bắt đầu lại"
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+: để người dùng chọn "Một ứng dụng" (game). Khi chỉ chụp 1 app, overlay của
            // mình không nằm trong khung hình => hết lồng hình.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }
}

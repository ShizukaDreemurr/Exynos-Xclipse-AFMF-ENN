package com.fluidframes.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.fluidframes.overlay.AccessibilityOverlayHost
import com.fluidframes.overlay.AppOverlayHost
import com.fluidframes.overlay.OverlayHost
import com.fluidframes.overlay.OverlayManager
import com.fluidframes.overlay.SettingsStore
import com.fluidframes.overlay.VideoOverlay
import com.fluidframes.telemetry.ThermalMonitor
import com.fluidframes.vulkan.NativePipeline
import com.fluidframes.capture.CaptureConfig
import com.fluidframes.capture.FrameRingBuffer
import com.fluidframes.capture.ScreenCaptureEngine
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Foreground service (type mediaProjection) sở hữu MediaProjection + capture + ring buffer.
 *
 * Thứ tự bắt buộc từ Android 14: startForeground(type=MEDIA_PROJECTION) TRƯỚC,
 * rồi mới getMediaProjection(). Token chỉ dùng được cho MỘT createVirtualDisplay.
 */
class ProjectionService : Service() {

    private var engine: ScreenCaptureEngine? = null
    val ring = FrameRingBuffer(capacity = 4)

    private var native: NativePipeline? = null
    private var overlay: OverlayManager? = null
    private var pipeline: FramePipeline? = null
    private val main = Handler(Looper.getMainLooper())
    private val hudTick = object : Runnable {
        override fun run() {
            pipeline?.let { overlay?.setHudText(it.hudText()); live.value = it.live() }
            main.postDelayed(this, 500)
        }
    }

    /** Tạo overlay + pipeline. Gọi sau khi capture đã chạy. false = không dựng được. */
    private fun startRendering(): Boolean {
        val np = NativePipeline.create(this).getOrElse {
            Log.e(TAG, "Vulkan init lỗi", it)
            Toast.makeText(this, "Vulkan lỗi: ${it.message}", Toast.LENGTH_LONG).show()
            return false
        }
        native = np

        val a11y = OverlayAccessibilityService.instance
        val host: OverlayHost = when {
            a11y != null -> AccessibilityOverlayHost(a11y)
            // Không dùng overlay thường (alpha 0.8): hình bị trộn 20% với game thật => bóng ma (after image).
            else -> {
                Toast.makeText(this, "Hãy bật dịch vụ Trợ năng Xclipse ENN-SS rồi bấm Bắt đầu lại", Toast.LENGTH_LONG).show()
                return false
            }
        }
        val store = SettingsStore(this)
        val thermal = ThermalMonitor(this)
        val fp = FramePipeline(
            context = this,
            ring = ring,
            native = np,
            settings = { overlay?.settings ?: store.load() },
            thermal = thermal,
            onOverlayVisible = { v -> main.post { overlay?.setVideoVisible(v) } },
        )
        pipeline = fp
        fp.captureInfo = { engine?.info() ?: "" }
        fp.hostLabel = if (host.trusted) "A11Y" else "APP(80%)"
        overlay = OverlayManager(
            host = host,
            surfaceListener = object : VideoOverlay.Listener {
                override fun onSurface(surface: Surface?, width: Int, height: Int) {
                    np.setSurface(surface)
                }
            },
            initial = store.load(),
            onSettings = { store.save(it) },
            onStopRequested = { stopSelf() },
        ).also { it.show() }
        fp.start()
        running.value = true
        main.post(hudTick)
        if (!host.trusted) {
            Toast.makeText(this, "Chưa bật Trợ năng: overlay bị giới hạn 80% độ mờ (hình trộn với game)", Toast.LENGTH_LONG).show()
        }
        return true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (engine != null) return START_NOT_STICKY // đã chạy

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        try {
            val projection = getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(resultCode, data)!!
            engine = ScreenCaptureEngine(
                projection = projection,
                ring = ring,
                initialConfig = CaptureConfig.fromContext(this),
                onProjectionStopped = { stopSelf() },
            ).also { it.start() }
            if (!startRendering()) stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Không khởi động được capture", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Xoay màn hình -> kích thước VirtualDisplay phải đổi theo. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engine?.reconfigure(CaptureConfig.fromContext(this))
    }

    override fun onDestroy() {
        main.removeCallbacks(hudTick)
        running.value = false
        live.value = null
        pipeline?.stop()
        pipeline = null
        overlay?.hide()
        overlay = null
        native?.setSurface(null)
        native?.close()
        native = null
        engine?.stop()
        engine = null
        ring.clear()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Capture", NotificationManager.IMPORTANCE_LOW),
        )
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Xclipse ENN-SS")
            .setContentText("Đang ghi màn hình")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Dừng",
                    stopPi,
                ).build(),
            )
            .build()
    }

    companion object {
        /** Trạng thái cho giao diện (Trang chủ). */
        val running = MutableStateFlow(false)
        val live = MutableStateFlow<LiveStats?>(null)

        private const val TAG = "ProjectionService"
        private const val CHANNEL_ID = "fluidframes.capture"
        private const val NOTIF_ID = 1001
        private const val ACTION_STOP = "com.fluidframes.action.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ProjectionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, ProjectionService::class.java).setAction(ACTION_STOP)
    }
}

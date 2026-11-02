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
    private val sysLoad = SysLoad()
    private val hzTick = object : Runnable {
        override fun run() { if (overlay != null) try { hzWatch() } catch (_: Throwable) {}; main.postDelayed(this, 250) }
    }
    private var hzLowSince = 0L
    private var hzFixCount = 0
    /** Game kéo tần số quét xuống thấp hơn mục tiêu => ép lại liên tục (hệ thống + bỏ phiếu overlay). */
    private fun hzWatch(): String {
        val st = overlay?.settings ?: return ""
        // Chỉ canh khi app đang ở trước là app đã bật nội suy (không ép Hz cho app khác).
        if (st.onlyEnabledApps) {
            val fg = com.fluidframes.app.telemetry.TelemetryRecorder.foreground()
            if (fg.isNotEmpty() && fg !in st.enabledApps) { hzLowSince = 0L; return "" }
        }
        val disp = try { getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY) } catch (_: Throwable) { null } ?: return ""
        // "Cao nhất" (0) = tần số cao nhất của chế độ màn hình hiện tại.
        val want = if (st.targetHz > 0) st.targetHz else disp.supportedModes
            .filter { it.physicalWidth == disp.mode.physicalWidth && it.physicalHeight == disp.mode.physicalHeight }
            .maxOfOrNull { it.refreshRate.toInt() } ?: return ""
        val cur = disp.refreshRate
        val can = RefreshForce.canForce(this)
        val now = android.os.SystemClock.elapsedRealtime()
        if (cur in 1f..(want - 1f)) {
            // Thấp hơn mục tiêu: ép lại NGAY mỗi lượt (không chờ), kèm bỏ phiếu overlay.
            if (RefreshForce.reassert(this, want.toFloat())) hzFixCount++
            if (now - hzLowSince >= 2000 || hzLowSince == 0L) { overlay?.revoteRefresh(); hzLowSince = now }   // bỏ phiếu overlay tối đa 2s/lần
        }
        val perm = if (can) "" else " · THIẾU quyền Sửa đổi cài đặt hệ thống"
        return com.fluidframes.overlay.HudOverlay.row("Ép Hz", "%.0f→%d · thử %d, được %d%s%s".format(cur, want, RefreshForce.attempts, RefreshForce.reassertCount, perm,
            if (RefreshForce.lastError.isNotEmpty()) " · LỖI " + RefreshForce.lastError else ""),
            if (cur >= want - 1f) com.fluidframes.overlay.HudOverlay.G else com.fluidframes.overlay.HudOverlay.R)
    }
    private val hudTick = object : Runnable {
        override fun run() {
            pipeline?.let {
                val st = overlay?.settings
                val mask = st?.hudLines ?: -1
                var txt = it.hudText(mask)
                if (mask and com.fluidframes.overlay.HudLines.POWER != 0) {
                    val pw = powerLine()
                    if (pw.isNotEmpty()) txt = if (txt.isEmpty()) pw else txt + "\n" + pw
                }
                hzWatch().let { h -> if (h.isNotEmpty() && mask and com.fluidframes.overlay.HudLines.HZ != 0) txt = if (txt.isEmpty()) h else txt + "\n" + h }
                if (mask and com.fluidframes.overlay.HudLines.LOAD != 0) sysLoad.rows().joinToString("\n").let { txt = if (txt.isEmpty()) it else txt + "\n" + it }
                readPower()?.let { p ->
                    bench.noteTemp(p.tmp)
                    if (bench.active) bench.tick(android.os.SystemClock.elapsedRealtime(), p.w, p.tmp, p.ma, it.live().outFps)
                }
                val bs = bench.status.ifEmpty { bench.result }
                if (bs.isNotEmpty()) txt = (if (txt.isEmpty()) "" else txt + "\n") + bs.lines().joinToString("\n") { l -> com.fluidframes.overlay.HudOverlay.row("Đo điện", l) }
                overlay?.setHudText(txt); live.value = it.live()
            }
            main.postDelayed(this, 500)
        }
    }

    /** Tạo overlay + pipeline. Gọi sau khi capture đã chạy. false = không dựng được. */
    /** Pin %, nhiệt pin, công suất tức thời (W) từ BatteryManager. */
    private class Pw(val lvl: Int, val tmp: Float, val ma: Float, val w: Float, val plugged: Boolean)
    private fun readPower(): Pw? = try {
        val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val tmp = (b?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val mv = b?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val plugged = (b?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val ua = getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val ma = if (kotlin.math.abs(ua) > 20000) ua / 1000f else ua.toFloat()
        Pw(lvl, tmp, kotlin.math.abs(ma), kotlin.math.abs(ma) * mv / 1_000_000f, plugged)
    } catch (_: Throwable) { null }

    val bench = PowerBench().also { b ->
        b.onDone = { txt ->
            val fg = com.fluidframes.app.telemetry.TelemetryRecorder.foreground()
            val f = com.fluidframes.app.telemetry.TelemetryRecorder.saveBench(fg, txt)
            main.post { Toast.makeText(this, if (f.isNotEmpty()) "Đo điện xong. Đã lưu $f trong Download/XclipseENN-SS" else "Đo điện xong (không lưu được file).", Toast.LENGTH_LONG).show() }
        }
    }

    /** Bấm nút "Đo điện": bắt đầu / hủy. Trả lời nhắn cho Toast. */
    private fun benchToggle(): String {
        if (bench.active) { bench.cancel(); return "Đã hủy đo điện." }
        val p = readPower() ?: return "Không đọc được pin."
        if (p.plugged) return "Rút sạc ra trước (đang cắm sạc thì số đo sai)."
        val fg = com.fluidframes.app.telemetry.TelemetryRecorder.foreground()
        if (fg.isEmpty()) return "Chuyển vào game một lần rồi mở lại menu."
        (overlay?.settings)?.let { st -> if (st.onlyEnabledApps && fg !in st.enabledApps) return "App \"$fg\" chưa bật nội suy trong menu: bật nó rồi đo lại." }
        bench.noteTemp(p.tmp)
        bench.start(android.os.SystemClock.elapsedRealtime(), p.tmp)
        return "Bắt đầu đo ~2 phút: đóng menu, quay lại GAME và cứ chơi bình thường, đừng đổi cài đặt. Kết quả hiện trên HUD."
    }

    private fun powerLine(): String = try {
        val b = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = b?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val tmp = (b?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val mv = b?.getIntExtra(android.os.BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val bm = getSystemService(android.os.BatteryManager::class.java)
        val ua = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // Đơn vị dòng điện khác nhau theo máy (µA hay mA): giá trị > 20000 coi là µA.
        val ma = if (kotlin.math.abs(ua) > 20000) ua / 1000f else ua.toFloat()
        val w = kotlin.math.abs(ma) * mv / 1_000_000f
        com.fluidframes.overlay.HudOverlay.row("Pin", "%d%% %.1f°C".format(lvl, tmp)) + "\n" + com.fluidframes.overlay.HudOverlay.row("Công suất", "%.2f W".format(w), if (w < 4f) com.fluidframes.overlay.HudOverlay.G else if (w < 7f) com.fluidframes.overlay.HudOverlay.Y else com.fluidframes.overlay.HudOverlay.R)
    } catch (_: Throwable) { "" }

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
            settings = { (overlay?.settings ?: store.load()).let { st -> bench.forced?.let { f -> st.copy(backend = f) } ?: st } },
            thermal = thermal,
            onOverlayVisible = { v -> main.post { overlay?.setVideoVisible(v) } },
        )
        pipeline = fp
        pipelineRef = fp
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
            onSettings = {
                val old = store.load()
                val oldHz = old.targetHz
                store.save(it)
                // Đổi độ phân giải chụp: áp dụng ngay (resize VirtualDisplay), không cần Bắt đầu lại.
                if (it.outLongSide != old.outLongSide) engine?.reconfigure(CaptureConfig.fromContext(this, it.outLongSide))
                if (it.targetHz != oldHz) {
                    if (it.targetHz > 0) RefreshForce.apply(this, it.targetHz.toFloat()) else RefreshForce.restore(this)
                }
            },
            onStopRequested = { stopSelf() },
            onRecordToggle = {
                if (com.fluidframes.app.telemetry.TelemetryRecorder.isRecording) {
                    com.fluidframes.app.telemetry.TelemetryRecorder.stopNow("Bấm dừng ở menu overlay")
                    "Đã dừng đo. Mở app → tab Đo đạc để xem/chia sẻ file CSV, XLSX."
                } else if (com.fluidframes.app.telemetry.TelemetryRecorder.startHere(this)) {
                    "Đang đo app đang chạy. Bấm lại để dừng."
                } else "Chưa biết app nào đang chạy: bật Trợ năng rồi chuyển vào game một lần."
            },
            isRecording = { com.fluidframes.app.telemetry.TelemetryRecorder.isRecording },
            onBench = ::benchToggle,
            isBench = { bench.active },
            foreground = { com.fluidframes.app.telemetry.TelemetryRecorder.foreground() },
        ).also { it.show() }
        fp.start()
        running.value = true
        main.post(hudTick)
        main.post(hzTick)
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
                initialConfig = CaptureConfig.fromContext(this, SettingsStore(this).load().outLongSide),
                onProjectionStopped = { stopSelf() },
            ).also { it.start() }
            if (!startRendering()) stopSelf()
            SettingsStore(this).load().targetHz.let { hz ->
                if (hz > 0) RefreshForce.apply(this, hz.toFloat())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không khởi động được capture", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Xoay màn hình -> kích thước VirtualDisplay phải đổi theo. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        engine?.reconfigure(CaptureConfig.fromContext(this, SettingsStore(this).load().outLongSide))
    }

    override fun onDestroy() {
        main.removeCallbacks(hudTick)
        main.removeCallbacks(hzTick)
        RefreshForce.restore(this)
        running.value = false
        live.value = null
        pipeline?.stop()
        pipeline = null
        pipelineRef = null
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
        @Volatile var pipelineRef: FramePipeline? = null

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

package com.fluidframes.app.telemetry

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Display
import com.fluidframes.app.BuildConfig
import com.fluidframes.app.ProjectionService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Đo đạc: chọn app -> ghi số liệu mỗi giây -> tự dừng khi app đó bị thoát -> xuất .xlsx + .csv vào Download/XclipseENN-SS.
 * Mọi nguồn số liệu đều "thử đọc": máy không cho đọc thì cột đó đơn giản vắng mặt (ghi rõ ở sheet Thiết bị).
 */
object TelemetryRecorder {

    data class ExportedFile(val name: String, val uri: Uri, val mime: String)

    sealed class State {
        object Idle : State()
        data class Recording(val label: String, val pkg: String, val seconds: Int, val samples: Int,
                             val targetSeen: Boolean, val note: String) : State()
        data class Done(val label: String, val seconds: Int, val samples: Int, val reason: String,
                        val files: List<ExportedFile>, val error: String = "") : State()
    }

    val state = MutableStateFlow<State>(State.Idle)

    private const val LEAVE_MS = 5_000L
    private const val NEVER_SEEN_MS = 90_000L

    private val lock = Any()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private lateinit var app: Context
    private var target = ""
    private var label = ""
    private var t0 = 0L
    private var wall0 = 0L
    private var fgPkg = ""
    private var targetSeen = false
    private var leftSince = 0L
    private val rows = ArrayList<LinkedHashMap<String, Any?>>()
    private val events = ArrayList<Triple<Double, String, String>>()
    private var prevMode = ""
    private var prevThermal = -1
    private var prevCpuMs = 0L
    private var prevWallMs = 0L
    private var prevStat: LongArray? = null
    private var cpuFreqFiles = listOf<Pair<String, File>>()
    private var devfreqFiles = listOf<Pair<String, File>>()
    private var thermalFiles = listOf<Pair<String, File>>()
    private var psiFiles = listOf<Pair<String, File>>()
    private var procStatOk = false
    private val unreadable = ArrayList<String>()

    val isRecording get() = state.value is State.Recording

    fun start(context: Context, pkg: String, appLabel: String, launch: Boolean = true) {
        synchronized(lock) {
            if (isRecording) return
            app = context.applicationContext
            ownPkg = app.packageName
            target = pkg; label = appLabel
            rows.clear(); events.clear(); unreadable.clear()
            t0 = SystemClock.elapsedRealtime(); wall0 = System.currentTimeMillis()
            fgPkg = ""; targetSeen = false; leftSince = 0L
            prevMode = ""; prevThermal = -1; prevCpuMs = android.os.Process.getElapsedCpuTime(); prevWallMs = t0; prevStat = null
            probeSysfs()
            event("Bắt đầu đo", "$appLabel ($pkg)")
            state.value = State.Recording(appLabel, pkg, 0, 0, false, "Đang mở app…")
            val th = HandlerThread("telemetry").also { it.start() }
            thread = th; handler = Handler(th.looper)
            handler!!.post(tick)
        }
        if (launch) app.packageManager.getLaunchIntentForPackage(pkg)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { app.startActivity(it) } catch (_: Throwable) {}
        }
    }

    /** Gọi từ dịch vụ Trợ năng mỗi khi cửa sổ đổi: biết app nào đang ở trước. */
    /** App thật đang ở trước (đã loại hệ thống, bàn phím, panel Game Booster, chụp màn hình...). */
    fun foreground(): String = lastFg

    private val ignoredFg = setOf(
        "com.android.systemui", "com.samsung.android.game.gametools", "com.samsung.android.app.smartcapture",
        "com.samsung.android.sidegesturepad", "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.game.gamehome", "com.samsung.android.app.taskedge",
        "com.samsung.android.app.peoplestripe", "com.samsung.android.app.routines", "com.samsung.android.honeyboard",
    )

    fun attach(context: Context) { if (ownPkg.isEmpty()) ownPkg = context.packageName }

    @Volatile private var lastFg = ""
    @Volatile private var ownPkg = ""

    /** Bấm 'Đo log' từ menu overlay: đo luôn app đang ở trước (không mở lại app). */
    fun startHere(context: Context): Boolean {
        val pkg = lastFg
        if (pkg.isEmpty() || isRecording) return false
        val pm = context.applicationContext.packageManager
        val name = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        start(context, pkg, name, launch = false)
        synchronized(lock) { fgPkg = pkg; targetSeen = true }
        return true
    }

    /** Lưu kết quả Đo điện thành file .csv trong Download/XclipseENN-SS (và ghi vào Sự kiện nếu đang đo log). */
    fun saveBench(app: String, text: String): String = try {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val csv = StringBuilder("\uFEFFche_do,cong_suat_W,dong_mA,nhiet_pin_C,fps_ra,so_mau\r\n")
        text.lines().filter { it.contains(" W |") }.forEach { l ->
            val name = l.substringBefore(":").trim()
            val n = Regex("([0-9.]+) W \\| ([0-9.]+) mA \\| ([0-9.]+)°C \\| ([0-9.]+) fps").find(l)?.groupValues
            if (n != null) csv.append("$name,${n[1]},${n[2]},${n[3]},${n[4]},\r\n")
        }
        csv.append("app,${csvCell(app)},,,,\r\n")
        if (isRecording) event("Đo điện", text.replace('\n', ' '))
        save("ENN-SS_DoDien_$stamp.csv", "text/csv", csv.toString().toByteArray(Charsets.UTF_8)).name
    } catch (t: Throwable) { "" }

    fun onForeground(pkg: String, cls: String = "") {
        // Panel cạnh/popup của hệ thống không phải Activity => không coi là app đang ở trước.
        val isActivity = cls.isEmpty() || cls.contains("Activity") || cls.contains("Game")
        if (ownPkg.isNotEmpty() && pkg != ownPkg && pkg !in ignoredFg && isActivity &&
            !pkg.contains("inputmethod") && !pkg.contains("keyboard")) lastFg = pkg
        if (!isRecording) return
        if (pkg == app.packageName || pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        synchronized(lock) {
            if (pkg == fgPkg) return
            fgPkg = pkg
            val now = SystemClock.elapsedRealtime()
            event("Cửa sổ trước", pkg)
            if (pkg == target) { targetSeen = true; leftSince = 0L } else if (targetSeen && leftSince == 0L) leftSince = now
        }
    }

    fun stopNow(reason: String = "Bạn bấm dừng") = finish(reason)

    private val tick = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val now = SystemClock.elapsedRealtime()
            try { rows.add(sample(now)) } catch (t: Throwable) { event("Lỗi lấy mẫu", t.javaClass.simpleName + ": " + t.message) }
            val secs = ((now - t0) / 1000).toInt()
            var stopReason: String? = null
            synchronized(lock) {
                if (targetSeen && fgPkg != target && leftSince != 0L && now - leftSince >= LEAVE_MS) stopReason = "Đã thoát app ($fgPkg ở trước)"
                else if (!targetSeen && now - t0 >= NEVER_SEEN_MS) stopReason = "Không thấy app mở trong 90 giây"
                state.value = State.Recording(label, target, secs, rows.size, targetSeen,
                    if (targetSeen) "Đang đo — thoát app để dừng" else "Chờ app mở lên…")
            }
            if (stopReason != null) { finish(stopReason!!); return }
            handler?.postDelayed(this, 1000)
        }
    }

    private fun finish(reason: String) {
        val files: List<ExportedFile>; var err = ""
        val secs: Int; val n: Int
        synchronized(lock) {
            if (!isRecording) return
            event("Dừng đo", reason)
            secs = ((SystemClock.elapsedRealtime() - t0) / 1000).toInt(); n = rows.size
            state.value = State.Recording(label, target, secs, n, targetSeen, "Đang xuất file…")
        }
        handler?.removeCallbacksAndMessages(null)
        files = try { export() } catch (t: Throwable) { err = t.javaClass.simpleName + ": " + t.message; emptyList() }
        thread?.quitSafely(); thread = null; handler = null
        state.value = State.Done(label, secs, n, reason, files, err)
    }

    fun reset() { if (!isRecording) state.value = State.Idle }

    private fun event(kind: String, detail: String) {
        events.add(Triple((SystemClock.elapsedRealtime() - t0) / 1000.0, kind, detail))
    }

    // ------------------------------------------------------------------ lấy mẫu

    private fun probeSysfs() {
        fun readable(f: File) = try { f.readText().isNotBlank() } catch (_: Throwable) { false }
        cpuFreqFiles = (0..15).map { "cpu${it}_mhz" to File("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq") }
            .filter { it.second.exists() }.also { l -> if (l.none { readable(it.second) }) unreadable += "Tần số CPU (sysfs cpufreq)" }
            .filter { readable(it.second) }
        devfreqFiles = (File("/sys/class/devfreq").listFiles()?.toList() ?: emptyList()).sortedBy { it.name }
            .flatMap { d -> listOf("freq_mhz_${d.name}" to File(d, "cur_freq"), "load_${d.name}" to File(d, "load")) }
            .filter { readable(it.second) }
        if (devfreqFiles.isEmpty()) unreadable += "Tần số GPU/NPU/bus (sysfs devfreq)"
        val seen = HashMap<String, Int>()
        thermalFiles = (File("/sys/class/thermal").listFiles()?.toList() ?: emptyList())
            .filter { it.name.startsWith("thermal_zone") }.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
            .mapNotNull { z ->
                val type = try { File(z, "type").readText().trim() } catch (_: Throwable) { z.name }
                val tf = File(z, "temp")
                if (!readable(tf)) null else {
                    val k = seen.merge(type, 1, Int::plus)!!
                    ("temp_" + type + (if (k > 1) "_$k" else "")) to tf
                }
            }
        if (thermalFiles.isEmpty()) unreadable += "Nhiệt độ các vùng (sysfs thermal)"
        psiFiles = listOf("psi_cpu" to File("/proc/pressure/cpu"), "psi_mem" to File("/proc/pressure/memory"), "psi_io" to File("/proc/pressure/io"))
            .filter { readable(it.second) }
        if (psiFiles.isEmpty()) unreadable += "Áp lực hệ thống (/proc/pressure)"
        procStatOk = readable(File("/proc/stat"))
        if (!procStatOk) unreadable += "Tải CPU toàn máy (/proc/stat)"
    }

    private val sysLoad = com.fluidframes.app.SysLoad()

    private fun sample(now: Long): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m.putAll(sysLoad.columns())
        m["t_s"] = ((now - t0) / 1000.0)
        m["gio"] = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(wall0 + (now - t0)))
        synchronized(lock) { m["app_truoc"] = fgPkg; m["app_muc_tieu_truoc"] = if (fgPkg == target) 1 else 0 }

        // --- pipeline của mình
        val p = ProjectionService.pipelineRef
        if (p != null) {
            val tm = p.telemetry()
            m.putAll(tm)
            val mode = (tm["mode"] ?: "").toString() + "/" + (tm["tier"] ?: "") + "/" + (tm["backend"] ?: "")
            if (mode != prevMode) { event("Chế độ đổi", mode); prevMode = mode }
        } else m["pipeline"] = "không chạy"

        // --- màn hình
        try {
            val d = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            m["man_hz"] = d.refreshRate.toDouble()
            m["ep_hz_lan"] = com.fluidframes.app.RefreshForce.reassertCount
            m["ep_hz_cai_dat"] = com.fluidframes.app.RefreshForce.dump(app)
        } catch (_: Throwable) {}

        // --- nhiệt / pin
        val pm = app.getSystemService(PowerManager::class.java)
        val ts = pm.currentThermalStatus
        m["nhiet_trang_thai"] = ts
        if (ts != prevThermal) { if (prevThermal != -1) event("Trạng thái nhiệt đổi", "$prevThermal -> $ts"); prevThermal = ts }
        try { val h = pm.getThermalHeadroom(10); if (!h.isNaN()) m["nhiet_headroom_10s"] = h.toDouble() } catch (_: Throwable) {}
        val bi = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (bi != null) {
            val lvl = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lvl >= 0) m["pin_pct"] = lvl * 100.0 / sc
            m["pin_nhiet_C"] = bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
            m["pin_mV"] = bi.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            m["dang_sac"] = if (bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) 1 else 0
        }
        val bm = app.getSystemService(BatteryManager::class.java)
        try {
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua != Int.MIN_VALUE) m["pin_dong_mA"] = ua / 1000.0
        } catch (_: Throwable) {}

        // --- bộ nhớ
        try {
            val mi = ActivityManager.MemoryInfo(); app.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            m["ram_trong_MB"] = mi.availMem / 1048576.0; m["ram_thap"] = if (mi.lowMemory) 1 else 0
            val dm = Debug.MemoryInfo(); Debug.getMemoryInfo(dm); m["app_pss_MB"] = dm.totalPss / 1024.0
        } catch (_: Throwable) {}

        // --- CPU của chính app
        val cpuMs = android.os.Process.getElapsedCpuTime()
        val dWall = now - prevWallMs
        if (dWall > 0) m["app_cpu_pct"] = (cpuMs - prevCpuMs) * 100.0 / dWall
        prevCpuMs = cpuMs; prevWallMs = now

        // --- sysfs
        for ((k, f) in cpuFreqFiles) readNum(f)?.let { m[k] = it / 1000.0 }
        for ((k, f) in devfreqFiles) {
            val v = readNum(f) ?: continue
            m[k] = if (k.startsWith("freq")) v / 1e6 else v
        }
        for ((k, f) in thermalFiles) readNum(f)?.let { m[k] = if (it > 1000) it / 1000.0 else it }
        for ((k, f) in psiFiles) try {
            Regex("some avg10=([0-9.]+)").find(f.readText())?.groupValues?.get(1)?.toDoubleOrNull()?.let { m[k + "_avg10"] = it }
        } catch (_: Throwable) {}
        if (procStatOk) try {
            val l = File("/proc/stat").useLines { it.first() }.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
            val idle = l[3] + l.getOrElse(4) { 0 }; val total = l.sum()
            prevStat?.let { ps -> val dt = total - ps[1]; if (dt > 0) m["cpu_tai_pct"] = (dt - (idle - ps[0])) * 100.0 / dt }
            prevStat = longArrayOf(idle, total)
        } catch (_: Throwable) { procStatOk = false }
        return m
    }

    private fun readNum(f: File): Double? = try { f.readText().trim().split(Regex("\\s+"))[0].toDouble() } catch (_: Throwable) { null }

    // ------------------------------------------------------------------ xuất file

    private fun columns(): List<String> {
        val cols = LinkedHashSet<String>(); rows.forEach { cols.addAll(it.keys) }; return cols.toList()
    }

    private fun pct(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        val i = (q * (sorted.size - 1)); val lo = i.toInt(); val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo)
    }

    private fun export(): List<ExportedFile> {
        val cols = columns()
        val series = listOf(cols) + rows.map { r -> cols.map { r[it] } }
        // Tổng kết: mỗi cột số -> số mẫu, TB, nhỏ nhất, p1 (1% thấp), trung vị, p99, lớn nhất
        val sum = ArrayList<List<Any?>>()
        sum.add(listOf("chỉ số", "số mẫu", "trung bình", "nhỏ nhất", "p1 (1% thấp)", "trung vị", "p99", "lớn nhất"))
        for (c in cols) {
            val v = rows.mapNotNull { (it[c] as? Number)?.toDouble() }.filter { !it.isNaN() }
            if (v.isEmpty() || c == "t_s") continue
            val s = v.sorted()
            sum.add(listOf(c, v.size, v.average(), s.first(), pct(s, 0.01), pct(s, 0.5), pct(s, 0.99), s.last()))
        }
        val ev = listOf(listOf<Any?>("giây", "loại", "chi tiết")) + events.map { listOf(it.first, it.second, it.third) }
        val dev = ArrayList<List<Any?>>()
        dev.add(listOf("mục", "giá trị"))
        dev.add(listOf("app đo", "$label ($target)"))
        dev.add(listOf("bắt đầu", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(wall0))))
        dev.add(listOf("thời lượng (giây)", (rows.lastOrNull()?.get("t_s") ?: 0.0)))
        dev.add(listOf("số mẫu", rows.size))
        dev.add(listOf("máy", "${Build.MANUFACTURER} ${Build.MODEL}"))
        dev.add(listOf("SoC", (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "") + " " + Build.HARDWARE))
        dev.add(listOf("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"))
        dev.add(listOf("phiên bản app", BuildConfig.BUILD_TAG))
        dev.add(listOf("số nhân CPU", Runtime.getRuntime().availableProcessors()))
        try { dev.add(listOf("tần số quét hỗ trợ", app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).supportedModes.map { it.refreshRate.toInt() }.distinct().sorted().joinToString(", "))) } catch (_: Throwable) {}
        unreadable.forEach { dev.add(listOf("KHÔNG đọc được", it)) }
        dev.add(listOf("ghi chú", "Cột trống = máy không cho đọc hoặc pipeline không chạy lúc đó."))

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(wall0))
        val base = "ENN-SS_" + label.replace(Regex("[^A-Za-z0-9]+"), "") .ifEmpty { target.substringAfterLast('.') } + "_" + stamp

        val xb = ByteArrayOutputStream()
        Xlsx.write(xb, listOf(Xlsx.Sheet("Tổng kết", sum), Xlsx.Sheet("Mỗi giây", series), Xlsx.Sheet("Sự kiện", ev), Xlsx.Sheet("Thiết bị", dev)))
        val csv = StringBuilder("﻿")
        series.forEach { r -> csv.append(r.joinToString(",") { csvCell(it) }).append("\r\n") }

        return listOf(
            save("$base.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xb.toByteArray()),
            save("$base.csv", "text/csv", csv.toString().toByteArray(Charsets.UTF_8)),
        )
    }

    private fun csvCell(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v.isNaN() || v.isInfinite()) "" else java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        is Float -> csvCell(v.toDouble())
        is Number -> v.toString()
        else -> v.toString().let { if (it.any { c -> c == ',' || c == '"' || c == '\n' }) "\"" + it.replace("\"", "\"\"") + "\"" else it }
    }

    private fun save(name: String, mime: String, data: ByteArray): ExportedFile {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/XclipseENN-SS")
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("Không tạo được file $name")
        app.contentResolver.openOutputStream(uri)!!.use { it.write(data) }
        return ExportedFile(name, uri, mime)
    }
}

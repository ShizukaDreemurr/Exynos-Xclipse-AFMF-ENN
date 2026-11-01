package com.fluidframes.app

import com.fluidframes.overlay.HudOverlay
import java.io.File

/** Đo CPU/GPU % và MHz không cần quyền đặc biệt (cách của fpsstat). Đọc không được thì trả null → HUD hiện "n/a". */
class SysLoad {
    private fun rd(p: String): String? = try { File(p).readText().trim().ifEmpty { null } } catch (_: Throwable) { null }
    private fun rdInt(p: String): Long? = rd(p)?.replace("%", "")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()

    private val ncpu = (0..9).count { File("/sys/devices/system/cpu/cpu$it").exists() }.coerceAtLeast(1)
    private var lastIdle = LongArray(ncpu); private var lastAll = 0L; private var lastIdleSum = 0L
    private var lastWall = 0L; private var haveLast = false

    private val gpuLoadPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy", "/sys/kernel/gpu/gpu_load",
        "/sys/class/devfreq/gpufreq/mali_ondemand/utilisation", "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/kernel/debug/ged/hal/gpu_utilization", "/sys/module/ged/parameters/gpu_loading",
        "/sys/devices/platform/gpusysfs/gpu_busy", "/sys/class/misc/mali0/device/utilization")
    private val gpuFreqPaths: List<String> by lazy {
        val l = mutableListOf("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "/sys/kernel/gpu/gpu_clock", "/sys/class/devfreq/gpufreq/cur_freq")
        try { File("/sys/class/devfreq").listFiles()?.filter { it.name.contains("gpu", true) || it.name.contains("mali", true) }
            ?.forEach { l += it.path + "/cur_freq" } } catch (_: Throwable) {}
        l
    }
    private val gpuLoadFromDevfreq: List<String> by lazy {
        val l = mutableListOf<String>()
        try { File("/sys/class/devfreq").listFiles()?.filter { it.name.contains("gpu", true) || it.name.contains("mali", true) }
            ?.forEach { l += it.path + "/load"; l += it.path + "/utilization" } } catch (_: Throwable) {}
        l
    }

    /** CPU %: /proc/stat nếu đọc được, không thì tổng thời gian idle trong cpuidle. */
    fun cpuPct(): Double? {
        val wall = System.nanoTime() / 1000
        rd("/proc/stat")?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }?.let { line ->
            val f = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
            if (f.size >= 5) {
                val all = f.take(8).sum(); val idle = f[3] + f[4]
                val r = if (haveLast && all > lastAll) 100.0 * (1 - (idle - lastIdleSum).toDouble() / (all - lastAll)) else null
                lastAll = all; lastIdleSum = idle; haveLast = true
                return r?.coerceIn(0.0, 100.0)
            }
        }
        var n = 0; val cur = LongArray(ncpu)
        for (i in 0 until ncpu) {
            val on = rd("/sys/devices/system/cpu/cpu$i/online")
            if (on == "0") continue
            var s = 0L; var any = false
            for (k in 0..9) { val t = rdInt("/sys/devices/system/cpu/cpu$i/cpuidle/state$k/time") ?: continue; s += t; any = true }
            if (!any) return null
            cur[i] = s; n++
        }
        if (n == 0) return null
        val dWall = wall - lastWall
        var r: Double? = null
        if (lastWall != 0L && dWall > 0) {
            var idle = 0.0
            for (i in 0 until ncpu) if (cur[i] != 0L) idle += (cur[i] - lastIdle[i]).coerceIn(0, dWall).toDouble()
            r = (100.0 * (1 - idle / (dWall.toDouble() * n))).coerceIn(0.0, 100.0)
        }
        lastIdle = cur; lastWall = wall
        return r
    }

    fun cpuMhz(): Int? {
        var best = 0L
        for (i in 0 until ncpu) {
            val v = rdInt("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") ?: rdInt("/sys/devices/system/cpu/cpufreq/policy$i/scaling_cur_freq") ?: continue
            if (v > best) best = v
        }
        return if (best > 0) (best / 1000).toInt() else null
    }

    fun gpuPct(): Int? {
        for (p in gpuLoadPaths + gpuLoadFromDevfreq) rdInt(p)?.let { return it.toInt().coerceIn(0, 100) }
        return null
    }

    fun gpuMhz(): Int? {
        for (p in gpuFreqPaths) rdInt(p)?.let { var v = it; while (v > 5000) v /= 1000; return v.toInt() }
        return null
    }

    fun rows(): List<String> {
        val c = cpuPct(); val cm = cpuMhz(); val g = gpuPct(); val gm = gpuMhz()
        fun col(p: Double?) = if (p == null) "" else if (p < 60) HudOverlay.G else if (p < 85) HudOverlay.Y else HudOverlay.R
        fun v(p: Double?, m: Int?) = (if (p != null) "%.0f%%".format(p) else "n/a") + (if (m != null) " ${m}MHz" else "")
        return listOf(HudOverlay.row("CPU", v(c, cm), col(c)), HudOverlay.row("GPU", v(g?.toDouble(), gm), col(g?.toDouble())))
    }

    fun columns(): Map<String, Any?> = mapOf("cpu_pct" to cpuPct(), "cpu_mhz" to cpuMhz(), "gpu_pct" to gpuPct(), "gpu_mhz" to gpuMhz())
}

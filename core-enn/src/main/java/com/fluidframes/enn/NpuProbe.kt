package com.fluidframes.enn

import android.os.Build
import java.io.File

/** Kiểm tra máy có thư viện ENN hệ thống mà app thường truy cập được không. */
object NpuProbe {
    private val libs = listOf(
        "libenn_public_api_cpp.so",
        "libenn_public_api_ndk_v1.so",
        "libenn_user.samsung_slsi.so",
        "libenn_user.so",
        "libeden_nn_on_system.so",
        "libGLES_mali.so",
    )
    private val dirs = listOf(
        "/vendor/lib64", "/system/lib64", "/system/vendor/lib64",
        "/vendor/lib64/hw", "/system_ext/lib64", "/odm/lib64",
    )

    fun run(): String {
        val sb = StringBuilder()
        sb.append("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL} / ${Build.HARDWARE}\n")
        for (l in libs) {
            val loaded = try { System.loadLibrary(l.removePrefix("lib").removeSuffix(".so")); "dlopen OK" }
            catch (t: Throwable) { "dlopen FAIL" }
            val found = dirs.filter { File(it, l).exists() }
            sb.append("$l: $loaded; file=${if (found.isEmpty()) "-" else found.joinToString()}\n")
        }
        val pub = listOf("/vendor/etc/public.libraries.txt", "/system/etc/public.libraries.txt",
            "/system/etc/public.libraries-samsung.txt", "/vendor/etc/public.libraries-samsung.txt")
        for (p in pub) {
            val f = File(p)
            if (f.canRead()) {
                val hit = f.readLines().filter { it.contains("enn", true) || it.contains("eden", true) }
                sb.append("$p: ${if (hit.isEmpty()) "no enn" else hit.joinToString()}\n")
            }
        }
        return sb.toString()
    }
}

package com.fluidframes.enn

/** Bắt log native của tiến trình (để thấy lỗi thật từ LiteRT/ENN khi không đọc được logcat). */
object NativeLog {
    private var ok = false
    fun start() {
        try { System.loadLibrary("ennlog"); nStart(); ok = true } catch (_: Throwable) { ok = false }
    }
    fun clear() { if (ok) try { nClear() } catch (_: Throwable) {} }
    fun tail(maxLines: Int = 20, maxChars: Int = 1500): String =
        if (!ok) "" else try { nDrain(maxLines).takeLast(maxChars) } catch (_: Throwable) { "" }

    @JvmStatic private external fun nStart()
    @JvmStatic private external fun nClear()
    @JvmStatic private external fun nDrain(maxLines: Int): String
}

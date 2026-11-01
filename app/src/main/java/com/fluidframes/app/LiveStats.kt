package com.fluidframes.app

/** Ảnh chụp số liệu của pipeline để hiển thị trên Trang chủ. */
data class LiveStats(
    val srcFps: Float,
    val outFps: Float,
    val mode: String,
    val tier: String,
    val backend: String,
    val workMs: Float,
    val dropPct: Float,
    val thermal: Int,
)

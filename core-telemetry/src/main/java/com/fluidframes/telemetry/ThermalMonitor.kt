package com.fluidframes.telemetry

import android.content.Context
import android.os.PowerManager

/** Theo dõi PowerManager thermal status (0 NONE … 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN). */
class ThermalMonitor(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)

    @Volatile var status: Int = 0
        private set

    private val listener = PowerManager.OnThermalStatusChangedListener { status = it }

    fun start() {
        status = pm.currentThermalStatus
        pm.addThermalStatusListener(listener)
    }

    fun stop() {
        pm.removeThermalStatusListener(listener)
    }

    /** Dự báo headroom (1.0 ≈ chạm ngưỡng throttle) sau [forecastSeconds]; NaN nếu thiết bị không hỗ trợ. */
    fun headroom(forecastSeconds: Int = 10): Float = pm.getThermalHeadroom(forecastSeconds)
}

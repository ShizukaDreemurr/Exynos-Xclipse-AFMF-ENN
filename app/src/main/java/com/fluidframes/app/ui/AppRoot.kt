package com.fluidframes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val tabs = listOf(
    NavItem("Trang chủ", Icons.Filled.Home),
    NavItem("Cài đặt", Icons.Filled.Settings),
    NavItem("NPU", Icons.Filled.Build),
    NavItem("Đo đạc", Icons.Filled.Info),
)

/** Khung ứng dụng: nội dung cuộn + thanh điều hướng viên thuốc nổi ở dưới (vùng thao tác tầm ngón cái). */
@Composable
fun AppRoot(home: @Composable () -> Unit, settings: @Composable () -> Unit, npu: @Composable () -> Unit, telemetry: @Composable () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().background(FF.c.bg)) {
        Box(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 108.dp)) {
            when (tab) {
                0 -> home()
                1 -> settings()
                2 -> npu()
                else -> telemetry()
            }
        }
        FloatingNav(
            items = tabs, selected = tab, onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp),
        )
    }
}

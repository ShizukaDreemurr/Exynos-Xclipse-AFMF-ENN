package com.fluidframes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluidframes.app.LiveStats

@Composable
fun HomeScreen(
    running: Boolean,
    a11yOn: Boolean,
    notifOn: Boolean,
    live: LiveStats?,
    message: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenA11y: () -> Unit,
    onRequestNotif: () -> Unit,
) {
    val ready = a11yOn && notifOn
    Column(Modifier.fillMaxWidth()) {
        LargeHeader(
            title = "Xclipse ENN-SS",
            subtitle = "Nội suy khung hình 30→60 fps cho Exynos 2600",
            minHeight = 300.dp,
        ) {
            EclipseMark(size = 132.dp, active = running)
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                when {
                    running -> StatusChip("Đang chạy", Level.OK)
                    ready -> StatusChip("Sẵn sàng", Level.INFO)
                    else -> StatusChip("Cần thiết lập", Level.WARN)
                }
            }
            if (running) PillButton("Dừng", onStop, danger = true)
            else PillButton(if (ready) "Bắt đầu" else "Hoàn tất thiết lập", onStart)

            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = FF.c.warn, modifier = Modifier.padding(horizontal = 4.dp))
            }

            if (running && live != null) {
                FFCard {
                    SectionTitle("Số liệu trực tiếp")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Metric("Nguồn (fps)", "%.0f".format(live.srcFps), Modifier.weight(1f))
                        Metric("Xuất (fps)", "%.0f".format(live.outFps), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Metric("Xử lý (ms)", "%.1f".format(live.workMs), Modifier.weight(1f))
                        Metric("Bỏ khung", "%.0f%%".format(live.dropPct), Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    MonoText("${live.mode} · ${live.tier} · ${live.backend} · nhiệt ${live.thermal}")
                }
            }

            FFCard {
                SectionTitle("Thiết lập")
                StepRow(1, "Dịch vụ Trợ năng", "Vẽ lớp phủ 100% mờ, chạm xuyên qua. Không đọc nội dung màn hình.",
                    a11yOn, "Bật", onOpenA11y)
                StepRow(2, "Thông báo", "Cần để dịch vụ chạy nền không bị hệ thống dừng.", notifOn, "Cấp quyền", onRequestNotif)
                StepRow(3, "Ghi màn hình", "Khi bấm Bắt đầu, chọn “Một ứng dụng” rồi chọn game.", false)
            }

            FFCard {
                SectionTitle("Cách dùng")
                Text("1. Bật Trợ năng, rồi bấm Bắt đầu và chọn game cần tăng khung hình.\n" +
                    "2. Mở game: chạm bong bóng nhật thực để đổi cài đặt ngay trong game.\n" +
                    "3. Nếu hình giật, vào Cài đặt chọn preset Tiết kiệm hoặc hạ độ phân giải.",
                    style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

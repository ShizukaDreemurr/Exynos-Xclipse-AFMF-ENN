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
import com.fluidframes.overlay.FluidSettings
import com.fluidframes.telemetry.FlowBackend
import com.fluidframes.telemetry.ProcTier

private val tiers = listOf(ProcTier.AUTO, ProcTier.P720, ProcTier.P540, ProcTier.P360)
private val tierLabels = listOf("Tự động", "720p", "540p", "360p")
private val backends = listOf(FlowBackend.AUTO, FlowBackend.NPU, FlowBackend.GPU, FlowBackend.OFF)
private val backendLabels = listOf("Tự động", "NPU", "GPU", "Tắt")
private val backendDesc = mapOf(
    FlowBackend.AUTO to "Hệ thống tự chọn: NPU nếu chạy được, không thì GPU.",
    FlowBackend.NPU to "Ép dùng NPU Exynos (model AI). Cần model đã huấn luyện để hình đẹp.",
    FlowBackend.GPU to "Block-matching trên GPU Vulkan. Ổn định, không cần model.",
    FlowBackend.OFF to "Tắt nội suy: chỉ chuyển tiếp khung hình gốc.",
)

private data class Preset(val name: String, val desc: String, val tier: ProcTier, val backend: FlowBackend)

private val presets = listOf(
    Preset("Cân bằng", "Tự động theo nhiệt và tải", ProcTier.AUTO, FlowBackend.AUTO),
    Preset("Chất lượng", "720p, ưu tiên NPU", ProcTier.P720, FlowBackend.NPU),
    Preset("Tiết kiệm", "360p, GPU, mát máy", ProcTier.P360, FlowBackend.GPU),
)

@Composable
fun SettingsScreen(settings: FluidSettings, running: Boolean, onChange: (FluidSettings) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        LargeHeader("Cài đặt", "Tinh chỉnh chất lượng và hiệu năng", minHeight = 170.dp)
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (running) {
                Text("Đang chạy: thay đổi ở đây áp dụng từ phiên sau (đổi ngay trong game bằng bong bóng nhật thực).",
                    style = MaterialTheme.typography.bodyMedium, color = FF.c.warn)
            }

            FFCard {
                SectionTitle("Preset nhanh")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        val sel = settings.tier == p.tier && settings.backend == p.backend
                        Column(
                            Modifier.weight(1f)
                                .background3(sel)
                                .clickable3 { onChange(settings.copy(tier = p.tier, backend = p.backend)) }
                                .padding(12.dp),
                        ) {
                            Text(p.name, style = MaterialTheme.typography.labelLarge,
                                color = if (sel) FF.c.text else FF.c.text)
                            Spacer(Modifier.height(2.dp))
                            Text(p.desc, style = MaterialTheme.typography.labelMedium, color = FF.c.muted)
                        }
                    }
                }
            }

            FFCard {
                SectionTitle("Công cụ tính flow")
                Segmented(backendLabels, backends.indexOf(settings.backend).coerceAtLeast(0)) {
                    onChange(settings.copy(backend = backends[it]))
                }
                Spacer(Modifier.height(10.dp))
                Text(backendDesc[settings.backend].orEmpty(), style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
            }

            FFCard {
                SectionTitle("Độ phân giải xử lý")
                Segmented(tierLabels, tiers.indexOf(settings.tier).coerceAtLeast(0)) {
                    onChange(settings.copy(tier = tiers[it]))
                }
                Spacer(Modifier.height(10.dp))
                Text("Thấp hơn = nhanh và mát hơn nhưng chuyển động kém mịn. “Tự động” hạ bậc khi máy nóng.",
                    style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
            }

            FFCard {
                SectionTitle("Tần số quét mục tiêu")
                val hzOpts = listOf(0, 60, 90, 120)
                Segmented(listOf("Cao nhất", "60 Hz", "90 Hz", "120 Hz"), hzOpts.indexOf(settings.targetHz).coerceAtLeast(0)) {
                    onChange(settings.copy(targetHz = hzOpts[it]))
                }
                Spacer(Modifier.height(10.dp))
                Text("Overlay xin hệ thống chạy đúng tần số này, và (nếu đã cấp quyền bên dưới) ép luôn trên toàn hệ thống trong lúc dịch vụ chạy. " +
                    "Game hoặc Game Booster của Samsung vẫn có thể từ chối nếu không có quyền ép.",
                    style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
                Spacer(Modifier.height(6.dp))
                Text("Cấp quyền ép một lần (chạy trên máy tính, bật gỡ lỗi USB): adb shell pm grant com.fluidframes android.permission.WRITE_SECURE_SETTINGS",
                    style = MaterialTheme.typography.labelMedium, color = FF.c.muted)
            }

            FFCard {
                SectionTitle("Hiển thị")
                SwitchRow("Nội suy khung hình", "Tắt để chỉ chuyển tiếp hình gốc", settings.enabled) {
                    onChange(settings.copy(enabled = it))
                }
                SwitchRow("HUD số liệu", "Bảng nhỏ ở góc trái trên khi chơi", settings.showHud) {
                    onChange(settings.copy(showHud = it))
                }
                SwitchRow("Overlay secure (thử nghiệm)", "Thử loại lớp phủ khỏi khung ghi hình", settings.secureOverlay) {
                    onChange(settings.copy(secureOverlay = it))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

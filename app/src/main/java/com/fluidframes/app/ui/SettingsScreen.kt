package com.fluidframes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fluidframes.overlay.FluidSettings
import com.fluidframes.telemetry.FlowBackend
import com.fluidframes.telemetry.ProcTier

private val tiers = listOf(ProcTier.AUTO, ProcTier.P720, ProcTier.P540, ProcTier.P360, ProcTier.P270, ProcTier.P216, ProcTier.P180, ProcTier.P100, ProcTier.P70)
private val backends = listOf(FlowBackend.AUTO, FlowBackend.NPU, FlowBackend.GPU, FlowBackend.OFF)
private val backendLabels = listOf("Tự động", "NPU", "GPU", "Tắt")
private val backendDesc = mapOf(
    FlowBackend.AUTO to "NPU nếu nạp được, nếu không thì GPU. Giữ nguyên lựa chọn, không tự đổi theo tải.",
    FlowBackend.NPU to "Ép dùng NPU Exynos (model AI). Cần model đã huấn luyện để hình đẹp.",
    FlowBackend.GPU to "Block-matching trên GPU Vulkan. Ổn định, không cần model.",
    FlowBackend.OFF to "Tắt nội suy: chỉ chuyển tiếp khung hình gốc.",
)

private data class Preset(val name: String, val desc: String, val tier: ProcTier, val backend: FlowBackend)

private val presets = listOf(
    Preset("Cân bằng", "130p, NPU nếu có (không tự đổi giữa chừng)", ProcTier.AUTO, FlowBackend.AUTO),
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
                var open by remember { mutableStateOf(false) }
                Box {
                    Text("${settings.tier.label}  ${if (open) "▲" else "▼"}", style = MaterialTheme.typography.titleMedium,
                        color = FF.c.text, modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 12.dp))
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        tiers.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { open = false; onChange(settings.copy(tier = t)) }) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Thấp hơn = nhanh và mát hơn nhưng chuyển động kém mịn. Không còn tự hạ bậc: app giữ đúng mức bạn chọn (chỉ tự dừng khi máy quá nóng mức nguy hiểm).",
                    style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
            }

            FFCard {
                SectionTitle("Độ phân giải hình xuất")
                val outOpts = listOf(0, 1920, 1560, 1280)
                Segmented(listOf("Gốc", "1920", "1560", "1280"), outOpts.indexOf(settings.outLongSide).coerceAtLeast(0)) {
                    onChange(settings.copy(outLongSide = outOpts[it]))
                }
                Spacer(Modifier.height(10.dp))
                Text("Cạnh dài của hình app vẽ ra (Gốc = đúng độ phân giải màn hình, nét nhất). Flow tính ở “Độ phân giải xử lý”, " +
                    "còn khung nội suy lấy chi tiết từ ảnh độ phân giải này nên Gốc cho hình nét như game. Thấp hơn = nhẹ GPU hơn nhưng mờ. " +
                    "Cần bấm Dừng rồi Bắt đầu lại để áp dụng.",
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
                Spacer(Modifier.height(10.dp))
                val ctx = LocalContext.current
                val canWrite = Settings.System.canWrite(ctx)
                Text(if (canWrite) "Quyền sửa cài đặt hệ thống: ĐÃ CẤP. Khi dịch vụ chạy, app đặt tần số quét tối thiểu/tối đa theo mục trên (và trả lại khi dừng). Game Booster/GOS của Samsung vẫn có thể ghi đè."
                    else "Quyền sửa cài đặt hệ thống: CHƯA CẤP.",
                    style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
                if (!canWrite) {
                    Spacer(Modifier.height(8.dp))
                    PillButton("Cấp quyền ép tần số (không cần máy tính)", {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text("Hoặc cấp qua máy tính (chạy trên máy tính, bật gỡ lỗi USB): adb shell pm grant com.fluidframes android.permission.WRITE_SECURE_SETTINGS",
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

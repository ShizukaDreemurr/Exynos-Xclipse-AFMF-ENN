package com.fluidframes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluidframes.enn.NpuDiagnostics

@Composable
fun NpuScreen(
    report: NpuDiagnostics.Report?,
    busy: Boolean,
    onRun: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        LargeHeader("NPU Exynos", "Kiểm tra ENN, nạp model và đo tốc độ", minHeight = 170.dp)
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FFCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Trạng thái", style = MaterialTheme.typography.labelMedium, color = FF.c.muted)
                        Spacer(Modifier.height(6.dp))
                        when {
                            busy -> StatusChip("Đang chẩn đoán…", Level.INFO)
                            report == null -> StatusChip("Chưa chạy", Level.INFO)
                            report.npuWorks -> StatusChip("NPU chạy được", Level.OK)
                            else -> StatusChip("NPU chưa chạy được", Level.WARN)
                        }
                    }
                    if (report?.npuMsAt640 != null) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("%.1f ms".format(report.npuMsAt640), style = MaterialTheme.typography.titleLarge, color = FF.c.text)
                            Text("640 · mỗi khung", style = MaterialTheme.typography.labelMedium, color = FF.c.muted)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                PillButton(if (busy) "Đang chạy…" else "Chạy chẩn đoán", onRun, enabled = !busy)
                if (busy) {
                    Spacer(Modifier.height(10.dp))
                    Text("Mất khoảng 10–30 giây. Giữ màn hình sáng.", style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
                }
            }

            if (report != null) {
                FFCard {
                    SectionTitle("Chi tiết")
                    report.rows.forEach { r ->
                        val lv = when (r.level) {
                            NpuDiagnostics.Level.OK -> Level.OK
                            NpuDiagnostics.Level.WARN -> Level.WARN
                            NpuDiagnostics.Level.FAIL -> Level.BAD
                            NpuDiagnostics.Level.INFO -> Level.INFO
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 7.dp).size(10.dp).background(levelColor(lv), CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.title, style = MaterialTheme.typography.bodyLarge, color = FF.c.text)
                                if (r.detail.isNotBlank()) {
                                    Text(r.detail, style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
                                }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Sao chép", onCopy, primary = false, modifier = Modifier.weight(1f))
                    PillButton("Chia sẻ", onShare, primary = false, modifier = Modifier.weight(1f))
                }
            }
            FFCard {
                SectionTitle("Về NPU trong app")
                Text("Model flow là IFRNet-S đã huấn luyện (MIT), chạy trên NPU Exynos để ước lượng chuyển động; " +
                    "GPU ghép khung. Khi NPU không chạy được, app tự dùng GPU.",
                    style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

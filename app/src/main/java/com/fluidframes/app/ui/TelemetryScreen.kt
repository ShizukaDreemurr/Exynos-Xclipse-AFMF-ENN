package com.fluidframes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluidframes.app.telemetry.TelemetryRecorder
import com.fluidframes.app.telemetry.TelemetryRecorder.State

data class AppEntry(val label: String, val pkg: String)

@Composable
fun TelemetryScreen(
    state: State,
    apps: List<AppEntry>,
    pipelineRunning: Boolean,
    onPick: (AppEntry) -> Unit,
    onStop: () -> Unit,
    onShare: (TelemetryRecorder.ExportedFile) -> Unit,
    onOpen: (TelemetryRecorder.ExportedFile) -> Unit,
    onReset: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        LargeHeader(title = "Đo đạc", subtitle = "Chọn app → đo ngay → thoát app thì dừng → xuất Excel/CSV", minHeight = 150.dp)
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (state) {
                is State.Idle -> {
                    if (!pipelineRunning) {
                        FFCard {
                            Text("Chưa bấm Bắt đầu ở Trang chủ nên chưa có fps/ms của app (chỉ đo được nhiệt, pin, bộ nhớ, tần số). " +
                                "Muốn đủ số liệu: bấm Bắt đầu ở Trang chủ trước, rồi quay lại đây chọn game.",
                                style = MaterialTheme.typography.bodyMedium, color = FF.c.warn)
                        }
                    }
                    FFCard {
                        SectionTitle("Chọn app cần đo")
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("Tìm app (vd: Genshin, Liên Quân)") }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }.take(40)
                        shown.forEach { a ->
                            Row(Modifier.fillMaxWidth().clickable3 { onPick(a) }.padding(vertical = 12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.label, style = MaterialTheme.typography.bodyLarge, color = FF.c.text)
                                    Text(a.pkg, style = MaterialTheme.typography.labelMedium, color = FF.c.muted)
                                }
                            }
                        }
                        if (shown.isEmpty()) Text("Không thấy app nào.", color = FF.c.muted)
                    }
                }
                is State.Recording -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { StatusChip("Đang đo", Level.OK) }
                    FFCard {
                        SectionTitle(state.label)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Metric("Thời gian (giây)", "${state.seconds}", Modifier.weight(1f))
                            Metric("Số mẫu", "${state.samples}", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(state.note, style = MaterialTheme.typography.bodyMedium, color = FF.c.muted)
                    }
                    PillButton("Dừng và xuất file", onStop, danger = true)
                }
                is State.Done -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        StatusChip(if (state.files.isNotEmpty()) "Đã xuất file" else "Xuất file lỗi", if (state.files.isNotEmpty()) Level.OK else Level.BAD)
                    }
                    FFCard {
                        SectionTitle(state.label)
                        Text("${state.seconds} giây · ${state.samples} mẫu · ${state.reason}", color = FF.c.muted, style = MaterialTheme.typography.bodyMedium)
                        if (state.error.isNotEmpty()) Text(state.error, color = FF.c.bad, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        state.files.forEach { f ->
                            Text(f.name, style = MaterialTheme.typography.bodyMedium, color = FF.c.text)
                            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                SmallPill("Mở", { onOpen(f) }); SmallPill("Chia sẻ", { onShare(f) })
                            }
                        }
                        if (state.files.isNotEmpty()) Text("Lưu trong thư mục Download/XclipseENN-SS", color = FF.c.muted, style = MaterialTheme.typography.labelMedium)
                    }
                    PillButton("Đo app khác", onReset, primary = false)
                }
            }
        }
    }
}

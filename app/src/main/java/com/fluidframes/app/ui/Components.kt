package com.fluidframes.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon

/** Vùng xem (viewing area): tiêu đề lớn căn giữa, lề rộng. */
@Composable
fun LargeHeader(title: String, subtitle: String? = null, minHeight: Dp = 150.dp, content: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
        Text(title, style = MaterialTheme.typography.headlineLarge, color = FF.c.text, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = FF.c.muted, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun FFCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.fillMaxWidth()
            .background(FF.c.card, RoundedCornerShape(28.dp))
            .border(1.dp, FF.c.outline.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .padding(20.dp),
    ) { content() }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = FF.c.text, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val c = FF.c
    val bg = when {
        danger -> Brush.horizontalGradient(listOf(c.bad, c.bad))
        primary -> Brush.horizontalGradient(listOf(c.accent, c.accent2))
        else -> Brush.horizontalGradient(listOf(c.card2, c.card2))
    }
    val fg = if (primary || danger) Color.White else c.text
    Box(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .background(bg, RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

@Composable
fun SmallPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = FF.c
    Box(
        modifier = modifier.heightIn(min = 48.dp)
            .background(FF.c.accent.copy(alpha = 0.16f), RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (c.dark) c.accent2 else c.accent, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = FF.c
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.card2, uncheckedBorderColor = c.outline,
            ),
        )
    }
}

/** Nhóm lựa chọn dạng viên thuốc (thay cho nút xoay vòng). */
@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = FF.c
    Row(
        modifier = Modifier.fillMaxWidth().background(c.card2, RoundedCornerShape(50)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .background(if (sel) c.accent else Color.Transparent, RoundedCornerShape(50))
                    .clickable(role = Role.RadioButton) { onSelect(i) }.padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, maxLines = 1, textAlign = TextAlign.Center,
                    color = if (sel) Color.White else c.muted, style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

enum class Level { OK, WARN, BAD, INFO }

@Composable
fun levelColor(l: Level): Color = when (l) {
    Level.OK -> FF.c.ok
    Level.WARN -> FF.c.warn
    Level.BAD -> FF.c.bad
    Level.INFO -> FF.c.muted
}

@Composable
fun StatusChip(text: String, level: Level) {
    val col = levelColor(level)
    Row(
        modifier = Modifier.background(col.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(col, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = col, style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp))
    }
}

@Composable
fun StepRow(index: Int, title: String, desc: String, done: Boolean, actionText: String? = null, onAction: () -> Unit = {}) {
    val c = FF.c
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(if (done) c.ok.copy(alpha = 0.18f) else c.card2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Text("✓", color = c.ok, style = MaterialTheme.typography.labelLarge)
            else Text("$index", color = c.muted, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = c.text)
            Text(desc, style = MaterialTheme.typography.bodyMedium, color = c.muted)
        }
        if (!done && actionText != null) {
            Spacer(Modifier.width(10.dp))
            SmallPill(actionText, onAction)
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(FF.c.card2, RoundedCornerShape(20.dp)).padding(14.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = FF.c.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = FF.c.muted)
    }
}

@Composable
fun MonoText(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = FF.c.muted)
}

/** Biểu tượng "nhật thực": đĩa gradient có lưỡi liềm khoét; xoay chậm khi [active]. */
@Composable
fun EclipseMark(size: Dp, active: Boolean) {
    val c = FF.c
    val t = rememberInfiniteTransition(label = "eclipse")
    val angle by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "rot",
    )
    Canvas(Modifier.size(size).rotate(if (active) angle else 0f)) {
        val w = this.size.width
        val center = Offset(w / 2f, w / 2f)
        val r = w * 0.36f
        drawCircle(
            brush = Brush.radialGradient(listOf(c.accent.copy(alpha = if (active) 0.55f else 0.28f), Color.Transparent), center, w / 2f),
            radius = w / 2f, center = center,
        )
        val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
        val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(center.x + r * 0.30f, center.y - r * 0.10f), r * 0.86f)) }
        val crescent = Path.combine(PathOperation.Difference, disc, cut)
        drawPath(crescent, Brush.sweepGradient(listOf(c.accent, c.accent2, c.accent), center))
    }
}

/** Thanh điều hướng dạng viên thuốc nổi (One UI 8.5). */
data class NavItem(val label: String, val icon: ImageVector)

@Composable
fun FloatingNav(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = FF.c
    Row(
        modifier = modifier
            .background(c.card.copy(alpha = 0.94f), RoundedCornerShape(50))
            .border(1.dp, c.outline.copy(alpha = 0.7f), RoundedCornerShape(50))
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { i, item ->
            val sel = i == selected
            Row(
                modifier = Modifier.heightIn(min = 52.dp)
                    .background(if (sel) c.accent.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(50))
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = if (sel) 18.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(item.icon, contentDescription = item.label, tint = if (sel) (if (c.dark) c.accent2 else c.accent) else c.muted, modifier = Modifier.size(24.dp))
                if (sel) Text(item.label, color = if (c.dark) c.accent2 else c.accent, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}


/** Ô chọn preset: nền thẻ phụ, viền nhấn khi được chọn. */
@Composable
fun Modifier.background3(selected: Boolean): Modifier {
    val c = FF.c
    return this.background(c.card2, RoundedCornerShape(20.dp))
        .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.outline.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
}

fun Modifier.clickable3(onClick: () -> Unit): Modifier = this.clickable(role = Role.Button, onClick = onClick)

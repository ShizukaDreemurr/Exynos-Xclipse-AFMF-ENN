package com.fluidframes.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Bảng màu "Eclipse" – thiết kế gốc, tinh thần One UI (xem docs/DESIGN.md). */
@Immutable
data class FFColors(
    val bg: Color,
    val card: Color,
    val card2: Color,
    val outline: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val accent2: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val dark: Boolean,
)

val DarkFF = FFColors(
    bg = Color(0xFF0A0C12), card = Color(0xFF141824), card2 = Color(0xFF1C2232), outline = Color(0xFF2A3146),
    text = Color(0xFFF3F5FA), muted = Color(0xFF9AA3B8), accent = Color(0xFF7C6CFF), accent2 = Color(0xFF35D8FF),
    ok = Color(0xFF3DDC97), warn = Color(0xFFFFC857), bad = Color(0xFFFF6B7A), dark = true,
)

val LightFF = FFColors(
    bg = Color(0xFFF2F3F8), card = Color(0xFFFFFFFF), card2 = Color(0xFFE9EBF3), outline = Color(0xFFD6DAE6),
    text = Color(0xFF12141C), muted = Color(0xFF5A6174), accent = Color(0xFF5B49F0), accent2 = Color(0xFF0AA6D6),
    ok = Color(0xFF17A672), warn = Color(0xFFB77700), bad = Color(0xFFD6334A), dark = false,
)

val LocalFF = staticCompositionLocalOf { DarkFF }

object FF {
    val c: FFColors
        @Composable @ReadOnlyComposable get() = LocalFF.current
}

private val FFTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun FluidTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) DarkFF else LightFF
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text,
            surface = c.card, onSurface = c.text, surfaceVariant = c.card2, onSurfaceVariant = c.muted,
            outline = c.outline, error = c.bad,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White, background = c.bg, onBackground = c.text,
            surface = c.card, onSurface = c.text, surfaceVariant = c.card2, onSurfaceVariant = c.muted,
            outline = c.outline, error = c.bad,
        )
    }
    CompositionLocalProvider(LocalFF provides c) {
        MaterialTheme(colorScheme = scheme, typography = FFTypography, content = content)
    }
}

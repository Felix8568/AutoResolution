package top.loongway.autoresolution.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * MIUI / HyperOS 风格主题：固定配色（不启用动态取色），浅灰背景 + 白色圆角卡片。
 */
private val LightColorScheme = lightColorScheme(
    primary = MiuiBlue,
    onPrimary = Color.White,
    background = MiuiBackground,
    onBackground = MiuiTextPrimary,
    surface = MiuiSurface,
    onSurface = MiuiTextPrimary,
    onSurfaceVariant = MiuiTextSecondary,
    // 弹窗/卡片等容器色：M3 默认由紫色基线派生，显式覆盖为 MIUI 白色
    surfaceContainer = MiuiSurface,
    surfaceContainerHigh = MiuiSurface,
    surfaceContainerHighest = MiuiSurface,
    outline = MiuiDivider,
    outlineVariant = MiuiDivider,
    secondaryContainer = MiuiInputBg,
    onSecondaryContainer = MiuiTextPrimary,
)

private val DarkColorScheme = darkColorScheme(
    primary = MiuiBlueDark,
    onPrimary = Color.White,
    background = MiuiBackgroundDark,
    onBackground = MiuiTextPrimaryDark,
    surface = MiuiSurfaceDark,
    onSurface = MiuiTextPrimaryDark,
    onSurfaceVariant = MiuiTextSecondaryDark,
    surfaceContainer = MiuiSurfaceDark,
    surfaceContainerHigh = MiuiSurfaceDark,
    surfaceContainerHighest = MiuiSurfaceDark,
    outline = MiuiDividerDark,
    outlineVariant = MiuiDividerDark,
    secondaryContainer = MiuiInputBgDark,
    onSecondaryContainer = MiuiTextPrimaryDark,
)

@Composable
fun AutoResolutionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}

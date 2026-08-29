package top.loongway.autoresolution.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * HUD 暗黑霓虹主题：固定深色配色（不随系统浅色切换）。
 */
private val HudColorScheme = darkColorScheme(
    primary = HudRed,
    onPrimary = Color.White,
    background = HudBg,
    onBackground = HudTextPrimary,
    surface = HudPanel,
    onSurface = HudTextPrimary,
    onSurfaceVariant = HudTextSecondary,
    surfaceContainer = HudPanelDim,
    surfaceContainerHigh = HudPanelDim,
    surfaceContainerHighest = HudPanelDim,
    outline = HudStroke,
    outlineVariant = HudDivider,
    secondaryContainer = HudPanelDim,
    onSecondaryContainer = HudTextPrimary,
)

@Composable
fun AutoResolutionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HudColorScheme,
        typography = Typography,
        content = content
    )
}

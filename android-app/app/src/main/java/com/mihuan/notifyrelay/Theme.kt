package com.mihuan.notifyrelay

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 清新淡蓝色系，浅色模式。
private val LightColors = lightColorScheme(
    primary = Color(0xFF3D7EBB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E3FB),
    onPrimaryContainer = Color(0xFF0B2749),
    secondaryContainer = Color(0xFFDCE7F7),
    onSecondaryContainer = Color(0xFF121C2B),
    background = Color(0xFFF6FAFF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF6FAFF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF73777F),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC6F0),
    onPrimary = Color(0xFF00315B),
    primaryContainer = Color(0xFF1D4A78),
    onPrimaryContainer = Color(0xFFD4E3FB),
    secondaryContainer = Color(0xFF35465D),
    onSecondaryContainer = Color(0xFFDCE7F7),
    background = Color(0xFF101418),
    onBackground = Color(0xFFDFE3EA),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFDFE3EA),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
    error = Color(0xFFFFB4AB),
)

/** 应用主题：深色模式自动跟随系统。 */
@Composable
fun RelayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}

/** 状态点与警示卡配色（日/夜各自适配）。 */
data class RelayStatusColors(
    val ok: Color,
    val bad: Color,
    val warnContainer: Color,
    val onWarnContainer: Color,
)

@Composable
fun relayStatusColors(): RelayStatusColors = if (isSystemInDarkTheme()) {
    RelayStatusColors(
        ok = Color(0xFF81C784),
        bad = Color(0xFFEF9A9A),
        warnContainer = Color(0xFF4A3200),
        onWarnContainer = Color(0xFFFFDDB8),
    )
} else {
    RelayStatusColors(
        ok = Color(0xFF2E7D32),
        bad = Color(0xFFC62828),
        warnContainer = Color(0xFFFFEDD9),
        onWarnContainer = Color(0xFF5C3A00),
    )
}

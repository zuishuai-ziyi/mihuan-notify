package com.mihuan.notifyrelay

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 品牌绿色系（与启动图标 #2E7D32 呼应），取值与 res/values[-night]/colors.xml 保持一致。
private val LightColors = lightColorScheme(
    primary = Color(0xFF356A3D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB7F1B6),
    onPrimaryContainer = Color(0xFF002204),
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF101F10),
    background = Color(0xFFF7FBF2),
    onBackground = Color(0xFF191D17),
    surface = Color(0xFFF7FBF2),
    onSurface = Color(0xFF191D17),
    surfaceVariant = Color(0xFFE0E7DA),
    onSurfaceVariant = Color(0xFF414940),
    outline = Color(0xFF72796F),
    error = Color(0xFFC62828),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BD49A),
    onPrimary = Color(0xFF00390F),
    primaryContainer = Color(0xFF1C5124),
    onPrimaryContainer = Color(0xFFB7F1B6),
    secondaryContainer = Color(0xFF394B38),
    onSecondaryContainer = Color(0xFFD5E8CF),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFDFE4DB),
    surface = Color(0xFF10140F),
    onSurface = Color(0xFFDFE4DB),
    surfaceVariant = Color(0xFF414940),
    onSurfaceVariant = Color(0xFFC1C9BE),
    outline = Color(0xFF8C9285),
    error = Color(0xFFEF9A9A),
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

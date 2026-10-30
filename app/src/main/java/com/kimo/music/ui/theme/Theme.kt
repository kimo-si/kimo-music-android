package com.kimo.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object KimoColors {
    val Paper = Color(0xFFF5F1E8)
    val Ink = Color(0xFF20211D)
    val Secondary = Color(0xFF75766F)
    val Orange = Color(0xFFD95E26)
    val Surface = Color(0xFFECE7DD)
    val Rail = Color(0xFFDCD8CF)
    val OnDark = Color(0xFFF5F1E8)
}

object KimoLayout {
    val PageInset = 20.dp
    val CardRadius = 15.dp
    val SectionGap = 22.dp
    val TouchTarget = 48.dp
    val MiniPlayerRadius = 20.dp
    val MiniPlayerHeight = 66.dp
    val MiniPlayerInset = 10.dp
    val BottomNavigationHeight = 80.dp
    const val BottomNavigationReductionPx = 20
    const val PlayerControlsLiftPx = 50
    const val PlayerBottomGroupLiftPx = 100
    const val PlayerContentLiftPx = 100
    val PlayerControlsHeight = 68.dp
    val CompactPlayerControlsHeight = 56.dp
    val PlayerButtonSize = 60.dp
    val CompactPlayerButtonSize = 52.dp
    val PlayerButtonIconSize = 30.dp
}

private val Palette = lightColorScheme(
    primary = KimoColors.Orange, onPrimary = Color.White,
    background = KimoColors.Paper, onBackground = KimoColors.Ink,
    surface = KimoColors.Paper, onSurface = KimoColors.Ink,
    surfaceVariant = KimoColors.Surface, onSurfaceVariant = KimoColors.Secondary,
    secondary = KimoColors.Orange, onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0DFD3), onSecondaryContainer = KimoColors.Ink,
    tertiary = KimoColors.Orange, onTertiary = Color.White,
    tertiaryContainer = KimoColors.Surface, onTertiaryContainer = KimoColors.Ink,
    outline = KimoColors.Rail, outlineVariant = KimoColors.Rail,
)

// 参考图使用固定暖色视觉，避免系统动态取色改变封面与橙色控制键的关系。
@Composable
fun KimoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Palette, content = content)
}

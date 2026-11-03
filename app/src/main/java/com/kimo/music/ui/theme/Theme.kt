package com.kimo.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kimo.music.data.AppearanceMode

private val LocalDarkAppearance = staticCompositionLocalOf { false }

object KimoColors {
    val Paper: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.background
    val Ink: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onBackground
    val Secondary: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val Orange: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary
    val Surface: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surfaceVariant
    val Rail: Color @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline
    val IsDark: Boolean @Composable @ReadOnlyComposable get() = LocalDarkAppearance.current
    val TipSurface: Color @Composable @ReadOnlyComposable get() = if (IsDark) Color(0xFF2C302B) else Color(0xFFFCF9F2)
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
    val BottomNavigationHeight = 60.dp
    val BottomNavigationIconSize = 20.dp
    const val PlayerControlsLiftPx = 50
    const val PlayerBottomGroupLiftPx = 100
    const val PlayerContentLiftPx = 100
    val PlayerControlsHeight = 68.dp
    val CompactPlayerControlsHeight = 56.dp
    val PlayerButtonSize = 60.dp
    val CompactPlayerButtonSize = 52.dp
    val PlayerButtonIconSize = 30.dp
}

private val LightPalette = lightColorScheme(
    primary = Color(0xFFD95E26), onPrimary = Color.White,
    background = Color(0xFFF5F1E8), onBackground = Color(0xFF20211D),
    surface = Color(0xFFF5F1E8), onSurface = Color(0xFF20211D),
    surfaceVariant = Color(0xFFECE7DD), onSurfaceVariant = Color(0xFF75766F),
    secondary = Color(0xFFD95E26), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0DFD3), onSecondaryContainer = Color(0xFF20211D),
    tertiary = Color(0xFFD95E26), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFECE7DD), onTertiaryContainer = Color(0xFF20211D),
    outline = Color(0xFFDCD8CF), outlineVariant = Color(0xFFDCD8CF),
    surfaceContainer = Color(0xFFECE7DD), surfaceContainerHigh = Color(0xFFECE7DD),
    surfaceContainerHighest = Color(0xFFE5DFD4), surfaceContainerLow = Color(0xFFF1EDE4),
)

private val DarkPalette = darkColorScheme(
    primary = Color(0xFFF18A53), onPrimary = Color(0xFF24180F),
    background = Color(0xFF181A19), onBackground = Color(0xFFF2EDE4),
    surface = Color(0xFF181A19), onSurface = Color(0xFFF2EDE4),
    surfaceVariant = Color(0xFF252825), onSurfaceVariant = Color(0xFFABADA5),
    secondary = Color(0xFFF18A53), onSecondary = Color(0xFF24180F),
    secondaryContainer = Color(0xFF473328), onSecondaryContainer = Color(0xFFF2EDE4),
    tertiary = Color(0xFFF18A53), onTertiary = Color(0xFF24180F),
    tertiaryContainer = Color(0xFF252825), onTertiaryContainer = Color(0xFFF2EDE4),
    outline = Color(0xFF3C403A), outlineVariant = Color(0xFF3C403A),
    surfaceContainer = Color(0xFF252825), surfaceContainerHigh = Color(0xFF2C302B),
    surfaceContainerHighest = Color(0xFF353932), surfaceContainerLow = Color(0xFF202320),
)

// 显式选择优先于系统设置，跟随系统时响应设备配置的实时变化。
fun AppearanceMode.usesDarkTheme(systemDark: Boolean): Boolean = when (this) {
    AppearanceMode.Light -> false
    AppearanceMode.Dark -> true
    AppearanceMode.System -> systemDark
}

// 统一提供语义配色，页面与弹层无需维护各自的主题状态。
@Composable
fun KimoTheme(mode: AppearanceMode = AppearanceMode.Light, content: @Composable () -> Unit) {
    val dark = mode.usesDarkTheme(isSystemInDarkTheme())
    CompositionLocalProvider(LocalDarkAppearance provides dark) {
        MaterialTheme(colorScheme = if (dark) DarkPalette else LightPalette, content = content)
    }
}

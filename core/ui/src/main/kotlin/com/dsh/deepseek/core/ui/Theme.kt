package com.dsh.deepseek.core.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.dsh.deepseek.core.datastore.ThemeMode

/**
 * DeepSeek 视觉基调（§7.4）：以官方 Demo 品牌蓝为主色，
 * 明暗两套 Material 3 配色，参考 Demo 的视觉密度而非照搬 Web CSS。
 */
object DeepSeekColors {
    val BrandBlue = Color(0xFF4D6BFE)
    val BrandBlueDark = Color(0xFF7C9AFF)
    val UserBubbleLight = Color(0xFFF2F3F5)
    val UserBubbleDark = Color(0xFF2C2D34)
    val ThinkingLight = Color(0xFF6B7280)
    val ThinkingDark = Color(0xFF9CA3AF)
    val CodeBackgroundLight = Color(0xFFF6F8FA)
    val CodeBackgroundDark = Color(0xFF1E2025)
    /** 官方删除/危险操作红（APK night hangup `#F25A5A`）。 */
    val Danger = Color(0xFFF25A5A)
    /** 侧栏当前会话底色，取自官方深色截图 `#283142`。 */
    val SessionSelectedDark = Color(0xFF283142)
    val SessionSelectedLight = Color(0xFFE7EDF6)
    /** 深色侧栏选中标题，截图采样 `#7099EB`。 */
    val SessionTitleDark = Color(0xFF7099EB)
    /** 选中行上的三个点，截图采样约 `#9599A2`，不是标题蓝。 */
    val SessionDotsDark = Color(0xFF9599A2)
    val SessionDotsLight = Color(0xFF6B7280)
    /** 长按打开菜单时的底色，中性灰，和选中蓝灰分开。 */
    val SessionPressedDark = Color(0xFF3A3B40)
    val SessionPressedLight = Color(0xFFE6E6EB)
}

/** 跟随应用内的浅色/深色，而不是系统栏。用户气泡色要用这个，避免应用浅色、系统深色时气泡反了。 */
val LocalDeepSeekDark = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = DeepSeekColors.BrandBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF00174A),
    secondary = Color(0xFF565E71),
    secondaryContainer = Color(0xFFDAE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    background = Color(0xFFFCFCFF),
    onBackground = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFFF1F2F8),
    surfaceContainerLow = Color(0xFFF6F7FB),
    surfaceContainerHigh = Color(0xFFEAECF3),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkColors = darkColorScheme(
    primary = DeepSeekColors.BrandBlueDark,
    onPrimary = Color(0xFF0A2472),
    primaryContainer = Color(0xFF25429B),
    onPrimaryContainer = Color(0xFFDBE1FF),
    secondary = Color(0xFFBEC6DC),
    secondaryContainer = Color(0xFF384153),
    onSecondaryContainer = Color(0xFFDAE2F9),
    surface = Color(0xFF1A1B20),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6D0),
    background = Color(0xFF17181C),
    onBackground = Color(0xFFE2E2E9),
    surfaceContainer = Color(0xFF22242A),
    surfaceContainerLow = Color(0xFF1D1F24),
    surfaceContainerHigh = Color(0xFF2C2E34),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

@Composable
fun DeepSeekTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalDeepSeekDark provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}

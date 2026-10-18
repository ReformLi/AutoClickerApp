package com.hpu.autoclicker.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 语义色（success / warning / primaryPressed）的 CompositionLocal，随浅/深色切换。 */
val LocalAppSemanticColors = staticCompositionLocalOf { LightSemantic }

/**
 * 浅色 ColorScheme —— 全部来自 Color.kt 的方案 A token，无硬编码色值、无动态取色。
 */
private val TealSteelLightScheme = lightColorScheme(
    primary = LightColor.primary,
    onPrimary = LightColor.onPrimary,
    primaryContainer = LightColor.primaryContainer,
    onPrimaryContainer = LightColor.onPrimaryContainer,
    secondary = LightColor.secondary,
    onSecondary = LightColor.onSecondary,
    secondaryContainer = LightColor.secondaryContainer,
    onSecondaryContainer = LightColor.onSecondaryContainer,
    tertiary = LightColor.tertiary,
    onTertiary = LightColor.onTertiary,
    tertiaryContainer = LightColor.tertiaryContainer,
    onTertiaryContainer = LightColor.onTertiaryContainer,
    background = LightColor.background,
    onBackground = LightColor.onBackground,
    surface = LightColor.surface,
    onSurface = LightColor.onSurface,
    surfaceVariant = LightColor.surfaceVariant,
    onSurfaceVariant = LightColor.onSurfaceVariant,
    surfaceContainerLowest = LightColor.surfaceContainerLowest,
    surfaceContainerLow = LightColor.surfaceContainerLow,
    surfaceContainer = LightColor.surfaceContainer,
    surfaceContainerHigh = LightColor.surfaceContainerHigh,
    surfaceContainerHighest = LightColor.surfaceContainerHighest,
    outline = LightColor.outline,
    outlineVariant = LightColor.outlineVariant,
    error = LightColor.error,
    onError = LightColor.onError,
    errorContainer = LightColor.errorContainer,
    onErrorContainer = LightColor.onErrorContainer,
)

/**
 * 深色 ColorScheme —— 与浅色同一 hue 的提亮/降饱和版本，完整适配。
 */
private val TealSteelDarkScheme = darkColorScheme(
    primary = DarkColor.primary,
    onPrimary = DarkColor.onPrimary,
    primaryContainer = DarkColor.primaryContainer,
    onPrimaryContainer = DarkColor.onPrimaryContainer,
    secondary = DarkColor.secondary,
    onSecondary = DarkColor.onSecondary,
    secondaryContainer = DarkColor.secondaryContainer,
    onSecondaryContainer = DarkColor.onSecondaryContainer,
    tertiary = DarkColor.tertiary,
    onTertiary = DarkColor.onTertiary,
    tertiaryContainer = DarkColor.tertiaryContainer,
    onTertiaryContainer = DarkColor.onTertiaryContainer,
    background = DarkColor.background,
    onBackground = DarkColor.onBackground,
    surface = DarkColor.surface,
    onSurface = DarkColor.onSurface,
    surfaceVariant = DarkColor.surfaceVariant,
    onSurfaceVariant = DarkColor.onSurfaceVariant,
    surfaceContainerLowest = DarkColor.surfaceContainerLowest,
    surfaceContainerLow = DarkColor.surfaceContainerLow,
    surfaceContainer = DarkColor.surfaceContainer,
    surfaceContainerHigh = DarkColor.surfaceContainerHigh,
    surfaceContainerHighest = DarkColor.surfaceContainerHighest,
    outline = DarkColor.outline,
    outlineVariant = DarkColor.outlineVariant,
    error = DarkColor.error,
    onError = DarkColor.onError,
    errorContainer = DarkColor.errorContainer,
    onErrorContainer = DarkColor.onErrorContainer,
)

/**
 * 应用主题入口。
 *
 * - 刻意**不启用 Material3 动态取色（Material You）**：始终使用方案 A 品牌 token，
 *   保证跨设备、跨 Android 版本观感一致、可控。
 * - 统一装配 ColorScheme / Typography / Shapes，并注入语义色 [LocalAppSemanticColors]。
 */
@Composable
fun AutoClickerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val semantic = if (darkTheme) DarkSemantic else LightSemantic
    val colorScheme = if (darkTheme) TealSteelDarkScheme else TealSteelLightScheme

    CompositionLocalProvider(LocalAppSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/**
 * 语义 token 统一访问入口。组件里用 [AppTheme.colors] 取 success/warning 等
 * ColorScheme 未覆盖的语义色；其余颜色一律走 MaterialTheme.colorScheme。
 */
object AppTheme {
    val colors: AppSemanticColors
        @Composable get() = LocalAppSemanticColors.current
}

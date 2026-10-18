package com.hpu.autoclicker.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 品牌色板 token —— 方案 A「钢青 Teal-Steel」。
 *
 * 设计理由：
 * - 主色取「可靠、专业、去默认蓝/紫」的青蓝色系，明度克制、不撞色。
 * - 严格区分 surface / surfaceContainer 五级，用色调层次替代阴影堆叠。
 * - 深色不是简单反色：主色提亮到可辨识、背景走低饱和青墨、语义色降饱和。
 * - 不使用 Material3 动态取色（Material You）；这套 token 是唯一来源。
 *
 * 命名约定：
 * - LightColor：方案 A 浅色全量 tone。
 * - DarkColor：方案 A 深色 tone。
 * - AppSemanticColors（文件尾部）：success/warning/primaryPressed 等
 *   ColorScheme 没有槽位、但交互与状态需要的补充色，浅/深各一份实例。
 */

// ==================== 方案 A · 浅色 Light ====================
internal object LightColor {
    // 品牌 primary / secondary / tertiary
    val primary = Color(0xFF0F6E7D)
    val primaryPressed = Color(0xFF0A5A68)
    val onPrimary = Color(0xFFFFFFFF)
    val primaryContainer = Color(0xFFB4EBF0)
    val onPrimaryContainer = Color(0xFF002026)
    val secondary = Color(0xFF4B6367)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFCDE8EC)
    val onSecondaryContainer = Color(0xFF061F23)
    val tertiary = Color(0xFF8A5A00)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFFFDFA8)
    val onTertiaryContainer = Color(0xFF2C1A00)

    // 中性 / 表面层次
    val background = Color(0xFFF5FAFB)
    val onBackground = Color(0xFF161D1E)
    val surface = Color(0xFFFFFFFF)
    val onSurface = Color(0xFF161D1E)
    val surfaceVariant = Color(0xFFDBE4E6)
    val onSurfaceVariant = Color(0xFF3F484A)
    val surfaceContainerLowest = Color(0xFFFFFFFF)
    val surfaceContainerLow = Color(0xFFF0F5F6)
    val surfaceContainer = Color(0xFFEAEEF0)
    val surfaceContainerHigh = Color(0xFFE4E9EA)
    val surfaceContainerHighest = Color(0xFFDEE3E5)
    val outline = Color(0xFF6F797B)
    val outlineVariant = Color(0xFFBFC8CA)

    // 语义色
    val error = Color(0xFFB3261E)
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFF9DEDC)
    val onErrorContainer = Color(0xFF410E0B)
    val success = Color(0xFF1B7A4B)
    val onSuccess = Color(0xFFFFFFFF)
    val successContainer = Color(0xFFBDF0D0)
    val onSuccessContainer = Color(0xFF00210F)
    val warning = Color(0xFF8A5A00)
    val onWarning = Color(0xFFFFFFFF)
    val warningContainer = Color(0xFFFFE0A6)
    val onWarningContainer = Color(0xFF2C1A00)
    val info = Color(0xFF0F6E7D)
    val onInfo = Color(0xFFFFFFFF)
    val infoContainer = Color(0xFFB4EBF0)
    val onInfoContainer = Color(0xFF002026)

    // 组件专用（浅色）
    val disabledContainer = Color(0xFFDDE3E5)
    val onDisabled = Color(0xFF7A8486)
    val fieldBackground = Color(0xFFF0F5F6)
}

// ==================== 方案 A · 深色 Dark ====================
internal object DarkColor {
    val primary = Color(0xFF5AD1E0)
    val primaryPressed = Color(0xFF7FDCE8)
    val onPrimary = Color(0xFF00363E)
    val primaryContainer = Color(0xFF004F5A)
    val onPrimaryContainer = Color(0xFFB4EBF0)
    val secondary = Color(0xFFB1CCD0)
    val onSecondary = Color(0xFF1D3438)
    val secondaryContainer = Color(0xFF334B4F)
    val onSecondaryContainer = Color(0xFFCDE8EC)
    val tertiary = Color(0xFFF2BF63)
    val onTertiary = Color(0xFF492F00)
    val tertiaryContainer = Color(0xFF684400)
    val onTertiaryContainer = Color(0xFFFFDFA8)

    val background = Color(0xFF0B1314)
    val onBackground = Color(0xFFDCE4E5)
    val surface = Color(0xFF0B1314)
    val onSurface = Color(0xFFDCE4E5)
    val surfaceVariant = Color(0xFF3F484A)
    val onSurfaceVariant = Color(0xFFBFC8CA)
    val surfaceContainerLowest = Color(0xFF060E0F)
    val surfaceContainerLow = Color(0xFF131B1C)
    val surfaceContainer = Color(0xFF171F20)
    val surfaceContainerHigh = Color(0xFF222A2B)
    val surfaceContainerHighest = Color(0xFF2D3537)
    val outline = Color(0xFF8A9395)
    val outlineVariant = Color(0xFF3F484A)

    val error = Color(0xFFF2B8B5)
    val onError = Color(0xFF681D19)
    val errorContainer = Color(0xFF93000A)
    val onErrorContainer = Color(0xFFF9DEDC)
    val success = Color(0xFF7DD0A0)
    val onSuccess = Color(0xFF00391F)
    val successContainer = Color(0xFF0B5A36)
    val onSuccessContainer = Color(0xFFBDF0D0)
    val warning = Color(0xFFE0B64C)
    val onWarning = Color(0xFF3D2E00)
    val warningContainer = Color(0xFF684400)
    val onWarningContainer = Color(0xFFFFE0A6)
    val info = Color(0xFF5AD1E0)
    val onInfo = Color(0xFF00363E)
    val infoContainer = Color(0xFF004F5A)
    val onInfoContainer = Color(0xFFB4EBF0)

    val disabledContainer = Color(0xFF2D3537)
    val onDisabled = Color(0xFF7A8486)
    val fieldBackground = Color(0xFF171F20)
}

// ==================== 语义色（success / warning） ====================
// Material3 ColorScheme 只有 error 槽，没有 success/warning。工具类需要成组的
// 成功/警示语义色（状态徽标、危险操作、提示行），单列一处作为唯一 token 源，
// 通过 Theme.kt 的 LocalAppSemanticColors 注入，组件用 AppTheme.colors 取用。

/**
 * 一组主题色（含 on 前景与 container 背景），浅/深各一份。
 * 除 success/warning 外，还承载 [primaryPressed]——悬浮按钮按下态的品牌主色变体，
 * ColorScheme 没有对应槽位但交互反馈需要，统一收进本组避免页面硬编码。
 */
@androidx.compose.runtime.Immutable
data class AppSemanticColors(
    val primaryPressed: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val LightSemantic = AppSemanticColors(
    primaryPressed = LightColor.primaryPressed,
    success = LightColor.success,
    onSuccess = LightColor.onSuccess,
    successContainer = LightColor.successContainer,
    onSuccessContainer = LightColor.onSuccessContainer,
    warning = LightColor.warning,
    onWarning = LightColor.onWarning,
    warningContainer = LightColor.warningContainer,
    onWarningContainer = LightColor.onWarningContainer,
)

internal val DarkSemantic = AppSemanticColors(
    primaryPressed = DarkColor.primaryPressed,
    success = DarkColor.success,
    onSuccess = DarkColor.onSuccess,
    successContainer = DarkColor.successContainer,
    onSuccessContainer = DarkColor.onSuccessContainer,
    warning = DarkColor.warning,
    onWarning = DarkColor.onWarning,
    warningContainer = DarkColor.warningContainer,
    onWarningContainer = DarkColor.onWarningContainer,
)

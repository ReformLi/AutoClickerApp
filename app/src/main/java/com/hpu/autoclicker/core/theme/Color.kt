package com.hpu.autoclicker.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 品牌色板 token —— 方案 A「钢青 Teal-Steel」。
 *
 * 设计理由：
 * - 主色取「可靠、专业、去默认蓝/紫」的青蓝色系，明度克制、不撞色。
 * - 中性色同样带极低饱和的青调（与主色同 hue 家族），避免灰调与品牌色割裂；
 *   表面层级靠「亮度差 + 同色相」区分，不再叠加阴影。
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
    val primaryContainer = Color(0xFFC9E9EE)
    val onPrimaryContainer = Color(0xFF00363D)
    val secondary = Color(0xFF4B6367)
    val onSecondary = Color(0xFFFFFFFF)
    val secondaryContainer = Color(0xFFD3E6EA)
    val onSecondaryContainer = Color(0xFF061F23)
    val tertiary = Color(0xFF8A5A00)
    val onTertiary = Color(0xFFFFFFFF)
    val tertiaryContainer = Color(0xFFFBE7BC)
    val onTertiaryContainer = Color(0xFF2C1A00)

    // 中性 / 表面层次（同色相低饱和青灰，靠亮度差分层）
    val background = Color(0xFFF3F7F8)
    val onBackground = Color(0xFF16201F)
    val surface = Color(0xFFFFFFFF)
    val onSurface = Color(0xFF16201F)
    val surfaceVariant = Color(0xFFDDE6E7)
    val onSurfaceVariant = Color(0xFF465456)
    val surfaceContainerLowest = Color(0xFFFFFFFF)
    val surfaceContainerLow = Color(0xFFF1F6F7)
    val surfaceContainer = Color(0xFFEBF1F2)
    val surfaceContainerHigh = Color(0xFFE4EBEC)
    val surfaceContainerHighest = Color(0xFFDCE4E5)
    val outline = Color(0xFF6B7879)
    val outlineVariant = Color(0xFFC3CFD0)

    // 语义色
    val error = Color(0xFFB3261E)
    val onError = Color(0xFFFFFFFF)
    val errorContainer = Color(0xFFF9DEDC)
    val onErrorContainer = Color(0xFF410E0B)
    val success = Color(0xFF1B7A4B)
    val onSuccess = Color(0xFFFFFFFF)
    val successContainer = Color(0xFFC4EFD6)
    val onSuccessContainer = Color(0xFF00210F)
    val warning = Color(0xFF8A5A00)
    val onWarning = Color(0xFFFFFFFF)
    val warningContainer = Color(0xFFFBE7BC)
    val onWarningContainer = Color(0xFF2C1A00)
    val info = Color(0xFF0F6E7D)
    val onInfo = Color(0xFFFFFFFF)
    val infoContainer = Color(0xFFC9E9EE)
    val onInfoContainer = Color(0xFF00363D)

    // 组件专用（浅色）
    val disabledContainer = Color(0xFFDCE4E5)
    val onDisabled = Color(0xFF6B7879)
    val fieldBackground = Color(0xFFF1F6F7)
}

// ==================== 方案 A · 深色 Dark ====================
internal object DarkColor {
    val primary = Color(0xFF5AD1E0)
    val primaryPressed = Color(0xFF7FDCE8)
    val onPrimary = Color(0xFF00363E)
    val primaryContainer = Color(0xFF0B4A54)
    val onPrimaryContainer = Color(0xFFBDE7EC)
    val secondary = Color(0xFFB1CCD0)
    val onSecondary = Color(0xFF1D3438)
    val secondaryContainer = Color(0xFF334B4F)
    val onSecondaryContainer = Color(0xFFCDE8EC)
    val tertiary = Color(0xFFF2BF63)
    val onTertiary = Color(0xFF492F00)
    val tertiaryContainer = Color(0xFF684400)
    val onTertiaryContainer = Color(0xFFFBE7BC)

    val background = Color(0xFF0A1213)
    val onBackground = Color(0xFFDDE6E6)
    val surface = Color(0xFF0F1819)
    val onSurface = Color(0xFFDDE6E6)
    val surfaceVariant = Color(0xFF3C4748)
    val onSurfaceVariant = Color(0xFFC0CCCD)
    val surfaceContainerLowest = Color(0xFF070F10)
    val surfaceContainerLow = Color(0xFF141D1E)
    val surfaceContainer = Color(0xFF182122)
    val surfaceContainerHigh = Color(0xFF222B2C)
    val surfaceContainerHighest = Color(0xFF2C3637)
    val outline = Color(0xFF889596)
    val outlineVariant = Color(0xFF3C4748)

    val error = Color(0xFFF2B8B5)
    val onError = Color(0xFF681D19)
    val errorContainer = Color(0xFF93000A)
    val onErrorContainer = Color(0xFFF9DEDC)
    val success = Color(0xFF7DD0A0)
    val onSuccess = Color(0xFF00391F)
    val successContainer = Color(0xFF0B5A36)
    val onSuccessContainer = Color(0xFFC4EFD6)
    val warning = Color(0xFFE0B64C)
    val onWarning = Color(0xFF3D2E00)
    val warningContainer = Color(0xFF684400)
    val onWarningContainer = Color(0xFFFBE7BC)
    val info = Color(0xFF5AD1E0)
    val onInfo = Color(0xFF00363E)
    val infoContainer = Color(0xFF0B4A54)
    val onInfoContainer = Color(0xFFBDE7EC)

    val disabledContainer = Color(0xFF2C3637)
    val onDisabled = Color(0xFF889596)
    val fieldBackground = Color(0xFF182122)
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

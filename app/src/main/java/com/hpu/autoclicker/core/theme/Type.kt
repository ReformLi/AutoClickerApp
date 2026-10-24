package com.hpu.autoclicker.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字号/字重 token —— 全站统一，页面禁止再写 fontSize / fontWeight。
 *
 * 语义（对应 Material3 Typography 角色，但固定值、不随系统变）：
 * - headlineSmall → 页面主标题（24sp semibold）
 * - titleLarge    → 分组标题（20sp semibold）
 * - titleMedium   → 卡片标题 / 列表主文字（17sp semibold）
 * - titleSmall    → 强调次级（15sp medium）
 * - bodyLarge     → 输入框正文（16sp）
 * - bodyMedium    → 正文（15sp）
 * - bodySmall     → 辅助说明 / caption（13sp）
 * - labelLarge    → 按钮文字（15sp semibold）
 * - labelMedium   → 表单字段标签（14sp medium）
 * - labelSmall    → 徽标 / 状态小字（12sp medium）
 *
 * 字距：中文正文不额外加字距（西文习惯的 0.3~0.5sp 会让中文显得松散），
 * 仅保留极小的 0.1sp 用于按钮与徽标，保持字形紧凑。
 */
internal val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.1.sp,
    ),
)

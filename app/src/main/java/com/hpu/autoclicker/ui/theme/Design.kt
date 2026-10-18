package com.hpu.autoclicker.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose 设计系统
 *
 * 由原 res/values/styles.xml + colors.xml（含 values-night）迁移而来：
 * - 色板 AppPalette 与 colors.xml 一一对应，并提供深色模式（values-night）取值
 * - 复用组件 AppButton / AppOutlineButton / ModernTextField / ModernCard
 *   一一对应原 styles.xml 中的 ModernButton / ModernButton.Outline /
 *   ModernEditText / ModernText.* / ModernCard
 * 全局面色、圆角（统一 12dp）在此集中维护，各页面直接引用。
 */

/** 与 colors.xml（浅色） / values-night/colors.xml（深色）对应的色板 */
@Immutable
data class AppPalette(
    val primary: Color,
    val primaryDark: Color,
    val primaryContainer: Color,
    val background: Color,
    val surface: Color,
    val inputBg: Color,
    val stroke: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textHint: Color,
    val success: Color,
    val danger: Color,
    val disabled: Color,
    // 主页渐变与渐变上的文字
    val gradientStart: Color,
    val gradientCenter: Color,
    val gradientEnd: Color,
    val gradientBlob1: Color,
    val gradientBlob2: Color,
    val onGradient: Color,
    val onGradientSecondary: Color,
)

private val LightPalette = AppPalette(
    primary = Color(0xFF2563EB),
    primaryDark = Color(0xFF1D4ED8),
    primaryContainer = Color(0xFFDBEAFE),
    background = Color(0xFFF5F7FA),
    surface = Color(0xFFFFFFFF),
    inputBg = Color(0xFFF1F5F9),
    stroke = Color(0xFFE2E8F0),
    textPrimary = Color(0xFF1F2937),
    textSecondary = Color(0xFF6B7280),
    textHint = Color(0xFF9CA3AF),
    success = Color(0xFF22C55E),
    danger = Color(0xFFEF4444),
    disabled = Color(0xFFD1D5DB),
    gradientStart = Color(0xFF38BDF8),
    gradientCenter = Color(0xFF3B82F6),
    gradientEnd = Color(0xFF6366F1),
    gradientBlob1 = Color(0x40FFFFFF),
    gradientBlob2 = Color(0x26FFFFFF),
    onGradient = Color(0xFFFFFFFF),
    onGradientSecondary = Color(0xB3FFFFFF),
)

private val DarkPalette = AppPalette(
    primary = Color(0xFF3B82F6),
    primaryDark = Color(0xFF1D4ED8),
    primaryContainer = Color(0xFF1E3A8A),
    background = Color(0xFF111827),
    surface = Color(0xFF1F2937),
    inputBg = Color(0xFF273548),
    stroke = Color(0xFF374151),
    textPrimary = Color(0xFFF9FAFB),
    textSecondary = Color(0xFF9CA3AF),
    textHint = Color(0xFF6B7280),
    success = Color(0xFF22C55E),
    danger = Color(0xFFEF4444),
    disabled = Color(0xFF4B5563),
    gradientStart = Color(0xFF172554),
    gradientCenter = Color(0xFF1E3A8A),
    gradientEnd = Color(0xFF312E81),
    gradientBlob1 = Color(0x14FFFFFF),
    gradientBlob2 = Color(0x0DFFFFFF),
    onGradient = Color(0xFFFFFFFF),
    onGradientSecondary = Color(0xB3FFFFFF),
)

val LocalAppPalette = staticCompositionLocalOf { LightPalette }

/** 全局主题：把色板注入 CompositionLocal，并同步 Material3 配色（供 Checkbox/RadioButton 等使用） */
@Composable
fun AutoClickerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = palette.primary,
            onPrimary = Color.White,
            primaryContainer = palette.primaryContainer,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.stroke,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            onPrimary = Color.White,
            primaryContainer = palette.primaryContainer,
            background = palette.background,
            surface = palette.surface,
            onSurface = palette.textPrimary,
            onSurfaceVariant = palette.textSecondary,
            outline = palette.stroke,
            error = palette.danger,
        )
    }
    CompositionLocalProvider(LocalAppPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

// ==================== 复用组件（对应 styles.xml） ====================

/** 主按钮：蓝底白字，12dp 圆角，48dp 高（对应 ModernButton） */
@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    containerColor: Color = LocalAppPalette.current.primary,
    contentColor: Color = Color.White,
    disabledContainerColor: Color = LocalAppPalette.current.disabled,
    disabledContentColor: Color = LocalAppPalette.current.textSecondary,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = disabledContainerColor,
            disabledContentColor = disabledContentColor,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 描边按钮：白底蓝字蓝描边（对应 ModernButton.Outline，深色背景上同样醒目） */
@Composable
fun AppOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val palette = LocalAppPalette.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(height),
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, palette.primary),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = palette.surface,
            contentColor = palette.primary,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
    ) {
        Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

/** 输入框：浅色填充 + 12dp 圆角，聚焦时蓝色描边（对应 ModernEditText） */
@Composable
fun ModernTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    maxLength: Int? = null,
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val palette = LocalAppPalette.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                color = if (focused) palette.surface else palette.inputBg,
                shape = RoundedCornerShape(12.dp),
            )
            .border(
                width = if (focused) 1.5.dp else 1.dp,
                color = if (focused) palette.primary else palette.stroke,
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { raw ->
                onValueChange(if (maxLength != null && raw.length > maxLength) raw.take(maxLength) else raw)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = palette.textPrimary,
                fontSize = 16.sp,
                textAlign = textAlign,
            ),
            cursorBrush = SolidColor(palette.primary),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            interactionSource = interaction,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && hint.isNotEmpty()) {
                        Text(text = hint, color = palette.textHint, fontSize = 16.sp)
                    }
                    inner()
                }
            },
        )
    }
}

/** 卡片：白底 12dp 圆角（对应 ModernCard） */
@Composable
fun ModernCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppPalette.current.surface),
        content = content,
    )
}

/** 页面标题：20sp 加粗（对应 ModernText.Title） */
@Composable
fun ModernTitle(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalAppPalette.current.textPrimary,
) {
    Text(text = text, modifier = modifier, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold)
}

/** 区块标签：14sp 次要色（对应 ModernText.Label） */
@Composable
fun ModernLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalAppPalette.current.textSecondary,
) {
    Text(text = text, modifier = modifier, color = color, fontSize = 14.sp)
}

/** 正文：16sp 主色（对应 ModernText.Body） */
@Composable
fun ModernBody(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalAppPalette.current.textPrimary,
) {
    Text(text = text, modifier = modifier, color = color, fontSize = 16.sp)
}

/**
 * 主页渐变背景：由 bg_main_gradient.xml 迁移。
 * 底层为「浅天蓝→亮蓝→蓝紫」对角渐变（等价 angle=315），叠加右上/左下两处半透明白色光斑。
 */
@Composable
fun MainGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalAppPalette.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val brush = Brush.linearGradient(
                    colors = listOf(palette.gradientStart, palette.gradientCenter, palette.gradientEnd),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                onDrawBehind { drawRect(brush) }
            },
    ) {
        // 装饰光斑（尺寸/偏移取自 bg_main_gradient.xml：右上 300dp、左下 260dp）
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = palette.gradientBlob1,
                radius = 150.dp.toPx(),
                center = Offset(size.width - 80.dp.toPx(), 60.dp.toPx()),
            )
            drawCircle(
                color = palette.gradientBlob2,
                radius = 130.dp.toPx(),
                center = Offset(70.dp.toPx(), size.height - 50.dp.toPx()),
            )
        }
        content()
    }
}

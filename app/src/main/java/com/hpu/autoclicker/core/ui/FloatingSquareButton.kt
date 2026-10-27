package com.hpu.autoclicker.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hpu.autoclicker.core.theme.AppTheme

/**
 * 悬浮窗方块按钮 —— 连点器（feature.clicker）与代点器（feature.quickclick）共用的唯一按钮样式。
 *
 * 视觉规格：40dp 见方、12dp 圆角（shapes.small）、品牌实心底、白色图标/文字、4dp 轻投影，
 * 与主页按钮同源（品牌色 + 小圆角 + 文字层级），两个悬浮窗因此看起来是一套东西。
 *
 * 触摸事件由调用方通过 [modifier] 传入（如 pointerInteropFilter）：
 * 组件本身不消费点击，避免与 Service 内既有的原始触摸状态机抢事件。
 */
@Composable
fun FloatingSquareButton(
    modifier: Modifier = Modifier,
    pressed: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = MaterialTheme.shapes.small,
    size: Dp = 40.dp,
    shadowElevation: Dp = 4.dp,
    background: Color = MaterialTheme.colorScheme.primary,
    content: @Composable BoxScope.() -> Unit,
) {
    val sem = AppTheme.colors
    Box(
        modifier = modifier
            .size(size)
            // 禁用态不画投影：半透明方块本身几乎看不见，只剩一团投影压在相邻按钮上，
            // 看起来像两个透明按钮叠在一起
            .shadow(
                elevation = if (enabled) shadowElevation else 0.dp,
                shape = shape,
                clip = false,
            )
            .alpha(if (enabled) 1f else 0.4f)
            .background(
                color = if (pressed) sem.primaryPressed else background,
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

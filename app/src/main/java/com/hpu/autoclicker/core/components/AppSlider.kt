package com.hpu.autoclicker.core.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 统一滑块 —— 胶囊轨道 + 圆形滑钮，替换 Material3 默认的方块滑钮。
 *
 * 视觉规则：
 * - 轨道：全圆角胶囊，未激活段用 surfaceContainerHigh + outlineVariant 细描边，激活段用 primary。
 * - 滑钮：圆形，surface 底 + primary 描边 + 投影；拖动时底色转为 primaryContainer、投影加深。
 * - 激活段的右端与滑钮圆心严格对齐（Material3 把滑钮摆在 trackWidth * fraction 处）。
 *
 * 交互沿用 Material3 的 Slider：点按轨道跳转、拖动改值、带无障碍语义；
 * [interactionSource] 透传出去，调用方可监听拖动状态（如拖动时隐藏弹框）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val scheme = MaterialTheme.colorScheme
    val dragging by interactionSource.collectIsDraggedAsState()
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span <= 0f) {
        0f
    } else {
        ((value - valueRange.start) / span).coerceIn(0f, 1f)
    }
    val shadowElevation by animateDpAsState(
        targetValue = if (dragging) Dimens.sliderThumbShadowActive else Dimens.sliderThumbShadowIdle,
        animationSpec = tween(Dimens.Motion.durationShort),
        label = "sliderThumbShadow",
    )

    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        enabled = enabled,
        interactionSource = interactionSource,
        modifier = modifier,
        thumb = {
            AppSliderThumb(
                ringColor = scheme.primary,
                fillColor = if (dragging) scheme.primaryContainer else scheme.surface,
                elevation = shadowElevation,
            )
        },
        track = {
            AppSliderTrack(
                fraction = fraction,
                activeColor = scheme.primary,
                inactiveColor = scheme.surfaceContainerHigh,
                borderColor = scheme.outlineVariant,
            )
        },
    )
}

/** 胶囊轨道：底色 + 细描边，激活段覆盖到滑钮圆心。 */
@Composable
private fun AppSliderTrack(
    fraction: Float,
    activeColor: Color,
    inactiveColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.sliderTrackHeight),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = inactiveColor, cornerRadius = radius)
        drawRoundRect(
            color = borderColor,
            cornerRadius = radius,
            style = Stroke(width = Dimens.strokeThin.toPx()),
        )
        val activeWidth = size.width * fraction
        if (activeWidth > 0f) {
            drawRoundRect(
                color = activeColor,
                size = Size(activeWidth, size.height),
                cornerRadius = radius,
            )
        }
    }
}

/** 圆形滑钮：底色 + 品牌描边 + 投影。 */
@Composable
private fun AppSliderThumb(
    ringColor: Color,
    fillColor: Color,
    elevation: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(Dimens.sliderThumbSize)
            .shadow(elevation = elevation, shape = CircleShape)
            .background(fillColor, CircleShape)
            .border(Dimens.sliderThumbStroke, ringColor, CircleShape),
    )
}

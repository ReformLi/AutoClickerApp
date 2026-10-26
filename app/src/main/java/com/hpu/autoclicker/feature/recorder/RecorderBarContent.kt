package com.hpu.autoclicker.feature.recorder

import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.unit.dp
import com.hpu.autoclicker.core.theme.AppTheme
import com.hpu.autoclicker.core.theme.Dimens
import com.hpu.autoclicker.core.ui.rememberRawTapDetector
import kotlinx.coroutines.delay

enum class RecorderBarPhase { READY, RECORDING, PAUSED }

/** 录制小条的状态（准备 / 录制中 / 已暂停） */
@Stable
class RecorderBarState {
    var phase by mutableStateOf(RecorderBarPhase.READY)
    var stepCount by mutableStateOf(0)
}

/**
 * 准备 / 录制小条。
 *
 * - 准备态：品牌浅底 + 品牌圆点，文案说明「点开始后哪些会被记录、哪些不会」；
 * - 录制态：白底 + 红色描边 + 呼吸红点，步骤数变化时整条闪一下并出现「+1」；
 * - 暂停态：白底 + 警示色描边 + 琥珀点。
 *
 * 左侧信息区是拖动把手（原始触摸交给 Service 移动窗口），右侧按钮各自判定点击。
 */
@Composable
fun RecorderBarContent(
    state: RecorderBarState,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onTogglePause: () -> Unit,
    onStop: () -> Unit,
    onDragTouch: (MotionEvent) -> Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors

    var flash by remember { mutableStateOf(false) }
    LaunchedEffect(state.stepCount) {
        if (state.stepCount > 0 && state.phase == RecorderBarPhase.RECORDING) {
            flash = true
            delay(180L)
            flash = false
        }
    }
    val container by animateColorAsState(
        targetValue = when {
            flash -> scheme.errorContainer
            state.phase == RecorderBarPhase.READY -> scheme.primaryContainer
            else -> scheme.surface
        },
        animationSpec = tween(180),
        label = "barContainer",
    )
    val borderColor = when (state.phase) {
        RecorderBarPhase.READY -> scheme.primary
        RecorderBarPhase.RECORDING -> scheme.error
        RecorderBarPhase.PAUSED -> sem.warning
    }
    val dotColor = when (state.phase) {
        RecorderBarPhase.READY -> scheme.primary
        RecorderBarPhase.RECORDING -> scheme.error
        RecorderBarPhase.PAUSED -> sem.warning
    }

    val pulse = rememberInfiniteTransition(label = "recordingPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "recordingPulseAlpha",
    )

    Row(
        modifier = Modifier
            .widthIn(max = 340.dp)
            .background(container, MaterialTheme.shapes.medium)
            .border(Dimens.strokeFocused, borderColor, MaterialTheme.shapes.medium)
            .padding(horizontal = Dimens.space8, vertical = Dimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .pointerInteropFilter { onDragTouch(it) }
                .padding(end = Dimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "≡",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Dimens.space8),
            )
            Box(
                modifier = Modifier
                    .size(Dimens.space12)
                    .graphicsLayer {
                        alpha = if (state.phase == RecorderBarPhase.RECORDING) pulseAlpha else 1f
                    }
                    .background(dotColor, CircleShape),
            )
            Column(modifier = Modifier.padding(start = Dimens.space8)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = titleOf(state),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                    )
                    if (flash) {
                        Text(
                            text = "+1",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.error,
                            modifier = Modifier.padding(start = Dimens.space4),
                        )
                    }
                }
                Text(
                    text = captionOf(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }

        when (state.phase) {
            RecorderBarPhase.READY -> {
                BarAction(
                    text = "开始",
                    onClick = onStart,
                    style = BarActionStyle.Filled,
                )
                Spacer(Modifier.width(Dimens.space4))
                BarAction(
                    text = "取消",
                    onClick = onCancel,
                    style = BarActionStyle.Text,
                )
            }

            RecorderBarPhase.RECORDING -> {
                BarAction(
                    text = "暂停",
                    onClick = onTogglePause,
                    style = BarActionStyle.Outlined,
                )
                Spacer(Modifier.width(Dimens.space4))
                BarAction(
                    text = "停止",
                    onClick = onStop,
                    style = BarActionStyle.Danger,
                )
            }

            RecorderBarPhase.PAUSED -> {
                BarAction(
                    text = "继续",
                    onClick = onTogglePause,
                    style = BarActionStyle.Filled,
                )
                Spacer(Modifier.width(Dimens.space4))
                BarAction(
                    text = "停止",
                    onClick = onStop,
                    style = BarActionStyle.OutlinedDanger,
                )
            }
        }
    }
}

private fun titleOf(state: RecorderBarState): String = when (state.phase) {
    RecorderBarPhase.READY -> "准备录制"
    RecorderBarPhase.RECORDING -> "录制中 · ${state.stepCount} 步"
    RecorderBarPhase.PAUSED -> "已暂停 · ${state.stepCount} 步"
}

private fun captionOf(state: RecorderBarState): String = when (state.phase) {
    RecorderBarPhase.READY -> "点开始后，除本栏、系统栏和安全界面外的触摸都会被记录"
    RecorderBarPhase.RECORDING -> "拖动左侧可移动；底部导航栏不会录制"
    RecorderBarPhase.PAUSED -> "已记录的步骤保留，点继续接着录"
}

private enum class BarActionStyle { Filled, Outlined, Danger, OutlinedDanger, Text }

@Composable
private fun BarAction(
    text: String,
    onClick: () -> Unit,
    style: BarActionStyle,
) {
    val scheme = MaterialTheme.colorScheme
    val tap = rememberRawTapDetector()
    val shape = MaterialTheme.shapes.small
    val background = when (style) {
        BarActionStyle.Filled -> scheme.primary
        BarActionStyle.Danger -> scheme.error
        BarActionStyle.Outlined, BarActionStyle.OutlinedDanger, BarActionStyle.Text -> Color.Transparent
    }
    val contentColor = when (style) {
        BarActionStyle.Filled -> scheme.onPrimary
        BarActionStyle.Danger -> scheme.onError
        BarActionStyle.Outlined -> scheme.primary
        BarActionStyle.OutlinedDanger -> scheme.error
        BarActionStyle.Text -> scheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .height(36.dp)
            .background(background, shape)
            .then(
                if (style == BarActionStyle.Outlined || style == BarActionStyle.OutlinedDanger) {
                    Modifier.border(Dimens.strokeThin, contentColor, shape)
                } else {
                    Modifier
                }
            )
            .graphicsLayer { alpha = if (tap.isPressed) 0.8f else 1f }
            .pointerInteropFilter { event -> tap.onEvent(event, onTap = onClick) }
            .padding(horizontal = Dimens.space12),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

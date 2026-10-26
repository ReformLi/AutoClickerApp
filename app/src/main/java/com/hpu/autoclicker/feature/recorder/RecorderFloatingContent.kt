package com.hpu.autoclicker.feature.recorder

import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hpu.autoclicker.R
import com.hpu.autoclicker.core.components.AppBadge
import com.hpu.autoclicker.core.components.AppBadgeTone
import com.hpu.autoclicker.core.components.AppBodyText
import com.hpu.autoclicker.core.components.AppCaption
import com.hpu.autoclicker.core.components.AppCard
import com.hpu.autoclicker.core.components.AppSectionTitle
import com.hpu.autoclicker.core.theme.AppTheme
import com.hpu.autoclicker.core.theme.Dimens
import com.hpu.autoclicker.core.ui.FloatingSquareButton
import com.hpu.autoclicker.core.ui.rememberRawTapDetector

enum class RecorderPanelMode { EDIT, RUNNING }

/** 悬浮窗 UI 状态：全部由 Service 更新，Compose 自动重组 */
@Stable
class RecorderFloatingUiState {
    var isExpanded by mutableStateOf(false)
    var mode by mutableStateOf(RecorderPanelMode.EDIT)

    /** 横屏高度放不下竖向面板时改为横排展开，由 Service 按屏幕尺寸切换 */
    var horizontal by mutableStateOf(false)

    var mainPressed by mutableStateOf(false)

    var scriptName by mutableStateOf("未命名脚本")
    var scriptSaved by mutableStateOf(false)
    var stepCount by mutableStateOf(0)
    var repeatCount by mutableStateOf(1)
    var speed by mutableStateOf(1f)
    var draftAvailable by mutableStateOf(false)

    /** 回放现场：playing 含暂停态，paused 单独标记 */
    var playing by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var currentStep by mutableStateOf(0)
    var currentRepeat by mutableStateOf(0)
}

/**
 * 录点器悬浮窗内容。
 *
 * 编辑态：主按钮 + 常驻的「录制 / 运行 / 脚本 / 设置 / 关闭」；
 * 运行态：只显示脚本信息、进度、重复次数、倍速与「开始/暂停/停止/返回编辑」，
 * 不出现任何编辑入口。
 *
 * 所有可点元件都走 pointerInteropFilter + [rememberRawTapDetector]，
 * 与主按钮共用一条原始触摸链路，避免 Compose clickable 与拖动状态机抢事件。
 */
@Composable
fun RecorderFloatingContent(
    state: RecorderFloatingUiState,
    onMainTouch: (MotionEvent) -> Boolean,
    onRecord: () -> Unit,
    onRun: () -> Unit,
    onScripts: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onStopPlay: () -> Unit,
    onReturnEdit: () -> Unit,
    onRepeatChange: (Int) -> Unit,
) {
    if (state.horizontal) {
        Row(
            modifier = Modifier
                .background(Color.Transparent)
                .padding(Dimens.space8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainButton(state, onMainTouch)
            if (state.isExpanded) {
                Spacer(Modifier.width(Dimens.space8))
                RecorderPanel(
                    state = state,
                    onRecord = onRecord,
                    onRun = onRun,
                    onScripts = onScripts,
                    onSettings = onSettings,
                    onClose = onClose,
                    onTogglePlay = onTogglePlay,
                    onStopPlay = onStopPlay,
                    onReturnEdit = onReturnEdit,
                    onRepeatChange = onRepeatChange,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .background(Color.Transparent)
                .padding(Dimens.space8),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MainButton(state, onMainTouch)
            if (state.isExpanded) {
                Spacer(Modifier.height(Dimens.space8))
                RecorderPanel(
                    state = state,
                    onRecord = onRecord,
                    onRun = onRun,
                    onScripts = onScripts,
                    onSettings = onSettings,
                    onClose = onClose,
                    onTogglePlay = onTogglePlay,
                    onStopPlay = onStopPlay,
                    onReturnEdit = onReturnEdit,
                    onRepeatChange = onRepeatChange,
                )
            }
        }
    }
}

@Composable
private fun MainButton(state: RecorderFloatingUiState, onMainTouch: (MotionEvent) -> Boolean) {
    val scheme = MaterialTheme.colorScheme
    FloatingSquareButton(
        modifier = Modifier.pointerInteropFilter { onMainTouch(it) },
        pressed = state.mainPressed,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_record),
            contentDescription = "录点器",
            modifier = Modifier
                .fillMaxSize()
                .padding(Dimens.space8),
            colorFilter = ColorFilter.tint(scheme.onPrimary),
        )
    }
}

@Composable
private fun RecorderPanel(
    state: RecorderFloatingUiState,
    onRecord: () -> Unit,
    onRun: () -> Unit,
    onScripts: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onTogglePlay: () -> Unit,
    onStopPlay: () -> Unit,
    onReturnEdit: () -> Unit,
    onRepeatChange: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val sem = AppTheme.colors
    AppCard(
        modifier = Modifier.width(236.dp),
        border = BorderStroke(Dimens.strokeThin, scheme.outlineVariant),
        contentPadding = PaddingValues(Dimens.space12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppSectionTitle(text = "录点器")
            Spacer(Modifier.weight(1f))
            PanelBadge(state)
        }
        AppCaption(
            text = "${state.scriptName} · ${state.stepCount} 步",
            modifier = Modifier.padding(top = Dimens.space4),
            maxLines = 1,
        )
        if (state.mode == RecorderPanelMode.EDIT && state.draftAvailable) {
            AppCaption(
                text = "有未完成草稿，可从脚本入口恢复",
                modifier = Modifier.padding(top = Dimens.space4),
                color = sem.warning,
                maxLines = 1,
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = Dimens.space8),
            thickness = Dimens.divider,
            color = scheme.outlineVariant,
        )

        if (state.mode == RecorderPanelMode.EDIT) {
            EditActions(
                stepCount = state.stepCount,
                onRecord = onRecord,
                onRun = onRun,
                onScripts = onScripts,
                onSettings = onSettings,
                onClose = onClose,
            )
        } else {
            RunningActions(
                state = state,
                onTogglePlay = onTogglePlay,
                onStopPlay = onStopPlay,
                onReturnEdit = onReturnEdit,
                onRepeatChange = onRepeatChange,
            )
        }
    }
}

@Composable
private fun PanelBadge(state: RecorderFloatingUiState) {
    when {
        state.mode == RecorderPanelMode.EDIT -> AppBadge(
            text = "编辑",
            tone = AppBadgeTone.Primary,
        )

        state.playing && state.paused -> AppBadge(
            text = "已暂停",
            tone = AppBadgeTone.Warning,
        )

        state.playing -> AppBadge(
            text = "回放中",
            tone = AppBadgeTone.Success,
        )

        else -> AppBadge(
            text = "待运行",
            tone = AppBadgeTone.Neutral,
        )
    }
}

@Composable
private fun EditActions(
    stepCount: Int,
    onRecord: () -> Unit,
    onRun: () -> Unit,
    onScripts: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        PanelAction(
            text = "录制",
            onClick = onRecord,
            modifier = Modifier.weight(1f),
            style = PanelActionStyle.Record,
        )
        PanelAction(
            text = "运行",
            onClick = onRun,
            modifier = Modifier.weight(1f),
            style = PanelActionStyle.Primary,
            enabled = stepCount > 0,
        )
    }
    Spacer(Modifier.height(Dimens.space8))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        PanelAction(
            text = "脚本",
            onClick = onScripts,
            modifier = Modifier.weight(1f),
            style = PanelActionStyle.Outlined,
        )
        PanelAction(
            text = "设置",
            onClick = onSettings,
            modifier = Modifier.weight(1f),
            style = PanelActionStyle.Outlined,
        )
    }
    Spacer(Modifier.height(Dimens.space4))
    PanelAction(
        text = "关闭悬浮窗",
        onClick = onClose,
        modifier = Modifier.fillMaxWidth(),
        style = PanelActionStyle.TextDanger,
    )
}

@Composable
private fun RunningActions(
    state: RecorderFloatingUiState,
    onTogglePlay: () -> Unit,
    onStopPlay: () -> Unit,
    onReturnEdit: () -> Unit,
    onRepeatChange: (Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    AppBodyText(
        text = state.scriptName,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    val progress = if (state.stepCount > 0) {
        val step = (state.currentStep + 1).coerceIn(1, state.stepCount)
        val repeat = (state.currentRepeat + 1).coerceIn(1, state.repeatCount)
        "第 $step/${state.stepCount} 步 · 重复 $repeat/${state.repeatCount}"
    } else {
        "尚无可回放步骤"
    }
    AppCaption(
        text = progress,
        modifier = Modifier.padding(top = Dimens.space4),
        maxLines = 1,
    )

    Spacer(Modifier.height(Dimens.space8))
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppCaption(text = "重复次数")
        Spacer(Modifier.weight(1f))
        RepeatStepper(
            value = state.repeatCount,
            onChange = onRepeatChange,
        )
    }
    Row(
        modifier = Modifier.padding(top = Dimens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppCaption(text = "回放倍速")
        Spacer(Modifier.weight(1f))
        Text(
            text = "${formatSpeed(state.speed)}×",
            style = MaterialTheme.typography.labelLarge,
            color = scheme.onSurface,
        )
    }

    Spacer(Modifier.height(Dimens.space8))
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
        val toggleText = when {
            state.playing && state.paused -> "继续"
            state.playing -> "暂停"
            else -> "开始"
        }
        PanelAction(
            text = toggleText,
            onClick = onTogglePlay,
            modifier = Modifier.weight(1f),
            style = PanelActionStyle.Primary,
            enabled = state.stepCount > 0,
        )
        PanelAction(
            text = "停止",
            onClick = onStopPlay,
            modifier = Modifier.weight(1f),
            style = PanelActionStyle.Outlined,
            enabled = state.playing,
        )
    }
    Spacer(Modifier.height(Dimens.space4))
    PanelAction(
        text = "返回编辑",
        onClick = onReturnEdit,
        modifier = Modifier.fillMaxWidth(),
        style = PanelActionStyle.Text,
    )
}

@Composable
private fun RepeatStepper(value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton(text = "−", enabled = value > RecorderScript.MIN_REPEAT) {
            onChange(value - 1)
        }
        Text(
            text = value.toString(),
            modifier = Modifier.width(Dimens.space32 + Dimens.space8),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        StepperButton(text = "+", enabled = value < RecorderScript.MAX_REPEAT) {
            onChange(value + 1)
        }
    }
}

@Composable
private fun StepperButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val tap = rememberRawTapDetector()
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .width(Dimens.space32)
            .height(Dimens.space32)
            .background(
                if (enabled) scheme.surfaceContainerHigh else scheme.surfaceContainer,
                MaterialTheme.shapes.extraSmall,
            )
            .pointerInteropFilter { event -> tap.onEvent(event, enabled, onTap = onClick) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) scheme.onSurface else scheme.outline,
        )
    }
}

private enum class PanelActionStyle { Primary, Record, Outlined, Text, TextDanger }

@Composable
private fun PanelAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PanelActionStyle = PanelActionStyle.Outlined,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val tap = rememberRawTapDetector()
    val shape = MaterialTheme.shapes.small

    val background = when {
        !enabled -> scheme.surfaceContainerHighest
        style == PanelActionStyle.Primary -> scheme.primary
        style == PanelActionStyle.Record -> scheme.error
        style == PanelActionStyle.Outlined -> scheme.surface
        else -> Color.Transparent
    }
    val foreground = when {
        !enabled -> scheme.outline
        style == PanelActionStyle.Primary -> scheme.onPrimary
        style == PanelActionStyle.Record -> scheme.onError
        style == PanelActionStyle.Outlined -> scheme.primary
        style == PanelActionStyle.TextDanger -> scheme.error
        else -> scheme.primary
    }

    Box(
        modifier = modifier
            .height(Dimens.buttonHeightSm)
            .background(background, shape)
            .then(
                if (style == PanelActionStyle.Outlined) {
                    Modifier.border(
                        Dimens.strokeThin,
                        if (enabled) scheme.outlineVariant else scheme.surfaceContainerHighest,
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .graphicsLayer { alpha = if (tap.isPressed && enabled) 0.82f else 1f }
            .pointerInteropFilter { event -> tap.onEvent(event, enabled, onTap = onClick) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
        )
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

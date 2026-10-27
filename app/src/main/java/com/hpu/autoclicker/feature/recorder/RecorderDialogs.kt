package com.hpu.autoclicker.feature.recorder

import android.text.InputFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.hpu.autoclicker.core.components.AppBodyText
import com.hpu.autoclicker.core.components.AppButton
import com.hpu.autoclicker.core.components.AppButtonVariant
import com.hpu.autoclicker.core.components.AppCaption
import com.hpu.autoclicker.core.components.AppConfirmMessage
import com.hpu.autoclicker.core.components.AppDialogActions
import com.hpu.autoclicker.core.components.AppDialogPanel
import com.hpu.autoclicker.core.components.AppFieldLabel
import com.hpu.autoclicker.core.components.AppOpacityDialogContent
import com.hpu.autoclicker.core.components.AppOpacityDialogState
import com.hpu.autoclicker.core.components.AppScriptList
import com.hpu.autoclicker.core.components.AppSegmentedControl
import com.hpu.autoclicker.core.components.AppTextField
import com.hpu.autoclicker.core.theme.Dimens
import com.hpu.autoclicker.core.util.ScriptNaming

/**
 * 录点器弹框集合。外框、按钮、间距全部复用统一组件，
 * 与另外两个模块的弹框保持同一套视觉。
 */

/** 切回录点器 / 服务重启时发现草稿：三选一，不自动恢复录制 */
@Composable
fun RecorderDraftRecoveryDialog(
    stepCount: Int,
    onContinue: () -> Unit,
    onLater: () -> Unit,
    onDiscard: () -> Unit,
) {
    AppDialogPanel(
        title = "发现未完成的录制",
        subtitle = "草稿 · $stepCount 步",
        actions = {
            AppButton(
                text = "继续录制",
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                variant = AppButtonVariant.Filled,
            )
            Spacer(Modifier.height(Dimens.space8))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                AppButton(
                    text = "稍后处理",
                    onClick = onLater,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Outlined,
                )
                AppButton(
                    text = "丢弃草稿",
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.DangerText,
                )
            }
        },
    ) {
        AppConfirmMessage(
            text = "上次录制在切换模块或服务重启时中断。继续录制会保留已有步骤并进入准备录制；稍后处理只载入步骤，不开始录制。"
        )
    }
}

/** 结束录制：保存 / 丢弃 / 继续，三选一 */
@Composable
fun RecorderStopDialog(
    stepCount: Int,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onContinue: () -> Unit,
) {
    AppDialogPanel(
        title = "结束录制",
        subtitle = "已录 $stepCount 步",
        actions = {
            AppButton(
                text = "保存并结束",
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                variant = AppButtonVariant.Filled,
                enabled = stepCount > 0,
            )
            Spacer(Modifier.height(Dimens.space8))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                AppButton(
                    text = "继续录制",
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Outlined,
                )
                AppButton(
                    text = "丢弃录制",
                    onClick = onDiscard,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.DangerText,
                )
            }
        },
    ) {
        AppConfirmMessage(
            text = if (stepCount > 0) {
                "保存会生成本次录制的独立脚本；丢弃后这些步骤无法恢复。"
            } else {
                "本次还没有录到步骤，丢弃会直接结束本次录制并退出。"
            }
        )
    }
}

/** 关闭悬浮窗：未保存步骤时给「保存并关闭 / 不保存关闭 / 取消」 */
@Composable
fun RecorderCloseDialog(
    stepCount: Int,
    onSaveClose: () -> Unit,
    onDiscardClose: () -> Unit,
    onCancel: () -> Unit,
) {
    AppDialogPanel(
        title = "关闭悬浮窗",
        subtitle = "当前 $stepCount 步未保存",
        actions = {
            AppButton(
                text = "保存并关闭",
                onClick = onSaveClose,
                modifier = Modifier.fillMaxWidth(),
                variant = AppButtonVariant.Filled,
            )
            Spacer(Modifier.height(Dimens.space8))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space8)) {
                AppButton(
                    text = "不保存关闭",
                    onClick = onDiscardClose,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.DangerText,
                )
                AppButton(
                    text = "取消",
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    variant = AppButtonVariant.Outlined,
                )
            }
        },
    ) {
        AppConfirmMessage(
            text = "关闭悬浮窗后，未保存的步骤会丢失；保存会写入当前脚本名的脚本。"
        )
    }
}

/** 统一二次确认（丢弃草稿 / 覆盖同名脚本 / 删除脚本 / 停止回放） */
@Composable
fun RecorderConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AppDialogPanel(
        title = title,
        actions = {
            AppDialogActions(
                confirmText = confirmText,
                onConfirm = onConfirm,
                cancelText = "取消",
                onCancel = onCancel,
                confirmVariant = if (danger) AppButtonVariant.Danger else AppButtonVariant.Filled,
            )
        },
    ) {
        AppConfirmMessage(text = message)
    }
}

private class RecorderRepeatState(initial: Int) {
    var value by mutableStateOf(initial.coerceIn(RecorderScript.MIN_REPEAT, RecorderScript.MAX_REPEAT).toString())
    var error by mutableStateOf<String?>(null)
}

private class RecorderNameState(initial: String) {
    var value by mutableStateOf(initial)
    var error by mutableStateOf<String?>(null)
}

/**
 * 脚本名限长：三个模块统一取 [ScriptNaming.MAX_NAME_LENGTH]。
 * 信息条放不下时由 UI 自动缩字号，不再按中英文分别截断——
 * 否则同一个脚本在录点器只许 6 字、在另外两个模块许 12 字，规则互相打架。
 */
internal val RecorderNameInputFilter = InputFilter { source, start, end, dest, dstart, dend ->
    val result = buildString {
        append(dest, 0, dstart)
        append(source, start, end)
        append(dest, dend, dest.length)
    }
    val max = ScriptNaming.MAX_NAME_LENGTH
    if (result.length <= max) {
        null
    } else {
        val keep = max - (dest.length - (dend - dstart))
        // 截断处若是空格（旧版长名「脚本 1」截成「脚本 」），一并去掉，别留悬空空格
        if (keep <= 0) "" else source.subSequence(start, start + keep).toString().trimEnd()
    }
}

/** 脚本命名：保存 / 改名共用 */
@Composable
fun RecorderNameDialog(
    title: String,
    initial: String,
    hint: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val state = remember(initial) { RecorderNameState(initial) }
    AppDialogPanel(
        title = title,
        actions = {
            AppDialogActions(
                confirmText = "确定",
                onConfirm = {
                    val value = state.value.trim()
                    if (value.isEmpty()) {
                        state.error = "名称不能为空"
                    } else {
                        onConfirm(value)
                    }
                },
                cancelText = "取消",
                onCancel = onCancel,
            )
        },
    ) {
        AppFieldLabel(text = "脚本名称")
        AppTextField(
            value = state.value,
            onValueChange = {
                state.value = it
                state.error = null
            },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = hint,
            inputFilter = RecorderNameInputFilter,
        )
        AppCaption(
            text = "最多 ${ScriptNaming.MAX_NAME_LENGTH} 个字符",
            modifier = Modifier.padding(top = Dimens.space4),
        )
        state.error?.let {
            AppCaption(
                text = it,
                modifier = Modifier.padding(top = Dimens.space4),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 脚本管理：加载 / 改名 / 删除；有草稿时可从这里恢复，
 * 当前步骤尚未落盘时可保存。
 */
@Composable
fun RecorderScriptManagerDialog(
    scripts: List<RecorderScript>,
    currentId: String?,
    hasDraft: Boolean,
    canSaveCurrent: Boolean,
    onLoad: (RecorderScript) -> Unit,
    onRename: (RecorderScript) -> Unit,
    onDelete: (RecorderScript) -> Unit,
    onRecoverDraft: () -> Unit,
    onSaveCurrent: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialogPanel(
        title = "脚本管理",
        subtitle = "${scripts.size} 个脚本",
        actions = {
            if (canSaveCurrent) {
                AppButton(
                    text = "保存当前步骤",
                    onClick = onSaveCurrent,
                    modifier = Modifier.fillMaxWidth(),
                    variant = AppButtonVariant.Outlined,
                )
                Spacer(Modifier.height(Dimens.space8))
            }
            AppButton(
                text = "关闭",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = AppButtonVariant.Text,
            )
        },
    ) {
        if (hasDraft) {
            AppBodyText(text = "有未完成的录制草稿")
            AppButton(
                text = "恢复草稿",
                onClick = onRecoverDraft,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Dimens.space8),
                variant = AppButtonVariant.Outlined,
            )
            Spacer(Modifier.height(Dimens.space12))
        }
        AppScriptList(
            scripts = scripts,
            nameOf = { script ->
                val current = if (script.id == currentId) " · 当前" else ""
                "${script.name}（${script.steps.size} 步）$current"
            },
            onLoad = onLoad,
            onRename = onRename,
            onDelete = onDelete,
            emptyTitle = "还没有脚本",
            emptyDescription = "点悬浮窗的「录制」录一段操作，结束后即可保存。",
        )
    }
}

/**
 * 启动确认：回放前先看清脚本与倍速，轮数在这里定下并写回脚本。
 * 倍速本身只在设置里改，这里只读展示。
 */
@Composable
fun RecorderLaunchDialog(
    scriptName: String,
    stepCount: Int,
    speed: Float,
    initialRepeat: Int,
    onStart: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val state = remember(initialRepeat) { RecorderRepeatState(initialRepeat) }
    AppDialogPanel(
        title = "开始回放",
        subtitle = "$scriptName · $stepCount 步",
        actions = {
            AppDialogActions(
                confirmText = "启动",
                onConfirm = {
                    val count = state.value.toIntOrNull()
                    if (count == null ||
                        count < RecorderScript.MIN_REPEAT ||
                        count > RecorderScript.MAX_REPEAT
                    ) {
                        state.error = "请输入 ${RecorderScript.MIN_REPEAT}–${RecorderScript.MAX_REPEAT} 之间的整数"
                    } else {
                        onStart(count)
                    }
                },
                cancelText = "取消",
                onCancel = onCancel,
            )
        },
    ) {
        AppFieldLabel(text = "重复次数")
        AppTextField(
            value = state.value,
            onValueChange = {
                state.value = it
                state.error = null
            },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "1",
            keyboardType = KeyboardType.Number,
            maxLength = 3,
            textAlign = TextAlign.Center,
        )
        AppCaption(
            text = "范围 ${RecorderScript.MIN_REPEAT}–${RecorderScript.MAX_REPEAT}；倍速 ${formatSpeedLabel(speed)}，只能在设置里改",
            modifier = Modifier.padding(top = Dimens.space4),
        )
        state.error?.let {
            AppCaption(
                text = it,
                modifier = Modifier.padding(top = Dimens.space4),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 设置：回放倍速 + 悬浮窗透明度 + 关闭悬浮窗入口 */
@Composable
fun RecorderSettingsDialog(
    initialSpeed: Float,
    initialAlpha: Float,
    onAlphaChange: (Float) -> Unit,
    onDraggingChange: (dragging: Boolean, value: Float, anchor: Rect?) -> Unit,
    onConfirm: (speed: Float, alpha: Float) -> Unit,
    onCancel: () -> Unit,
    onCloseWindow: () -> Unit,
) {
    val speeds = RecorderStore.SPEEDS
    var speedIndex by remember(initialSpeed) {
        mutableStateOf(
            speeds.indexOfFirst { it == initialSpeed }.let { if (it >= 0) it else 1 }
        )
    }
    val opacityState = remember(initialAlpha) {
        AppOpacityDialogState().apply { alpha = initialAlpha }
    }

    AppDialogPanel(
        title = "录点器设置",
        actions = {
            AppDialogActions(
                confirmText = "确定",
                onConfirm = { onConfirm(speeds[speedIndex], opacityState.alpha) },
                cancelText = "取消",
                onCancel = onCancel,
            )
            // 悬浮窗上没有关闭按钮，退出收在这里
            Spacer(Modifier.height(Dimens.space8))
            AppButton(
                text = "关闭悬浮窗",
                onClick = onCloseWindow,
                modifier = Modifier.fillMaxWidth(),
                variant = AppButtonVariant.DangerText,
            )
        },
    ) {
        AppFieldLabel(text = "回放倍速")
        AppCaption(
            text = "2× 表示等待时间减半，手势本身时长不变",
            modifier = Modifier.padding(top = Dimens.space4),
        )
        AppSegmentedControl(
            options = speeds.map { formatSpeedLabel(it) },
            selectedIndex = speedIndex,
            onSelect = { speedIndex = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.fieldGap),
        )

        // 透明度与连点器 / 代点器共用同一套内容：拖动实时改窗口透明度，
        // 拖动期间弹框隐藏、在原位显示预览条，松手恢复
        Column(modifier = Modifier.padding(top = Dimens.space24)) {
            AppFieldLabel(text = "悬浮窗透明度")
            AppOpacityDialogContent(
                state = opacityState,
                minAlpha = RecorderStore.MIN_WINDOW_ALPHA,
                onAlphaChange = {
                    opacityState.alpha = it
                    onAlphaChange(it)
                },
                onDraggingChange = onDraggingChange,
            )
        }
    }
}

private fun formatSpeedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"

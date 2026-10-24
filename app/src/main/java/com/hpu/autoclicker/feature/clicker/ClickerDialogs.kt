package com.hpu.autoclicker.feature.clicker

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.hpu.autoclicker.core.components.AppButton
import com.hpu.autoclicker.core.components.AppButtonVariant
import com.hpu.autoclicker.core.components.AppFieldLabel
import com.hpu.autoclicker.core.components.AppRadioButtonRow
import com.hpu.autoclicker.core.components.AppTextField
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 连点器（旧功能）的两个服务内弹框（原 dialog_settings.xml / activity_point_settings.xml）。
 * 经 AlertDialog.setView(ComposeView) 挂载，业务侧仍沿用原有 overlay / 确认逻辑。
 *
 * UI 美化：布局/控件全部走通用组件 + token；**行为红线不动**——
 * 内部输入框用 AppTextField（保留原生 EditText 内核，保证 overlay 内软键盘可弹），
 * 弹框外框统一走 [com.hpu.autoclicker.core.components.AppDialogPanel]（圆角 20dp），
 * 间距全走 [Dimens]。本文件只提供弹框正文，标题与底部按钮由外框统一渲染。
 */

// ==================== 悬浮窗设置（dialog_settings.xml） ====================

/** 悬浮窗设置状态（总循环次数字符串） */
@Stable
class SettingsDialogState {
    var loopCount by mutableStateOf("")
}

/** 悬浮窗设置弹框正文（标题与底部按钮由 AppDialogPanel 统一渲染） */
@Composable
fun SettingsDialogContent(
    state: SettingsDialogState,
    onSavePoints: () -> Unit,
    onLoadPoints: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AppFieldLabel(text = "总循环次数")
        AppTextField(
            value = state.loopCount,
            onValueChange = { state.loopCount = it },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "0 表示无限循环",
            keyboardType = KeyboardType.Number,
        )

        AppButton(
            text = "保存当前点击位置",
            onClick = onSavePoints,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space24),
            variant = AppButtonVariant.Outlined,
        )
        AppButton(
            text = "打开点击文件",
            onClick = onLoadPoints,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space12),
            variant = AppButtonVariant.Outlined,
        )
    }
}

// ==================== 点击点设置（activity_point_settings.xml） ====================

/** 点击点设置状态（替代原 XML 中大量控件取值） */
@Stable
class PointSettingsFormState {
    var name by mutableStateOf("")
    /** 点击类型：点击 / 双击 / 返回键 / 主页键 / 打开通知栏 / 滑动 */
    var type by mutableStateOf("点击")
    /** 点击位置：中间 / 随机 */
    var clickPosition by mutableStateOf("中间")
    var delay by mutableStateOf("0")
    var touchDuration by mutableStateOf("100")
    var repeatCount by mutableStateOf("1")
    var randomDistance by mutableStateOf("0")
    /** 是否为滑动点（滑动点隐藏其它类型、仅展示「滑动」且不可改） */
    var isSwipePoint by mutableStateOf(false)
}

/** 点击点设置弹框正文（标题与底部按钮由 AppDialogPanel 统一渲染） */
@Composable
fun PointSettingsDialogContent(
    state: PointSettingsFormState,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        AppFieldLabel(text = "点击名称")
        AppTextField(
            value = state.name,
            onValueChange = {},
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "按钮名称",
            readOnly = true,
        )

        AppFieldLabel(text = "点击类型", modifier = Modifier.padding(top = Dimens.space16))
        Column(modifier = Modifier.padding(top = Dimens.space4)) {
            if (state.isSwipePoint) {
                // 滑动点：仅显示「滑动」（选中且不可改）
                AppRadioButtonRow(text = "滑动", selected = true, enabled = false, onSelect = {})
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    AppRadioButtonRow("点击", state.type == "点击", Modifier.weight(1f)) { state.type = "点击" }
                    AppRadioButtonRow("双击", state.type == "双击", Modifier.weight(1f)) { state.type = "双击" }
                    AppRadioButtonRow("返回键", state.type == "返回键", Modifier.weight(1f)) { state.type = "返回键" }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    AppRadioButtonRow("主页键", state.type == "主页键", Modifier.weight(1f)) { state.type = "主页键" }
                    AppRadioButtonRow("打开通知栏", state.type == "打开通知栏", Modifier.weight(1f)) { state.type = "打开通知栏" }
                }
            }
        }

        AppFieldLabel(text = "点击位置", modifier = Modifier.padding(top = Dimens.space16))
        Row(modifier = Modifier.padding(top = Dimens.space4)) {
            AppRadioButtonRow("中间", state.clickPosition == "中间", Modifier.weight(1f)) { state.clickPosition = "中间" }
            AppRadioButtonRow("随机", state.clickPosition == "随机", Modifier.weight(1f)) { state.clickPosition = "随机" }
        }

        AppFieldLabel(text = "延迟（毫秒）", modifier = Modifier.padding(top = Dimens.space16))
        AppTextField(
            value = state.delay,
            onValueChange = { state.delay = it },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "0",
            keyboardType = KeyboardType.Number,
        )

        // 滑动点显示「滑动持续时间」，其余显示「触摸时长」（与原文案一致）
        AppFieldLabel(
            text = if (state.isSwipePoint) "滑动持续时间（毫秒）" else "触摸时长（毫秒）",
            modifier = Modifier.padding(top = Dimens.space16),
        )
        AppTextField(
            value = state.touchDuration,
            onValueChange = { state.touchDuration = it },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "100",
            keyboardType = KeyboardType.Number,
        )

        AppFieldLabel(text = "重复次数", modifier = Modifier.padding(top = Dimens.space16))
        AppTextField(
            value = state.repeatCount,
            onValueChange = { state.repeatCount = it },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "1",
            keyboardType = KeyboardType.Number,
        )

        AppFieldLabel(text = "随机距离（像素）", modifier = Modifier.padding(top = Dimens.space16))
        AppTextField(
            value = state.randomDistance,
            onValueChange = { state.randomDistance = it },
            modifier = Modifier.padding(top = Dimens.fieldGap),
            hint = "0",
            keyboardType = KeyboardType.Number,
        )
    }
}

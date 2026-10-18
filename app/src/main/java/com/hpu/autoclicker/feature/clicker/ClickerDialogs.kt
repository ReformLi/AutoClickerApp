package com.hpu.autoclicker.feature.clicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hpu.autoclicker.core.theme.AppButton
import com.hpu.autoclicker.core.theme.AppOutlineButton
import com.hpu.autoclicker.core.theme.LocalAppPalette
import com.hpu.autoclicker.core.theme.ModernLabel
import com.hpu.autoclicker.core.theme.ModernTextField
import com.hpu.autoclicker.core.theme.ModernTitle
import com.hpu.autoclicker.core.ui.LabeledRadio

/**
 * 连点器（旧功能）的两个服务内弹框（原 dialog_settings.xml / activity_point_settings.xml）。
 * 经 AlertDialog.setView(ComposeView) 挂载，业务侧仍沿用原有 overlay / 确认逻辑，
 * 仅把「布局 + 控件取值」换成 Compose（取值改为读写状态对象）。
 */

// ==================== 悬浮窗设置（dialog_settings.xml） ====================

/** 悬浮窗设置状态（总循环次数字符串） */
@Stable
class SettingsDialogState {
    var loopCount by mutableStateOf("")
}

@Composable
fun SettingsDialogContent(
    state: SettingsDialogState,
    onSavePoints: () -> Unit,
    onLoadPoints: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    // 原 XML 根布局自带 dialog_background（白底 12dp 圆角），此处等价还原
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalAppPalette.current.surface)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        ModernTitle(text = "悬浮窗设置")

        ModernLabel(text = "总循环次数", modifier = Modifier.padding(top = 24.dp))
        ModernTextField(
            value = state.loopCount,
            onValueChange = { state.loopCount = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "0 表示无限循环",
            keyboardType = KeyboardType.Number,
        )

        AppOutlineButton(
            text = "保存当前点击位置",
            onClick = onSavePoints,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
        )
        AppOutlineButton(
            text = "打开点击文件",
            onClick = onLoadPoints,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        Row(modifier = Modifier.padding(top = 24.dp)) {
            AppOutlineButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            Box(modifier = Modifier.width(8.dp))
            AppButton(
                text = "确定",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
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

@Composable
fun PointSettingsDialogContent(
    state: PointSettingsFormState,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val palette = LocalAppPalette.current
    // 原 XML 根布局自带 dialog_background；外层 wrapper 负责百分比留白与点外关闭
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.surface)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        ModernTitle(text = "点击点设置")

        ModernLabel(text = "点击名称", modifier = Modifier.padding(top = 24.dp))
        ModernTextField(
            value = state.name,
            onValueChange = {},
            modifier = Modifier.padding(top = 8.dp),
            hint = "按钮名称",
            readOnly = true,
        )

        ModernLabel(text = "点击类型", modifier = Modifier.padding(top = 20.dp))
        Column(modifier = Modifier.padding(top = 4.dp)) {
            if (state.isSwipePoint) {
                // 滑动点：仅显示「滑动」（选中且不可改）
                LabeledRadio(text = "滑动", selected = true, enabled = false, onSelect = {})
            } else {
                Row(modifier = Modifier.fillMaxWidth()) {
                    LabeledRadio("点击", state.type == "点击", Modifier.weight(1f)) { state.type = "点击" }
                    LabeledRadio("双击", state.type == "双击", Modifier.weight(1f)) { state.type = "双击" }
                    LabeledRadio("返回键", state.type == "返回键", Modifier.weight(1f)) { state.type = "返回键" }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    LabeledRadio("主页键", state.type == "主页键", Modifier.weight(1f)) { state.type = "主页键" }
                    LabeledRadio("打开通知栏", state.type == "打开通知栏", Modifier.weight(1f)) { state.type = "打开通知栏" }
                }
            }
        }

        ModernLabel(text = "点击位置", modifier = Modifier.padding(top = 20.dp))
        Row(modifier = Modifier.padding(top = 4.dp)) {
            LabeledRadio("中间", state.clickPosition == "中间", Modifier.weight(1f)) { state.clickPosition = "中间" }
            LabeledRadio("随机", state.clickPosition == "随机", Modifier.weight(1f)) { state.clickPosition = "随机" }
        }

        ModernLabel(text = "延迟（毫秒）", modifier = Modifier.padding(top = 20.dp))
        ModernTextField(
            value = state.delay,
            onValueChange = { state.delay = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "0",
            keyboardType = KeyboardType.Number,
        )

        // 滑动点显示「滑动持续时间」，其余显示「触摸时长」（与原文案一致）
        ModernLabel(
            text = if (state.isSwipePoint) "滑动持续时间（毫秒）" else "触摸时长（毫秒）",
            modifier = Modifier.padding(top = 20.dp),
        )
        ModernTextField(
            value = state.touchDuration,
            onValueChange = { state.touchDuration = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "100",
            keyboardType = KeyboardType.Number,
        )

        ModernLabel(text = "重复次数", modifier = Modifier.padding(top = 20.dp))
        ModernTextField(
            value = state.repeatCount,
            onValueChange = { state.repeatCount = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "1",
            keyboardType = KeyboardType.Number,
        )

        ModernLabel(text = "随机距离（像素）", modifier = Modifier.padding(top = 20.dp))
        ModernTextField(
            value = state.randomDistance,
            onValueChange = { state.randomDistance = it },
            modifier = Modifier.padding(top = 8.dp),
            hint = "0",
            keyboardType = KeyboardType.Number,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppOutlineButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            AppButton(
                text = "保存",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
            )
        }

        // 底部占位，保证滚动到底部时有呼吸空间
        Box(modifier = Modifier.size(8.dp))
    }
}

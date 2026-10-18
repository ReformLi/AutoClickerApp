package com.hpu.autoclicker.feature.quickclick

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.hpu.autoclicker.core.components.AppButton
import com.hpu.autoclicker.core.components.AppBodyText
import com.hpu.autoclicker.core.components.AppCheckboxRow
import com.hpu.autoclicker.core.components.AppCaption
import com.hpu.autoclicker.core.components.AppEmptyState
import com.hpu.autoclicker.core.components.AppRadioButtonRow
import com.hpu.autoclicker.core.components.AppTextField
import com.hpu.autoclicker.core.theme.Dimens

/**
 * 代点器（快捷点击）的两个服务内弹框（原 dialog_quick_click_config.xml / dialog_script_manager.xml）。
 * 经 AlertDialog.setView(ComposeView) 挂载，取值改为读写状态对象。
 *
 * UI 美化（阶段三）：全部走通用组件 + token；输入框用 AppTextField
 * （保留原生 EditText 内核，保证 overlay 内软键盘可弹）。
 */

// ==================== 新建快捷按钮（dialog_quick_click_config.xml） ====================

/** 新建快捷按钮表单状态（替代原来对 EditText / CheckBox 的 findViewById 取值） */
@Stable
class QuickConfigFormState {
    var name by mutableStateOf("")
    var targetText by mutableStateOf("")
    var autoCollapse by mutableStateOf(false)
}

@Composable
fun QuickConfigDialogContent(
    state: QuickConfigFormState,
    nameFilter: (String) -> String = { it },
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // 与原 XML 一致的边距：左右 24dp、上 12dp、下 4dp
            .padding(start = Dimens.dialogHPadding, end = Dimens.dialogHPadding, top = Dimens.space12, bottom = Dimens.space4),
    ) {
        AppCaption(text = "按钮名称（可选）")
        AppTextField(
            value = state.name,
            onValueChange = { state.name = nameFilter(it) },
            modifier = Modifier.padding(top = Dimens.space4),
            hint = "默认为“按钮N”",
            maxLength = 10,
        )

        AppCaption(text = "动作类型", modifier = Modifier.padding(top = Dimens.space16))
        Column(modifier = Modifier.padding(top = Dimens.space4)) {
            // 目前仅支持文本匹配，其余两项置灰占位（与原文案、禁用态一致）
            AppRadioButtonRow(text = "文本匹配", selected = true, onSelect = {})
            AppRadioButtonRow(text = "图像识别（即将支持）", selected = false, enabled = false, onSelect = {})
            AppRadioButtonRow(text = "坐标点击（即将支持）", selected = false, enabled = false, onSelect = {})
        }

        AppCaption(text = "目标文字", modifier = Modifier.padding(top = Dimens.space16))
        AppTextField(
            value = state.targetText,
            onValueChange = { state.targetText = it },
            modifier = Modifier.padding(top = Dimens.space4),
            hint = "输入要在屏幕上匹配的文字",
        )

        // 点击后自动收起悬浮窗（整行可切换，token 化复选行）
        AppCheckboxRow(
            text = "点击后自动收起悬浮窗",
            checked = state.autoCollapse,
            onCheckedChange = { state.autoCollapse = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Dimens.space16),
        )
    }
}

// ==================== 脚本管理（dialog_script_manager.xml） ====================

/** 脚本管理状态：脚本列表（空列表时展示空态提示） */
@Stable
class ScriptManagerState {
    var scripts by mutableStateOf<List<QuickScript>>(emptyList())
}

@Composable
fun ScriptManagerDialogContent(
    state: ScriptManagerState,
    onSaveCurrent: () -> Unit,
    onLoadScript: (QuickScript) -> Unit,
    onRenameScript: (QuickScript) -> Unit,
    onDeleteScript: (QuickScript) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .padding(horizontal = Dimens.space16, vertical = Dimens.space12),
    ) {
        // 保存当前按钮
        AppButton(
            text = "保存当前按钮",
            onClick = onSaveCurrent,
            modifier = Modifier.fillMaxWidth(),
        )

        // 空态提示（无脚本时显示）
        if (state.scripts.isEmpty()) {
            AppEmptyState(
                title = "暂无已保存脚本",
                description = "先在悬浮窗上添加按钮，再回到这里保存",
                modifier = Modifier.padding(top = Dimens.space8),
            )
        }

        // 脚本列表（原 ScrollView 固定 200dp 高，可滚动；每行：名称 / 改名 / 删除）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.space24 * 8)   // 192dp ≈ 原 200dp 列表高度，落在 8 的倍数阶梯上
                .padding(top = Dimens.space8)
                .verticalScroll(rememberScrollState()),
        ) {
            state.scripts.forEach { script ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.touchMin),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 点击名称 = 加载该脚本
                    AppBodyText(
                        text = script.name,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onLoadScript(script) }
                            .padding(start = Dimens.space4),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "改名",
                        modifier = Modifier
                            .clickable { onRenameScript(script) }
                            .padding(horizontal = Dimens.space12, vertical = Dimens.space4),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.primary,
                    )
                    Text(
                        text = "删除",
                        modifier = Modifier
                            .clickable { onDeleteScript(script) }
                            .padding(start = Dimens.space12, end = Dimens.space4, top = Dimens.space4, bottom = Dimens.space4),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.error,
                    )
                }
            }
        }
    }
}

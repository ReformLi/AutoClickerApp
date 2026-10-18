package com.hpu.autoclicker.feature.quickclick

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hpu.autoclicker.core.theme.AppButton
import com.hpu.autoclicker.core.theme.LocalAppPalette
import com.hpu.autoclicker.core.theme.ModernLabel
import com.hpu.autoclicker.core.theme.ModernTextField
import com.hpu.autoclicker.core.ui.LabeledRadio

/**
 * 代点器（快捷点击）的两个服务内弹框（原 dialog_quick_click_config.xml / dialog_script_manager.xml）。
 * 经 AlertDialog.setView(ComposeView) 挂载，取值改为读写状态对象。
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
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // 与原 XML 一致的边距：左右 24dp、上 12dp、下 4dp
            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    ) {
        ModernLabel(text = "按钮名称（可选）")
        ModernTextField(
            value = state.name,
            onValueChange = { state.name = nameFilter(it) },
            modifier = Modifier.padding(top = 6.dp),
            hint = "默认为“按钮N”",
            maxLength = 10,
        )

        ModernLabel(text = "动作类型", modifier = Modifier.padding(top = 16.dp))
        Column(modifier = Modifier.padding(top = 4.dp)) {
            // 目前仅支持文本匹配，其余两项置灰占位（与原文案、禁用态一致）
            LabeledRadio(text = "文本匹配", selected = true, onSelect = {})
            LabeledRadio(text = "图像识别（即将支持）", selected = false, enabled = false, onSelect = {})
            LabeledRadio(text = "坐标点击（即将支持）", selected = false, enabled = false, onSelect = {})
        }

        ModernLabel(text = "目标文字", modifier = Modifier.padding(top = 16.dp))
        ModernTextField(
            value = state.targetText,
            onValueChange = { state.targetText = it },
            modifier = Modifier.padding(top = 6.dp),
            hint = "输入要在屏幕上匹配的文字",
        )

        // 点击后自动收起悬浮窗
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(top = 20.dp)
                .clickable { state.autoCollapse = !state.autoCollapse },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = state.autoCollapse,
                onCheckedChange = { state.autoCollapse = it },
                colors = CheckboxDefaults.colors(checkedColor = palette.primary),
            )
            Text(text = "点击后自动收起悬浮窗", color = palette.textPrimary, fontSize = 16.sp)
        }
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
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
    ) {
        // 保存当前按钮（原 btnSaveCurrent，44dp 高）
        AppButton(
            text = "保存当前按钮",
            onClick = onSaveCurrent,
            modifier = Modifier.fillMaxWidth(),
            height = 44.dp,
        )

        // 空态提示（无脚本时显示）
        if (state.scripts.isEmpty()) {
            Text(
                text = "暂无已保存脚本",
                modifier = Modifier.padding(top = 12.dp),
                color = palette.textSecondary,
                fontSize = 14.sp,
            )
        }

        // 脚本列表（原 ScrollView 固定 200dp 高，可滚动；每行：名称 / 改名 / 删除）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(top = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            state.scripts.forEach { script ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 点击名称 = 加载该脚本
                    Text(
                        text = script.name,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onLoadScript(script) }
                            .padding(start = 4.dp),
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "改名",
                        modifier = Modifier
                            .clickable { onRenameScript(script) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        color = palette.primary,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = "删除",
                        modifier = Modifier
                            .clickable { onDeleteScript(script) }
                            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        color = palette.danger,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
